package br.com.emconta.acesso.sessao;

import br.com.emconta.acesso.auditoria.Auditoria;
import br.com.emconta.acesso.auditoria.TipoEvento;
import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.usuarios.Emails;
import br.com.emconta.acesso.usuarios.PoliticaSenha;
import br.com.emconta.acesso.usuarios.SituacaoConta;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Verificação de credenciais. Qualquer motivo de recusa (e-mail inexistente, senha errada, conta
 * convidada ou desativada) produz a mesma resposta e custa o mesmo cálculo de hash.
 */
@Service
public class Autenticacao {

    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final LimitadorTentativas limitador;
    private final Auditoria auditoria;
    private final Clock clock;
    private final String hashFicticio;

    Autenticacao(
            UsuarioRepository usuarios,
            PasswordEncoder passwordEncoder,
            LimitadorTentativas limitador,
            Auditoria auditoria,
            Clock clock) {
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
        this.limitador = limitador;
        this.auditoria = auditoria;
        this.clock = clock;
        this.hashFicticio = passwordEncoder.encode("hash-ficticio-para-tempo-constante");
    }

    public SessaoUsuario autenticar(String emailInformado, String senha, String ip) {
        String chaveConta = Emails.tentarNormalizar(emailInformado).orElse(String.valueOf(emailInformado));

        exigirDentroDoLimite(TipoLimite.LOGIN_IP, ip, ip);
        exigirDentroDoLimite(TipoLimite.LOGIN_CONTA, chaveConta, ip);

        Optional<Usuario> encontrado = Emails.tentarNormalizar(emailInformado).flatMap(usuarios::findByEmail);
        String hash = encontrado.map(Usuario::getSenhaHash).orElse(hashFicticio);
        boolean senhaConfere = senha != null
                && !PoliticaSenha.excedeLimite(senha)
                && passwordEncoder.matches(senha, hash != null ? hash : hashFicticio);

        if (!senhaConfere || encontrado.isEmpty() || encontrado.get().getSituacao() != SituacaoConta.ATIVO) {
            auditoria.registrar(
                    TipoEvento.LOGIN_FALHA, null, encontrado.map(Usuario::getId).orElse(null), ip, null);
            throw FalhaAcesso.credenciaisInvalidas();
        }

        Usuario usuario = encontrado.get();
        limitador.limpar(TipoLimite.LOGIN_CONTA, chaveConta);
        limitador.devolver(TipoLimite.LOGIN_IP, ip);
        auditoria.registrar(TipoEvento.LOGIN_SUCESSO, usuario.getId(), usuario.getId(), ip, null);
        return SessaoUsuario.nova(usuario.getId(), usuario.getVersaoSeguranca(), clock.instant());
    }

    private void exigirDentroDoLimite(TipoLimite tipo, String valor, String ip) {
        Optional<Duration> bloqueio = limitador.consumir(tipo, valor);
        if (bloqueio.isPresent()) {
            auditoria.registrar(TipoEvento.LOGIN_BLOQUEADO, null, null, ip, tipo.name());
            throw FalhaAcesso.muitasTentativas(bloqueio.get());
        }
    }
}
