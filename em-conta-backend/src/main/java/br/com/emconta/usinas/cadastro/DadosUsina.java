package br.com.emconta.usinas.cadastro;

/** Dados informados para uma usina, antes da validação. {@code fazendaId} é opcional. */
public record DadosUsina(
        String nome, Long fazendaId, String cep, String endereco, String cidade, String uf, String observacoes) {}
