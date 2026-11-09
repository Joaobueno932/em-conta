package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Avisos de mudança da conversa (SSE): só depois do commit, só para quem assina a conversa alterada, sem
 * conteúdo, com a permissão de consulta; e a chave da operação na linha do tempo só para o autor.
 */
class EventosConversaIntegrationTests extends AtendimentoIntegracao {

    @Test
    void avisaSoAConversaAlteradaDepoisDoCommitESemConteudo() throws Exception {
        Navegador admin = administrador();
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        long outro = novoAtendimento(numeroUnico());
        MockHttpServletResponse fluxo = admin.get(url(atendimento, "eventos"));
        MockHttpServletResponse alheio = admin.get(url(outro, "eventos"));
        assertThat(fluxo.getStatus()).isEqualTo(200);
        assertThat(fluxo.getContentType()).startsWith("text/event-stream");
        assertThat(fluxo.getHeader("X-Accel-Buffering")).isEqualTo("no");
        esperar(() -> conteudo(fluxo).contains("event:pronto"));

        // Ação do atendente: um aviso depois do commit.
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        esperar(() -> avisos(fluxo) == 1);

        // Mensagem do contato processada pelo webhook: outro aviso.
        String m = mensagem(numero, "Contato", wamid(), "texto sigiloso do contato", agoraEpoch());
        enviarWebhook(m, assinar(m));
        recebimento.processarPendentes(50);
        esperar(() -> avisos(fluxo) == 2);

        // Resposta, envio e status do webhook: cada etapa confirmada avisa (avisos próximos podem virar um).
        long resposta = responder(admin, atendimento, "resposta");
        envio.enviarPendentes(5);
        esperar(() -> avisos(fluxo) >= 3);
        int antes = avisos(fluxo);
        String wamid = jdbc.sql("select id_externo from atd_mensagem where id = :id")
                .param("id", resposta)
                .query(String.class)
                .single();
        webhookSituacao(wamid, "delivered", null, null);
        recebimento.processarPendentes(10);
        esperar(() -> avisos(fluxo) > antes);

        assertThat(conteudo(fluxo)).doesNotContain("texto sigiloso", "resposta", numero);
        assertThat(avisos(alheio)).as("outra conversa não é avisada").isZero();
    }

    @Test
    void acaoRecusadaNaoAvisa() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        long contador = contador(atendimento);
        assertThat(administrador().post(url(atendimento, "assuncao"), null).getStatus())
                .isEqualTo(409);
        assertThat(admin.post(url(atendimento, "encerramento"), java.util.Map.of("versao", 999))
                        .getStatus())
                .isEqualTo(409);
        assertThat(contador(atendimento)).as("nada confirmado, nada sinalizado").isEqualTo(contador);
    }

    @Test
    void exigeSessaoPermissaoDeConsultaEAtendimentoExistente() throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        assertThat(new Navegador(mvc, json).get(url(atendimento, "eventos")).getStatus())
                .isEqualTo(401);
        assertThat(comPermissoes(Permissao.ATENDIMENTO_RESPONDER)
                        .get(url(atendimento, "eventos"))
                        .getStatus())
                .isEqualTo(403);
        assertThat(administrador().get(url(999_999_999L, "eventos")).getStatus())
                .isEqualTo(404);
    }

    @Test
    void chaveDaOperacaoSoParaOAutor() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        String chave = java.util.UUID.randomUUID().toString();
        assertThat(admin.post(
                                url(atendimento, "mensagens"),
                                java.util.Map.of("texto", "oi", "chaveIdempotencia", chave))
                        .getStatus())
                .isEqualTo(202);

        JsonNode doAutor = ultima(admin, atendimento);
        assertThat(doAutor.get("chaveIdempotencia").asString()).isEqualTo(chave);
        JsonNode deOutro = ultima(comPermissoes(Permissao.ATENDIMENTO_CONSULTAR), atendimento);
        assertThat(deOutro.has("chaveIdempotencia")
                        && !deOutro.get("chaveIdempotencia").isNull())
                .isFalse();
    }

    // ------------------------------------------------------------------ apoio

    private JsonNode ultima(Navegador navegador, long atendimento) {
        JsonNode itens =
                corpo(navegador.get(url(atendimento, "linha-do-tempo"))).get("itens");
        return itens.get(itens.size() - 1);
    }

    private long contador(long atendimento) {
        return jdbc.sql("select coalesce((select s.contador from atd_sinal s join atd_atendimento a"
                        + " on a.conversa_id = s.conversa_id where a.id = :a), 0)")
                .param("a", atendimento)
                .query(Long.class)
                .single();
    }

    private static String conteudo(MockHttpServletResponse fluxo) {
        try {
            return fluxo.getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int avisos(MockHttpServletResponse fluxo) {
        return conteudo(fluxo).split("event:mudanca", -1).length - 1;
    }

    private static void esperar(BooleanSupplier condicao) throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(5));
        while (!condicao.getAsBoolean()) {
            assertThat(Instant.now()).as("condição não atingida em 5 s").isBefore(limite);
            Thread.sleep(50);
        }
    }
}
