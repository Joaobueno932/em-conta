package br.com.emconta.acesso;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Lê as mensagens capturadas pelo Mailpit de teste, como faria a pessoa ao abrir o e-mail. */
final class Mailpit {

    private final String api;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    Mailpit(String api, JsonMapper json) {
        this.api = api;
        this.json = json;
    }

    /** Textos das mensagens enviadas ao destinatário, da mais recente para a mais antiga. */
    List<String> mensagens(String destinatario) {
        JsonNode busca = obter(
                "/api/v1/search?query=" + URLEncoder.encode("to:\"" + destinatario + "\"", StandardCharsets.UTF_8));
        List<String> textos = new ArrayList<>();
        for (JsonNode mensagem : busca.get("messages")) {
            textos.add(obter("/api/v1/message/" + mensagem.get("ID").asString())
                    .get("Text")
                    .asString());
        }
        return textos;
    }

    /** Aguarda até haver {@code quantidade} mensagens e devolve o token do link da mais recente. */
    String aguardarToken(String destinatario, String caminho, int quantidade) {
        Pattern link = Pattern.compile(Pattern.quote(caminho) + "#token=([A-Za-z0-9_-]{43})");
        Instant limite = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(limite)) {
            List<String> textos = mensagens(destinatario);
            if (textos.size() >= quantidade) {
                Matcher m = link.matcher(textos.getFirst());
                if (m.find()) {
                    return m.group(1);
                }
            }
            dormir();
        }
        JsonNode todas = obter("/api/v1/messages?limit=50");
        List<String> destinatarios = new ArrayList<>();
        for (JsonNode mensagem : todas.get("messages")) {
            destinatarios.add(mensagem.get("To").toString());
        }
        throw new AssertionError("Nenhum e-mail com link " + caminho + " para " + destinatario + "; encontrados "
                + mensagens(destinatario).size() + " para ele; total " + todas.get("total") + ": " + destinatarios);
    }

    private JsonNode obter(String caminho) {
        try {
            HttpResponse<String> resposta = http.send(
                    HttpRequest.newBuilder(URI.create(api + caminho))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return json.readTree(resposta.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void dormir() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
