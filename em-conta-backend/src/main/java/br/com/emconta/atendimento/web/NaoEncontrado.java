package br.com.emconta.atendimento.web;

/** Recurso do atendimento inexistente (404 {@code ATENDIMENTO_NAO_ENCONTRADO}). */
class NaoEncontrado extends RuntimeException {

    NaoEncontrado() {
        super("Atendimento não encontrado.");
    }
}
