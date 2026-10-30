package br.com.emconta.integracoes;

import br.com.emconta.integracoes.armazenamento.ArmazenamentoProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Armazenamento privado de objetos pela API S3 (MinIO no ambiente local), com assinatura AWS SigV4 e o
 * cliente HTTP do JDK, sem SDK. Só o necessário: gravar a partir de arquivo, ler (inteiro ou por faixa,
 * em streaming), remover e garantir o bucket.
 *
 * <p>Chaves são opacas e geradas pelo chamador ({@link #CHAVE}); nomes de arquivo nunca entram no
 * caminho. Logs não registram chaves de acesso, assinaturas nem conteúdo.
 */
@Component
@EnableConfigurationProperties(ArmazenamentoProperties.class)
public class ArmazenamentoObjetos {

    private static final Logger log = LoggerFactory.getLogger(ArmazenamentoObjetos.class);

    /** Formato aceito para chaves: segmentos simples separados por barra, sem "..". */
    public static final Pattern CHAVE = Pattern.compile("[a-z0-9-]{1,40}(/[a-z0-9-]{1,64}){1,4}");

    private static final String SEM_ASSINATURA_DE_CORPO = "UNSIGNED-PAYLOAD";
    private static final DateTimeFormatter AMZ_DATA = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ArmazenamentoProperties propriedades;
    private final HttpClient http;

    ArmazenamentoObjetos(ArmazenamentoProperties propriedades) {
        this.propriedades = propriedades;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propriedades.timeoutConexao())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean configurado() {
        return propriedades.configurado();
    }

    /** Falha de comunicação ou resposta inesperada do armazenamento (pode ser tentada de novo). */
    public static class FalhaArmazenamento extends RuntimeException {
        FalhaArmazenamento(String mensagem) {
            super(mensagem);
        }
    }

    /** Conteúdo lido: {@code faixa} é o {@code Content-Range} quando a leitura foi parcial (206). */
    public record Objeto(InputStream conteudo, long tamanho, String faixa, boolean parcial) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            conteudo.close();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void garantirBucket() {
        if (!configurado() || !propriedades.criarBucket()) {
            return;
        }
        try {
            int existe = enviar("HEAD", "/" + propriedades.bucket(), HttpRequest.BodyPublishers.noBody(), null)
                    .statusCode();
            if (existe == 404) {
                int criado = enviar("PUT", "/" + propriedades.bucket(), HttpRequest.BodyPublishers.noBody(), null)
                        .statusCode();
                log.info("Armazenamento: bucket criado (HTTP {})", criado);
            }
        } catch (FalhaArmazenamento e) {
            log.warn("Armazenamento: não foi possível conferir o bucket na inicialização");
        }
    }

    /** Grava o arquivo inteiro na chave informada (substitui se existir). */
    public void gravar(String chave, Path arquivo, String tipoConteudo) {
        exigirChave(chave);
        try {
            HttpResponse<InputStream> resposta =
                    enviar("PUT", caminho(chave), HttpRequest.BodyPublishers.ofFile(arquivo), tipoConteudo);
            try (InputStream corpo = resposta.body()) {
                corpo.readAllBytes(); // resposta curta (XML de erro ou vazia)
            }
            if (resposta.statusCode() / 100 != 2) {
                throw new FalhaArmazenamento("gravação recusada (HTTP " + resposta.statusCode() + ")");
            }
        } catch (IOException e) {
            throw new FalhaArmazenamento("arquivo temporário indisponível");
        }
    }

    /**
     * Abre o objeto para leitura em streaming. {@code faixa} é o valor de um cabeçalho {@code Range}
     * já validado ({@code bytes=inicio-fim}); vazio lê o objeto inteiro. Objeto inexistente: vazio.
     */
    public Optional<Objeto> abrir(String chave, String faixa) {
        exigirChave(chave);
        HttpResponse<InputStream> resposta =
                enviar("GET", caminho(chave), HttpRequest.BodyPublishers.noBody(), null, faixa);
        int status = resposta.statusCode();
        if (status == 404) {
            fechar(resposta);
            return Optional.empty();
        }
        if (status == 416) {
            fechar(resposta);
            throw new FaixaInvalida();
        }
        if (status != 200 && status != 206) {
            fechar(resposta);
            throw new FalhaArmazenamento("leitura recusada (HTTP " + status + ")");
        }
        long tamanho = resposta.headers().firstValueAsLong("Content-Length").orElse(-1);
        return Optional.of(new Objeto(
                resposta.body(),
                tamanho,
                resposta.headers().firstValue("Content-Range").orElse(null),
                status == 206));
    }

    /** Faixa pedida fora do objeto (HTTP 416). */
    public static class FaixaInvalida extends RuntimeException {}

    /** Remove o objeto; inexistente também conta como removido. */
    public void remover(String chave) {
        exigirChave(chave);
        HttpResponse<InputStream> resposta =
                enviar("DELETE", caminho(chave), HttpRequest.BodyPublishers.noBody(), null);
        fechar(resposta);
        if (resposta.statusCode() / 100 != 2 && resposta.statusCode() != 404) {
            throw new FalhaArmazenamento("remoção recusada (HTTP " + resposta.statusCode() + ")");
        }
    }

    // ------------------------------------------------------------------ HTTP assinado (SigV4)

    private String caminho(String chave) {
        return "/" + propriedades.bucket() + "/" + chave;
    }

    private static void exigirChave(String chave) {
        if (chave == null || !CHAVE.matcher(chave).matches()) {
            throw new IllegalArgumentException("chave de objeto inválida");
        }
    }

    private HttpResponse<InputStream> enviar(
            String metodo, String caminho, HttpRequest.BodyPublisher corpo, String tipoConteudo) {
        return enviar(metodo, caminho, corpo, tipoConteudo, null);
    }

    private HttpResponse<InputStream> enviar(
            String metodo, String caminho, HttpRequest.BodyPublisher corpo, String tipoConteudo, String faixa) {
        if (!configurado()) {
            throw new FalhaArmazenamento("armazenamento não configurado");
        }
        URI base = propriedades.endpoint();
        URI uri = base.resolve(caminho);
        // Relógio do sistema, não o da aplicação: a assinatura precisa da hora real (tolerância do S3).
        ZonedDateTime agora = Clock.systemUTC().instant().atZone(ZoneOffset.UTC);
        String amzData = AMZ_DATA.format(agora);
        String dia = DIA.format(agora);
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        String cabecalhosCanonicos =
                "host:" + host + "\nx-amz-content-sha256:" + SEM_ASSINATURA_DE_CORPO + "\nx-amz-date:" + amzData + "\n";
        String assinados = "host;x-amz-content-sha256;x-amz-date";
        String requisicaoCanonica = metodo + "\n" + uri.getRawPath() + "\n\n" + cabecalhosCanonicos + "\n" + assinados
                + "\n" + SEM_ASSINATURA_DE_CORPO;
        String escopo = dia + "/" + propriedades.regiao() + "/s3/aws4_request";
        String paraAssinar = "AWS4-HMAC-SHA256\n" + amzData + "\n" + escopo + "\n" + hex(sha256(requisicaoCanonica));
        byte[] chave = hmac(("AWS4" + propriedades.chaveSecreta()).getBytes(StandardCharsets.UTF_8), dia);
        chave = hmac(chave, propriedades.regiao());
        chave = hmac(chave, "s3");
        chave = hmac(chave, "aws4_request");
        String assinatura = hex(hmac(chave, paraAssinar));
        HttpRequest.Builder requisicao = HttpRequest.newBuilder(uri)
                .timeout(propriedades.timeoutResposta())
                .header("x-amz-date", amzData)
                .header("x-amz-content-sha256", SEM_ASSINATURA_DE_CORPO)
                .header(
                        "Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + propriedades.chaveAcesso() + "/" + escopo + ", SignedHeaders="
                                + assinados + ", Signature=" + assinatura)
                .method(metodo, corpo);
        if (tipoConteudo != null) {
            requisicao.header("Content-Type", tipoConteudo);
        }
        if (faixa != null) {
            requisicao.header("Range", faixa);
        }
        try {
            return http.send(requisicao.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new FalhaArmazenamento(
                    "armazenamento indisponível (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaArmazenamento("operação interrompida");
        }
    }

    private static void fechar(HttpResponse<InputStream> resposta) {
        try (InputStream corpo = resposta.body()) {
            corpo.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignorada) {
            // resposta já descartada
        }
    }

    private static byte[] sha256(String valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] chave, String valor) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(chave, "HmacSHA256"));
            return mac.doFinal(valor.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
