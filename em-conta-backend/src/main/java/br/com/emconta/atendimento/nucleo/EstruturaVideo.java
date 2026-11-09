package br.com.emconta.atendimento.nucleo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Estrutura e codecs de um vídeo MP4/3GPP a enviar. A Cloud API só aceita vídeo H.264 e áudio AAC, com
 * no máximo uma faixa de áudio ("Supported media types"); fora disso a Meta recusaria o arquivo só depois
 * do upload. MP4 fragmentado ({@code mvex} no {@code moov} ou {@code moof} no topo, como o gerado pelo
 * MediaRecorder dos navegadores) também é recusado: na homologação de 09/11/2026 a Meta aceitou o envio e
 * o marcou como falho (#131053, "Media upload error"), enquanto o mesmo conteúdo em MP4 convencional
 * passou. Lê apenas os cabeçalhos das caixas ISO BMFF ({@code moov → trak → mdia → hdlr/minf → stbl →
 * stsd}), pulando os dados de mídia, com limite de caixas e de profundidade. Vídeos recebidos não passam
 * por aqui: vêm da própria Meta e valem só assinatura, MIME, tamanho e hash.
 */
final class EstruturaVideo {

    private EstruturaVideo() {}

    static final int MAXIMO_CAIXAS = 10_000;

    static final String MP4_FRAGMENTADO = "Este vídeo está em um formato incompatível com o envio pelo WhatsApp."
            + " Exporte como MP4 padrão e tente novamente.";
    private static final int MAXIMO_PROFUNDIDADE = 8;

    private static final Map<String, String> NOMES = Map.ofEntries(
            Map.entry("hvc1", "H.265 (HEVC)"),
            Map.entry("hev1", "H.265 (HEVC)"),
            Map.entry("vp09", "VP9"),
            Map.entry("av01", "AV1"),
            Map.entry("s263", "H.263"),
            Map.entry("mp4v", "MPEG-4 Visual"),
            Map.entry("samr", "AMR"),
            Map.entry("sawb", "AMR-WB"),
            Map.entry("Opus", "Opus"),
            Map.entry("ac-3", "AC-3"),
            Map.entry("ec-3", "E-AC-3"),
            Map.entry(".mp3", "MP3"));

    /** Faixa encontrada: tipo ({@code vide}, {@code soun}...) e codecs das entradas de amostra. */
    private static final class Faixa {
        String tipo;
        final List<String> codecs = new ArrayList<>();
    }

    private static final class Leitura {
        int caixas;
        final List<Faixa> faixas = new ArrayList<>();
    }

    static void exigirCodecsAceitos(Path arquivo) throws ArquivoValidado.Invalido {
        Leitura leitura = new Leitura();
        try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.READ)) {
            percorrer(canal, 0, canal.size(), 0, leitura, null);
        } catch (IOException e) {
            throw new ArquivoValidado.Invalido("Não foi possível ler a estrutura do vídeo.");
        }
        List<Faixa> videos =
                leitura.faixas.stream().filter(f -> "vide".equals(f.tipo)).toList();
        List<Faixa> audios =
                leitura.faixas.stream().filter(f -> "soun".equals(f.tipo)).toList();
        if (videos.isEmpty()) {
            throw new ArquivoValidado.Invalido("O arquivo não tem uma faixa de vídeo legível.");
        }
        for (Faixa video : videos) {
            for (String codec : video.codecs) {
                if (!"avc1".equals(codec) && !"avc3".equals(codec)) {
                    throw new ArquivoValidado.Invalido(
                            "O WhatsApp só aceita vídeo em H.264; este arquivo usa " + nome(codec) + ".");
                }
            }
        }
        if (audios.size() > 1) {
            throw new ArquivoValidado.Invalido(
                    "O vídeo tem " + audios.size() + " faixas de áudio; o WhatsApp aceita no máximo uma.");
        }
        for (Faixa audio : audios) {
            for (String codec : audio.codecs) {
                if (!"mp4a".equals(codec)) {
                    throw new ArquivoValidado.Invalido(
                            "O WhatsApp só aceita o áudio do vídeo em AAC; este arquivo usa " + nome(codec) + ".");
                }
            }
        }
    }

    private static String nome(String codec) {
        return NOMES.getOrDefault(codec, "o formato \"" + codec.strip() + "\"");
    }

    private static void percorrer(
            FileChannel canal, long inicio, long fim, int profundidade, Leitura leitura, Faixa faixa)
            throws IOException, ArquivoValidado.Invalido {
        if (profundidade > MAXIMO_PROFUNDIDADE) {
            throw new ArquivoValidado.Invalido("Estrutura do vídeo inválida.");
        }
        long posicao = inicio;
        while (posicao + 8 <= fim) {
            if (++leitura.caixas > MAXIMO_CAIXAS) {
                throw new ArquivoValidado.Invalido("Estrutura do vídeo grande demais para ser conferida.");
            }
            ByteBuffer cabecalho = ler(canal, posicao, (int) Math.min(16, fim - posicao));
            long tamanho = Integer.toUnsignedLong(cabecalho.getInt(0));
            String tipo = fourcc(cabecalho, 4);
            int bytesCabecalho = 8;
            if (tamanho == 1) {
                if (cabecalho.limit() < 16) {
                    throw new ArquivoValidado.Invalido("Estrutura do vídeo inválida.");
                }
                tamanho = cabecalho.getLong(8);
                bytesCabecalho = 16;
            } else if (tamanho == 0) {
                tamanho = fim - posicao; // a caixa vai até o fim do conteúdo que a contém
            }
            if (tamanho < bytesCabecalho || tamanho > fim - posicao) {
                throw new ArquivoValidado.Invalido("Estrutura do vídeo inválida.");
            }
            long corpo = posicao + bytesCabecalho;
            long fimCaixa = posicao + tamanho;
            switch (tipo) {
                case "trak" -> {
                    Faixa nova = new Faixa();
                    leitura.faixas.add(nova);
                    percorrer(canal, corpo, fimCaixa, profundidade + 1, leitura, nova);
                }
                // Marcas de MP4 fragmentado: recusa já, sem ler os fragmentos.
                case "mvex", "moof" -> throw new ArquivoValidado.Invalido(MP4_FRAGMENTADO);
                case "moov", "mdia", "minf", "stbl" ->
                    percorrer(canal, corpo, fimCaixa, profundidade + 1, leitura, faixa);
                // hdlr: versão/flags (4), pre_defined (4) e o tipo da faixa.
                case "hdlr" -> {
                    if (faixa != null && fimCaixa - corpo >= 12) {
                        faixa.tipo = fourcc(ler(canal, corpo + 8, 4), 0);
                    }
                }
                // stsd: versão/flags (4), quantidade (4) e as entradas, cada uma com tamanho e codec.
                case "stsd" -> {
                    if (faixa != null && fimCaixa - corpo >= 8) {
                        long quantidade =
                                Integer.toUnsignedLong(ler(canal, corpo + 4, 4).getInt(0));
                        long entrada = corpo + 8;
                        for (long i = 0; i < Math.min(quantidade, 8) && entrada + 8 <= fimCaixa; i++) {
                            ByteBuffer amostra = ler(canal, entrada, 8);
                            long tamanhoEntrada = Integer.toUnsignedLong(amostra.getInt(0));
                            faixa.codecs.add(fourcc(amostra, 4));
                            if (tamanhoEntrada < 8) {
                                break;
                            }
                            entrada += tamanhoEntrada;
                        }
                    }
                }
                default -> {
                    // dados de mídia e caixas sem relação com os codecs: pulados sem leitura
                }
            }
            posicao = fimCaixa;
        }
    }

    private static ByteBuffer ler(FileChannel canal, long posicao, int quantidade) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(quantidade);
        while (buffer.hasRemaining()) {
            if (canal.read(buffer, posicao + buffer.position()) < 0) {
                throw new IOException("fim do arquivo");
            }
        }
        return buffer.flip();
    }

    private static String fourcc(ByteBuffer buffer, int deslocamento) {
        byte[] bytes = new byte[4];
        buffer.get(deslocamento, bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
