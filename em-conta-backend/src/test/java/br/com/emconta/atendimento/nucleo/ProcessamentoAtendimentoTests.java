package br.com.emconta.atendimento.nucleo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import br.com.emconta.atendimento.nucleo.AvisosAtendimento.TrabalhoPendente;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Processamento imediato: pedidos em sequência viram poucas execuções e nenhum fica sem execução. */
class ProcessamentoAtendimentoTests {

    @Test
    void pedidosEnquantoUmEsperaViramUmEPedidoDuranteAExecucaoGaranteOutra() throws Exception {
        Recebimento recebimento = mock(Recebimento.class);
        AtomicInteger execucoes = new AtomicInteger();
        CountDownLatch primeiraComecou = new CountDownLatch(1);
        CountDownLatch liberarPrimeira = new CountDownLatch(1);
        doAnswer(chamada -> {
                    if (execucoes.incrementAndGet() == 1) {
                        primeiraComecou.countDown();
                        liberarPrimeira.await(5, TimeUnit.SECONDS);
                    }
                    return 0;
                })
                .when(recebimento)
                .processarPendentes(anyInt());
        ProcessamentoAtendimento processamento =
                new ProcessamentoAtendimento(recebimento, mock(Envio.class), mock(Midias.class));
        try {
            processamento.despertar(new TrabalhoPendente());
            assertThat(primeiraComecou.await(5, TimeUnit.SECONDS)).isTrue();
            // Durante a primeira execução: três pedidos viram uma execução a mais.
            for (int i = 0; i < 3; i++) {
                processamento.despertar(new TrabalhoPendente());
            }
            liberarPrimeira.countDown();
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (execucoes.get() < 2 && System.nanoTime() < limite) {
                Thread.sleep(20);
            }
            Thread.sleep(200);
            assertThat(execucoes.get()).isEqualTo(2);
        } finally {
            processamento.encerrar();
        }
    }
}
