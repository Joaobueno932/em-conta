package br.com.emconta.integracoes;

import br.com.emconta.integracoes.whatsapp.WhatsAppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    /**
     * Mensagem recebida. {@code texto} só existe para {@code type=text}; {@code midia} só para
     * {@code image}, {@code document} e {@code audio} (os tipos de anexo tratados).
     */
    public record MensagemRecebida(
            String waId,
            String nomePerfil,
            String idExterno,
            Instant horario,
            String tipo,
            String texto,
            MidiaRecebida midia) {}

    /**
     * Metadados de mídia do webhook: {@code id} (para consultar a URL temporária), {@code mime_type},
     * {@code sha256} do arquivo, {@code filename} (documentos) e {@code caption} (imagem e documento).
     */
    public record MidiaRecebida(String id, String mimeType, String sha256, String nomeArquivo, String legenda) {}

    /** Tipos de mensagem do webhook cujo arquivo é tratado como anexo. */
    public static final Set<String> TIPOS_MIDIA = Set.of("image", "document", "audio");

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
                    MidiaRecebida midia = null;
                    if (TIPOS_MIDIA.contains(tipo)) {
                        JsonNode no = m.path(tipo);
                        midia = new MidiaRecebida(
                                no.path("id").asString(null),
                                no.path("mime_type").asString(null),
                                no.path("sha256").asString(null),
                                no.path("filename").asString(null),
                                no.path("caption").asString(null));
                    }
                    mensagens.add(new MensagemRecebida(
                            de,
                            nomes.get(de),
                            m.path("id").asString(null),
                            instante(m.path("timestamp")),
                            tipo,
                            "text".equals(tipo) ? m.path("text").path("body").asString(null) : null,
                            midia));
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
        return enviarMensagem(para, "text", Map.of("preview_url", false, "body", texto), correlacao);
    }

    /**
     * Envia um anexo já enviado à Meta ({@link #enviarArquivo}), pelo id da mídia. {@code tipo} é
     * {@code image}, {@code document} ou {@code audio}. Legenda só em imagem e documento (até 1024
     * caracteres); {@code nomeArquivo} só em documento.
     */
    public ResultadoEnvio enviarMidia(
            String para, String tipo, String idMidia, String legenda, String nomeArquivo, String correlacao) {
        if (!TIPOS_MIDIA.contains(tipo)) {
            throw new IllegalArgumentException("tipo de mídia não suportado");
        }
        Map<String, Object> midia = new LinkedHashMap<>();
        midia.put("id", idMidia);
        if (legenda != null && !"audio".equals(tipo)) {
            midia.put("caption", legenda);
        }
        if (nomeArquivo != null && "document".equals(tipo)) {
            midia.put("filename", nomeArquivo);
        }
        return enviarMensagem(para, tipo, midia, correlacao);
    }

    private ResultadoEnvio enviarMensagem(String para, String tipo, Map<String, Object> conteudo, String correlacao) {
        if (!configurado()) {
            return new Recusada(null, "Canal não configurado", false);
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("messaging_product", "whatsapp");
        corpo.put("recipient_type", "individual");
        corpo.put("to", para);
        corpo.put("type", tipo);
        corpo.put(tipo, conteudo);
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

    // ------------------------------------------------------------------ mídia

    /** Resultado de consulta, download ou upload de mídia. */
    public sealed interface ResultadoMidia {}

    /** Recusa ou falha na etapa de mídia. {@code temporaria}: pode ser tentada de novo. */
    public record FalhaMidia(Integer codigo, String titulo, boolean temporaria) implements ResultadoMidia {}

    /** {@code GET /{media-id}}: URL temporária (expira em 5 minutos), tipo, hash e tamanho informados. */
    public record InfoMidia(URI url, String mimeType, String sha256, Long tamanho) implements ResultadoMidia {}

    /** Arquivo baixado para o destino local, com {@code bytes} lidos. */
    public record MidiaBaixada(long bytes) implements ResultadoMidia {}

    /** {@code POST /{phone-number-id}/media}: id da mídia na Meta, para usar no envio. */
    public record MidiaEnviada(String idMidia) implements ResultadoMidia {}

    /** Consulta a URL temporária de uma mídia recebida (uma nova a cada chamada). */
    public ResultadoMidia consultarMidia(String idMidia) {
        if (!configurado()) {
            return new FalhaMidia(null, "Canal não configurado", false);
        }
        if (idMidia == null || !idMidia.matches("[A-Za-z0-9_.-]{1,128}")) {
            return new FalhaMidia(null, "Identificador de mídia inválido", false);
        }
        URI uri = propriedades
                .graphUrl()
                .resolve("/" + propriedades.graphVersion() + "/" + idMidia + "?phone_number_id="
                        + propriedades.phoneNumberId());
        HttpRequest requisicao = HttpRequest.newBuilder(uri)
                .timeout(propriedades.timeoutResposta())
                .header("Authorization", "Bearer " + propriedades.accessToken())
                .GET()
                .build();
        HttpResponse<String> resposta;
        try {
            resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("WhatsApp: falha na consulta de mídia ({})", e.getClass().getSimpleName());
            return new FalhaMidia(null, "Falha de comunicação ao consultar a mídia", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FalhaMidia(null, "Consulta interrompida", true);
        }
        if (resposta.statusCode() / 100 != 2) {
            return falhaMidia(resposta.statusCode(), resposta.body(), "consulta de mídia");
        }
        JsonNode raiz = json.readTree(resposta.body());
        String url = raiz.path("url").asString(null);
        if (url == null) {
            return new FalhaMidia(null, "Resposta da consulta sem URL", true);
        }
        URI destino;
        try {
            destino = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new FalhaMidia(null, "URL de mídia inválida", false);
        }
        JsonNode tamanho = raiz.path("file_size");
        return new InfoMidia(
                destino,
                raiz.path("mime_type").asString(null),
                raiz.path("sha256").asString(null),
                tamanho.isMissingNode() || tamanho.isNull() ? null : tamanho.asLong());
    }

    /**
     * Baixa a mídia para {@code destino}, lendo no máximo {@code limite} bytes. Proteção contra SSRF: só
     * HTTPS para um dos {@code hostsMidia} configurados (ou a própria origem da Graph API configurada, que
     * em testes é o servidor controlado); redirecionamentos não são seguidos; o token só vai para destino
     * autorizado. URL expirada (404) é falha temporária: a próxima tentativa consulta uma URL nova.
     */
    public ResultadoMidia baixarMidia(URI url, Path destino, long limite) {
        if (!configurado()) {
            return new FalhaMidia(null, "Canal não configurado", false);
        }
        if (!destinoAutorizado(url)) {
            log.warn("WhatsApp: download de mídia recusado para destino não autorizado");
            return new FalhaMidia(null, "Destino de download não autorizado", false);
        }
        HttpRequest requisicao = HttpRequest.newBuilder(url)
                .timeout(propriedades.timeoutDownload())
                .header("Authorization", "Bearer " + propriedades.accessToken())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream corpo = resposta.body()) {
                int status = resposta.statusCode();
                if (status / 100 == 3) {
                    return new FalhaMidia(null, "Redirecionamento não autorizado no download", false);
                }
                if (status == 404) {
                    return new FalhaMidia(null, "URL temporária expirada ou mídia indisponível", true);
                }
                if (status / 100 != 2) {
                    return new FalhaMidia(
                            null, "Download recusado (HTTP " + status + ")", status >= 500 || status == 429);
                }
                long declarado =
                        resposta.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declarado > limite) {
                    return new FalhaMidia(null, "Arquivo acima do limite", false);
                }
                long lidos = copiarComLimite(corpo, destino, limite);
                return lidos < 0 ? new FalhaMidia(null, "Arquivo acima do limite", false) : new MidiaBaixada(lidos);
            }
        } catch (IOException e) {
            log.warn("WhatsApp: falha no download de mídia ({})", e.getClass().getSimpleName());
            return new FalhaMidia(null, "Falha de comunicação no download", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FalhaMidia(null, "Download interrompido", true);
        }
    }

    private boolean destinoAutorizado(URI url) {
        if (url == null || url.getHost() == null || url.getUserInfo() != null) {
            return false;
        }
        URI graph = propriedades.graphUrl();
        boolean mesmaOrigemDaGraph = graph.getScheme().equalsIgnoreCase(url.getScheme())
                && graph.getHost().equalsIgnoreCase(url.getHost())
                && graph.getPort() == url.getPort();
        if (mesmaOrigemDaGraph) {
            return true;
        }
        return "https".equalsIgnoreCase(url.getScheme())
                && (url.getPort() == -1 || url.getPort() == 443)
                && propriedades.hostsMidia().stream().anyMatch(h -> h.equalsIgnoreCase(url.getHost()));
    }

    /** Copia até {@code limite} bytes; devolve -1 (e apaga o parcial) se o arquivo passar do limite. */
    private static long copiarComLimite(InputStream origem, Path destino, long limite) throws IOException {
        long total = 0;
        byte[] bloco = new byte[64 * 1024];
        try (var saida = java.nio.file.Files.newOutputStream(destino)) {
            int lidos;
            while ((lidos = origem.read(bloco)) != -1) {
                total += lidos;
                if (total > limite) {
                    saida.close();
                    java.nio.file.Files.deleteIfExists(destino);
                    return -1;
                }
                saida.write(bloco, 0, lidos);
            }
        }
        return total;
    }

    /**
     * Envia o arquivo à Meta ({@code POST /{phone-number-id}/media}, multipart com {@code file},
     * {@code type} e {@code messaging_product}). Não envia nada ao contato. Sem resposta conclusiva é falha
     * temporária: repetir o upload só cria outra mídia, nunca uma mensagem duplicada.
     */
    public ResultadoMidia enviarArquivo(Path arquivo, String mimeType, String extensao) {
        if (!configurado()) {
            return new FalhaMidia(null, "Canal não configurado", false);
        }
        String fronteira =
                "----emconta" + java.util.UUID.randomUUID().toString().replace("-", "");
        String inicio = "--" + fronteira
                + "\r\nContent-Disposition: form-data; name=\"messaging_product\"\r\n\r\nwhatsapp\r\n"
                + "--" + fronteira + "\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\n" + mimeType + "\r\n"
                + "--" + fronteira + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"anexo." + extensao
                + "\"\r\nContent-Type: " + mimeType + "\r\n\r\n";
        String fim = "\r\n--" + fronteira + "--\r\n";
        HttpRequest requisicao;
        try {
            requisicao = HttpRequest.newBuilder(propriedades
                            .graphUrl()
                            .resolve("/" + propriedades.graphVersion() + "/" + propriedades.phoneNumberId() + "/media"))
                    .timeout(propriedades.timeoutDownload())
                    .header("Authorization", "Bearer " + propriedades.accessToken())
                    .header("Content-Type", "multipart/form-data; boundary=" + fronteira)
                    .POST(HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofString(inicio),
                            HttpRequest.BodyPublishers.ofFile(arquivo),
                            HttpRequest.BodyPublishers.ofString(fim)))
                    .build();
        } catch (java.io.FileNotFoundException e) {
            return new FalhaMidia(null, "Arquivo local indisponível", true);
        }
        HttpResponse<String> resposta;
        try {
            resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("WhatsApp: falha no upload de mídia ({})", e.getClass().getSimpleName());
            return new FalhaMidia(null, "Falha de comunicação no upload ao WhatsApp", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FalhaMidia(null, "Upload interrompido", true);
        }
        if (resposta.statusCode() / 100 != 2) {
            return falhaMidia(resposta.statusCode(), resposta.body(), "upload de mídia");
        }
        String id = json.readTree(resposta.body()).path("id").asString(null);
        return id == null ? new FalhaMidia(null, "Resposta do upload sem id", true) : new MidiaEnviada(id);
    }

    private FalhaMidia falhaMidia(int status, String corpo, String etapa) {
        JsonNode erro;
        try {
            erro = json.readTree(corpo == null ? "" : corpo).path("error");
        } catch (RuntimeException e) {
            erro = null;
        }
        Integer codigo = erro == null || erro.path("code").isMissingNode()
                ? null
                : erro.path("code").asInt();
        String titulo = erro == null ? null : erro.path("message").asString(null);
        boolean temporaria = (codigo != null && TEMPORARIOS.contains(codigo)) || status >= 500 || status == 429;
        log.warn("WhatsApp: {} recusada (HTTP {}, código {})", etapa, status, codigo);
        return new FalhaMidia(codigo, titulo == null ? "HTTP " + status : titulo, temporaria);
    }

    private static boolean iguais(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
