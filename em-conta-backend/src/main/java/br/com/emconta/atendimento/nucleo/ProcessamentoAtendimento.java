package br.com.emconta.atendimento.nucleo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Poller da caixa de entrada e da fila de envio. Roda na API e no worker (com o agendamento habilitado);
 * várias instâncias são seguras porque as linhas são reservadas com {@code SKIP LOCKED}.
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

    ProcessamentoAtendimento(Recebimento recebimento, Envio envio) {
        this.recebimento = recebimento;
        this.envio = envio;
    }

    @Scheduled(
            initialDelayString = "${emconta.atendimento.processamento.intervalo:PT2S}",
            fixedDelayString = "${emconta.atendimento.processamento.intervalo:PT2S}")
    void processar() {
        try {
            recebimento.processarPendentes(50);
            envio.enviarPendentes(20);
        } catch (RuntimeException e) {
            log.warn(
                    "Atendimento: falha no processamento periódico ({})",
                    e.getClass().getSimpleName());
        }
    }
}
