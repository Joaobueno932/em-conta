package br.com.emconta.acesso;

import br.com.emconta.acesso.sessao.PermissoesEfetivas;
import br.com.emconta.acesso.sessao.SessaoUsuario;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.SituacaoConta;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * API pública do módulo de acesso para registrar e exibir autoria em outros módulos. Expõe o id do
 * usuário autenticado, nomes de usuários (inclusive desativados; usuários nunca são excluídos) e quais
 * usuários ativos têm uma permissão (ex.: destinatários de transferência de atendimento).
 */
@Component
public class Autoria {

    private final UsuarioRepository usuarios;
    private final PermissoesEfetivas permissoes;

    Autoria(UsuarioRepository usuarios, ObjectProvider<PermissoesEfetivas> permissoes) {
        this.usuarios = usuarios;
        this.permissoes = permissoes.getIfAvailable(() -> (usuarioId, perfis) -> Permissao.de(perfis));
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

    public record UsuarioResumo(long id, String nome) {}

    /** Usuários ativos que têm a permissão (pelo valor da autoridade, ex.: {@code atendimento:responder}). */
    @Transactional(readOnly = true)
    public List<UsuarioResumo> ativosComPermissao(String autoridade) {
        return usuarios.findAll().stream()
                .filter(u -> u.getSituacao() == SituacaoConta.ATIVO && tem(u, autoridade))
                .sorted(Comparator.comparing(Usuario::getNome))
                .map(u -> new UsuarioResumo(u.getId(), u.getNome()))
                .toList();
    }

    /** O usuário existe, está ativo e tem a permissão agora. */
    @Transactional(readOnly = true)
    public boolean ativoComPermissao(long usuarioId, String autoridade) {
        return usuarios.findById(usuarioId)
                .filter(u -> u.getSituacao() == SituacaoConta.ATIVO && tem(u, autoridade))
                .isPresent();
    }

    private boolean tem(Usuario usuario, String autoridade) {
        return permissoes.calcular(usuario.getId(), usuario.getPerfis()).stream()
                .anyMatch(p -> p.autoridade().equals(autoridade));
    }
}
