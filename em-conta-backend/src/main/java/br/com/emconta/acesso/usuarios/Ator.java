package br.com.emconta.acesso.usuarios;

import java.util.Set;

/** Quem executa uma operação administrativa, com as permissões vigentes calculadas pelo servidor. */
public record Ator(long id, Set<Permissao> permissoes) {

    public boolean pode(Permissao permissao) {
        return permissoes.contains(permissao);
    }
}
