package br.com.emconta.acesso.usuarios;

import br.com.emconta.acesso.auditoria.Auditoria;
import br.com.emconta.acesso.auditoria.TipoEvento;
import br.com.emconta.acesso.config.AcessoProperties;
import br.com.emconta.acesso.email.EmailsAcesso;
import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.sessao.LimitadorTentativas;
import br.com.emconta.acesso.sessao.SessaoUsuario;
import br.com.emconta.acesso.sessao.SessoesUsuario;
import br.com.emconta.acesso.sessao.TipoLimite;
import br.com.emconta.acesso.tokens.FinalidadeToken;
import br.com.emconta.acesso.tokens.TokensAcesso;
import br.com.emconta.acesso.tokens.TokensAcesso.TokenEmitido;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Aceite de convite, recuperação, redefinição e troca de senha. */
@Service
public class Credenciais {

    private static final Logger log = LoggerFactory.getLogger(Credenciais.class);

    private final UsuarioRepository usuarios;
    private final TokensAcesso tokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailsAcesso emails;
    private final Auditoria auditoria;
    private final LimitadorTentativas limitador;
    private final SessoesUsuario sessoes;
    private final AcessoProperties properties;
    private final Clock clock;
    private final TaskExecutor executor;
    private final TransactionTemplate transacao;

    Credenciais(
            UsuarioRepository usuarios,
            TokensAcesso tokens,
            PasswordEncoder passwordEncoder,
            EmailsAcesso emails,
            Auditoria auditoria,
            LimitadorTentativas limitador,
            SessoesUsuario sessoes,
            AcessoProperties properties,
            Clock clock,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            PlatformTransactionManager transacoes) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.emails = emails;
        this.auditoria = auditoria;
        this.limitador = limitador;
        this.sessoes = sessoes;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        this.transacao = new TransactionTemplate(transacoes);
    }

    public record TokenValido(FinalidadeToken finalidade, String nome) {}

    /** Confere um link sem consumi-lo. Só confirma tokens cuja conta ainda está apta à finalidade. */
    public TokenValido validarToken(String token, FinalidadeToken finalidade, String ip) {
        limitarUsoDeToken(ip);
        Usuario usuario = contaDoToken(token, finalidade);
        return new TokenValido(finalidade, usuario.getNome());
    }

    /**
     * Aceite de convite. Ordem: limite por IP, validação do token (consulta barata), política da senha,
     * Argon2 fora da transação e, por fim, consumo atômico do token na transação que ativa a conta. A
     * validação prévia só evita trabalho caro; quem garante o uso único é o consumo condicional.
     */
    public void aceitarConvite(String token, String senha, String ip) {
        limitarUsoDeToken(ip);
        contaDoToken(token, FinalidadeToken.CONVITE);
        PoliticaSenha.validar(senha);
        String hash = passwordEncoder.encode(senha); // fora da transação: o Argon2 é deliberadamente lento
        transacao.executeWithoutResult(status -> {
            long id = tokens.consumir(token, FinalidadeToken.CONVITE).orElseThrow(FalhaAcesso::tokenInvalido);
            Usuario usuario = usuarios.travar(id).orElseThrow(FalhaAcesso::tokenInvalido);
            usuario.definirSenhaInicial(hash, clock.instant());
            auditoria.registrar(TipoEvento.CONVITE_ACEITO, id, id, ip, null);
        });
    }

    private void limitarUsoDeToken(String ip) {
        Optional<Duration> bloqueio = limitador.consumir(TipoLimite.USO_TOKEN_IP, ip);
        if (bloqueio.isPresent()) {
            throw FalhaAcesso.muitasTentativas(bloqueio.get());
        }
    }

    /** Conta do token, se o token é válido e a conta está apta à finalidade; senão TOKEN_INVALIDO. */
    private Usuario contaDoToken(String token, FinalidadeToken finalidade) {
        return tokens.validar(token, finalidade)
                .flatMap(usuarios::findById)
                .filter(u -> u.getSituacao() == situacaoEsperada(finalidade))
                .orElseThrow(FalhaAcesso::tokenInvalido);
    }

    /**
     * Solicitação de recuperação. A resposta HTTP é sempre a mesma; a busca da conta e o envio do
     * e-mail rodam em segundo plano para que o tempo de resposta também não revele se a conta existe.
     * Contas convidadas ou desativadas não recebem link.
     */
    public void solicitarRecuperacao(String emailInformado, String ip) {
        Optional<Duration> bloqueioIp = limitador.consumir(TipoLimite.RECUPERACAO_IP, ip);
        if (bloqueioIp.isPresent()) {
            throw FalhaAcesso.muitasTentativas(bloqueioIp.get());
        }
        Optional<String> email = Emails.tentarNormalizar(emailInformado);
        if (email.isEmpty()) {
            return;
        }
        executor.execute(() -> processarRecuperacao(email.get(), ip));
    }

    private void processarRecuperacao(String email, String ip) {
        try {
            if (limitador.consumir(TipoLimite.RECUPERACAO_CONTA, email).isPresent()) {
                return; // excesso de pedidos para a conta: ignorado em silêncio
            }
            Optional<Usuario> usuario = usuarios.findByEmail(email).filter(u -> u.getSituacao() == SituacaoConta.ATIVO);
            if (usuario.isEmpty()) {
                return;
            }
            Usuario u = usuario.get();
            TokenEmitido token = transacao.execute(status -> {
                TokenEmitido emitido = tokens.emitir(
                        u.getId(),
                        FinalidadeToken.REDEFINICAO_SENHA,
                        properties.tokens().validadeRedefinicao());
                auditoria.registrar(TipoEvento.RECUPERACAO_SOLICITADA, null, u.getId(), ip, null);
                return emitido;
            });
            boolean enviado = emails.enviarRedefinicao(
                    u.getEmail(),
                    u.getNome(),
                    properties.link("/redefinir-senha", token.valor()),
                    properties.tokens().validadeRedefinicao());
            if (!enviado) {
                transacao.executeWithoutResult(status -> {
                    tokens.invalidar(token.valor());
                    auditoria.registrar(TipoEvento.RECUPERACAO_EMAIL_FALHOU, null, u.getId(), ip, null);
                });
            }
        } catch (RuntimeException e) {
            log.error(
                    "Falha ao processar solicitação de recuperação de senha: {}",
                    e.getClass().getSimpleName());
        }
    }

    /** Redefinição pelo link: encerra todas as sessões do usuário. Mesma ordem do aceite de convite. */
    public void redefinirSenha(String token, String novaSenha, String ip) {
        limitarUsoDeToken(ip);
        contaDoToken(token, FinalidadeToken.REDEFINICAO_SENHA);
        PoliticaSenha.validar(novaSenha);
        String hash = passwordEncoder.encode(novaSenha);
        Usuario usuario = transacao.execute(status -> {
            long id = tokens.consumir(token, FinalidadeToken.REDEFINICAO_SENHA).orElseThrow(FalhaAcesso::tokenInvalido);
            // Trava a conta: serializa com trocas de senha e desativações concorrentes.
            Usuario u = usuarios.travar(id).orElseThrow(FalhaAcesso::tokenInvalido);
            u.trocarSenha(hash, clock.instant()); // recusa conta inativa: rollback desfaz o consumo do token
            tokens.invalidarPendentes(id, FinalidadeToken.REDEFINICAO_SENHA);
            sessoes.removerRenovacoes(id);
            auditoria.registrar(TipoEvento.SENHA_REDEFINIDA, id, id, ip, null);
            return u;
        });
        sessoes.encerrarTodas(usuario.getId());
        limitador.limpar(TipoLimite.LOGIN_CONTA, usuario.getEmail());
    }

    /**
     * Troca autenticada. Política: a sessão atual continua (com novo identificador, renovado por quem
     * chama) e todas as outras sessões do usuário são encerradas.
     *
     * <p>Concorrência: a senha atual é conferida (Argon2) fora de transação contra um instantâneo da
     * credencial (hash e versão de segurança). A gravação acontece numa transação curta que trava a
     * linha do usuário e só prossegue se a conta continua ativa e o instantâneo ainda é o atual; senão
     * nada é gravado e a resposta é 409 {@code CREDENCIAL_ALTERADA}. Assim, uma redefinição, outra
     * troca ou uma desativação que concluam no intervalo não são sobrescritas.
     *
     * <p>Na mesma transação fica registrado que esta sessão (pela chave interna) continua válida na nova
     * versão; requisições concorrentes da própria sessão são aceitas e promovidas pelo filtro, enquanto
     * as demais sessões continuam recusadas.
     *
     * @return o principal com a nova versão de segurança, a ser gravado na sessão atual
     */
    public SessaoUsuario alterarSenha(
            SessaoUsuario sessao, String senhaAtual, String novaSenha, String sessaoAtualId, String ip) {
        Usuario usuario = usuarios.findById(sessao.usuarioId()).orElseThrow(FalhaAcesso::usuarioNaoEncontrado);
        Optional<Duration> bloqueio = limitador.consumir(TipoLimite.LOGIN_CONTA, usuario.getEmail());
        if (bloqueio.isPresent()) {
            throw FalhaAcesso.muitasTentativas(bloqueio.get());
        }
        if (usuario.getSituacao() != SituacaoConta.ATIVO || usuario.getVersaoSeguranca() != sessao.versaoSeguranca()) {
            throw FalhaAcesso.credencialAlterada();
        }
        String hashValidado = usuario.getSenhaHash();
        int versaoValidada = usuario.getVersaoSeguranca();
        if (senhaAtual == null
                || PoliticaSenha.excedeLimite(senhaAtual)
                || !passwordEncoder.matches(senhaAtual, usuario.getSenhaHash())) {
            auditoria.registrar(TipoEvento.SENHA_ATUAL_INCORRETA, usuario.getId(), usuario.getId(), ip, null);
            throw FalhaAcesso.requisicaoInvalida("SENHA_ATUAL_INCORRETA", "A senha atual não confere.");
        }
        PoliticaSenha.validar(novaSenha);
        String hash = passwordEncoder.encode(novaSenha);
        Usuario atualizado = transacao.execute(status -> {
            Usuario u = usuarios.travar(sessao.usuarioId()).orElseThrow(FalhaAcesso::usuarioNaoEncontrado);
            if (u.getSituacao() != SituacaoConta.ATIVO
                    || u.getVersaoSeguranca() != versaoValidada
                    || !Objects.equals(u.getSenhaHash(), hashValidado)) {
                throw FalhaAcesso.credencialAlterada();
            }
            u.trocarSenha(hash, clock.instant());
            usuarios.flush();
            tokens.invalidarPendentes(u.getId(), FinalidadeToken.REDEFINICAO_SENHA);
            sessoes.autorizarContinuidade(sessao, u.getVersaoSeguranca());
            auditoria.registrar(TipoEvento.SENHA_ALTERADA, u.getId(), u.getId(), ip, null);
            return u;
        });
        limitador.limpar(TipoLimite.LOGIN_CONTA, atualizado.getEmail());
        sessoes.encerrarOutras(atualizado.getId(), sessaoAtualId);
        return sessao.naVersao(atualizado.getVersaoSeguranca());
    }

    private static SituacaoConta situacaoEsperada(FinalidadeToken finalidade) {
        return finalidade == FinalidadeToken.CONVITE ? SituacaoConta.CONVIDADO : SituacaoConta.ATIVO;
    }
}
