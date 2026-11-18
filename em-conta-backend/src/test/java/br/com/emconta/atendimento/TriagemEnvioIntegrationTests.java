package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Triagem e fila de envio: a saudação e a repetição do menu que ainda aguardam envio não saem depois que
 * alguém assume (nem depois que a triagem termina por outro motivo), inclusive à espera de nova
 * tentativa, no reprocessamento da fila e na disputa com o envio. A mensagem já reservada (chamada à
 * Meta em andamento) segue o envio normal. O aviso de responsável fica ligado aqui e sai uma única vez.
 */
@TestPropertySource(properties = "emconta.atendimento.aviso-responsavel.habilitado=true")
class TriagemEnvioIntegrationTests extends TriagemIntegracao {

    private static final String AVISO = "Seu atendimento agora est";
    private static final String NOVA_TENTATIVA = "Não identifiquei a opção";

    @Test
    void assumirAntesDoEnvioDaSaudacaoCancelaEOAvisoSaiUmaVez() throws Exception {
        Navegador admin = administrador();
        configurar(admin, true, setor(admin, "Financeiro " + marca()), null, null);
        String numero = numeroUnico();
        String corpo = mensagem(numero, "Contato " + numero, wamid(), "Olá", agoraEpoch());
        enviarWebhook(corpo, assinar(corpo));
        recebimento.processarPendentes(50); // ciclo aberto; a saudação aguarda envio
        long atendimento = aberto(numero);
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("PENDENTE");

        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("CANCELADA");
        assertThat(triagem(atendimento)).isEqualTo("INTERROMPIDA");
        assertThat(historicoDaSaudacao(atendimento)).containsExactly("CANCELADA");

        processar();
        assertThat(enviosPara(numero, SAUDACAO)).isEmpty();
        assertThat(enviosPara(numero, AVISO)).hasSize(1);

        // Reprocessar a fila e reentregar a mensagem que abriu o ciclo não recriam nem enviam a saudação.
        processar();
        enviarWebhook(corpo, assinar(corpo));
        String outraEntrega = mensagem(numero, "Contato " + numero, idExterno(atendimento), "Olá", agoraEpoch() + 5);
        enviarWebhook(outraEntrega, assinar(outraEntrega));
        processar();
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("CANCELADA");
        assertThat(enviosPara(numero, SAUDACAO)).isEmpty();
        assertThat(enviosPara(numero, AVISO)).hasSize(1);
        assertThat(contar("atd_mensagem where atendimento_id = :c and origem = 'AVISO_RESPONSAVEL'", atendimento))
                .isEqualTo(1);

        // Na linha do tempo, a saudação fica registrada como não enviada, com o motivo.
        var itens = corpo(admin.get(url(atendimento, "linha-do-tempo"))).get("itens");
        List<String> canceladas = new ArrayList<>();
        itens.forEach(i -> {
            if ("TRIAGEM".equals(i.path("origem").asString(""))) {
                canceladas.add(i.get("situacao").asString() + ": "
                        + i.get("erroTitulo").asString());
            }
        });
        assertThat(canceladas).singleElement().asString().startsWith("CANCELADA: Não enviada: a triagem terminou");
    }

    @Test
    void repeticaoDoMenuPendenteNaoSaiDepoisDaAssuncao() throws Exception {
        Navegador admin = administrador();
        configurar(admin, true, setor(admin, "Financeiro " + marca()), null, null);
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        processar(); // saudação enviada
        String invalida = mensagem(numero, "Contato " + numero, wamid(), "bom dia", agoraEpoch());
        enviarWebhook(invalida, assinar(invalida));
        recebimento.processarPendentes(50); // a repetição do menu aguarda envio
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("ACEITA", "PENDENTE");

        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        processar();
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("ACEITA", "CANCELADA");
        assertThat(enviosPara(numero, SAUDACAO)).hasSize(1);
        assertThat(enviosPara(numero, NOVA_TENTATIVA)).isEmpty();
        assertThat(enviosPara(numero, AVISO)).hasSize(1);
    }

    @Test
    void saudacaoAEsperaDeNovaTentativaTambemECancelada() throws Exception {
        Navegador admin = administrador();
        configurar(admin, true, setor(admin, "Financeiro " + marca()), null, null);
        GRAPH.modo(Modo.ERRO_TEMPORARIO);
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        envio.enviarPendentes(20); // erro temporário: volta para a fila com nova tentativa marcada
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("PENDENTE");
        assertThat(enviosPara(numero, SAUDACAO)).hasSize(1);

        GRAPH.modo(Modo.ACEITAR);
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        relogio.fixar(Instant.now().plus(Duration.ofMinutes(10))); // a nova tentativa já venceu
        envio.enviarPendentes(20);
        relogio.liberar();
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("CANCELADA");
        assertThat(enviosPara(numero, SAUDACAO)).as("só a tentativa recusada").hasSize(1);
        assertThat(enviosPara(numero, AVISO)).hasSize(1);
    }

    @Test
    void saudacaoDeUmaTriagemJaRespondidaNaoSaiNaReserva() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        configurar(admin, true, financeiro, null, null);
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        // O cliente responde antes de a saudação sair (ex.: já conhecia o menu): a triagem classifica e a
        // saudação, ainda na fila, fica obsoleta; a confirmação do encaminhamento sai.
        String resposta = mensagem(numero, "Contato " + numero, wamid(), "1", agoraEpoch());
        enviarWebhook(resposta, assinar(resposta));
        recebimento.processarPendentes(50);
        assertThat(triagem(atendimento)).isEqualTo("CLASSIFICADA");

        processar();
        assertThat(situacoesDaTriagem(atendimento)).containsExactly("CANCELADA", "ACEITA");
        assertThat(enviosPara(numero, SAUDACAO)).isEmpty();
        assertThat(enviosPara(numero, "Obrigado!")).hasSize(1);
        assertThat(setorDe(atendimento)).isEqualTo(financeiro);
    }

    @Test
    void envioJaEmAndamentoNaoECanceladoEOResultadoERegistrado() throws Exception {
        Navegador admin = administrador();
        configurar(admin, true, setor(admin, "Financeiro " + marca()), null, null);
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        long saudacao = jdbc.sql("select id from atd_mensagem where atendimento_id = :a and origem = 'TRIAGEM'")
                .param("a", atendimento)
                .query(Long.class)
                .single();
        // Reserva confirmada pelo Envio (a chamada à Meta está em andamento, fora de transação).
        jdbc.sql("update atd_mensagem set situacao = 'ENVIANDO', tentativas = 1 where id = :id")
                .param("id", saudacao)
                .update();

        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(situacao(saudacao)).isEqualTo("ENVIANDO");
        assertThat(triagem(atendimento)).isEqualTo("INTERROMPIDA");

        // O resultado da chamada chega pelo webhook (correlação) e é registrado como o de qualquer envio.
        webhookSituacao("wamid.TESTE" + saudacao, "sent", correlacao(saudacao), null);
        processar();
        assertThat(situacao(saudacao)).isEqualTo("ENVIADA");
        assertThat(enviosPara(numero, AVISO)).hasSize(1);
    }

    @Test
    void assuncaoEEnvioAoMesmoTempoNuncaCancelamDepoisDeEnviarNemEnviamDepoisDeCancelar() throws Exception {
        Navegador admin = administrador();
        configurar(admin, true, setor(admin, "Financeiro " + marca()), null, null);
        for (int rodada = 0; rodada < 8; rodada++) {
            String numero = numeroUnico();
            long atendimento = novoAtendimento(numero);
            Navegador sessao = administrador();
            List<Callable<Integer>> tarefas = new ArrayList<>();
            tarefas.add(() -> envio.enviarPendentes(20));
            tarefas.add(() -> sessao.post(url(atendimento, "assuncao"), null).getStatus());
            List<Future<Integer>> resultados = emParalelo(tarefas);
            assertThat(resultados.get(1).get()).isEqualTo(204);
            processar();

            String saudacao = situacoesDaTriagem(atendimento).getFirst();
            assertThat(saudacao).as("rodada %d", rodada).isIn("ACEITA", "CANCELADA");
            assertThat(enviosPara(numero, SAUDACAO)).hasSize("ACEITA".equals(saudacao) ? 1 : 0);
            assertThat(triagem(atendimento)).isEqualTo("INTERROMPIDA");
            assertThat(enviosPara(numero, AVISO)).hasSize(1);
            assertThat(contar("atd_mensagem where atendimento_id = :c and origem = 'AVISO_RESPONSAVEL'", atendimento))
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ apoio

    private List<String> situacoesDaTriagem(long atendimento) {
        return jdbc.sql(
                        "select situacao from atd_mensagem where atendimento_id = :a and origem = 'TRIAGEM' order by id")
                .param("a", atendimento)
                .query(String.class)
                .list();
    }

    private List<String> historicoDaSaudacao(long atendimento) {
        return jdbc.sql("select s.situacao from atd_mensagem_situacao s join atd_mensagem m on m.id = s.mensagem_id"
                        + " where m.chave_idempotencia = :chave order by s.id")
                .param("chave", "triagem:" + atendimento + ":saudacao")
                .query(String.class)
                .list();
    }

    private String idExterno(long atendimento) {
        return jdbc.sql("select id_externo from atd_mensagem where atendimento_id = :a and direcao = 'ENTRADA'"
                        + " order by id limit 1")
                .param("a", atendimento)
                .query(String.class)
                .single();
    }

    private static List<String> enviosPara(String numero, String trecho) {
        return GRAPH.requisicoes().stream()
                .filter(r -> r.contains("/messages") && r.contains(numero) && r.contains(trecho))
                .toList();
    }
}
