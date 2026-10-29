package br.com.emconta.acesso.usuarios;

import br.com.emconta.acesso.erros.FalhaAcesso;
import java.nio.charset.StandardCharsets;

/**
 * Política técnica inicial de senha (provisória, ver docs/seguranca.md): de 15 a 128 caracteres
 * (pontos de código Unicode) e no máximo 512 bytes em UTF-8, sem regras de composição nem expiração.
 * A senha é usada exatamente como digitada: nada é truncado, aparado ou normalizado.
 */
public final class PoliticaSenha {

    public static final int MINIMO_CARACTERES = 15;
    public static final int MAXIMO_CARACTERES = 128;
    public static final int MAXIMO_BYTES = 512;

    private PoliticaSenha() {}

    public static void validar(String senha) {
        if (senha == null || senha.isEmpty()) {
            throw invalida("Informe a senha.");
        }
        int caracteres = senha.codePointCount(0, senha.length());
        if (caracteres < MINIMO_CARACTERES) {
            throw invalida("A senha deve ter pelo menos " + MINIMO_CARACTERES + " caracteres.");
        }
        if (caracteres > MAXIMO_CARACTERES || senha.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES) {
            throw invalida("A senha deve ter no máximo " + MAXIMO_CARACTERES + " caracteres.");
        }
        if (senha.isBlank()) {
            throw invalida("A senha não pode conter apenas espaços.");
        }
    }

    /** Senhas maiores que o limite nunca são válidas; evita processar entradas arbitrariamente grandes. */
    public static boolean excedeLimite(String senha) {
        return senha.length() > MAXIMO_BYTES || senha.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES;
    }

    private static FalhaAcesso invalida(String mensagem) {
        return FalhaAcesso.requisicaoInvalida("SENHA_FORA_DA_POLITICA", mensagem);
    }
}
