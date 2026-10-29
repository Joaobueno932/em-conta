package br.com.emconta.acesso;

import br.com.emconta.acesso.sessao.SessaoUsuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * API pública do módulo de acesso para registrar e exibir autoria em outros módulos. Expõe apenas o
 * id do usuário autenticado e o nome de usuários, inclusive desativados (usuários nunca são excluídos).
 */
@Component
public class Autoria {

    private final UsuarioRepository usuarios;

    Autoria(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Id do usuário da requisição corrente, já revalidado pelo filtro de sessão. */
    public long usuarioAtual() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.getPrincipal() instanceof SessaoUsuario sessao) {
            return sessao.usuarioId();
        }
        throw new AuthenticationCredentialsNotFoundException("Sem usuário autenticado.");
    }

    /** Nomes por id; ids inexistentes ficam fora do mapa. */
    @Transactional(readOnly = true)
    public Map<Long, String> nomes(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return usuarios.nomes(ids).stream()
                .collect(
                        Collectors.toMap(UsuarioRepository.NomeUsuario::getId, UsuarioRepository.NomeUsuario::getNome));
    }
}
