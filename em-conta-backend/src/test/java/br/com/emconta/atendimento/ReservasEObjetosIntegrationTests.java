package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.atendimento.GraphApiControlada.Bloqueio;
import br.com.emconta.atendimento.GraphApiControlada.Download;
import br.com.emconta.atendimento.GraphApiControlada.Midia;
import br.com.emconta.integracoes.ArmazenamentoObjetos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Disputas de reserva de download, limpeza durável de objetos sem dono e janela de 24 h conferida depois
 * do upload. A sincronização é explícita (latches) e o relógio é fixado pelo teste; não há sleeps para
 * provocar as disputas. O armazenamento é o MinIO real, observado por um spy só neste teste.
 */
@org.springframework.test.context.TestPropertySource(
        properties = "emconta.whatsapp.timeout-download=30s") // o download suspenso não expira sozinho
class ReservasEObjetosIntegrationTests extends AtendimentoIntegracao {

    @MockitoSpyBean
    ArmazenamentoObjetos armazenamento;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void esvaziar() {
        processar();
        GRAPH.limpar();
        jdbc.sql("delete from atd_objeto_pendente").update();
        // Downloads de outros testes com nova tentativa agendada ficariam elegíveis quando o relógio
        // avança: encerra-os para que cada disputa envolva só o anexo do teste.
        jdbc.sql("update atd_anexo set situacao = 'FALHA', reserva = null, reservado_em = null"
                        + " where direcao = 'ENTRADA' and situacao in ('PENDENTE', 'FALHA_TEMPORARIA', 'PROCESSANDO')")
                .update();
    }

    @AfterEach
    void encerrar() {
        reset(armazenamento);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ reservas

    @Test
    void execucaoAntigaQueVoltaComSucessoNaoInterfereNaVencedora() throws Exception {
        String midia = receberImagem();
        Bloqueio bloqueio = new Bloqueio(new CountDownLatch(1), new CountDownLatch(1), false);
        GRAPH.bloquearPrimeiroDownload(midia, bloqueio);

        Future<Integer> a = executor.submit(() -> midias.processarPendentes(1)); // execução A
        assertThat(bloqueio.entrou().await(10, TimeUnit.SECONDS)).isTrue();
        relogio.fixar(Instant.now().plus(Duration.ofMinutes(5).plusSeconds(30))); // a reserva de A expira
        midias.processarPendentes(1); // execução B recupera e assume
        Map<String, Object> vencedora = anexo(midia);
        assertThat(vencedora.get("situacao")).isEqualTo("DISPONIVEL");

        bloqueio.liberar().countDown(); // A volta com o download completo
        a.get(10, TimeUnit.SECONDS);
        Map<String, Object> depois = anexo(midia);
        assertThat(depois.get("chave_objeto")).isEqualTo(vencedora.get("chave_objeto"));
        assertThat(depois.get("tentativas")).isEqualTo(vencedora.get("tentativas"));
        assertThat(depois.get("situacao")).isEqualTo("DISPONIVEL");
        assertThat(objeto((String) depois.get("chave_objeto")))
                .as("a chave vencedora continua acessível")
                .isTrue();
        assertThat(pendentes())
                .as("A perdeu a reserva antes de gravar: nada sobrou")
                .isZero();
    }

    @Test
    void execucaoAntigaQueGravouObjetoExcedenteTemOObjetoRemovido() throws Exception {
        String midia = receberImagem();
        CountDownLatch entrou = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicReference<String> chaveDeA = new AtomicReference<>();
        AtomicBoolean primeira = new AtomicBoolean(true);
        doAnswer(invocacao -> {
                    if (primeira.getAndSet(false)) {
                        chaveDeA.set(invocacao.getArgument(0));
                        entrou.countDown();
                        liberar.await(10, TimeUnit.SECONDS);
                    }
                    return invocacao.callRealMethod();
                })
                .when(armazenamento)
                .gravar(anyString(), any(Path.class), anyString());

        Future<Integer> a = executor.submit(() -> midias.processarPendentes(1));
        assertThat(entrou.await(10, TimeUnit.SECONDS)).isTrue();
        // Intenção de A já está no diário, ligada ao token de A.
        assertThat(pendentes()).isEqualTo(1);
        relogio.fixar(Instant.now().plus(Duration.ofMinutes(5).plusSeconds(30)));
        midias.processarPendentes(1); // B assume, grava outra chave e conclui
        Map<String, Object> deB = anexo(midia);
        String chaveDeB = (String) deB.get("chave_objeto");
        assertThat(chaveDeB).as(deB.toString()).isNotNull().isNotEqualTo(chaveDeA.get());
        assertThat(objeto(chaveDeA.get())).as("A ainda não gravou").isFalse();

        liberar.countDown(); // A grava o objeto e tenta concluir com o token antigo
        a.get(10, TimeUnit.SECONDS);
        assertThat(anexo(midia).get("chave_objeto")).isEqualTo(chaveDeB);
        assertThat(objeto(chaveDeB)).isTrue();
        assertThat(objeto(chaveDeA.get())).as("objeto excedente de A removido").isFalse();
        assertThat(pendentes()).isZero();
    }

    @Test
    void execucaoAntigaQueFalhaNaoAlteraAVencedora() throws Exception {
        String midia = receberImagem();
        Bloqueio bloqueio = new Bloqueio(new CountDownLatch(1), new CountDownLatch(1), true); // A recebe 404
        GRAPH.bloquearPrimeiroDownload(midia, bloqueio);
        Future<Integer> a = executor.submit(() -> midias.processarPendentes(1));
        assertThat(bloqueio.entrou().await(10, TimeUnit.SECONDS)).isTrue();
        relogio.fixar(Instant.now().plus(Duration.ofMinutes(5).plusSeconds(30)));
        midias.processarPendentes(1);
        Map<String, Object> vencedora = anexo(midia);

        bloqueio.liberar().countDown();
        a.get(10, TimeUnit.SECONDS);
        Map<String, Object> depois = anexo(midia);
        assertThat(depois.get("situacao")).isEqualTo("DISPONIVEL");
        assertThat(depois.get("erro")).isNull();
        assertThat(depois.get("tentativas")).isEqualTo(vencedora.get("tentativas"));
        assertThat(depois.get("proxima_tentativa_em")).isNull();
    }

    // ------------------------------------------------------------------ objetos sem dono

    @Test
    void remocaoQueFalhaFicaRegistradaEEhRetomada() throws Exception {
        String midia = receberImagem();
        CountDownLatch entrou = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicReference<String> chaveDeA = new AtomicReference<>();
        AtomicBoolean primeiraGravacao = new AtomicBoolean(true);
        AtomicBoolean primeiraRemocao = new AtomicBoolean(true);
        doAnswer(invocacao -> {
                    if (primeiraGravacao.getAndSet(false)) {
                        chaveDeA.set(invocacao.getArgument(0));
                        entrou.countDown();
                        liberar.await(10, TimeUnit.SECONDS);
                    }
                    return invocacao.callRealMethod();
                })
                .when(armazenamento)
                .gravar(anyString(), any(Path.class), anyString());
        doAnswer(invocacao -> {
                    if (primeiraRemocao.getAndSet(false)) {
                        throw new ArmazenamentoObjetos.FalhaArmazenamento("indisponível no teste");
                    }
                    return invocacao.callRealMethod();
                })
                .when(armazenamento)
                .remover(anyString());

        Future<Integer> a = executor.submit(() -> midias.processarPendentes(1));
        assertThat(entrou.await(10, TimeUnit.SECONDS)).isTrue();
        Instant depoisDaExpiracao = Instant.now().plus(Duration.ofMinutes(5).plusSeconds(30));
        relogio.fixar(depoisDaExpiracao);
        midias.processarPendentes(1);
        liberar.countDown();
        a.get(10, TimeUnit.SECONDS);

        Map<String, Object> pendente = jdbc.sql("select situacao, erro, chave_objeto from atd_objeto_pendente")
                .query()
                .singleRow();
        assertThat(pendente.get("situacao")).isEqualTo("REMOVER");
        assertThat((String) pendente.get("erro")).contains("Remoção falhou");
        assertThat(objeto(chaveDeA.get())).as("ainda no armazenamento").isTrue();

        relogio.fixar(depoisDaExpiracao.plus(Duration.ofMinutes(2)));
        midias.processarPendentes(1);
        assertThat(pendentes()).isZero();
        assertThat(objeto(chaveDeA.get())).isFalse();
        assertThat(objeto((String) anexo(midia).get("chave_objeto"))).isTrue();
    }

    @Test
    void quedaEntreGravarEConcluirEhRecuperadaSemTocarEmProcessamentoAtivo() throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        Instant antes = Instant.now().minus(Duration.ofMinutes(20));
        // Execução que caiu: reserva antiga, intenção GRAVANDO e o objeto já gravado.
        long caido = anexoProcessando(atendimento, UUID.randomUUID().toString(), antes);
        String orfa = "atendimento/anexos/" + UUID.randomUUID();
        gravarObjeto(orfa);
        intencao(orfa, caido, tokenDe(caido), antes);
        // Execução ativa: reserva recente; a intenção é antiga (já vencida para a limpeza), mas vigente.
        long ativo = anexoProcessando(atendimento, UUID.randomUUID().toString(), Instant.now());
        String emUso = "atendimento/anexos/" + UUID.randomUUID();
        gravarObjeto(emUso);
        intencao(emUso, ativo, tokenDe(ativo), antes);

        midias.processarPendentes(0);
        assertThat(objeto(orfa)).as("órfão da queda removido").isFalse();
        assertThat(objeto(emUso)).as("processamento ativo intacto").isTrue();
        assertThat(jdbc.sql("select count(*) from atd_objeto_pendente where chave_objeto = :c")
                        .param("c", emUso)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(anexoPorId(caido).get("reserva"))
                .as("reserva da queda invalidada")
                .isNull();
        assertThat(anexoPorId(ativo).get("reserva")).isNotNull();
    }

    @Test
    void limpezaRepetidaEConcorrenteRemoveCadaObjetoUmaVez() throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        long anexo = anexoProcessando(atendimento, null, Instant.now());
        List<String> chaves = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String chave = "atendimento/anexos/" + UUID.randomUUID();
            gravarObjeto(chave);
            jdbc.sql("insert into atd_objeto_pendente (chave_objeto, anexo_id, reserva, situacao, proxima_tentativa_em,"
                            + " criado_em, atualizado_em) values (:c, :a, :t, 'REMOVER', :agora, :agora, :agora)")
                    .param("c", chave)
                    .param("a", anexo)
                    .param("t", UUID.randomUUID().toString())
                    .param("agora", Timestamp.from(Instant.now().minusSeconds(5)))
                    .update();
            chaves.add(chave);
        }
        // Um objeto vinculado a anexo nunca é removido, mesmo se aparecer no diário.
        String vinculada = "atendimento/anexos/" + UUID.randomUUID();
        gravarObjeto(vinculada);
        jdbc.sql("update atd_anexo set chave_objeto = :c where id = :id")
                .param("c", vinculada)
                .param("id", anexo)
                .update();
        intencao(vinculada, anexo, UUID.randomUUID().toString(), Instant.now().minusSeconds(600));

        List<Callable<Integer>> tarefas = List.of(
                () -> midias.processarPendentes(0),
                () -> midias.processarPendentes(0),
                () -> midias.processarPendentes(0));
        for (Future<Integer> f : emParalelo(tarefas)) {
            f.get();
        }
        midias.processarPendentes(0); // repetida
        assertThat(pendentes()).isZero();
        assertThat(chaves).noneMatch(this::objeto);
        assertThat(objeto(vinculada)).isTrue();
    }

    // ------------------------------------------------------------------ janela depois do upload

    @Test
    void janelaVenceDuranteALeituraDoArmazenamento() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(Duration.ofHours(24)).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = enviarAnexo(admin, atendimento);
        doAnswer(invocacao -> {
                    relogio.fixar(t0.plusSeconds(120)); // o tempo passa enquanto o arquivo é lido
                    return invocacao.callRealMethod();
                })
                .when(armazenamento)
                .abrir(anyString(), any());

        envio.enviarPendentes(5);
        assertExpiradaDepoisDoUpload(mensagem);
    }

    @Test
    void janelaVenceDuranteOUploadAMetaENadaEhEnviadoNemRepetido() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(Duration.ofHours(24)).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = enviarAnexo(admin, atendimento);
        GRAPH.aoReceberUpload(() -> relogio.fixar(t0.plusSeconds(120)));

        envio.enviarPendentes(5);
        assertExpiradaDepoisDoUpload(mensagem);
        envio.enviarPendentes(5); // sem reenvio nem novo upload
        assertThat(situacao(mensagem)).isEqualTo("JANELA_EXPIRADA");
        assertThat(GRAPH.uploads()).hasSize(1);
        assertThat(contar("atd_mensagem_situacao where situacao = 'JANELA_EXPIRADA' and mensagem_id = :c", mensagem))
                .isEqualTo(1);
    }

    @Test
    void novaMensagemDoContatoDuranteOUploadRenovaAJanelaAntesDaConferenciaFinal() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        String numero = numeroUnico();
        long atendimento = assumido(admin, numero);
        ultimaEntrada(atendimento, t0.minus(Duration.ofHours(24)).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = enviarAnexo(admin, atendimento);
        GRAPH.aoReceberUpload(() -> {
            relogio.fixar(t0.plusSeconds(120));
            try {
                String corpo = mensagem(
                        numero,
                        "Contato",
                        wamid(),
                        "ainda estou aqui",
                        t0.plusSeconds(90).getEpochSecond());
                enviarWebhook(corpo, assinar(corpo));
                recebimento.processarPendentes(10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        envio.enviarPendentes(5);
        assertThat(situacao(mensagem)).isEqualTo("ACEITA");
        assertThat(GRAPH.requisicoes().stream().filter(r -> r.contains("/messages")))
                .hasSize(1);
    }

    // ------------------------------------------------------------------ apoio

    private void assertExpiradaDepoisDoUpload(long mensagem) {
        assertThat(situacao(mensagem)).isEqualTo("JANELA_EXPIRADA");
        assertThat(GRAPH.uploads()).as("o upload aconteceu").hasSize(1);
        assertThat(GRAPH.requisicoes().stream().filter(r -> r.contains("/messages")))
                .as("nenhuma mensagem enviada ao contato")
                .isEmpty();
        Map<String, Object> linha = jdbc.sql("select m.erro_titulo, m.proxima_tentativa_em, x.id_midia_externo"
                        + " from atd_mensagem m join atd_anexo x on x.mensagem_id = m.id where m.id = :id")
                .param("id", mensagem)
                .query()
                .singleRow();
        assertThat((String) linha.get("erro_titulo")).contains("carregado no WhatsApp", "nenhuma mensagem");
        assertThat(linha.get("proxima_tentativa_em")).isNull();
        assertThat(linha.get("id_midia_externo")).as("id da mídia preservado").isNotNull();
    }

    private String receberImagem() throws Exception {
        String numero = numeroUnico();
        novoAtendimento(numero);
        String id = "midia" + UUID.randomUUID().toString().replace("-", "");
        GRAPH.midia(id, new Midia(AnexosIntegrationTests.PNG, "image/png", false, Download.NORMAL, null));
        receber(numero, "image", id, "image/png", null, null);
        return id;
    }

    private long enviarAnexo(Navegador admin, long atendimento) {
        MockHttpServletResponse resposta = admin.multipart(
                url(atendimento, "anexos"),
                new MockMultipartFile("arquivo", "a.png", "image/png", AnexosIntegrationTests.PNG),
                Map.of("chaveIdempotencia", UUID.randomUUID().toString()));
        assertThat(resposta.getStatus()).isEqualTo(202);
        return corpo(resposta).get("mensagemId").asLong();
    }

    private Map<String, Object> anexo(String idMidia) {
        return jdbc.sql("select * from atd_anexo where id_midia_externo = :m")
                .param("m", idMidia)
                .query()
                .singleRow();
    }

    private Map<String, Object> anexoPorId(long id) {
        return jdbc.sql("select * from atd_anexo where id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }

    private String tokenDe(long anexo) {
        return (String) anexoPorId(anexo).get("reserva");
    }

    /** Anexo recebido em processamento (reservado em {@code reservadoEm}), sem mídia real na Graph. */
    private long anexoProcessando(long atendimento, String token, Instant reservadoEm) {
        String idMidia = "sem-midia-" + UUID.randomUUID();
        jdbc.sql("insert into atd_anexo (conversa_id, atendimento_id, direcao, categoria, mime_type, id_midia_externo,"
                        + " situacao, reserva, reservado_em, criado_em, atualizado_em) select conversa_id, id, 'ENTRADA',"
                        + " 'IMAGEM', 'image/png', :midia, 'PROCESSANDO', :token, :reservado, :reservado, :reservado"
                        + " from atd_atendimento where id = :a")
                .param("midia", idMidia)
                .param("token", token == null ? UUID.randomUUID().toString() : token)
                .param("reservado", Timestamp.from(reservadoEm))
                .param("a", atendimento)
                .update();
        return jdbc.sql("select id from atd_anexo where id_midia_externo = :m")
                .param("m", idMidia)
                .query(Long.class)
                .single();
    }

    private void intencao(String chave, long anexo, String token, Instant quando) {
        jdbc.sql("insert into atd_objeto_pendente (chave_objeto, anexo_id, reserva, situacao, proxima_tentativa_em,"
                        + " criado_em, atualizado_em) values (:c, :a, :t, 'GRAVANDO', :q, :q, :q)")
                .param("c", chave)
                .param("a", anexo)
                .param("t", token)
                .param("q", Timestamp.from(quando))
                .update();
    }

    private void gravarObjeto(String chave) throws Exception {
        Path temporario = Files.createTempFile("teste-objeto-", ".tmp");
        try {
            Files.write(temporario, AnexosIntegrationTests.PNG);
            armazenamento.gravar(chave, temporario, "image/png");
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    private boolean objeto(String chave) {
        Optional<ArmazenamentoObjetos.Objeto> lido = armazenamento.abrir(chave, null);
        lido.ifPresent(o -> {
            try {
                o.close();
            } catch (java.io.IOException ignorada) {
                // só a existência importa
            }
        });
        return lido.isPresent();
    }

    private long pendentes() {
        return jdbc.sql("select count(*) from atd_objeto_pendente")
                .query(Long.class)
                .single();
    }

    private void ultimaEntrada(long atendimento, Instant instante) {
        jdbc.sql("update atd_conversa set ultima_entrada_em = :h"
                        + " where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("h", Timestamp.from(instante))
                .param("id", atendimento)
                .update();
    }
}
