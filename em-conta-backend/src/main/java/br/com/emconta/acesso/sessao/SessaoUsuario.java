package br.com.emconta.acesso.sessao;

import java.io.Serial;
import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * O que fica guardado na sessão: a identidade, a versão de segurança vigente no login e uma chave
 * interna aleatória que identifica esta sessão mesmo quando o identificador HTTP é renovado. Situação
 * e permissões são relidas do banco a cada requisição ({@link SessaoUsuarioFilter}).
 *
 * <p>A chave nunca sai do servidor (a sessão é guardada no banco); serve para a troca de senha
 * autorizar somente a sessão que a realizou ({@code sessao_renovada}). Sessões anteriores a este
 * campo têm chave nula e simplesmente não podem ser promovidas.
 *
 * <p>{@link #getName()} devolve o id, usado pelo Spring Session no índice {@code PRINCIPAL_NAME}.
 */
public record SessaoUsuario(long usuarioId, int versaoSeguranca, Instant autenticadoEm, String chave)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Nova sessão (login), com chave interna aleatória de 128 bits. */
    public static SessaoUsuario nova(long usuarioId, int versaoSeguranca, Instant autenticadoEm) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return new SessaoUsuario(
                usuarioId,
                versaoSeguranca,
                autenticadoEm,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** A mesma sessão (mesma chave e instante de login) numa nova versão de segurança. */
    public SessaoUsuario naVersao(int novaVersao) {
        return new SessaoUsuario(usuarioId, novaVersao, autenticadoEm, chave);
    }

    @Override
    public String getName() {
        return String.valueOf(usuarioId);
    }
}
