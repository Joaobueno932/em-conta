package br.com.emconta.acesso.usuarios;

import br.com.emconta.acesso.auditoria.Auditoria;
import br.com.emconta.acesso.auditoria.TipoEvento;
import java.time.Clock;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Criação do primeiro administrador. Usado apenas pelo modo de bootstrap (profile
 * {@code bootstrap-admin}); não há endpoint HTTP. Recusa a execução se já existir administrador
 * convidado ou ativo, ou se o e-mail já estiver em uso.
 */
@Service
public class BootstrapAdministrador {

    private final UsuarioRepository usuarios;
    private final PasswordEncoder passwordEncoder;
    private final Auditoria auditoria;
    private final Clock clock;
    private final TransactionTemplate transacao;

    BootstrapAdministrador(
            UsuarioRepository usuarios,
            PasswordEncoder passwordEncoder,
            Auditoria auditoria,
            Clock clock,
            PlatformTransactionManager transacoes) {
        this.usuarios = usuarios;
        this.passwordEncoder = passwordEncoder;
        this.auditoria = auditoria;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
    }

    public long criar(String nome, String emailInformado, String senha) {
        String nomeValidado = Usuario.validarNome(nome);
        String email = Emails.normalizar(emailInformado);
        PoliticaSenha.validar(senha);
        String hash = passwordEncoder.encode(senha);
        return transacao.execute(status -> {
            usuarios.travarPerfilAdministrador(); // serializa execuções simultâneas do bootstrap
            if (usuarios.existeAdministradorNaoDesativado()) {
                throw new IllegalStateException(
                        "Já existe administrador. Use a administração de usuários para criar novos acessos.");
            }
            if (usuarios.findByEmail(email).isPresent()) {
                throw new IllegalStateException("Já existe usuário com este e-mail; nada foi alterado.");
            }
            Usuario admin = usuarios.saveAndFlush(
                    Usuario.ativo(nomeValidado, email, hash, Set.of(Perfil.ADMINISTRADOR), clock.instant()));
            auditoria.registrar(TipoEvento.ADMINISTRADOR_INICIAL_CRIADO, null, admin.getId(), null, null);
            return admin.getId();
        });
    }
}
