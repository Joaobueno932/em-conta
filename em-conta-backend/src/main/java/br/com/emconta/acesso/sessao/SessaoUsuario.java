package br.com.emconta.acesso.sessao;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * O que fica guardado na sessão: só a identidade e a versão de segurança vigente no login. Situação
 * e permissões são relidas do banco a cada requisição ({@link SessaoUsuarioFilter}).
 *
 * <p>{@link #getName()} devolve o id, usado pelo Spring Session no índice {@code PRINCIPAL_NAME}.
 */
public record SessaoUsuario(long usuarioId, int versaoSeguranca, Instant autenticadoEm)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return String.valueOf(usuarioId);
    }
}
