package br.com.emconta.atendimento.nucleo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Validação de um arquivo temporário já em disco: assinatura do formato, tamanho (não vazio e dentro do
 * limite) e SHA-256, lendo em blocos (nunca o arquivo inteiro na memória).
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
        return new ArquivoValidado(total, digest.digest());
    }
}
