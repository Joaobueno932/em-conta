package br.com.emconta.acesso.auditoria;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Registro das operações de segurança. Participa da transação corrente, quando houver. Os detalhes
 * nunca incluem senhas, tokens, hashes, cookies ou e-mails digitados em tentativas.
 */
@Component
public class Auditoria {

    private static final int TAMANHO_MAXIMO_DETALHES = 500;

    private final JdbcClient jdbc;
    private final Clock clock;

    Auditoria(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void registrar(TipoEvento tipo, Long ator, Long alvo, String ip, String detalhes) {
        String texto = detalhes != null && detalhes.length() > TAMANHO_MAXIMO_DETALHES
                ? detalhes.substring(0, TAMANHO_MAXIMO_DETALHES)
                : detalhes;
        jdbc.sql("insert into auditoria_seguranca (ocorrido_em, tipo, ator_usuario_id, alvo_usuario_id, ip, detalhes)"
                        + " values (:agora, :tipo, :ator, :alvo, :ip, :detalhes)")
                .param("agora", Timestamp.from(clock.instant()))
                .param("tipo", tipo.name())
                .param("ator", ator)
                .param("alvo", alvo)
                .param("ip", ip)
                .param("detalhes", texto)
                .update();
    }
}
