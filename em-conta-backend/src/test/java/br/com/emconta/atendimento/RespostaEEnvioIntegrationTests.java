package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Resposta do atendente: idempotência vinculada à operação (autor, atendimento e texto) e revalidação da
 * janela de 24 h no envio assíncrono, com o relógio da aplicação fixado pelo teste (sem esperas).
 */
class RespostaEEnvioIntegrationTests extends AtendimentoIntegracao {

    private static final Duration JANELA = Duration.ofHours(24);

    // ------------------------------------------------------------------ idempotência

    @Test
    void repeticaoExataDevolveAMesmaMensagemSemNovoEnvio() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        String chave = UUID.randomUUID().toString();
        String texto = "Mensagem idempotente " + chave;

        long primeira = idDaMensagem(admin.post(url(atendimento, "mensagens"), resposta(texto, chave)));
        // Resposta HTTP perdida: o cliente repete a mesma requisição.
        long repetida = idDaMensagem(admin.post(url(atendimento, "mensagens"), resposta(texto, chave)));
        assertThat(repetida).isEqualTo(primeira);
        // Normalização: o texto aceito é o texto sem espaços nas pontas (strip), e é ele que é comparado.
        assertThat(idDaMensagem(admin.post(url(atendimento, "mensagens"), resposta("  " + texto + "\n", chave))))
                .isEqualTo(primeira);
        assertThat(contar("atd_mensagem where chave_idempotencia = :c", chave)).isEqualTo(1);

        processar();
        assertThat(situacao(primeira)).isEqualTo("ACEITA");
        // Repetir depois do envio não reenfileira.
        assertThat(idDaMensagem(admin.post(url(atendimento, "mensagens"), resposta(texto, chave))))
                .isEqualTo(primeira);
        processar();
        assertThat(situacao(primeira)).isEqualTo("ACEITA");
        assertThat(enviosCom(texto)).isEqualTo(1);
    }

    @Test
    void mesmaChaveComOutroTextoAtendimentoOuAutorEConflito() throws Exception {
        String email = emailUnico("autor");
        Usuario autor = criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador sessao = logado(email);
        String emailOutro = emailUnico("outro");
        Usuario outro = criarAtivo(emailOutro, Perfil.ADMINISTRADOR);
        Navegador sessaoOutro = logado(emailOutro);
        long atendimento = assumido(sessao, numeroUnico());
        long segundo = assumido(sessao, numeroUnico());
        String chave = UUID.randomUUID().toString();
        long mensagem = idDaMensagem(sessao.post(url(atendimento, "mensagens"), resposta("Texto original", chave)));

        MockHttpServletResponse outroTexto =
                sessao.post(url(atendimento, "mensagens"), resposta("Texto diferente", chave));
        MockHttpServletResponse outroAtendimento =
                sessao.post(url(segundo, "mensagens"), resposta("Texto original", chave));
        for (MockHttpServletResponse recusada : List.of(outroTexto, outroAtendimento)) {
            assertThat(recusada.getStatus()).isEqualTo(409);
            assertThat(codigo(recusada)).isEqualTo("CHAVE_IDEMPOTENCIA_REUTILIZADA");
            assertThat(recusada.getContentAsString()).doesNotContain(String.valueOf(mensagem));
        }

        // Outro autor, mesmo atendimento e mesmo texto (depois de receber a transferência).
        assertThat(sessao.post(
                                url(atendimento, "transferencia"),
                                Map.of("versao", versao(atendimento), "destinoId", outro.getId()))
                        .getStatus())
                .isEqualTo(204);
        MockHttpServletResponse outroAutor =
                sessaoOutro.post(url(atendimento, "mensagens"), resposta("Texto original", chave));
        assertThat(outroAutor.getStatus()).isEqualTo(409);
        assertThat(codigo(outroAutor)).isEqualTo("CHAVE_IDEMPOTENCIA_REUTILIZADA");

        // O autor original repetindo a própria operação depois da transferência e do encerramento: a mesma
        // mensagem, sem nova gravação.
        assertThat(idDaMensagem(sessao.post(url(atendimento, "mensagens"), resposta("Texto original", chave))))
                .isEqualTo(mensagem);
        assertThat(sessaoOutro
                        .post(url(atendimento, "encerramento"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
        assertThat(idDaMensagem(sessao.post(url(atendimento, "mensagens"), resposta("Texto original", chave))))
                .isEqualTo(mensagem);

        assertThat(contar("atd_mensagem where chave_idempotencia = :c", chave)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from atd_mensagem where atendimento_id in (:a, :b) and direcao = 'SAIDA'")
                        .param("a", atendimento)
                        .param("b", segundo)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        processar();
        assertThat(enviosCom("Texto original")).isEqualTo(1);
        assertThat(enviosCom("Texto diferente")).isZero();
        assertThat(autor.getId()).isNotEqualTo(outro.getId());
    }

    @Test
    void requisicoesSimultaneasIdenticasConvergemParaUmaMensagem() throws Exception {
        String email = emailUnico("simultaneo");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        long atendimento = assumido(logado(email), numeroUnico());
        for (int rodada = 0; rodada < 3; rodada++) {
            String chave = UUID.randomUUID().toString();
            String texto = "Clique duplo " + chave;
            List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Navegador aba = logado(email);
                tarefas.add(() -> aba.post(url(atendimento, "mensagens"), resposta(texto, chave)));
            }
            List<Long> ids = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
                ids.add(idDaMensagem(f.get()));
            }
            assertThat(ids).as("todas recebem a mesma mensagem").containsOnly(ids.getFirst());
            assertThat(contar("atd_mensagem where chave_idempotencia = :c", chave))
                    .isEqualTo(1);
            processar();
            assertThat(enviosCom(texto)).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ janela de 24 h no envio

    @Test
    void registradaDentroDaJanelaEProcessadaDepoisNaoEEnviada() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(JANELA).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = responderTexto(admin, atendimento, "quase no limite");

        relogio.fixar(t0.plusSeconds(61));
        processar();
        assertThat(situacao(mensagem)).isEqualTo("JANELA_EXPIRADA");
        assertThat(GRAPH.requisicoes()).as("nenhuma chamada à Graph").isEmpty();
        Map<String, Object> linha = jdbc.sql(
                        "select erro_codigo, erro_titulo, proxima_tentativa_em from atd_mensagem where id = :id")
                .param("id", mensagem)
                .query()
                .singleRow();
        assertThat(linha.get("erro_codigo")).as("não é recusa do provedor").isNull();
        assertThat((String) linha.get("erro_titulo")).contains("janela de 24 horas");
        assertThat(linha.get("proxima_tentativa_em")).isNull();
        assertThat(contar("atd_mensagem_situacao where situacao = 'JANELA_EXPIRADA' and mensagem_id = :c", mensagem))
                .isEqualTo(1);

        // Situação final: não volta para a fila, nem com uma nova mensagem do contato.
        relogio.fixar(t0.plusSeconds(120));
        novaEntrada(atendimento, t0.plusSeconds(120));
        processar();
        assertThat(situacao(mensagem)).isEqualTo("JANELA_EXPIRADA");
        assertThat(GRAPH.requisicoes()).isEmpty();
        assertThat(corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento + "/linha-do-tempo"))
                        .toString())
                .contains("\"situacao\":\"JANELA_EXPIRADA\"");
    }

    @Test
    void novaTentativaDeErroTemporarioRevalidaAJanela() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(JANELA).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = responderTexto(admin, atendimento, "temporária perto do limite");

        GRAPH.modo(Modo.ERRO_TEMPORARIO);
        processar();
        assertThat(situacao(mensagem)).isEqualTo("PENDENTE");
        assertThat(GRAPH.requisicoes()).hasSize(1);

        GRAPH.modo(Modo.ACEITAR);
        relogio.fixar(t0.plusSeconds(3600)); // depois da próxima tentativa e da janela
        processar();
        assertThat(situacao(mensagem)).isEqualTo("JANELA_EXPIRADA");
        assertThat(GRAPH.requisicoes())
                .as("a nova tentativa não chamou a Graph")
                .hasSize(1);
    }

    @Test
    void novaEntradaDoContatoAntesDoProcessamentoRenovaAJanela() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(JANELA).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = responderTexto(admin, atendimento, "renovada pelo contato");

        relogio.fixar(t0.plusSeconds(30));
        novaEntrada(atendimento, t0.plusSeconds(30));
        relogio.fixar(t0.plusSeconds(600));
        processar();
        assertThat(situacao(mensagem)).isEqualTo("ACEITA");
        assertThat(enviosCom("renovada pelo contato")).isEqualTo(1);
    }

    @Test
    void limiteExatoDaJanelaIgualNoRegistroENoEnvio() throws Exception {
        Instant entrada = Instant.now().minus(JANELA).plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        Instant limite = entrada.plus(JANELA);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, entrada);

        // No instante exato do limite a janela ainda está aberta (registro e envio).
        relogio.fixar(limite);
        long noLimite = responderTexto(admin, atendimento, "no limite exato");
        processar();
        assertThat(situacao(noLimite)).isEqualTo("ACEITA");

        // Registrada no limite e processada 1 µs depois: não enviada.
        long depois = responderTexto(admin, atendimento, "um microssegundo depois");
        relogio.fixar(limite.plusNanos(1_000));
        processar();
        assertThat(situacao(depois)).isEqualTo("JANELA_EXPIRADA");
        MockHttpServletResponse fora = admin.post(
                url(atendimento, "mensagens"),
                resposta("registro fora", UUID.randomUUID().toString()));
        assertThat(codigo(fora)).isEqualTo("JANELA_ENCERRADA");
        assertThat(enviosCom("um microssegundo depois")).isZero();
        assertThat(GRAPH.requisicoes()).hasSize(1);
    }

    @Test
    void resultadoDesconhecidoContinuaSemReenvioDepoisDaJanela() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(JANELA).plusSeconds(60));
        relogio.fixar(t0);
        long mensagem = responderTexto(admin, atendimento, "talvez enviada perto do limite");
        GRAPH.modo(Modo.SEM_RESPOSTA);
        processar();
        assertThat(situacao(mensagem)).isEqualTo("DESCONHECIDA");

        GRAPH.modo(Modo.ACEITAR);
        relogio.fixar(t0.plusSeconds(3600));
        processar();
        assertThat(situacao(mensagem))
                .as("não vira JANELA_EXPIRADA nem é reenviada")
                .isEqualTo("DESCONHECIDA");
        assertThat(GRAPH.requisicoes()).hasSize(1);
    }

    // ------------------------------------------------------------------ apoio

    private static Map<String, Object> resposta(String texto, String chave) {
        return Map.of("texto", texto, "chaveIdempotencia", chave);
    }

    private long idDaMensagem(MockHttpServletResponse resposta) {
        assertThat(resposta.getStatus()).isEqualTo(202);
        return corpo(resposta).get("mensagemId").asLong();
    }

    private long responderTexto(Navegador navegador, long atendimento, String texto) {
        return idDaMensagem(navegador.post(
                url(atendimento, "mensagens"), resposta(texto, UUID.randomUUID().toString())));
    }

    private long enviosCom(String texto) {
        return GRAPH.requisicoes().stream()
                .filter(r -> r.contains("\"" + texto + "\""))
                .count();
    }

    private void ultimaEntrada(long atendimento, Instant instante) {
        jdbc.sql("update atd_conversa set ultima_entrada_em = :h"
                        + " where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("h", Timestamp.from(instante))
                .param("id", atendimento)
                .update();
    }

    /** Mensagem real do contato pelo webhook assinado, com o horário informado. */
    private void novaEntrada(long atendimento, Instant horario) throws Exception {
        String numero = jdbc.sql("select c.wa_id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where a.id = :id")
                .param("id", atendimento)
                .query(String.class)
                .single();
        String corpo = mensagem(numero, "Contato", wamid(), "nova mensagem", horario.getEpochSecond());
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
        recebimento.processarPendentes(50);
    }
}
