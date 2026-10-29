package br.com.emconta.atendimento;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servidor externo CONTROLADO, só para testes, que responde como a Graph API no envio de mensagens
 * ({@code POST /{versão}/{phone-number-id}/messages}), com o formato de resposta documentado pela Meta.
 * Não é integração real: a homologação com a Meta é feita à parte, com as credenciais do ambiente.
 */
final class GraphApiControlada implements AutoCloseable {

    enum Modo {
        ACEITAR,
        ERRO_DEFINITIVO,
        ERRO_TEMPORARIO,
        SEM_RESPOSTA
    }

    private final HttpServer servidor;
    private final List<String> requisicoes = new CopyOnWriteArrayList<>();
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Modo> modo = new AtomicReference<>(Modo.ACEITAR);

    GraphApiControlada() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requisicoes.add(troca.getRequestURI().getPath() + " " + corpo);
            autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
            String resposta;
            int status;
            switch (modo.get()) {
                case ACEITAR -> {
                    status = 200;
                    resposta = "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"5567999990000\","
                            + "\"wa_id\":\"5567999990000\"}],\"messages\":[{\"id\":\"wamid.TESTE"
                            + java.util.UUID.randomUUID().toString().replace("-", "")
                            + "\",\"message_status\":\"accepted\"}]}";
                }
                case ERRO_DEFINITIVO -> {
                    status = 400;
                    resposta = "{\"error\":{\"message\":\"Message undeliverable\",\"type\":\"OAuthException\","
                            + "\"code\":131026,\"fbtrace_id\":\"teste\"}}";
                }
                case ERRO_TEMPORARIO -> {
                    status = 500;
                    resposta = "{\"error\":{\"message\":\"Something went wrong\",\"code\":131000}}";
                }
                default -> {
                    try {
                        Thread.sleep(3000); // mais que o timeout configurado no teste
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    status = 200;
                    resposta = "{}";
                }
            }
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, bytes.length);
            troca.getResponseBody().write(bytes);
            troca.close();
        });
        // Várias conexões ao mesmo tempo: uma requisição sem resposta não bloqueia as seguintes.
        servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        servidor.start();
    }

    String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    void modo(Modo novo) {
        modo.set(novo);
    }

    List<String> requisicoes() {
        return requisicoes;
    }

    List<String> autorizacoes() {
        return autorizacoes;
    }

    void limpar() {
        requisicoes.clear();
        autorizacoes.clear();
        modo.set(Modo.ACEITAR);
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
