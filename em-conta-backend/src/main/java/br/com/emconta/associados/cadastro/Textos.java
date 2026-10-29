package br.com.emconta.associados.cadastro;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Limpeza e validação de campos de texto do cadastro. */
final class Textos {

    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cntrl}");
    // Verificação de formato propositalmente simples: um @, sem espaços, domínio com ponto.
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern NAO_DIGITOS = Pattern.compile("\\D");
    private static final Pattern TELEFONE_DIGITADO = Pattern.compile("\\+?[\\d\\s().\\-]+");

    static final int TELEFONE_MIN_DIGITOS = 10;
    static final int TELEFONE_MAX_DIGITOS = 15;

    private Textos() {}

    /** NFC, sem caracteres de controle, espaços internos colapsados. Vazio vira {@code null}. */
    static String limpar(String valor) {
        if (valor == null) {
            return null;
        }
        String limpo = ESPACOS.matcher(CONTROLE.matcher(Normalizer.normalize(valor, Normalizer.Form.NFC))
                        .replaceAll(" "))
                .replaceAll(" ")
                .strip();
        return limpo.isEmpty() ? null : limpo;
    }

    static String obrigatorio(String campo, String valor, int maximo, String rotulo) {
        String limpo = limpar(valor);
        if (limpo == null) {
            throw FalhaCadastro.campoInvalido(campo, "Informe " + rotulo + ".");
        }
        return limite(campo, limpo, maximo);
    }

    static String opcional(String campo, String valor, int maximo) {
        String limpo = limpar(valor);
        return limpo == null ? null : limite(campo, limpo, maximo);
    }

    private static String limite(String campo, String valor, int maximo) {
        if (valor.length() > maximo) {
            throw FalhaCadastro.campoInvalido(campo, "Use no máximo " + maximo + " caracteres.");
        }
        return valor;
    }

    static String email(String campo, String valor) {
        String limpo = opcional(campo, valor, 254);
        if (limpo == null) {
            return null;
        }
        if (!EMAIL.matcher(limpo).matches()) {
            throw FalhaCadastro.campoInvalido(campo, "E-mail em formato inválido.");
        }
        return limpo.toLowerCase(Locale.ROOT);
    }

    /** Guarda só os dígitos (com DDI/DDD, se informados). Aceita parênteses, espaços, pontos e hífens. */
    static String telefone(String campo, String valor) {
        String limpo = limpar(valor);
        if (limpo == null) {
            return null;
        }
        if (!TELEFONE_DIGITADO.matcher(limpo).matches()) {
            throw FalhaCadastro.campoInvalido(campo, "Telefone em formato inválido.");
        }
        String digitos = NAO_DIGITOS.matcher(limpo).replaceAll("");
        if (digitos.length() < TELEFONE_MIN_DIGITOS || digitos.length() > TELEFONE_MAX_DIGITOS) {
            throw FalhaCadastro.campoInvalido(
                    campo,
                    "Informe o telefone com DDD (" + TELEFONE_MIN_DIGITOS + " a " + TELEFONE_MAX_DIGITOS
                            + " dígitos).");
        }
        return digitos;
    }
}
