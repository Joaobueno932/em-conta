package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * "Digitando…" no WhatsApp do contato: o formato documentado pela Meta (leitura da mensagem recebida
 * junto com {@code typing_indicator}), as regras de resposta e o intervalo mínimo. Graph controlada.
 */
class DigitandoIntegrationTests extends AtendimentoIntegracao {

    @BeforeEach
    void esvaziar() {
        processar();
        GRAPH.limpar();
    }

    @Test
    void responsavelPedeDigitandoComALeituraDaUltimaRecebidaNoMaximoACada20Segundos() throws Exception {
        Navegador admin = administrador();
        String numero = numeroUnico();
        long atendimento = assumido(admin, numero);
        String ultima = wamid();
        String m = mensagem(numero, "Contato", ultima, "segunda mensagem", agoraEpoch());
        enviarWebhook(m, assinar(m));
        recebimento.processarPendentes(50);
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        relogio.fixar(t0);

        assertThat(admin.post(url(atendimento, "digitando"), null).getStatus()).isEqualTo(204);
        assertThat(admin.post(url(atendimento, "digitando"), null).getStatus()).isEqualTo(204);
        assertThat(pedidos()).as("um pedido no intervalo").hasSize(1);
        assertThat(pedidos().getFirst())
                .contains(
                        "\"messaging_product\":\"whatsapp\"",
                        "\"status\":\"read\"",
                        "\"message_id\":\"" + ultima + "\"",
                        "\"typing_indicator\":{\"type\":\"text\"}")
                .doesNotContain("\"to\"", "\"text\":{");

        relogio.fixar(t0.plusSeconds(21));
        admin.post(url(atendimento, "digitando"), null);
        assertThat(pedidos()).hasSize(2);
        // A resposta registrada libera o próximo pedido na hora (o indicador some com ela).
        responder(admin, atendimento, "já respondo");
        admin.post(url(atendimento, "digitando"), null);
        assertThat(pedidos()).hasSize(3);
        assertThat(GRAPH.requisicoes().stream().filter(r -> r.contains("/messages") && !r.contains("typing_indicator")))
                .as("nenhuma mensagem foi enviada ao contato pelo indicador")
                .isEmpty();
    }

    @Test
    void regrasDeRespostaValemESemMensagemRecebidaNadaEPedido() throws Exception {
        Navegador admin = administrador();
        long aguardando = novoAtendimento(numeroUnico());
        assertThat(codigo(admin.post(url(aguardando, "digitando"), null))).isEqualTo("NAO_E_RESPONSAVEL");

        long atendimento = assumido(admin, numeroUnico());
        assertThat(codigo(administrador().post(url(atendimento, "digitando"), null)))
                .isEqualTo("NAO_E_RESPONSAVEL");
        MockHttpServletResponse semPermissao =
                comPermissoes(Permissao.ATENDIMENTO_CONSULTAR).post(url(atendimento, "digitando"), null);
        assertThat(semPermissao.getStatus()).isEqualTo(403);
        assertThat(new Navegador(mvc, json)
                        .post(url(atendimento, "digitando"), null)
                        .getStatus())
                .isIn(401, 403);

        jdbc.sql("update atd_conversa set ultima_entrada_em = :h"
                        + " where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("h", Timestamp.from(Instant.now().minus(Duration.ofHours(25))))
                .param("id", atendimento)
                .update();
        assertThat(codigo(admin.post(url(atendimento, "digitando"), null))).isEqualTo("JANELA_ENCERRADA");

        // Atendimento novo (outro ciclo da conversa) ainda sem mensagem recebida nele: nada a ler.
        long semEntrada = assumido(admin, numeroUnico());
        jdbc.sql("update atd_mensagem set id_externo = null where atendimento_id = :a and direcao = 'ENTRADA'")
                .param("a", semEntrada)
                .update();
        assertThat(admin.post(url(semEntrada, "digitando"), null).getStatus()).isEqualTo(204);
        assertThat(pedidos()).isEmpty();
    }

    private static List<String> pedidos() {
        return GRAPH.requisicoes().stream()
                .filter(r -> r.contains("typing_indicator"))
                .toList();
    }
}
