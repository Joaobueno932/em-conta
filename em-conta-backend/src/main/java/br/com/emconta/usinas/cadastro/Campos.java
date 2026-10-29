package br.com.emconta.usinas.cadastro;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Limpeza e validação dos campos de fazenda e usina. */
final class Campos {

    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cntrl}");
    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern CEP = Pattern.compile("\\d{8}");
    private static final Pattern CEP_DIGITADO = Pattern.compile("[\\d.\\-\\s]+");

    static final Set<String> UFS = Set.of(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
            "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private Campos() {}

    /** NFC, sem caracteres de controle, espaços colapsados. Vazio vira {@code null}. */
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
            throw FalhaUsinas.campoInvalido(campo, "Informe " + rotulo + ".");
        }
        return limite(campo, limpo, maximo);
    }

    static String opcional(String campo, String valor, int maximo) {
        String limpo = limpar(valor);
        return limpo == null ? null : limite(campo, limpo, maximo);
    }

    private static String limite(String campo, String valor, int maximo) {
        if (valor.length() > maximo) {
            throw FalhaUsinas.campoInvalido(campo, "Use no máximo " + maximo + " caracteres.");
        }
        return valor;
    }

    /**
     * Chave para detectar nomes possivelmente duplicados: sem acentos, minúsculas e espaços colapsados.
     * Não é chave única: nomes iguais geram aviso, e o usuário pode confirmar.
     */
    static String chaveNome(String nome) {
        String semAcento =
                MARCAS.matcher(Normalizer.normalize(nome, Normalizer.Form.NFD)).replaceAll("");
        return ESPACOS.matcher(semAcento.toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .strip();
    }

    /** CEP com 8 dígitos; aceita ponto, hífen e espaços na digitação. */
    static String cep(String valor) {
        String limpo = limpar(valor);
        if (limpo == null) {
            return null;
        }
        String digitos = limpo.replaceAll("\\D", "");
        if (!CEP_DIGITADO.matcher(limpo).matches() || !CEP.matcher(digitos).matches()) {
            throw FalhaUsinas.campoInvalido("cep", "CEP deve ter 8 dígitos.");
        }
        return digitos;
    }

    static String uf(String valor) {
        String limpo = limpar(valor);
        if (limpo == null) {
            return null;
        }
        String uf = limpo.toUpperCase(Locale.ROOT);
        if (!UFS.contains(uf)) {
            throw FalhaUsinas.campoInvalido("uf", "UF inválida.");
        }
        return uf;
    }
}
