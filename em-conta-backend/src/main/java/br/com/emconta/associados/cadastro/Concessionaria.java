package br.com.emconta.associados.cadastro;

/**
 * Concessionárias em que uma UC pode ser cadastrada. O número da UC é único por concessionária
 * (RN-CLI-03). Formato e normalização do número de cada concessionária ainda não foram documentados:
 * o número é guardado como texto, preservando zeros à esquerda.
 */
public enum Concessionaria {
    ENERGISA_MS("Energisa MS");

    private final String nome;

    Concessionaria(String nome) {
        this.nome = nome;
    }

    public String nome() {
        return nome;
    }
}
