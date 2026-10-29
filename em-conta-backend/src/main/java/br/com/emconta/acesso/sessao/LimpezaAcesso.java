package br.com.emconta.acesso.sessao;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mantém limitado o armazenamento de contadores e tokens. Idempotente: pode rodar em várias réplicas. */
@Component
class LimpezaAcesso {

    private final LimitadorTentativas limitador;
    private final JdbcClient jdbc;
    private final Clock clock;

    LimpezaAcesso(LimitadorTentativas limitador, JdbcClient jdbc, Clock clock) {
        this.limitador = limitador;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    void limpar() {
        limitador.removerVencidos();
        // Tokens vencidos há mais de 30 dias não têm mais uso; a auditoria preserva o histórico.
        jdbc.sql("delete from token_acesso where expira_em < :limite")
                .param("limite", Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                .update();
    }
}
