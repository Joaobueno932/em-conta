package br.com.emconta.associados.cadastro;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * CPF e CNPJ: normalização para a forma canônica, validação estrutural e máscara de apresentação.
 *
 * <p>Forma canônica: sem pontuação, letras em maiúsculas. CPF tem 11 dígitos. CNPJ tem 14 posições:
 * 12 alfanuméricas ({@code 0-9}, {@code A-Z}) e 2 dígitos verificadores numéricos, conforme o CNPJ
 * alfanumérico da Receita Federal (IN RFB 2.229/2024, vigente desde julho de 2026). Os CNPJs
 * numéricos continuam válidos e são o caso particular em que as 12 posições são dígitos.
 *
 * <p>Dígitos verificadores pelo módulo 11, convertendo cada caractere pelo código ASCII menos 48
 * ({@code 0}=0 … {@code 9}=9, {@code A}=17, {@code B}=18 …) e pesos 2 a 9 da direita para a esquerda
 * (o CPF usa pesos 2 a 11). Validação apenas estrutural: não consulta a situação na Receita.
 */
public final class Documentos {

    /** Separadores aceitos na digitação ou colagem; são descartados na normalização. */
    private static final Pattern SEPARADORES = Pattern.compile("[\\s.\\-/]");

    private static final Pattern CPF = Pattern.compile("\\d{11}");
    private static final Pattern CNPJ = Pattern.compile("[0-9A-Z]{12}\\d{2}");

    private Documentos() {}

    /** Remove separadores e coloca letras em maiúsculas. Não valida. */
    public static String normalizar(String informado) {
        if (informado == null) {
            return "";
        }
        return SEPARADORES.matcher(informado).replaceAll("").toUpperCase(Locale.ROOT);
    }

    public static boolean cpfValido(String canonico) {
        if (canonico == null || !CPF.matcher(canonico).matches() || repetido(canonico)) {
            return false;
        }
        return digito(canonico, 9, 10) == canonico.charAt(9) - '0'
                && digito(canonico, 10, 11) == canonico.charAt(10) - '0';
    }

    public static boolean cnpjValido(String canonico) {
        if (canonico == null || !CNPJ.matcher(canonico).matches() || repetido(canonico)) {
            return false;
        }
        return digitoCnpj(canonico, 12) == canonico.charAt(12) - '0'
                && digitoCnpj(canonico, 13) == canonico.charAt(13) - '0';
    }

    /** Máscara completa: {@code 000.000.000-00} ou {@code 00.000.000/0000-00} (também alfanumérico). */
    public static String formatar(TipoPessoa tipo, String canonico) {
        if (tipo == TipoPessoa.PF) {
            return canonico.substring(0, 3) + "." + canonico.substring(3, 6) + "." + canonico.substring(6, 9) + "-"
                    + canonico.substring(9);
        }
        return canonico.substring(0, 2) + "." + canonico.substring(2, 5) + "." + canonico.substring(5, 8) + "/"
                + canonico.substring(8, 12) + "-" + canonico.substring(12);
    }

    /**
     * Máscara parcial para listagens e históricos: {@code 123.***.***-00} e {@code 12.345.***}{@code
     * /0001-90}. Mantém o suficiente para conferência visual.
     */
    public static String mascarar(TipoPessoa tipo, String canonico) {
        if (tipo == TipoPessoa.PF) {
            return canonico.substring(0, 3) + ".***.***-" + canonico.substring(9);
        }
        return canonico.substring(0, 2) + "." + canonico.substring(2, 5) + ".***/" + canonico.substring(8, 12) + "-"
                + canonico.substring(12);
    }

    private static boolean repetido(String valor) {
        return valor.chars().allMatch(c -> c == valor.charAt(0));
    }

    /** CPF: soma dos {@code quantidade} primeiros dígitos com pesos decrescentes a partir de {@code pesoInicial}. */
    private static int digito(String valor, int quantidade, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < quantidade; i++) {
            soma += (valor.charAt(i) - '0') * (pesoInicial - i);
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    /** CNPJ: pesos 2 a 9 da direita para a esquerda, recomeçando em 2 após o 9. */
    private static int digitoCnpj(String valor, int quantidade) {
        int soma = 0;
        int peso = 2;
        for (int i = quantidade - 1; i >= 0; i--) {
            soma += (valor.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
