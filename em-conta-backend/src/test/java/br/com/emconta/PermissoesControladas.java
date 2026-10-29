package br.com.emconta;

import br.com.emconta.acesso.sessao.PermissoesEfetivas;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permissões controladas para testes de autorização parcial. Para usuários registrados aqui, as
 * permissões vigentes são exatamente as concedidas; os demais seguem os perfis, como em produção.
 *
 * <p>Não cria perfil de produção nem desliga nada: a sessão, o {@code SessaoUsuarioFilter} e as regras
 * de rota da {@code SecurityConfiguration} são os reais; só o cálculo perfil → permissões é trocado
 * pelo ponto de extensão {@link PermissoesEfetivas}.
 */
public class PermissoesControladas implements PermissoesEfetivas {

    private final Map<Long, Set<Permissao>> concedidas = new ConcurrentHashMap<>();

    public void conceder(long usuarioId, Permissao... permissoes) {
        Set<Permissao> conjunto = EnumSet.noneOf(Permissao.class);
        conjunto.addAll(List.of(permissoes));
        concedidas.put(usuarioId, conjunto);
    }

    @Override
    public Set<Permissao> calcular(long usuarioId, Collection<Perfil> perfis) {
        Set<Permissao> controladas = concedidas.get(usuarioId);
        return controladas != null ? controladas : Permissao.de(perfis);
    }
}
