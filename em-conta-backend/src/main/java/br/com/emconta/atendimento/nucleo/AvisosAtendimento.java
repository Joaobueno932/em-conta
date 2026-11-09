package br.com.emconta.atendimento.nucleo;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Avisos depois da confirmação da transação, para a tela e o processamento não esperarem os
 * intervalos de consulta:
 *
 * <ul>
 *   <li>{@link #conversa}: algo visível na linha do tempo mudou. Incrementa {@code atd_sinal} (lido por
 *       todas as instâncias da API, inclusive quando quem processou foi o worker) e publica
 *       {@link ConversaAlterada} para a instância local avisar na hora;
 *   <li>{@link #trabalho}: há evento do webhook, envio ou download à espera; publica
 *       {@link TrabalhoPendente} para o processamento local rodar já, sem esperar o próximo ciclo.
 * </ul>
 *
 * <p>Só depois do commit: antes disso a mudança ainda não é visível para quem relê. São sinais, nunca o
 * conteúdo; falha ao sinalizar só atrasa a tela até a consulta de segurança, nunca desfaz a mudança.
 */
@Component
public class AvisosAtendimento {

    private static final Logger log = LoggerFactory.getLogger(AvisosAtendimento.class);

    /** Uma conversa mudou (já confirmado no banco). */
    public record ConversaAlterada(long conversaId) {}

    /** Há trabalho para o processamento (já confirmado no banco). */
    record TrabalhoPendente() {}

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    private final TransactionTemplate transacao;

    AvisosAtendimento(
            JdbcClient jdbc, ApplicationEventPublisher eventos, Clock clock, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.eventos = eventos;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Conversas alteradas e se há trabalho, acumulados até o commit da transação corrente. É uma
     * sincronização da própria transação: numa transação interna ({@code REQUIRES_NEW}) o Spring suspende
     * a externa, e cada uma avisa no seu commit (rollback não avisa nada).
     */
    private final class Acumulador implements TransactionSynchronization {
        final Set<Long> conversas = new LinkedHashSet<>();
        boolean trabalho;

        @Override
        public void afterCommit() {
            conversas.forEach(AvisosAtendimento.this::sinalizar);
            if (trabalho) {
                eventos.publishEvent(new TrabalhoPendente());
            }
        }
    }

    public void conversa(long conversaId) {
        Acumulador acumulador = acumulador();
        if (acumulador == null) {
            sinalizar(conversaId);
        } else {
            acumulador.conversas.add(conversaId);
        }
    }

    public void trabalho() {
        Acumulador acumulador = acumulador();
        if (acumulador == null) {
            eventos.publishEvent(new TrabalhoPendente());
        } else {
            acumulador.trabalho = true;
        }
    }

    /** Acumulador da transação corrente (registrado na primeira chamada); nulo fora de transação. */
    private Acumulador acumulador() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (s instanceof Acumulador existente) {
                return existente;
            }
        }
        Acumulador novo = new Acumulador();
        TransactionSynchronizationManager.registerSynchronization(novo);
        return novo;
    }

    private void sinalizar(long conversaId) {
        try {
            // Transação própria: depois do commit, a da mudança não grava mais nada.
            transacao.executeWithoutResult(status -> jdbc.sql(
                            "insert into atd_sinal (conversa_id, contador, atualizado_em) values (:c, 1, :agora)"
                                    + " on duplicate key update contador = contador + 1,"
                                    + " atualizado_em = values(atualizado_em)")
                    .param("c", conversaId)
                    .param("agora", Timestamp.from(clock.instant()))
                    .update());
            eventos.publishEvent(new ConversaAlterada(conversaId));
        } catch (RuntimeException e) {
            log.warn(
                    "Atendimento: sinal de mudança da conversa {} não gravado ({})",
                    conversaId,
                    e.getClass().getSimpleName());
        }
    }
}
