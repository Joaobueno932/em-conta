package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.emconta.ApiIntegracao;
import br.com.emconta.acesso.Navegador;
import br.com.emconta.atendimento.nucleo.Envio;
import br.com.emconta.atendimento.nucleo.Recebimento;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Base dos testes de atendimento: MySQL real, a Graph API substituída por um servidor externo
 * CONTROLADO ({@link GraphApiControlada}) e eventos de webhook no formato dos exemplos oficiais da Meta,
 * assinados com um App Secret de teste. Não há credencial real nem chamada à Meta. O relógio da
 * aplicação é {@link RelogioControlado}: o teste pode fixar o instante sem esperar (sem sleeps).
 */
@Import(AtendimentoIntegracao.Relogio.class)
abstract class AtendimentoIntegracao extends ApiIntegracao {

    protected static final String SEGREDO = "app-secret-de-teste";
    protected static final String NUMERO = "106540352242922";
    protected static final GraphApiControlada GRAPH;

    /**
     * MinIO real (mesma imagem fixada no Docker Compose) para os anexos, com credenciais de teste. Um
     * contêiner por JVM, como o MySQL, e um bucket só dos testes.
     */
    protected static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
                    "cgr.dev/chainguard/minio@sha256:e70b2b1e74d79457f808710483bd726ff364529ca30736eaa15a6ab547d3a5a7"))
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "teste-minio")
            .withEnv("MINIO_ROOT_PASSWORD", "teste-minio-segredo")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    protected static final String BUCKET = "anexos-teste";

    static {
        try {
            GRAPH = new GraphApiControlada();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        MINIO.start();
    }

    @DynamicPropertySource
    static void canal(DynamicPropertyRegistry registro) {
        registro.add("emconta.whatsapp.access-token", () -> "token-de-teste");
        registro.add("emconta.whatsapp.phone-number-id", () -> NUMERO);
        registro.add("emconta.whatsapp.app-secret", () -> SEGREDO);
        registro.add("emconta.whatsapp.verify-token", () -> "verificacao-de-teste");
        registro.add("emconta.whatsapp.graph-url", GRAPH::url);
        registro.add("emconta.whatsapp.timeout-resposta", () -> "1s");
        registro.add("emconta.whatsapp.timeout-download", () -> "1s");
        // Processamento acionado pelo teste, passo a passo.
        registro.add("emconta.atendimento.processamento.habilitado", () -> "false");
        registro.add(
                "emconta.armazenamento.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registro.add("emconta.armazenamento.bucket", () -> BUCKET);
        registro.add("emconta.armazenamento.chave-acesso", () -> "teste-minio");
        registro.add("emconta.armazenamento.chave-secreta", () -> "teste-minio-segredo");
        registro.add("emconta.armazenamento.criar-bucket", () -> "true");
        registro.add("emconta.armazenamento.timeout-resposta", () -> "2s");
        // Limites pequenos para testar a recusa com arquivos de poucos KB.
        registro.add("emconta.atendimento.anexos.limite-imagem", () -> "64KB");
        registro.add("emconta.atendimento.anexos.limite-documento", () -> "64KB");
        registro.add("emconta.atendimento.anexos.limite-audio", () -> "64KB");
    }

    /** Relógio da aplicação nos testes: o do sistema, ou um instante fixado pelo teste. */
    static final class RelogioControlado extends Clock {

        private volatile Instant fixo;

        void fixar(Instant instante) {
            fixo = instante;
        }

        void liberar() {
            fixo = null;
        }

        @Override
        public Instant instant() {
            Instant f = fixo;
            return f != null ? f : Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Relogio {
        @Bean
        @Primary
        RelogioControlado relogioControlado() {
            return new RelogioControlado();
        }
    }

    @AfterAll
    static void parar() {
        GRAPH.limpar();
    }

    @Autowired
    protected Recebimento recebimento;

    @Autowired
    protected Envio envio;

    @Autowired
    protected br.com.emconta.atendimento.nucleo.Midias midias;

    @Autowired
    protected RelogioControlado relogio;

    @BeforeEach
    void limpar() {
        GRAPH.limpar();
        relogio.liberar();
    }

    @AfterEach
    void liberarRelogio() {
        relogio.liberar();
    }

    // ------------------------------------------------------------------ apoio

    protected static final String WEBHOOK = "/api/v1/integracoes/whatsapp/webhook";

    protected static String url(long atendimento, String acao) {
        return "/api/v1/atendimento/atendimentos/" + atendimento + "/" + acao;
    }

    protected void processar() {
        recebimento.processarPendentes(50);
        midias.processarPendentes(20);
        envio.enviarPendentes(20);
    }

    protected MockHttpServletResponse enviarWebhook(String corpo, String assinatura) throws Exception {
        var requisicao =
                post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(corpo.getBytes(StandardCharsets.UTF_8));
        if (assinatura != null) {
            requisicao = requisicao.header("X-Hub-Signature-256", assinatura);
        }
        return mvc.perform(requisicao).andReturn().getResponse();
    }

    protected static String assinar(String corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGREDO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
    }

    /** Notificação de mensagem de texto no formato do exemplo oficial da Meta. */
    protected static String mensagem(String de, String nome, String id, String texto, long timestamp) {
        return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"102290129340398\",\"changes\":[{\"value\":{"
                + "\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"15550783881\","
                + "\"phone_number_id\":\"" + NUMERO + "\"},\"contacts\":[{\"profile\":{\"name\":\"" + nome + "\"},"
                + "\"wa_id\":\"" + de + "\"}],\"messages\":[{\"from\":\"" + de + "\",\"id\":\"" + id
                + "\",\"timestamp\":\""
                + timestamp + "\",\"type\":\"text\",\"text\":{\"body\":\"" + texto
                + "\"}}]},\"field\":\"messages\"}]}]}";
    }

    protected void webhookSituacao(String idExterno, String situacao, String correlacao, Integer erro)
            throws Exception {
        String corpo = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"102290129340398\",\"changes\":[{"
                + "\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"15550783881\","
                + "\"phone_number_id\":\"" + NUMERO + "\"},\"statuses\":[{\"id\":\"" + idExterno + "\",\"status\":\""
                + situacao + "\",\"timestamp\":\"" + agoraEpoch() + "\",\"recipient_id\":\"5567999990000\""
                + (correlacao == null ? "" : ",\"biz_opaque_callback_data\":\"" + correlacao + "\"")
                + (erro == null ? "" : ",\"errors\":[{\"code\":" + erro + ",\"title\":\"Re-engagement message\"}]")
                + "}]},\"field\":\"messages\"}]}]}";
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
    }

    protected long novoAtendimento(String numero) throws Exception {
        String corpo = mensagem(numero, "Contato " + numero, wamid(), "Olá", agoraEpoch());
        enviarWebhook(corpo, assinar(corpo));
        recebimento.processarPendentes(50);
        return jdbc.sql("select a.id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where c.wa_id = :n and a.situacao <> 'ENCERRADO'")
                .param("n", numero)
                .query(Long.class)
                .single();
    }

    protected long assumido(Navegador admin, String numero) throws Exception {
        long atendimento = novoAtendimento(numero);
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        return atendimento;
    }

    protected long responder(Navegador navegador, long atendimento, String texto) {
        MockHttpServletResponse resposta = navegador.post(
                url(atendimento, "mensagens"),
                Map.of("texto", texto, "chaveIdempotencia", UUID.randomUUID().toString()));
        assertThat(resposta.getStatus()).isEqualTo(202);
        return corpo(resposta).get("mensagemId").asLong();
    }

    protected long associadoComTelefone(Navegador admin, String numero) {
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

    protected long versao(long atendimento) {
        return jdbc.sql("select versao from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query(Long.class)
                .single();
    }

    protected String situacao(long mensagem) {
        return jdbc.sql("select situacao from atd_mensagem where id = :id")
                .param("id", mensagem)
                .query(String.class)
                .single();
    }

    protected String correlacao(long mensagem) {
        return jdbc.sql("select correlacao from atd_mensagem where id = :id")
                .param("id", mensagem)
                .query(String.class)
                .single();
    }

    protected long contar(String deOnde, Object valor) {
        return jdbc.sql("select count(*) from " + deOnde)
                .param("c", valor)
                .query(Long.class)
                .single();
    }

    protected static String numeroUnico() {
        return "5567" + (900000000L + ThreadLocalRandom.current().nextLong(99_999_999L));
    }

    protected static String wamid() {
        return "wamid.TESTE" + UUID.randomUUID().toString().replace("-", "");
    }

    protected static long agoraEpoch() {
        return Instant.now().getEpochSecond();
    }
}
