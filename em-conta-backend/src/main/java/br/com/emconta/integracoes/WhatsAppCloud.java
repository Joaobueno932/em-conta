package br.com.emconta.integracoes;

import br.com.emconta.integracoes.whatsapp.WhatsAppProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador da WhatsApp Cloud API (Meta), API pública do módulo de integrações. Implementa só o que o
 * contrato oficial documenta e esta entrega usa: verificação do webhook, assinatura
 * {@code X-Hub-Signature-256} (HMAC-SHA256 do corpo bruto com o App Secret), leitura das notificações
 * {@code messages} e {@code statuses} e envio de texto em {@code /{versão}/{phone-number-id}/messages}.
 *
 * <p>Uma resposta 200 do envio significa apenas que a Meta aceitou a mensagem; envio, entrega e leitura
 * chegam depois pelo webhook. Logs nunca contêm conteúdo de mensagens, telefones nem credenciais.
 */
@Component
@EnableConfigurationProperties(WhatsAppProperties.class)
public class WhatsAppCloud {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCloud.class);

    /** Códigos documentados como temporários (tentar novamente mais tarde). */
    private static final Set<Integer> TEMPORARIOS = Set.of(130429, 131056, 4, 80007, 131000, 131016, 2);

    private final WhatsAppProperties propriedades;
    private final JsonMapper json;
    private final HttpClient http;

    WhatsAppCloud(WhatsAppProperties propriedades, JsonMapper json) {
        this.propriedades = propriedades;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propriedades.timeoutConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean configurado() {
        return propriedades.configurado();
    }

    // ------------------------------------------------------------------ webhook

    /** Verificação inicial: devolve o {@code hub.challenge} só se o modo e o token conferirem. */
    public Optional<String> desafio(String modo, String token, String challenge) {
        if (!configurado() || !"subscribe".equals(modo) || challenge == null || token == null) {
            return Optional.empty();
        }
        return iguais(token, propriedades.verifyToken()) ? Optional.of(challenge) : Optional.empty();
    }

    /** Confere {@code X-Hub-Signature-256: sha256=<hex>} sobre os bytes exatos recebidos. */
    public boolean assinaturaValida(byte[] corpo, String cabecalho) {
        if (!configurado() || corpo == null || cabecalho == null || !cabecalho.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(propriedades.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] esperado = mac.doFinal(corpo);
            byte[] recebido = HexFormat.of()
                    .parseHex(cabecalho.substring("sha256=".length()).toLowerCase());
            return MessageDigest.isEqual(esperado, recebido);
        } catch (IllegalArgumentException e) {
            return false; // hex inválido
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    /** Mensagem recebida. {@code texto} só existe para {@code type=text}. */
    public record MensagemRecebida(
            String waId, String nomePerfil, String idExterno, Instant horario, String tipo, String texto) {}

    /** Situação de mensagem enviada. {@code correlacao} é o {@code biz_opaque_callback_data} do envio. */
    public record SituacaoRecebida(
            String idExterno,
            String situacao,
            Instant horario,
            String destinatario,
            String correlacao,
            Integer erroCodigo,
            String erroTitulo) {}

    /**
     * Conteúdo útil de uma notificação. Eventos de outros números (outro {@code phone_number_id}) e
     * campos não tratados são contados em {@code ignorados}, nunca descartados em silêncio.
     */
    public record Notificacao(List<MensagemRecebida> mensagens, List<SituacaoRecebida> situacoes, int ignorados) {}

    public Notificacao interpretar(String corpo) {
        JsonNode raiz = json.readTree(corpo);
        List<MensagemRecebida> mensagens = new ArrayList<>();
        List<SituacaoRecebida> situacoes = new ArrayList<>();
        int ignorados = 0;
        if (!"whatsapp_business_account".equals(raiz.path("object").asString(""))) {
            return new Notificacao(mensagens, situacoes, 1);
        }
        for (JsonNode entrada : raiz.path("entry")) {
            for (JsonNode mudanca : entrada.path("changes")) {
                JsonNode valor = mudanca.path("value");
                String numero = valor.path("metadata").path("phone_number_id").asString("");
                if (!"messages".equals(mudanca.path("field").asString(""))
                        || !numero.equals(propriedades.phoneNumberId())) {
                    ignorados++;
                    continue;
                }
                Map<String, String> nomes = new LinkedHashMap<>();
                for (JsonNode contato : valor.path("contacts")) {
                    nomes.put(
                            contato.path("wa_id").asString(""),
                            contato.path("profile").path("name").asString(null));
                }
                for (JsonNode m : valor.path("messages")) {
                    String tipo = m.path("type").asString("unknown");
                    String de = m.path("from").asString("");
                    mensagens.add(new MensagemRecebida(
                            de,
                            nomes.get(de),
                            m.path("id").asString(null),
                            instante(m.path("timestamp")),
                            tipo,
                            "text".equals(tipo) ? m.path("text").path("body").asString(null) : null));
                }
                for (JsonNode s : valor.path("statuses")) {
                    JsonNode erro = s.path("errors").path(0);
                    situacoes.add(new SituacaoRecebida(
                            s.path("id").asString(null),
                            s.path("status").asString("desconhecida"),
                            instante(s.path("timestamp")),
                            s.path("recipient_id").asString(null),
                            s.path("biz_opaque_callback_data").asString(null),
                            erro.isMissingNode() ? null : erro.path("code").asInt(),
                            erro.isMissingNode() ? null : erro.path("title").asString(null)));
                }
                if (valor.path("messages").isEmpty() && valor.path("statuses").isEmpty()) {
                    ignorados++;
                }
            }
        }
        return new Notificacao(mensagens, situacoes, ignorados);
    }

    private static Instant instante(JsonNode timestamp) {
        String valor = timestamp.asString("");
        return valor.matches("\\d{1,12}") ? Instant.ofEpochSecond(Long.parseLong(valor)) : null;
    }

    // ------------------------------------------------------------------ envio

    /** Resultado de um envio. Só {@link Aceita} carrega o id externo (wamid). */
    public sealed interface ResultadoEnvio {}

    /** Aceita pela Meta; não significa enviada nem entregue. */
    public record Aceita(String idExterno, String situacaoInformada) implements ResultadoEnvio {}

    /** Recusada pela Meta com código documentado. {@code temporaria}: pode ser tentada de novo. */
    public record Recusada(Integer codigo, String titulo, boolean temporaria) implements ResultadoEnvio {}

    /**
     * Sem resposta conclusiva (tempo esgotado ou conexão interrompida após o envio). A mensagem pode ter
     * sido aceita; não deve ser reenviada automaticamente.
     */
    public record Desconhecido(String motivo) implements ResultadoEnvio {}

    /**
     * Envia texto livre. {@code correlacao} vai em {@code biz_opaque_callback_data} e volta nos eventos de
     * situação, permitindo correlacionar a mensagem mesmo quando a resposta do envio se perdeu.
     */
    public ResultadoEnvio enviarTexto(String para, String texto, String correlacao) {
        if (!configurado()) {
            return new Recusada(null, "Canal não configurado", false);
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("messaging_product", "whatsapp");
        corpo.put("recipient_type", "individual");
        corpo.put("to", para);
        corpo.put("type", "text");
        corpo.put("text", Map.of("preview_url", false, "body", texto));
        corpo.put("biz_opaque_callback_data", correlacao);
        HttpRequest requisicao = HttpRequest.newBuilder(propriedades
                        .graphUrl()
                        .resolve("/" + propriedades.graphVersion() + "/" + propriedades.phoneNumberId() + "/messages"))
                .timeout(propriedades.timeoutResposta())
                .header("Authorization", "Bearer " + propriedades.accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();
        HttpResponse<String> resposta;
        try {
            resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            // Não chegou a conectar: a mensagem certamente não foi enviada.
            log.warn("WhatsApp: falha de conexão no envio ({})", e.getClass().getSimpleName());
            return new Recusada(null, "Falha de conexão com a Cloud API", true);
        } catch (HttpTimeoutException e) {
            log.warn("WhatsApp: tempo esgotado aguardando resposta do envio");
            return new Desconhecido("Tempo esgotado aguardando a Cloud API");
        } catch (IOException e) {
            log.warn(
                    "WhatsApp: conexão interrompida no envio ({})", e.getClass().getSimpleName());
            return new Desconhecido("Conexão interrompida durante o envio");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Desconhecido("Envio interrompido");
        }
        return interpretarResposta(resposta.statusCode(), resposta.body());
    }

    private ResultadoEnvio interpretarResposta(int status, String corpo) {
        JsonNode raiz;
        try {
            raiz = json.readTree(corpo == null ? "" : corpo);
        } catch (RuntimeException e) {
            raiz = null;
        }
        if (status >= 200 && status < 300) {
            JsonNode mensagem = raiz == null ? null : raiz.path("messages").path(0);
            String id = mensagem == null ? null : mensagem.path("id").asString(null);
            if (id == null) {
                log.warn("WhatsApp: resposta {} sem id de mensagem", status);
                return new Desconhecido("Resposta sem identificador da mensagem");
            }
            return new Aceita(id, mensagem.path("message_status").asString("accepted"));
        }
        JsonNode erro = raiz == null ? null : raiz.path("error");
        Integer codigo = erro == null || erro.path("code").isMissingNode()
                ? null
                : erro.path("code").asInt();
        String titulo = erro == null ? null : erro.path("message").asString(null);
        boolean temporaria = (codigo != null && TEMPORARIOS.contains(codigo)) || status >= 500 || status == 429;
        log.warn("WhatsApp: envio recusado (HTTP {}, código {})", status, codigo);
        return new Recusada(codigo, titulo == null ? "HTTP " + status : titulo, temporaria);
    }

    private static boolean iguais(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
