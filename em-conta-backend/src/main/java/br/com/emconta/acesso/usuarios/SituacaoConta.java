package br.com.emconta.acesso.usuarios;

/** Situação da conta. Somente {@link #ATIVO} pode autenticar. */
public enum SituacaoConta {
    /** Convite enviado; senha ainda não definida. */
    CONVIDADO,
    /** Senha definida e acesso liberado. */
    ATIVO,
    /** Acesso revogado. O registro é mantido para preservar autoria e histórico. */
    DESATIVADO
}
