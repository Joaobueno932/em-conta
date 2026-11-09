package br.com.emconta.atendimento.nucleo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validação de um arquivo temporário já em disco: assinatura do formato, tamanho (não vazio e dentro do
 * limite), SHA-256 e, para planilhas, o pacote; lendo em blocos (nunca o arquivo inteiro na memória).
 */
record ArquivoValidado(long tamanho, byte[] sha256) {

    String sha256Hex() {
        return HexFormat.of().formatHex(sha256);
    }

    /** Arquivo recusado pela validação (motivo apresentável, sem conteúdo). */
    static final class Invalido extends Exception {
        Invalido(String motivo) {
            super(motivo);
        }
    }

    static ArquivoValidado de(Path arquivo, FormatosAnexo.Formato formato, long limite) throws Invalido, IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] inicio = new byte[64];
        int noInicio = 0;
        long total = 0;
        byte[] bloco = new byte[64 * 1024];
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            int lidos;
            while ((lidos = entrada.read(bloco)) != -1) {
                if (noInicio < inicio.length) {
                    int copiar = Math.min(lidos, inicio.length - noInicio);
                    System.arraycopy(bloco, 0, inicio, noInicio, copiar);
                    noInicio += copiar;
                }
                total += lidos;
                if (total > limite) {
                    throw new Invalido("Arquivo acima do limite de " + FormatosAnexo.tamanhoLegivel(limite) + ".");
                }
                digest.update(bloco, 0, lidos);
            }
        }
        if (total == 0) {
            throw new Invalido("Arquivo vazio.");
        }
        if (!FormatosAnexo.assinaturaConfere(formato, Arrays.copyOf(inicio, noInicio))) {
            throw new Invalido("O conteúdo do arquivo não corresponde ao tipo " + formato.mime() + ".");
        }
        if (FormatosAnexo.XLSX.equals(formato.mime())) {
            conferirPlanilha(arquivo);
        }
        return new ArquivoValidado(total, digest.digest());
    }

    /** Entradas no pacote de uma planilha além das quais ela não é conferida (nem aceita). */
    static final int MAXIMO_ENTRADAS_PLANILHA = 10_000;

    /**
     * Pacote OOXML de planilha: lê só o diretório central do ZIP (nada é descompactado) e exige
     * {@code [Content_Types].xml} e {@code xl/workbook.xml}. Projeto VBA (planilha com macros renomeada
     * para .xlsx) é recusado: o WhatsApp aceita XLSX, não XLSM.
     */
    private static void conferirPlanilha(Path arquivo) throws Invalido {
        boolean tipos = false;
        boolean pasta = false;
        int entradas = 0;
        try (ZipFile zip = new ZipFile(arquivo.toFile())) {
            Enumeration<? extends ZipEntry> todas = zip.entries();
            while (todas.hasMoreElements()) {
                String nome = todas.nextElement().getName();
                if (++entradas > MAXIMO_ENTRADAS_PLANILHA) {
                    throw new Invalido("A planilha tem itens demais para ser conferida.");
                }
                tipos |= "[Content_Types].xml".equals(nome);
                pasta |= "xl/workbook.xml".equals(nome);
                if ("xl/vbaproject.bin".equals(nome.toLowerCase(Locale.ROOT))) {
                    throw new Invalido("A planilha contém macros; o WhatsApp aceita só XLSX sem macros.");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new Invalido("O arquivo não é uma planilha XLSX válida.");
        }
        if (!tipos || !pasta) {
            throw new Invalido("O arquivo não é uma planilha XLSX válida.");
        }
    }
}
