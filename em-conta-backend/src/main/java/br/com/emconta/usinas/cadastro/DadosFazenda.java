package br.com.emconta.usinas.cadastro;

/** Dados informados para uma fazenda, antes da validação. */
public record DadosFazenda(String nome, String cidade, String uf, String observacoes) {}
