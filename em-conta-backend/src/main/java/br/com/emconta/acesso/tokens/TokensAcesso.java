package br.com.emconta.acesso.tokens;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tokens de uso único (convite e redefinição de senha): 256 bits aleatórios, entregues ao usuário em
 * Base64URL e persistidos somente como SHA-256. Emitir um token novo invalida os pendentes da mesma
 * conta e finalidade.
 */
@Component
public class TokensAcesso {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMATO = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final JdbcClient jdbc;
    private final Clock clock;

    TokensAcesso(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record TokenEmitido(String valor, Instant expiraEm) {}

    public TokenEmitido emitir(long usuarioId, FinalidadeToken finalidade, Duration validade) {
        invalidarPendentes(usuarioId, finalidade);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant agora = clock.instant();
        Instant expiraEm = agora.plus(validade);
        jdbc.sql("insert into token_acesso (usuario_id, finalidade, hash_token, criado_em, expira_em)"
                        + " values (:usuario, :finalidade, :hash, :agora, :expira)")
                .param("usuario", usuarioId)
                .param("finalidade", finalidade.name())
                .param("hash", Hashes.sha256Hex(valor))
                .param("agora", Timestamp.from(agora))
                .param("expira", Timestamp.from(expiraEm))
                .update();
        return new TokenEmitido(valor, expiraEm);
    }

    /**
     * Consome o token de forma atômica: o UPDATE condicional trava a linha, e uma segunda requisição
     * concorrente encontra o token já consumido. Deve rodar na mesma transação que usa o resultado,
     * para que uma falha posterior desfaça o consumo.
     */
    public Optional<Long> consumir(String valor, FinalidadeToken finalidade) {
        if (!formatoValido(valor)) {
            return Optional.empty();
        }
        String hash = Hashes.sha256Hex(valor);
        int atualizados = jdbc.sql("update token_acesso set consumido_em = :agora"
                        + " where hash_token = :hash and finalidade = :finalidade"
                        + " and consumido_em is null and invalidado_em is null and expira_em > :agora")
                .param("agora", Timestamp.from(clock.instant()))
                .param("hash", hash)
                .param("finalidade", finalidade.name())
                .update();
        if (atualizados != 1) {
            return Optional.empty();
        }
        return jdbc.sql("select usuario_id from token_acesso where hash_token = :hash")
                .param("hash", hash)
                .query(Long.class)
                .optional();
    }

    /** Verifica sem consumir (para a tela exibir link inválido antes de o usuário digitar a senha). */
    public Optional<Long> validar(String valor, FinalidadeToken finalidade) {
        if (!formatoValido(valor)) {
            return Optional.empty();
        }
        return jdbc.sql("select usuario_id from token_acesso where hash_token = :hash and finalidade = :finalidade"
                        + " and consumido_em is null and invalidado_em is null and expira_em > :agora")
                .param("hash", Hashes.sha256Hex(valor))
                .param("finalidade", finalidade.name())
                .param("agora", Timestamp.from(clock.instant()))
                .query(Long.class)
                .optional();
    }

    public void invalidar(String valor) {
        jdbc.sql("update token_acesso set invalidado_em = :agora where hash_token = :hash and invalidado_em is null")
                .param("agora", Timestamp.from(clock.instant()))
                .param("hash", Hashes.sha256Hex(valor))
                .update();
    }

    public void invalidarPendentes(long usuarioId, FinalidadeToken finalidade) {
        jdbc.sql("update token_acesso set invalidado_em = :agora where usuario_id = :usuario"
                        + " and finalidade = :finalidade and consumido_em is null and invalidado_em is null")
                .param("agora", Timestamp.from(clock.instant()))
                .param("usuario", usuarioId)
                .param("finalidade", finalidade.name())
                .update();
    }

    public void invalidarTodosPendentes(long usuarioId) {
        for (FinalidadeToken finalidade : FinalidadeToken.values()) {
            invalidarPendentes(usuarioId, finalidade);
        }
    }

    private static boolean formatoValido(String valor) {
        return valor != null && FORMATO.matcher(valor).matches();
    }
}
