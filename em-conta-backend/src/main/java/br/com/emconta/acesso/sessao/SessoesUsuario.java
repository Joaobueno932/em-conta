package br.com.emconta.acesso.sessao;

import br.com.emconta.acesso.config.AcessoProperties;
import br.com.emconta.acesso.tokens.Hashes;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.SituacaoConta;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Estado vigente das sessões.
 *
 * <p>Estratégia de revogação: a sessão guarda a {@code versao_seguranca} do login; desativação,
 * redefinição e troca de senha incrementam a versão, e toda requisição com versão antiga é recusada.
 * Além disso, as sessões do usuário são apagadas do Spring Session. Permissões nunca vêm da sessão:
 * são recalculadas a partir dos perfis no banco a cada requisição.
 */
@Component
public class SessoesUsuario {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AcessoProperties properties;
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositorio;
    private final PermissoesEfetivas permissoes;

    SessoesUsuario(
            JdbcClient jdbc,
            Clock clock,
            AcessoProperties properties,
            ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositorio,
            ObjectProvider<PermissoesEfetivas> permissoes) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.repositorio = repositorio;
        // Sem bean (produção): permissões vêm só dos perfis.
        this.permissoes = permissoes.getIfAvailable(() -> (usuarioId, perfis) -> Permissao.de(perfis));
    }

    private record Estado(String situacao, int versaoSeguranca, String perfis) {}

    /**
     * Sessão aceita nesta requisição: o principal (promovido à versão atual quando a troca de senha
     * autorizou esta sessão) e as autoridades vigentes.
     */
    public record SessaoValida(SessaoUsuario principal, List<GrantedAuthority> autoridades, boolean promovida) {}

    /** Autoridades vigentes, ou vazio se a sessão não vale mais (conta inapta, versão antiga ou expirada). */
    public Optional<List<GrantedAuthority>> autoridadesVigentes(SessaoUsuario sessao) {
        return validar(sessao).map(SessaoValida::autoridades);
    }

    /**
     * Valida a sessão contra o estado atual da conta. Versão de segurança antiga só é aceita se a troca
     * de senha registrou, na mesma transação que incrementou a versão, que exatamente esta sessão
     * (pela chave interna) continua válida nessa versão. Não há janela de tolerância: qualquer outra
     * sessão, uma redefinição por link ou uma desativação (que incrementam a versão sem esse registro)
     * continuam recusadas.
     */
    public Optional<SessaoValida> validar(SessaoUsuario sessao) {
        if (sessao.autenticadoEm().plus(properties.sessao().duracaoMaxima()).isBefore(clock.instant())) {
            return Optional.empty();
        }
        Optional<Estado> estado = jdbc.sql("select u.situacao, u.versao_seguranca, group_concat(p.perfil) as perfis"
                        + " from usuario u left join usuario_perfil p on p.usuario_id = u.id"
                        + " where u.id = :id group by u.id, u.situacao, u.versao_seguranca")
                .param("id", sessao.usuarioId())
                .query((rs, n) -> new Estado(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .optional();
        if (estado.isEmpty() || !SituacaoConta.ATIVO.name().equals(estado.get().situacao())) {
            return Optional.empty();
        }
        Estado atual = estado.get();
        if (atual.versaoSeguranca() == sessao.versaoSeguranca()) {
            return Optional.of(new SessaoValida(sessao, autoridades(sessao.usuarioId(), atual.perfis()), false));
        }
        if (sessao.chave() != null && renovacaoAutorizada(sessao, atual.versaoSeguranca())) {
            SessaoUsuario promovida = sessao.naVersao(atual.versaoSeguranca());
            return Optional.of(new SessaoValida(promovida, autoridades(sessao.usuarioId(), atual.perfis()), true));
        }
        return Optional.empty();
    }

    private boolean renovacaoAutorizada(SessaoUsuario sessao, int versaoAtual) {
        return jdbc.sql("select count(*) from sessao_renovada where usuario_id = :usuario and chave_sessao = :chave"
                                + " and versao_seguranca = :versao")
                        .param("usuario", sessao.usuarioId())
                        .param("chave", Hashes.sha256Hex(sessao.chave()))
                        .param("versao", versaoAtual)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Registra que a sessão continua válida na nova versão de segurança. Chamar dentro da transação
     * que incrementa a versão; autorizações anteriores do usuário são descartadas.
     */
    public void autorizarContinuidade(SessaoUsuario sessao, int novaVersao) {
        removerRenovacoes(sessao.usuarioId());
        if (sessao.chave() == null) {
            return;
        }
        jdbc.sql("insert into sessao_renovada (usuario_id, chave_sessao, versao_seguranca, criado_em)"
                        + " values (:usuario, :chave, :versao, :agora)")
                .param("usuario", sessao.usuarioId())
                .param("chave", Hashes.sha256Hex(sessao.chave()))
                .param("versao", novaVersao)
                .param("agora", Timestamp.from(clock.instant()))
                .update();
    }

    /** Descarta autorizações de continuidade do usuário (redefinição por link, por exemplo). */
    public void removerRenovacoes(long usuarioId) {
        jdbc.sql("delete from sessao_renovada where usuario_id = :usuario")
                .param("usuario", usuarioId)
                .update();
    }

    private List<GrantedAuthority> autoridades(long usuarioId, String perfis) {
        List<Perfil> lista = perfis == null
                ? List.of()
                : Arrays.stream(perfis.split(","))
                        .map(SessoesUsuario::perfil)
                        .filter(Objects::nonNull)
                        .toList();
        return permissoes.calcular(usuarioId, lista).stream()
                .<GrantedAuthority>map(p -> new SimpleGrantedAuthority(p.autoridade()))
                .toList();
    }

    private static Perfil perfil(String nome) {
        try {
            return Perfil.valueOf(nome);
        } catch (IllegalArgumentException e) {
            return null; // perfil removido do código: não concede nada
        }
    }

    public void encerrarTodas(long usuarioId) {
        encerrar(usuarioId, null);
    }

    public void encerrarOutras(long usuarioId, String sessaoMantida) {
        encerrar(usuarioId, sessaoMantida);
    }

    private void encerrar(long usuarioId, String sessaoMantida) {
        FindByIndexNameSessionRepository<? extends Session> sessoes = repositorio.getIfAvailable();
        if (sessoes == null) {
            return; // processo sem sessões HTTP (worker/bootstrap): a versão de segurança já revoga
        }
        sessoes.findByPrincipalName(String.valueOf(usuarioId)).keySet().stream()
                .filter(id -> !id.equals(sessaoMantida))
                .forEach(sessoes::deleteById);
    }
}
