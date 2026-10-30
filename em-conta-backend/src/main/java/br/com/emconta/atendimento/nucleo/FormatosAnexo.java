package br.com.emconta.atendimento.nucleo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Formatos de anexo aceitos, nos dois sentidos. É a interseção entre os tipos documentados pela Cloud
 * API (Media, "Supported media types") e o que a aplicação sabe validar e exibir com segurança: imagens
 * JPEG e PNG, documentos PDF e áudios AAC, AMR, MP3, M4A e OGG/Opus. Nenhum outro tipo é aceito (nem
 * convertido): documentos do Office, texto, vídeo e figurinhas recebidos aparecem como não suportados.
 *
 * <p>Cada arquivo é conferido pelo MIME declarado, pela extensão (quando há nome) e pela assinatura dos
 * primeiros bytes; nome e Content-Type sozinhos não bastam.
 */
public final class FormatosAnexo {

    private FormatosAnexo() {}

    public enum Categoria {
        IMAGEM("image", 5L * 1024 * 1024, true),
        DOCUMENTO("document", 100L * 1024 * 1024, true),
        AUDIO("audio", 16L * 1024 * 1024, false);

        /** Tipo da mensagem na Cloud API. */
        public final String tipoCloud;
        /** Limite do provedor (a configuração nunca pode passar dele). */
        public final long limiteProvedor;
        /** A Cloud API aceita legenda ({@code caption}) neste tipo. */
        public final boolean aceitaLegenda;

        Categoria(String tipoCloud, long limiteProvedor, boolean aceitaLegenda) {
            this.tipoCloud = tipoCloud;
            this.limiteProvedor = limiteProvedor;
            this.aceitaLegenda = aceitaLegenda;
        }
    }

    /** Legenda: até 1024 caracteres (Cloud API), só em imagem e documento. */
    public static final int TAMANHO_MAXIMO_LEGENDA = 1024;

    public record Formato(String mime, Categoria categoria, List<String> extensoes) {
        public String extensaoPadrao() {
            return extensoes.getFirst();
        }
    }

    public static final List<Formato> SUPORTADOS = List.of(
            new Formato("image/jpeg", Categoria.IMAGEM, List.of("jpg", "jpeg")),
            new Formato("image/png", Categoria.IMAGEM, List.of("png")),
            new Formato("application/pdf", Categoria.DOCUMENTO, List.of("pdf")),
            new Formato("audio/ogg", Categoria.AUDIO, List.of("ogg", "opus")),
            new Formato("audio/mpeg", Categoria.AUDIO, List.of("mp3")),
            new Formato("audio/mp4", Categoria.AUDIO, List.of("m4a")),
            new Formato("audio/aac", Categoria.AUDIO, List.of("aac")),
            new Formato("audio/amr", Categoria.AUDIO, List.of("amr")));

    /** Tamanho para mensagens: "16 MB", "64 KB". */
    public static String tamanhoLegivel(long bytes) {
        return bytes >= 1024 * 1024 ? bytes / (1024 * 1024) + " MB" : Math.max(1, bytes / 1024) + " KB";
    }

    /** MIME sem parâmetros ({@code audio/ogg; codecs=opus} → {@code audio/ogg}), em minúsculas. */
    public static String mimeBase(String mime) {
        if (mime == null) {
            return null;
        }
        int separador = mime.indexOf(';');
        return (separador < 0 ? mime : mime.substring(0, separador)).strip().toLowerCase(Locale.ROOT);
    }

    public static Optional<Formato> porMime(String mime) {
        String base = mimeBase(mime);
        return SUPORTADOS.stream().filter(f -> f.mime().equals(base)).findFirst();
    }

    /** Extensão do nome (minúsculas), se houver. */
    public static Optional<String> extensao(String nome) {
        if (nome == null) {
            return Optional.empty();
        }
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 || ponto == nome.length() - 1
                ? Optional.empty()
                : Optional.of(nome.substring(ponto + 1).toLowerCase(Locale.ROOT));
    }

    /** O conteúdo começa com a assinatura esperada do formato? {@code inicio}: primeiros bytes (até 64). */
    public static boolean assinaturaConfere(Formato formato, byte[] inicio) {
        return switch (formato.mime()) {
            case "image/jpeg" -> comeca(inicio, 0xFF, 0xD8, 0xFF);
            case "image/png" -> comeca(inicio, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "application/pdf" -> comeca(inicio, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            // OGG com Opus (a Cloud API não aceita OGG com outros codecs): página OggS e cabeçalho OpusHead.
            case "audio/ogg" ->
                comeca(inicio, "OggS".getBytes(StandardCharsets.US_ASCII))
                        && contem(inicio, "OpusHead".getBytes(StandardCharsets.US_ASCII));
            case "audio/mpeg" ->
                comeca(inicio, "ID3".getBytes(StandardCharsets.US_ASCII))
                        || (inicio.length > 1 && (inicio[0] & 0xFF) == 0xFF && (inicio[1] & 0xE6) == 0xE2);
            case "audio/mp4" ->
                inicio.length >= 12
                        && Arrays.equals(Arrays.copyOfRange(inicio, 4, 8), "ftyp".getBytes(StandardCharsets.US_ASCII));
            case "audio/aac" -> inicio.length > 1 && (inicio[0] & 0xFF) == 0xFF && (inicio[1] & 0xF6) == 0xF0;
            case "audio/amr" -> comeca(inicio, "#!AMR".getBytes(StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private static boolean comeca(byte[] dados, int... esperado) {
        if (dados.length < esperado.length) {
            return false;
        }
        for (int i = 0; i < esperado.length; i++) {
            if ((dados[i] & 0xFF) != esperado[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean comeca(byte[] dados, byte[] esperado) {
        return dados.length >= esperado.length && Arrays.equals(Arrays.copyOf(dados, esperado.length), esperado);
    }

    private static boolean contem(byte[] dados, byte[] trecho) {
        outer:
        for (int i = 0; i + trecho.length <= dados.length; i++) {
            for (int j = 0; j < trecho.length; j++) {
                if (dados[i + j] != trecho[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static final Pattern PROIBIDOS = Pattern.compile("[\\p{Cntrl}<>:\"/\\\\|?*\\u202A-\\u202E\\u2066-\\u2069]");

    /**
     * Nome seguro para exibir e para o {@code Content-Disposition}: sem diretórios, caracteres de controle,
     * separadores, marcas de direção de texto nem caracteres reservados; até 120 caracteres, preservando a
     * extensão. Nunca é usado como caminho de armazenamento. Vazio depois da limpeza: nulo.
     */
    public static String nomeSeguro(String original) {
        if (original == null) {
            return null;
        }
        String nome = original;
        int barra = Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\'));
        nome = barra >= 0 ? nome.substring(barra + 1) : nome;
        nome = PROIBIDOS.matcher(nome).replaceAll("_").replaceAll("\\s+", " ").strip();
        while (nome.startsWith(".")) {
            nome = nome.substring(1);
        }
        if (nome.isBlank()) {
            return null;
        }
        if (nome.length() > 120) {
            Optional<String> ext = extensao(nome);
            String sufixo = ext.map(e -> "." + e).filter(e -> e.length() <= 10).orElse("");
            nome = nome.substring(0, 120 - sufixo.length()) + sufixo;
        }
        return nome;
    }

    /**
     * Confere o hash informado pela Meta com o SHA-256 calculado. A documentação não fixa a codificação
     * do campo {@code sha256}; aceitam-se hexadecimal (64 caracteres) e Base64 (32 bytes). Valor em outro
     * formato é tratado como divergente (o arquivo não é disponibilizado).
     */
    public static boolean hashConfere(String informado, byte[] calculado) {
        if (informado == null || informado.isBlank()) {
            return true; // sem hash do provedor: valem as demais validações
        }
        String valor = informado.strip();
        if (valor.matches("[0-9a-fA-F]{64}")) {
            return Arrays.equals(HexFormat.of().parseHex(valor.toLowerCase(Locale.ROOT)), calculado);
        }
        try {
            byte[] decodificado = Base64.getDecoder().decode(valor);
            return decodificado.length == 32 && Arrays.equals(decodificado, calculado);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
