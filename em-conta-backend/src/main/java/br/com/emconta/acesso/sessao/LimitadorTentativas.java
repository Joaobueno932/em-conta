package br.com.emconta.acesso.sessao;

import br.com.emconta.acesso.config.AcessoProperties;
import br.com.emconta.acesso.config.AcessoProperties.Regra;
import br.com.emconta.acesso.tokens.Hashes;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Limite de tentativas em janela fixa, com bloqueio temporário, persistido no MySQL (compartilhado
 * entre réplicas, sem Redis).
 *
 * <p>Cada tentativa é contada <em>antes</em> da operação, em transação própria: o upsert trava a
 * linha da chave e requisições concorrentes são serializadas, então não é possível exceder o limite
 * disparando tentativas em paralelo. A chave é o SHA-256 de tipo+valor; nenhum e-mail ou IP fica em
 * claro.
 */
@Component
public class LimitadorTentativas {

    private static final String UPSERT = "insert into limite_tentativa"
            + " (tipo, chave, tentativas, janela_inicio, bloqueado_ate, atualizado_em)"
            + " values (:tipo, :chave, 1, :agora, null, :agora)"
            + " on duplicate key update"
            // Avaliadas da esquerda para a direita: as duas últimas já enxergam o novo valor de tentativas.
            + " tentativas = if(janela_inicio < :inicioJanela and (bloqueado_ate is null or bloqueado_ate <= :agora),"
            + " 1, tentativas + 1),"
            + " janela_inicio = if(tentativas = 1, :agora, janela_inicio),"
            + " bloqueado_ate = if(tentativas = 1, null, bloqueado_ate),"
            + " atualizado_em = :agora";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AcessoProperties properties;
    private final TransactionTemplate novaTransacao;

    LimitadorTentativas(
            JdbcClient jdbc, Clock clock, AcessoProperties properties, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.novaTransacao = new TransactionTemplate(transacoes);
        this.novaTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Registra uma tentativa.
     *
     * @return vazio se permitida; caso contrário, o tempo até o fim do bloqueio
     */
    public Optional<Duration> consumir(TipoLimite tipo, String valor) {
        Regra regra = properties.limites().regra(tipo);
        String chave = chave(tipo, valor);
        Instant agora = clock.instant();
        return novaTransacao.execute(status -> {
            jdbc.sql(UPSERT)
                    .param("tipo", tipo.name())
                    .param("chave", chave)
                    .param("agora", Timestamp.from(agora))
                    .param("inicioJanela", Timestamp.from(agora.minus(regra.janela())))
                    .update();
            Map<String, Object> linha = jdbc.sql(
                            "select tentativas, bloqueado_ate from limite_tentativa where tipo = :tipo and chave = :chave")
                    .param("tipo", tipo.name())
                    .param("chave", chave)
                    .query()
                    .singleRow();
            Instant bloqueadoAte = linha.get("bloqueado_ate") instanceof Timestamp ts ? ts.toInstant() : null;
            if (bloqueadoAte != null && bloqueadoAte.isAfter(agora)) {
                return Optional.of(Duration.between(agora, bloqueadoAte));
            }
            int tentativas = ((Number) linha.get("tentativas")).intValue();
            if (tentativas > regra.maximo()) {
                jdbc.sql("update limite_tentativa set bloqueado_ate = :ate where tipo = :tipo and chave = :chave")
                        .param("ate", Timestamp.from(agora.plus(regra.bloqueio())))
                        .param("tipo", tipo.name())
                        .param("chave", chave)
                        .update();
                return Optional.of(regra.bloqueio());
            }
            return Optional.<Duration>empty();
        });
    }

    /** Zera o contador (ex.: login bem-sucedido na conta). */
    public void limpar(TipoLimite tipo, String valor) {
        jdbc.sql("delete from limite_tentativa where tipo = :tipo and chave = :chave")
                .param("tipo", tipo.name())
                .param("chave", chave(tipo, valor))
                .update();
    }

    /** Devolve uma tentativa (ex.: login bem-sucedido não conta para o limite do IP). */
    public void devolver(TipoLimite tipo, String valor) {
        jdbc.sql("update limite_tentativa set tentativas = greatest(tentativas - 1, 0)"
                        + " where tipo = :tipo and chave = :chave")
                .param("tipo", tipo.name())
                .param("chave", chave(tipo, valor))
                .update();
    }

    /** Remove contadores sem atividade há mais de um dia e sem bloqueio vigente. */
    public int removerVencidos() {
        Instant agora = clock.instant();
        return jdbc.sql("delete from limite_tentativa where atualizado_em < :limite"
                        + " and (bloqueado_ate is null or bloqueado_ate < :agora)")
                .param("limite", Timestamp.from(agora.minus(Duration.ofDays(1))))
                .param("agora", Timestamp.from(agora))
                .update();
    }

    private static String chave(TipoLimite tipo, String valor) {
        return Hashes.sha256Hex(tipo.name() + ":" + (valor == null ? "" : valor));
    }
}
