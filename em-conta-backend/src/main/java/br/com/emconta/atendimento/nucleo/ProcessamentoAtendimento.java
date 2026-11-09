package br.com.emconta.atendimento.nucleo;

import br.com.emconta.atendimento.nucleo.AvisosAtendimento.TrabalhoPendente;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Poller da caixa de entrada e da fila de envio. Roda na API e no worker (com o agendamento habilitado);
 * várias instâncias são seguras porque as linhas são reservadas com {@code SKIP LOCKED}.
 *
 * <p>Além do ciclo periódico, roda logo depois que algo entra na fila nesta instância (evento do webhook
 * gravado, resposta ou anexo registrado: {@link TrabalhoPendente}), numa thread própria. Pedidos que
 * chegam enquanto um já espera viram um só; um pedido durante uma execução garante mais uma depois
 * dela, então nada fica para o ciclo seguinte. O ciclo periódico continua como rede de segurança (e é o
 * que pega o trabalho gravado por outras instâncias).
 */
@Component
@ConditionalOnProperty(
        name = "emconta.atendimento.processamento.habilitado",
        havingValue = "true",
        matchIfMissing = true)
class ProcessamentoAtendimento {

    private static final Logger log = LoggerFactory.getLogger(ProcessamentoAtendimento.class);

    private final Recebimento recebimento;
    private final Envio envio;
    private final Midias midias;
    private final ExecutorService imediato = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("atendimento-imediato").daemon().factory());
    private final AtomicBoolean agendado = new AtomicBoolean();

    ProcessamentoAtendimento(Recebimento recebimento, Envio envio, Midias midias) {
        this.recebimento = recebimento;
        this.envio = envio;
        this.midias = midias;
    }

    @Scheduled(
            initialDelayString = "${emconta.atendimento.processamento.intervalo:PT2S}",
            fixedDelayString = "${emconta.atendimento.processamento.intervalo:PT2S}")
    void processar() {
        try {
            recebimento.processarPendentes(50);
            envio.enviarPendentes(20);
            midias.processarPendentes(10);
        } catch (RuntimeException e) {
            log.warn(
                    "Atendimento: falha no processamento periódico ({})",
                    e.getClass().getSimpleName());
        }
    }

    @EventListener
    void despertar(TrabalhoPendente pedido) {
        if (!agendado.compareAndSet(false, true)) {
            return; // já há uma execução esperando: ela pega este trabalho também
        }
        try {
            imediato.execute(() -> {
                agendado.set(false);
                processar();
            });
        } catch (RejectedExecutionException e) {
            agendado.set(false); // encerrando: o trabalho fica para o ciclo de outra instância
        }
    }

    @PreDestroy
    void encerrar() {
        imediato.shutdownNow();
    }
}
