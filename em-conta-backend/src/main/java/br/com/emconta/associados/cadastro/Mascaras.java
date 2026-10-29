package br.com.emconta.associados.cadastro;

/**
 * Máscaras usadas no histórico: o registro mostra o que mudou sem copiar o dado pessoal completo.
 */
final class Mascaras {

    private Mascaras() {}

    /** {@code maria@exemplo.com} vira {@code m***@exemplo.com}. */
    static String email(String email) {
        if (email == null) {
            return null;
        }
        int arroba = email.indexOf('@');
        return arroba <= 0 ? "***" : email.charAt(0) + "***" + email.substring(arroba);
    }

    /** Mantém só os 4 últimos dígitos: {@code ****3344}. */
    static String telefone(String digitos) {
        if (digitos == null) {
            return null;
        }
        return "****" + digitos.substring(Math.max(0, digitos.length() - 4));
    }
}
