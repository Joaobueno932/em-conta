package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.emconta.ApiIntegracao;
import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import br.com.emconta.atendimento.nucleo.Envio;
import br.com.emconta.atendimento.nucleo.Recebimento;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Atendimento pelo WhatsApp sobre MySQL real, com a Graph API substituída por um servidor externo
 * CONTROLADO ({@link GraphApiControlada}) e eventos de webhook montados no formato dos exemplos oficiais
 * da Meta e assinados com um App Secret de teste. Não há credencial real nem chamada à Meta.
 */
class AtendimentoIntegrationTests extends ApiIntegracao {

    private static final String SEGREDO = "app-secret-de-teste";
    private static final String NUMERO = "106540352242922";
    private static final GraphApiControlada GRAPH;

    static {
        try {
            GRAPH = new GraphApiControlada();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void canal(DynamicPropertyRegistry registro) {
        registro.add("emconta.whatsapp.access-token", () -> "token-de-teste");
        registro.add("emconta.whatsapp.phone-number-id", () -> NUMERO);
        registro.add("emconta.whatsapp.app-secret", () -> SEGREDO);
        registro.add("emconta.whatsapp.verify-token", () -> "verificacao-de-teste");
        registro.add("emconta.whatsapp.graph-url", GRAPH::url);
        registro.add("emconta.whatsapp.timeout-resposta", () -> "1s");
        // Processamento acionado pelo teste, passo a passo.
        registro.add("emconta.atendimento.processamento.habilitado", () -> "false");
    }

    @AfterAll
    static void parar() {
        GRAPH.close();
    }

    @Autowired
    Recebimento recebimento;

    @Autowired
    Envio envio;

    @BeforeEach
    void limpar() {
        GRAPH.limpar();
    }

    // ------------------------------------------------------------------ webhook

    @Test
    void verificacaoInicialEAssinatura() throws Exception {
        MockHttpServletResponse ok = mvc.perform(get(WEBHOOK)
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "verificacao-de-teste")
                        .param("hub.challenge", "1158201444"))
                .andReturn()
                .getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).isEqualTo("1158201444");
        assertThat(mvc.perform(get(WEBHOOK)
                                .param("hub.mode", "subscribe")
                                .param("hub.verify_token", SEGREDO)
                                .param("hub.challenge", "1"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("App Secret não serve como verify token")
                .isEqualTo(403);

        String corpo = mensagem(numeroUnico(), "Fulano", wamid(), "Olá", agoraEpoch());
        assertThat(enviarWebhook(corpo, "sha256=" + "0".repeat(64)).getStatus()).isEqualTo(401);
        assertThat(enviarWebhook(corpo, null).getStatus()).isEqualTo(401);
        assertThat(enviarWebhook(corpo + " ", assinar(corpo)).getStatus())
                .as("assinatura sobre o corpo bruto exato")
                .isEqualTo(401);
        assertThat(contar("atd_webhook_evento where corpo = :c", corpo)).isZero();
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
        assertThat(contar("atd_webhook_evento where corpo = :c", corpo)).isEqualTo(1);
    }

    @Test
    void textoDeContatoDesconhecidoEntraNaFilaSemCriarAssociado() throws Exception {
        long associadosAntes =
                jdbc.sql("select count(*) from associado").query(Long.class).single();
        String numero = numeroUnico();
        String id = wamid();
        String corpo = mensagem(numero, "Maria Teste", id, "Quero a segunda via", agoraEpoch());
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
        // Duplicado: a mesma entrega e uma reentrega com corpo diferente (mesmo wamid).
        enviarWebhook(corpo, assinar(corpo));
        String reentrega = corpo.replace("\"Maria Teste\"", "\"Maria\"");
        enviarWebhook(reentrega, assinar(reentrega));
        processar();

        assertThat(contar("atd_mensagem where id_externo = :c", id)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from associado").query(Long.class).single())
                .isEqualTo(associadosAntes);

        Navegador admin = administrador();
        JsonNode fila = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=" + numero));
        assertThat(fila.get("totalItens").asLong()).isEqualTo(1);
        JsonNode item = fila.get("itens").get(0);
        assertThat(item.get("situacao").asString()).isEqualTo("AGUARDANDO");
        assertThat(item.has("associado") && !item.get("associado").isNull()).isFalse();
        JsonNode linha = corpo(
                admin.get("/api/v1/atendimento/atendimentos/" + item.get("id").asLong() + "/linha-do-tempo"));
        assertThat(linha.toString()).contains("Quero a segunda via");
        assertThat(jdbc.sql(
                                "select count(*) from atd_conversa v join atd_contato c on c.id = v.contato_id where c.wa_id = :n")
                        .param("n", numero)
                        .query(Long.class)
                        .single())
                .as("uma conversa por contato")
                .isEqualTo(1);
    }

    @Test
    void outroNumeroETipoNaoSuportadoNaoSaoDescartadosEmSilencio() throws Exception {
        String numero = numeroUnico();
        String outro =
                mensagem(numero, "X", wamid(), "de outro número", agoraEpoch()).replace(NUMERO, "999999999");
        enviarWebhook(outro, assinar(outro));
        String imagem = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"value\":{"
                + "\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"15550783881\","
                + "\"phone_number_id\":\"" + NUMERO
                + "\"},\"contacts\":[{\"profile\":{\"name\":\"Imagem\"},\"wa_id\":\""
                + numero + "\"}],\"messages\":[{\"from\":\"" + numero + "\",\"id\":\"" + wamid() + "\",\"timestamp\":\""
                + agoraEpoch() + "\",\"type\":\"image\",\"image\":{\"id\":\"123\",\"mime_type\":\"image/jpeg\"}}]},"
                + "\"field\":\"messages\"}]}]}";
        enviarWebhook(imagem, assinar(imagem));
        processar();

        assertThat(jdbc.sql("select ignorados from atd_webhook_evento where corpo = :c")
                        .param("c", outro)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(contar("atd_mensagem where texto = :c", "de outro número")).isZero();
        Map<String, Object> msg = jdbc.sql("select m.tipo, m.tipo_externo from atd_mensagem m join atd_conversa v on"
                        + " v.id = m.conversa_id join atd_contato c on c.id = v.contato_id where c.wa_id = :n")
                .param("n", numero)
                .query()
                .singleRow();
        assertThat(msg.get("tipo")).isEqualTo("NAO_SUPORTADO");
        assertThat(msg.get("tipo_externo")).isEqualTo("image");
    }

    // ------------------------------------------------------------------ fila e responsabilidade

    @Test
    void duasPessoasNaoAssumemOMesmoAtendimento() throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        Navegador a = administrador();
        Navegador b = administrador();
        List<Callable<MockHttpServletResponse>> tarefas = List.of(
                () -> a.post("/api/v1/atendimento/atendimentos/" + atendimento + "/assuncao", null),
                () -> b.post("/api/v1/atendimento/atendimentos/" + atendimento + "/assuncao", null));
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            status.add(f.get().getStatus());
        }
        assertThat(status).containsExactlyInAnyOrder(204, 409);
        assertThat(contar("atd_evento where atendimento_id = :c and tipo = 'ASSUMIDO'", atendimento))
                .isEqualTo(1);
    }

    @Test
    void respostaExigeResponsavelEAceiteNaoEConfirmacaoDeEntrega() throws Exception {
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        Navegador admin = administrador();
        String chave = UUID.randomUUID().toString();
        Map<String, Object> resposta = Map.of("texto", "Olá! Vou verificar.", "chaveIdempotencia", chave);

        MockHttpServletResponse semAssumir = admin.post(url(atendimento, "mensagens"), resposta);
        assertThat(semAssumir.getStatus()).isEqualTo(409);
        assertThat(codigo(semAssumir)).isEqualTo("NAO_E_RESPONSAVEL");

        admin.post(url(atendimento, "assuncao"), null);
        MockHttpServletResponse aceita = admin.post(url(atendimento, "mensagens"), resposta);
        assertThat(aceita.getStatus()).isEqualTo(202);
        long mensagem = corpo(aceita).get("mensagemId").asLong();
        // Repetição da mesma requisição (clique duplo): mesma mensagem, um único envio.
        assertThat(corpo(admin.post(url(atendimento, "mensagens"), resposta))
                        .get("mensagemId")
                        .asLong())
                .isEqualTo(mensagem);
        assertThat(situacao(mensagem)).isEqualTo("PENDENTE");

        processar();
        assertThat(GRAPH.requisicoes()).hasSize(1);
        String enviada = GRAPH.requisicoes().getFirst();
        assertThat(enviada).startsWith("/v23.0/" + NUMERO + "/messages ");
        assertThat(enviada).contains("\"to\":\"" + numero + "\"", "\"type\":\"text\"", "Olá! Vou verificar.");
        assertThat(enviada).contains("\"biz_opaque_callback_data\":\"" + correlacao(mensagem) + "\"");
        assertThat(GRAPH.autorizacoes().getFirst()).isEqualTo("Bearer token-de-teste");
        assertThat(situacao(mensagem)).as("aceita pela Meta não é entregue").isEqualTo("ACEITA");

        // Eventos fora de ordem: entregue antes de enviada; lida depois; enviada atrasada não regride.
        String id = jdbc.sql("select id_externo from atd_mensagem where id = :id")
                .param("id", mensagem)
                .query(String.class)
                .single();
        webhookSituacao(id, "delivered", null, null);
        webhookSituacao(id, "sent", null, null);
        processar();
        assertThat(situacao(mensagem)).isEqualTo("ENTREGUE");
        webhookSituacao(id, "read", null, null);
        webhookSituacao(id, "sent", null, null);
        processar();
        assertThat(situacao(mensagem)).isEqualTo("LIDA");
        assertThat(contar("atd_mensagem_situacao where mensagem_id = :c", mensagem))
                .as("histórico completo")
                .isEqualTo(4);
    }

    @Test
    void erroExternoDefinitivoTemporarioETimeoutSemReenvio() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());

        GRAPH.modo(Modo.ERRO_DEFINITIVO);
        long falha = responder(admin, atendimento, "não entregável");
        processar();
        assertThat(situacao(falha)).isEqualTo("FALHA");
        assertThat(jdbc.sql("select erro_codigo from atd_mensagem where id = :id")
                        .param("id", falha)
                        .query(Integer.class)
                        .single())
                .isEqualTo(131026);

        GRAPH.modo(Modo.ERRO_TEMPORARIO);
        long temporaria = responder(admin, atendimento, "temporária");
        processar();
        assertThat(situacao(temporaria)).as("volta para a fila com espera").isEqualTo("PENDENTE");
        GRAPH.modo(Modo.ACEITAR);
        jdbc.sql("update atd_mensagem set proxima_tentativa_em = :agora where id = :id")
                .param("agora", Timestamp.from(Instant.now().minusSeconds(1)))
                .param("id", temporaria)
                .update();
        processar();
        assertThat(situacao(temporaria)).isEqualTo("ACEITA");

        GRAPH.modo(Modo.SEM_RESPOSTA);
        GRAPH.requisicoes().clear();
        long desconhecida = responder(admin, atendimento, "talvez enviada");
        processar();
        assertThat(situacao(desconhecida)).isEqualTo("DESCONHECIDA");
        GRAPH.modo(Modo.ACEITAR);
        processar();
        assertThat(GRAPH.requisicoes()).as("sem reenvio automático").hasSize(1);
        // A Meta informa depois, correlacionando pelo biz_opaque_callback_data.
        webhookSituacao("wamid.RECUPERADO" + desconhecida, "sent", correlacao(desconhecida), null);
        processar();
        assertThat(situacao(desconhecida)).isEqualTo("ENVIADA");
        assertThat(jdbc.sql("select id_externo from atd_mensagem where id = :id")
                        .param("id", desconhecida)
                        .query(String.class)
                        .single())
                .isEqualTo("wamid.RECUPERADO" + desconhecida);

        // Falha informada pelo webhook depois do aceite também fica registrada.
        long recusada = responder(admin, atendimento, "falha posterior");
        processar();
        String id = jdbc.sql("select id_externo from atd_mensagem where id = :id")
                .param("id", recusada)
                .query(String.class)
                .single();
        webhookSituacao(id, "failed", null, 131047);
        processar();
        assertThat(situacao(recusada)).isEqualTo("FALHA");
    }

    @Test
    void reinicioNaoPerdeEventosNemReenviaReservaInterrompida() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        long mensagem = responder(admin, atendimento, "interrompida");
        // Simula queda do processo durante o envio: reserva antiga em ENVIANDO.
        jdbc.sql("update atd_mensagem set situacao = 'ENVIANDO', atualizado_em = :antes where id = :id")
                .param("antes", Timestamp.from(Instant.now().minusSeconds(600)))
                .param("id", mensagem)
                .update();
        processar();
        assertThat(situacao(mensagem)).isEqualTo("DESCONHECIDA");
        assertThat(GRAPH.requisicoes()).noneMatch(r -> r.contains("interrompida"));

        // Evento gravado e ainda não processado continua pendente e é processado depois.
        String numero = numeroUnico();
        String corpo = mensagem(numero, "Depois", wamid(), "chegou antes do reinício", agoraEpoch());
        enviarWebhook(corpo, assinar(corpo));
        assertThat(contar("atd_webhook_evento where corpo = :c and situacao = 'PENDENTE'", corpo))
                .isEqualTo(1);
        processar();
        assertThat(contar("atd_mensagem where texto = :c", "chegou antes do reinício"))
                .isEqualTo(1);

        // Falha de processamento: retentativas e, ao final, FALHA com reprocessamento manual.
        String quebrado = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"field\":\"messages\","
                + "\"value\":{\"metadata\":{\"phone_number_id\":\"" + NUMERO
                + "\"},\"messages\":[{\"from\":\"1\",\"id\":\""
                + wamid() + "\",\"type\":\"text\",\"text\":{\"body\":\"" + "x".repeat(70_000) + "\"}}]}}]}]}";
        enviarWebhook(quebrado, assinar(quebrado));
        for (int i = 0; i < 5; i++) {
            jdbc.sql("update atd_webhook_evento set proxima_tentativa_em = :agora where corpo = :c")
                    .param("agora", Timestamp.from(Instant.now().minusSeconds(1)))
                    .param("c", quebrado)
                    .update();
            recebimento.processarPendentes(10);
        }
        long evento = jdbc.sql("select id from atd_webhook_evento where corpo = :c")
                .param("c", quebrado)
                .query(Long.class)
                .single();
        assertThat(jdbc.sql("select situacao from atd_webhook_evento where id = :id")
                        .param("id", evento)
                        .query(String.class)
                        .single())
                .isEqualTo("FALHA");
        assertThat(corpo(admin.get("/api/v1/atendimento/webhook-falhas")).toString())
                .contains("\"id\":" + evento);
        assertThat(admin.post("/api/v1/atendimento/webhook-eventos/" + evento + "/reprocessamento", null)
                        .getStatus())
                .isEqualTo(204);
        assertThat(jdbc.sql("select situacao from atd_webhook_evento where id = :id")
                        .param("id", evento)
                        .query(String.class)
                        .single())
                .isEqualTo("PENDENTE");
    }

    @Test
    void janelaDe24HorasBloqueiaTextoLivre() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        jdbc.sql(
                        "update atd_conversa set ultima_entrada_em = :antes where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("antes", Timestamp.from(Instant.now().minusSeconds(25 * 3600)))
                .param("id", atendimento)
                .update();
        MockHttpServletResponse resposta = admin.post(
                url(atendimento, "mensagens"),
                Map.of(
                        "texto",
                        "fora da janela",
                        "chaveIdempotencia",
                        UUID.randomUUID().toString()));
        assertThat(resposta.getStatus()).isEqualTo(409);
        assertThat(codigo(resposta)).isEqualTo("JANELA_ENCERRADA");
        assertThat(contar("atd_mensagem where texto = :c", "fora da janela")).isZero();
    }

    @Test
    void transferenciaEncerramentoERetornoAbremNovoAtendimento() throws Exception {
        String numero = numeroUnico();
        Navegador admin = administrador();
        long atendimento = assumido(admin, numero);
        long versao = versao(atendimento);
        String emailDestino = emailUnico("destino");
        Usuario destino = criarAtivo(emailDestino, Perfil.ADMINISTRADOR);
        Usuario semPermissao = criarAtivo(emailUnico("sem-permissao"));

        MockHttpServletResponse invalido = admin.post(
                url(atendimento, "transferencia"), Map.of("versao", versao, "destinoId", semPermissao.getId()));
        assertThat(invalido.getStatus()).isEqualTo(400);
        assertThat(corpo(admin.get("/api/v1/atendimento/responsaveis")).toString())
                .contains("\"id\":" + destino.getId());

        assertThat(admin.post(
                                url(atendimento, "transferencia"),
                                Map.of("versao", versao, "destinoId", destino.getId(), "motivo", "Assunto financeiro"))
                        .getStatus())
                .isEqualTo(204);
        assertThat(codigo(admin.post(url(atendimento, "transferencia"), Map.of("versao", versao))))
                .as("versão antiga")
                .isEqualTo("CONFLITO_CONCORRENTE");
        Navegador novoResponsavel = logado(emailDestino);
        assertThat(responder(novoResponsavel, atendimento, "assumi após a transferência"))
                .isPositive();
        assertThat(codigo(admin.post(
                        url(atendimento, "mensagens"),
                        Map.of(
                                "texto",
                                "não sou mais responsável",
                                "chaveIdempotencia",
                                UUID.randomUUID().toString()))))
                .isEqualTo("NAO_E_RESPONSAVEL");

        // Responsável desativado não pode receber novas transferências.
        jdbc.sql("update usuario set situacao = 'DESATIVADO', versao_seguranca = versao_seguranca + 1 where id = :id")
                .param("id", semPermissao.getId())
                .update();

        assertThat(admin.post(url(atendimento, "encerramento"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
        JsonNode linha = corpo(
                admin.get("/api/v1/atendimento/atendimentos/" + atendimento + "/linha-do-tempo?somenteEste=true"));
        assertThat(linha.toString())
                .contains("\"evento\":\"TRANSFERIDO\"", "\"evento\":\"ENCERRADO\"", "Assunto financeiro");
        assertThat(codigo(admin.post(url(atendimento, "assuncao"), null))).isEqualTo("ATENDIMENTO_ENCERRADO");

        // Mensagem após o encerramento: novo atendimento AGUARDANDO, sem responsável; o anterior fica no histórico.
        String retorno = mensagem(numero, "Retorno", wamid(), "voltei", agoraEpoch());
        enviarWebhook(retorno, assinar(retorno));
        processar();
        JsonNode aguardando = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=" + numero));
        assertThat(aguardando.get("totalItens").asLong()).isEqualTo(1);
        assertThat(aguardando.get("itens").get(0).get("id").asLong()).isNotEqualTo(atendimento);
        JsonNode historico = corpo(admin.get("/api/v1/atendimento/historico?busca=" + numero));
        assertThat(historico.get("itens").get(0).get("id").asLong()).isEqualTo(atendimento);
        assertThat(historico.get("itens").get(0).get("encerradoPor").get("id").asLong())
                .isPositive();
    }

    @Test
    void vinculoComAssociadoAmbiguoExigeEscolhaECorrecaoComMotivo() throws Exception {
        String numero = numeroUnico();
        Navegador admin = administrador();
        long primeiro = associadoComTelefone(admin, numero);
        long segundo = associadoComTelefone(admin, numero);
        long atendimento = novoAtendimento(numero);

        JsonNode sugeridos = corpo(admin.get(url(atendimento, "associados-sugeridos")));
        assertThat(sugeridos).hasSize(2);
        assertThat(sugeridos.toString()).doesNotContain(numero).contains("***");
        assertThat(contar(
                        "atd_conversa where associado_id is not null and id = (select conversa_id from atd_atendimento"
                                + " where id = :c)",
                        atendimento))
                .as("nada é vinculado automaticamente")
                .isZero();

        long versaoConversa = corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento))
                .get("versaoConversa")
                .asLong();
        assertThat(admin.put(
                                url(atendimento, "associado"),
                                Map.of("versaoConversa", versaoConversa, "associadoId", primeiro))
                        .getStatus())
                .isEqualTo(204);
        JsonNode semMotivo = corpo(admin.put(
                url(atendimento, "associado"), Map.of("versaoConversa", versaoConversa + 1, "associadoId", segundo)));
        assertThat(semMotivo.get("campos").has("motivo")).isTrue();
        assertThat(admin.put(
                                url(atendimento, "associado"),
                                Map.of(
                                        "versaoConversa",
                                        versaoConversa + 1,
                                        "associadoId",
                                        segundo,
                                        "motivo",
                                        "Titular correto"))
                        .getStatus())
                .isEqualTo(204);
        JsonNode linha = corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento + "/linha-do-tempo"));
        assertThat(linha.toString()).contains("ASSOCIADO_VINCULADO", "ASSOCIADO_ALTERADO", "Titular correto");
        assertThat(corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento))
                        .get("resumo")
                        .get("associado")
                        .get("id")
                        .asLong())
                .isEqualTo(segundo);
    }

    // ------------------------------------------------------------------ autorização e sessão

    @Test
    void permissoesParciaisESessaoRevogada() throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        Navegador leitor = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR);
        assertThat(leitor.get("/api/v1/atendimento/atendimentos").getStatus()).isEqualTo(200);
        assertThat(leitor.get("/api/v1/atendimento/atendimentos/" + atendimento + "/linha-do-tempo")
                        .getStatus())
                .isEqualTo(200);
        for (MockHttpServletResponse negada : List.of(
                leitor.post(url(atendimento, "assuncao"), null),
                leitor.post(
                        url(atendimento, "mensagens"),
                        Map.of(
                                "texto",
                                "x",
                                "chaveIdempotencia",
                                UUID.randomUUID().toString())),
                leitor.post(url(atendimento, "transferencia"), Map.of("versao", 0)),
                leitor.post(url(atendimento, "encerramento"), Map.of("versao", 0)),
                leitor.put(url(atendimento, "associado"), Map.of("versaoConversa", 0)),
                leitor.get(url(atendimento, "associados-sugeridos")),
                leitor.get("/api/v1/atendimento/responsaveis"),
                leitor.get("/api/v1/atendimento/webhook-falhas"))) {
            assertThat(negada.getStatus()).isEqualTo(403);
            assertThat(codigo(negada)).isEqualTo("ACESSO_NEGADO");
        }

        Navegador assume = comPermissoes(Permissao.ATENDIMENTO_ASSUMIR);
        assertThat(assume.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(assume.get("/api/v1/atendimento/atendimentos").getStatus()).isEqualTo(403);

        String email = emailUnico("revogada");
        Usuario usuario = criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador sessao = logado(email);
        assertThat(sessao.get("/api/v1/atendimento/atendimentos").getStatus()).isEqualTo(200);
        jdbc.sql("update usuario set situacao = 'DESATIVADO', versao_seguranca = versao_seguranca + 1 where id = :id")
                .param("id", usuario.getId())
                .update();
        assertThat(sessao.get("/api/v1/atendimento/atendimentos").getStatus()).isEqualTo(401);
        assertThat(new Navegador(mvc, json)
                        .get("/api/v1/atendimento/atendimentos")
                        .getStatus())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ apoio

    private static final String WEBHOOK = "/api/v1/integracoes/whatsapp/webhook";

    private static String url(long atendimento, String acao) {
        return "/api/v1/atendimento/atendimentos/" + atendimento + "/" + acao;
    }

    private void processar() {
        recebimento.processarPendentes(50);
        envio.enviarPendentes(20);
    }

    private MockHttpServletResponse enviarWebhook(String corpo, String assinatura) throws Exception {
        var requisicao =
                post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(corpo.getBytes(StandardCharsets.UTF_8));
        if (assinatura != null) {
            requisicao = requisicao.header("X-Hub-Signature-256", assinatura);
        }
        return mvc.perform(requisicao).andReturn().getResponse();
    }

    private static String assinar(String corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
    }

    /** Notificação de mensagem de texto no formato do exemplo oficial da Meta. */
    private static String mensagem(String de, String nome, String id, String texto, long timestamp) {
        return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"102290129340398\",\"changes\":[{\"value\":{"
                + "\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"15550783881\","
                + "\"phone_number_id\":\"" + NUMERO + "\"},\"contacts\":[{\"profile\":{\"name\":\"" + nome + "\"},"
                + "\"wa_id\":\"" + de + "\"}],\"messages\":[{\"from\":\"" + de + "\",\"id\":\"" + id
                + "\",\"timestamp\":\""
                + timestamp + "\",\"type\":\"text\",\"text\":{\"body\":\"" + texto
                + "\"}}]},\"field\":\"messages\"}]}]}";
    }

    private void webhookSituacao(String idExterno, String situacao, String correlacao, Integer erro) throws Exception {
        String corpo = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"102290129340398\",\"changes\":[{"
                + "\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"15550783881\","
                + "\"phone_number_id\":\"" + NUMERO + "\"},\"statuses\":[{\"id\":\"" + idExterno + "\",\"status\":\""
                + situacao + "\",\"timestamp\":\"" + agoraEpoch() + "\",\"recipient_id\":\"5567999990000\""
                + (correlacao == null ? "" : ",\"biz_opaque_callback_data\":\"" + correlacao + "\"")
                + (erro == null ? "" : ",\"errors\":[{\"code\":" + erro + ",\"title\":\"Re-engagement message\"}]")
                + "}]},\"field\":\"messages\"}]}]}";
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
    }

    private long novoAtendimento(String numero) throws Exception {
        String corpo = mensagem(numero, "Contato " + numero, wamid(), "Olá", agoraEpoch());
        enviarWebhook(corpo, assinar(corpo));
        recebimento.processarPendentes(50);
        return jdbc.sql("select a.id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where c.wa_id = :n and a.situacao <> 'ENCERRADO'")
                .param("n", numero)
                .query(Long.class)
                .single();
    }

    private long assumido(Navegador admin, String numero) throws Exception {
        long atendimento = novoAtendimento(numero);
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        return atendimento;
    }

    private long responder(Navegador navegador, long atendimento, String texto) {
        MockHttpServletResponse resposta = navegador.post(
                url(atendimento, "mensagens"),
                Map.of("texto", texto, "chaveIdempotencia", UUID.randomUUID().toString()));
        assertThat(resposta.getStatus()).isEqualTo(202);
        return corpo(resposta).get("mensagemId").asLong();
    }

    private long associadoComTelefone(Navegador admin, String numero) {
        JsonNode criado = corpo(admin.post(
                "/api/v1/associados",
                Map.of(
                        "tipo",
                        "PJ",
                        "documento",
                        cnpjValido(),
                        "nome",
                        "Associado " + UUID.randomUUID(),
                        "contatos",
                        List.of(Map.of("nome", "Contato", "telefone", numero.substring(2))))));
        return criado.get("id").asLong();
    }

    private long versao(long atendimento) {
        return jdbc.sql("select versao from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query(Long.class)
                .single();
    }

    private String situacao(long mensagem) {
        return jdbc.sql("select situacao from atd_mensagem where id = :id")
                .param("id", mensagem)
                .query(String.class)
                .single();
    }

    private String correlacao(long mensagem) {
        return jdbc.sql("select correlacao from atd_mensagem where id = :id")
                .param("id", mensagem)
                .query(String.class)
                .single();
    }

    private long contar(String deOnde, Object valor) {
        return jdbc.sql("select count(*) from " + deOnde)
                .param("c", valor)
                .query(Long.class)
                .single();
    }

    private static String numeroUnico() {
        return "5567" + (900000000L + ThreadLocalRandom.current().nextLong(99_999_999L));
    }

    private static String wamid() {
        return "wamid.TESTE" + UUID.randomUUID().toString().replace("-", "");
    }

    private static long agoraEpoch() {
        return Instant.now().getEpochSecond();
    }
}
