package br.com.emconta.atendimento;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Servidor externo CONTROLADO, só para testes, que responde como a Graph API nos pontos usados, com o
 * formato documentado pela Meta: envio de mensagens ({@code POST /{versão}/{phone-number-id}/messages}),
 * upload de mídia ({@code POST /{versão}/{phone-number-id}/media}), consulta de mídia
 * ({@code GET /{versão}/{media-id}}, com URL temporária) e o download dessa URL (com Bearer). Não é
 * integração real: a homologação com a Meta é feita à parte, com as credenciais do ambiente.
 */
final class GraphApiControlada implements AutoCloseable {

    enum Modo {
        ACEITAR,
        ERRO_DEFINITIVO,
        ERRO_TEMPORARIO,
        SEM_RESPOSTA
    }

    /** Comportamento do download de uma mídia registrada. */
    enum Download {
        NORMAL,
        /** Primeira tentativa responde 404 (URL expirada); as seguintes, o arquivo. */
        EXPIRAR_UMA_VEZ,
        REDIRECIONAR,
        SEM_RESPOSTA,
        /** A consulta devolve uma URL para outro destino (não autorizado). */
        URL_EXTERNA
    }

    record Midia(byte[] conteudo, String mime, boolean hashBase64, Download download, String urlExterna) {}

    private final HttpServer servidor;
    private final List<String> requisicoes = new CopyOnWriteArrayList<>();
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final List<String> uploads = new CopyOnWriteArrayList<>();
    private final Map<String, Midia> midias = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> downloads = new ConcurrentHashMap<>();
    private final Map<String, Bloqueio> bloqueios = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> aoReceberUpload = new AtomicReference<>();

    /**
     * Segura o PRIMEIRO download de uma mídia até o teste liberar (sincronização explícita, sem sleeps):
     * {@code entrou} sinaliza que a execução chegou ao download; ao liberar, responde o arquivo ou, com
     * {@code falhar}, 404.
     */
    record Bloqueio(
            java.util.concurrent.CountDownLatch entrou, java.util.concurrent.CountDownLatch liberar, boolean falhar) {}

    private final AtomicReference<Modo> modo = new AtomicReference<>(Modo.ACEITAR);
    private final AtomicReference<Modo> modoUpload = new AtomicReference<>(Modo.ACEITAR);
    private final AtomicInteger sequencia = new AtomicInteger();

    GraphApiControlada() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            try {
                rotear(troca);
            } finally {
                troca.close();
            }
        });
        // Várias conexões ao mesmo tempo: uma requisição sem resposta não bloqueia as seguintes.
        servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        servidor.start();
    }

    private void rotear(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        String metodo = troca.getRequestMethod();
        byte[] corpo = troca.getRequestBody().readAllBytes();
        autorizacoes.add(String.valueOf(troca.getRequestHeaders().getFirst("Authorization")));
        if (caminho.startsWith("/arquivos/")) {
            baixar(troca, caminho.substring("/arquivos/".length()));
            return;
        }
        if ("POST".equals(metodo) && caminho.endsWith("/media")) {
            uploads.add(new String(corpo, StandardCharsets.ISO_8859_1));
            Runnable acao = aoReceberUpload.getAndSet(null);
            if (acao != null) {
                acao.run(); // ex.: o tempo passa durante o upload
            }
            requisicoes.add(caminho + " [upload " + corpo.length + " bytes]");
            if (modoUpload.get() == Modo.ERRO_TEMPORARIO) {
                responder(troca, 500, "{\"error\":{\"message\":\"Something went wrong\",\"code\":131000}}");
            } else if (modoUpload.get() == Modo.ERRO_DEFINITIVO) {
                responder(troca, 400, "{\"error\":{\"message\":\"Unsupported file type\",\"code\":131053}}");
            } else {
                responder(troca, 200, "{\"id\":\"midia-enviada-" + sequencia.incrementAndGet() + "\"}");
            }
            return;
        }
        if ("GET".equals(metodo) && caminho.matches("/v[0-9.]+/[^/]+")) {
            consultar(troca, caminho.substring(caminho.lastIndexOf('/') + 1));
            return;
        }
        requisicoes.add(caminho + " " + new String(corpo, StandardCharsets.UTF_8));
        mensagens(troca);
    }

    private void mensagens(HttpExchange troca) throws IOException {
        switch (modo.get()) {
            case ACEITAR ->
                responder(
                        troca,
                        200,
                        "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"5567999990000\","
                                + "\"wa_id\":\"5567999990000\"}],\"messages\":[{\"id\":\"wamid.TESTE"
                                + java.util.UUID.randomUUID().toString().replace("-", "")
                                + "\",\"message_status\":\"accepted\"}]}");
            case ERRO_DEFINITIVO ->
                responder(
                        troca,
                        400,
                        "{\"error\":{\"message\":\"Message undeliverable\",\"type\":\"OAuthException\","
                                + "\"code\":131026,\"fbtrace_id\":\"teste\"}}");
            case ERRO_TEMPORARIO ->
                responder(troca, 500, "{\"error\":{\"message\":\"Something went wrong\",\"code\":131000}}");
            default -> {
                esperar();
                responder(troca, 200, "{}");
            }
        }
    }

    private void consultar(HttpExchange troca, String id) throws IOException {
        Midia midia = midias.get(id);
        if (midia == null) {
            responder(troca, 404, "{\"error\":{\"message\":\"Media not found\",\"code\":100}}");
            return;
        }
        String url = midia.download() == Download.URL_EXTERNA ? midia.urlExterna() : url() + "/arquivos/" + id;
        responder(
                troca,
                200,
                "{\"messaging_product\":\"whatsapp\",\"url\":\"" + url + "\",\"mime_type\":\"" + midia.mime()
                        + "\",\"sha256\":\"" + hash(midia) + "\",\"file_size\":" + midia.conteudo().length
                        + ",\"id\":\""
                        + id + "\"}");
    }

    private void baixar(HttpExchange troca, String id) throws IOException {
        Midia midia = midias.get(id);
        int tentativa = downloads.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        Bloqueio bloqueio = tentativa == 1 ? bloqueios.remove(id) : null;
        if (bloqueio != null) {
            bloqueio.entrou().countDown();
            try {
                bloqueio.liberar().await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (bloqueio.falhar()) {
                responder(troca, 404, "");
                return;
            }
        }
        if (midia == null) {
            responder(troca, 404, "");
            return;
        }
        switch (midia.download()) {
            case EXPIRAR_UMA_VEZ -> {
                if (tentativa == 1) {
                    responder(troca, 404, "");
                    return;
                }
            }
            case REDIRECIONAR -> {
                troca.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
                troca.sendResponseHeaders(302, -1);
                return;
            }
            case SEM_RESPOSTA -> {
                esperar();
                responder(troca, 200, "");
                return;
            }
            default -> {}
        }
        troca.getResponseHeaders().add("Content-Type", midia.mime());
        troca.sendResponseHeaders(200, midia.conteudo().length);
        troca.getResponseBody().write(midia.conteudo());
    }

    private static String hash(Midia midia) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(midia.conteudo());
            return midia.hashBase64()
                    ? Base64.getEncoder().encodeToString(sha)
                    : HexFormat.of().formatHex(sha);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void esperar() {
        try {
            Thread.sleep(3000); // mais que o timeout configurado no teste
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void responder(HttpExchange troca, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            troca.getResponseBody().write(bytes);
        }
    }

    String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    void modo(Modo novo) {
        modo.set(novo);
    }

    void modoUpload(Modo novo) {
        modoUpload.set(novo);
    }

    /** Registra uma mídia que "a Meta" passa a servir pelo id. */
    void midia(String id, Midia midia) {
        midias.put(id, midia);
    }

    int downloads(String id) {
        AtomicInteger contador = downloads.get(id);
        return contador == null ? 0 : contador.get();
    }

    List<String> requisicoes() {
        return requisicoes;
    }

    List<String> autorizacoes() {
        return autorizacoes;
    }

    List<String> uploads() {
        return uploads;
    }

    void bloquearPrimeiroDownload(String id, Bloqueio bloqueio) {
        bloqueios.put(id, bloqueio);
    }

    /** Executa {@code acao} enquanto o próximo upload de mídia é recebido. */
    void aoReceberUpload(Runnable acao) {
        aoReceberUpload.set(acao);
    }

    void limpar() {
        requisicoes.clear();
        autorizacoes.clear();
        uploads.clear();
        downloads.clear();
        bloqueios.clear();
        aoReceberUpload.set(null);
        modo.set(Modo.ACEITAR);
        modoUpload.set(Modo.ACEITAR);
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
