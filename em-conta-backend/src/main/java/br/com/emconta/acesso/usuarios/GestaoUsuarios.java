package br.com.emconta.acesso.usuarios;

import br.com.emconta.acesso.auditoria.Auditoria;
import br.com.emconta.acesso.auditoria.TipoEvento;
import br.com.emconta.acesso.config.AcessoProperties;
import br.com.emconta.acesso.email.EmailsAcesso;
import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.sessao.LimitadorTentativas;
import br.com.emconta.acesso.sessao.SessoesUsuario;
import br.com.emconta.acesso.sessao.TipoLimite;
import br.com.emconta.acesso.tokens.FinalidadeToken;
import br.com.emconta.acesso.tokens.TokensAcesso;
import br.com.emconta.acesso.tokens.TokensAcesso.TokenEmitido;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Administração de usuários internos. As transações cobrem só o banco; e-mails e remoção de sessões
 * acontecem depois do commit.
 */
@Service
public class GestaoUsuarios {

    private final UsuarioRepository usuarios;
    private final TokensAcesso tokens;
    private final EmailsAcesso emails;
    private final Auditoria auditoria;
    private final LimitadorTentativas limitador;
    private final SessoesUsuario sessoes;
    private final AcessoProperties properties;
    private final Clock clock;
    private final TransactionTemplate transacao;

    GestaoUsuarios(
            UsuarioRepository usuarios,
            TokensAcesso tokens,
            EmailsAcesso emails,
            Auditoria auditoria,
            LimitadorTentativas limitador,
            SessoesUsuario sessoes,
            AcessoProperties properties,
            Clock clock,
            PlatformTransactionManager transacoes) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.emails = emails;
        this.auditoria = auditoria;
        this.limitador = limitador;
        this.sessoes = sessoes;
        this.properties = properties;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
    }

    public record ResultadoConvite(Usuario usuario, boolean conviteEnviado) {}

    private record ConviteCriado(Usuario usuario, TokenEmitido token) {}

    public Page<Usuario> listar(int pagina, int tamanho) {
        return usuarios.findAll(PageRequest.of(pagina, tamanho, Sort.by("nome").and(Sort.by("id"))));
    }

    public Usuario obter(long id) {
        return usuarios.findById(id).orElseThrow(FalhaAcesso::usuarioNaoEncontrado);
    }

    public ResultadoConvite convidar(Ator ator, String nome, String emailInformado, Set<Perfil> perfis, String ip) {
        String email = Emails.normalizar(emailInformado);
        String nomeValidado = Usuario.validarNome(nome);
        Set<Perfil> perfisConvite = copia(perfis);
        if (!perfisConvite.isEmpty()) {
            exigir(ator, Permissao.USUARIOS_ATRIBUIR_PERFIS);
            exigirPodeConceder(ator, perfisConvite);
        }

        ConviteCriado criado;
        try {
            criado = transacao.execute(status -> {
                usuarios.findByEmail(email).ifPresent(existente -> {
                    throw conflitoEmail(existente);
                });
                Usuario usuario = usuarios.saveAndFlush(Usuario.convidar(nomeValidado, email, perfisConvite, agora()));
                TokenEmitido token = tokens.emitir(
                        usuario.getId(),
                        FinalidadeToken.CONVITE,
                        properties.tokens().validadeConvite());
                auditoria.registrar(
                        TipoEvento.CONVITE_CRIADO, ator.id(), usuario.getId(), ip, "perfis=" + perfisConvite);
                return new ConviteCriado(usuario, token);
            });
        } catch (DataIntegrityViolationException e) {
            // Outra requisição criou o mesmo e-mail entre a verificação e o insert (índice único).
            throw FalhaAcesso.conflito("EMAIL_EM_USO", "Já existe um usuário com este e-mail.");
        }
        return new ResultadoConvite(criado.usuario(), enviarConvite(ator, criado.usuario(), criado.token(), ip));
    }

    public boolean reenviarConvite(Ator ator, long id, String ip) {
        Optional<Duration> bloqueio = limitador.consumir(TipoLimite.REENVIO_CONVITE, String.valueOf(id));
        if (bloqueio.isPresent()) {
            throw FalhaAcesso.muitasTentativas(bloqueio.get());
        }
        ConviteCriado criado = transacao.execute(status -> {
            Usuario usuario = obter(id);
            if (usuario.getSituacao() != SituacaoConta.CONVIDADO) {
                throw FalhaAcesso.conflito(
                        "CONVITE_NAO_APLICAVEL", "Só é possível reenviar convite para usuários com convite pendente.");
            }
            TokenEmitido token = tokens.emitir(
                    usuario.getId(),
                    FinalidadeToken.CONVITE,
                    properties.tokens().validadeConvite());
            auditoria.registrar(TipoEvento.CONVITE_REENVIADO, ator.id(), usuario.getId(), ip, null);
            return new ConviteCriado(usuario, token);
        });
        return enviarConvite(ator, criado.usuario(), criado.token(), ip);
    }

    public Usuario renomear(Ator ator, long id, String nome, String ip) {
        return transacao.execute(status -> {
            Usuario usuario = obter(id);
            usuario.renomear(nome, agora());
            auditoria.registrar(TipoEvento.USUARIO_RENOMEADO, ator.id(), id, ip, null);
            return usuario;
        });
    }

    public Usuario definirPerfis(Ator ator, long id, Set<Perfil> perfis, String ip) {
        Set<Perfil> novos = copia(perfis);
        return transacao.execute(status -> {
            List<Long> administradores = usuarios.travarAdministradoresAtivos();
            Usuario usuario = obter(id);
            Set<Perfil> alterados = EnumSet.copyOf(diferencaSimetrica(usuario.getPerfis(), novos));
            exigirPodeConceder(ator, alterados);
            if (usuario.administradorAtivo() && !novos.contains(Perfil.ADMINISTRADOR)) {
                exigirOutroAdministrador(administradores, id);
            }
            usuario.definirPerfis(novos, agora());
            auditoria.registrar(TipoEvento.PERFIS_ALTERADOS, ator.id(), id, ip, "perfis=" + novos);
            return usuario;
        });
    }

    public Usuario desativar(Ator ator, long id, String ip) {
        if (ator.id() == id) {
            throw FalhaAcesso.conflito("PROPRIA_CONTA", "Não é permitido desativar a própria conta.");
        }
        Usuario desativado = transacao.execute(status -> {
            List<Long> administradores = usuarios.travarAdministradoresAtivos();
            Usuario usuario = obter(id);
            if (usuario.getSituacao() == SituacaoConta.DESATIVADO) {
                return usuario;
            }
            if (usuario.administradorAtivo()) {
                exigirOutroAdministrador(administradores, id);
            }
            usuario.desativar(agora());
            tokens.invalidarTodosPendentes(id);
            auditoria.registrar(TipoEvento.USUARIO_DESATIVADO, ator.id(), id, ip, null);
            return usuario;
        });
        sessoes.encerrarTodas(id);
        return desativado;
    }

    public Usuario reativar(Ator ator, long id, String ip) {
        return transacao.execute(status -> {
            Usuario usuario = obter(id);
            if (usuario.getSituacao() != SituacaoConta.DESATIVADO) {
                throw FalhaAcesso.conflito("USUARIO_NAO_DESATIVADO", "O usuário não está desativado.");
            }
            usuario.reativar(agora());
            auditoria.registrar(TipoEvento.USUARIO_REATIVADO, ator.id(), id, ip, "situacao=" + usuario.getSituacao());
            return usuario;
        });
    }

    private boolean enviarConvite(Ator ator, Usuario usuario, TokenEmitido token, String ip) {
        boolean enviado = emails.enviarConvite(
                usuario.getEmail(),
                usuario.getNome(),
                properties.link("/convite", token.valor()),
                properties.tokens().validadeConvite());
        if (!enviado) {
            // Ninguém recebeu o link: o token é invalidado e o administrador vê a falha e pode reenviar.
            transacao.executeWithoutResult(status -> {
                tokens.invalidar(token.valor());
                auditoria.registrar(TipoEvento.CONVITE_EMAIL_FALHOU, ator.id(), usuario.getId(), ip, null);
            });
        }
        return enviado;
    }

    private static void exigir(Ator ator, Permissao permissao) {
        if (!ator.pode(permissao)) {
            throw FalhaAcesso.proibido("ACESSO_NEGADO", "Você não tem permissão para esta ação.");
        }
    }

    /** Impede escalada: só concede ou retira perfis cujas permissões o próprio ator já possui. */
    private static void exigirPodeConceder(Ator ator, Set<Perfil> perfis) {
        for (Perfil perfil : perfis) {
            if (!ator.permissoes().containsAll(perfil.permissoes())) {
                throw FalhaAcesso.proibido(
                        "PERFIL_NAO_CONCEDIVEL", "Você não pode conceder ou retirar o perfil " + perfil.nome() + ".");
            }
        }
    }

    private static void exigirOutroAdministrador(List<Long> administradoresAtivos, long id) {
        if (administradoresAtivos.stream().noneMatch(outro -> outro != id)) {
            throw FalhaAcesso.conflito(
                    "ULTIMO_ADMINISTRADOR", "Não é possível remover o acesso do último administrador ativo.");
        }
    }

    private static FalhaAcesso conflitoEmail(Usuario existente) {
        return switch (existente.getSituacao()) {
            case CONVIDADO ->
                FalhaAcesso.conflito(
                        "CONVITE_PENDENTE", "Já existe um convite pendente para este e-mail. Use o reenvio.");
            case ATIVO -> FalhaAcesso.conflito("EMAIL_EM_USO", "Já existe um usuário com este e-mail.");
            case DESATIVADO ->
                FalhaAcesso.conflito(
                        "USUARIO_DESATIVADO", "O usuário com este e-mail está desativado. Reative-o se necessário.");
        };
    }

    private static Set<Perfil> copia(Set<Perfil> perfis) {
        return perfis == null || perfis.isEmpty() ? EnumSet.noneOf(Perfil.class) : EnumSet.copyOf(perfis);
    }

    private static Set<Perfil> diferencaSimetrica(Set<Perfil> a, Set<Perfil> b) {
        Set<Perfil> resultado = EnumSet.noneOf(Perfil.class);
        a.stream().filter(p -> !b.contains(p)).forEach(resultado::add);
        b.stream().filter(p -> !a.contains(p)).forEach(resultado::add);
        return resultado;
    }

    private Instant agora() {
        return clock.instant();
    }
}
