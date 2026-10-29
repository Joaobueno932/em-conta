package br.com.emconta.acesso.sessao;

import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import java.util.Collection;
import java.util.Set;

/**
 * Cálculo das permissões de um usuário a partir dos seus perfis, feito a cada requisição pelo
 * {@link SessaoUsuarioFilter}. Em produção não há bean: vale {@link Permissao#de(Collection)}.
 *
 * <p>Existe para que testes concedam conjuntos parciais de permissões a usuários de teste sem criar
 * perfis fictícios no código de produção; sessão, filtro e regras de rota continuam os reais.
 */
@FunctionalInterface
public interface PermissoesEfetivas {

    Set<Permissao> calcular(long usuarioId, Collection<Perfil> perfis);
}
