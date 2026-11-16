package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Aviso ao cliente pelo WhatsApp quando muda o responsável (assumir ou transferir): um por mudança
 * efetiva, pela fila de envio, com a janela e as falhas tratadas como nas respostas, sem desfazer a
 * atribuição e fora da primeira resposta humana. Graph controlada.
 */
@TestPropertySource(properties = "emconta.atendimento.aviso-responsavel.habilitado=true")
class AvisoResponsavelIntegrationTests extends AtendimentoIntegracao {

    @BeforeEach
    void esvaziar() {
        processar();
        GRAPH.limpar();
        GRAPH.modo(Modo.ACEITAR);
    }

    @Test
    void assumirETransferirAvisamQuemPassaAAtenderUmaVezPorMudanca() throws Exception {
        String email = emailUnico("assume");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador primeira = logado(email);
        String emailSegunda = emailUnico("recebe");
        long segunda = criarAtivo(emailSegunda, Perfil.ADMINISTRADOR).getId();
        long atendimento = assumido(primeira, numeroUnico());

        assertThat(avisos(atendimento)).containsExactly("Olá! Seu atendimento agora está com Pessoa " + email + ".");
        processar();
        assertThat(envios()).hasSize(1);
        assertThat(envios().getFirst()).contains("\"type\":\"text\"", "Pessoa " + email);

        // Repetir a mesma ação não cria outro aviso.
        assertThat(primeira.post(url(atendimento, "assuncao"), null).getStatus())
                .isEqualTo(409);
        assertThat(primeira.post(
                                url(atendimento, "transferencia"),
                                Map.of("versao", versao(atendimento) - 1, "destinoId", segunda))
                        .getStatus())
                .as("versão antiga")
                .isEqualTo(409);
        assertThat(avisos(atendimento)).hasSize(1);

        // Transferir para alguém avisa com o nome dele; devolver à fila não avisa.
        assertThat(primeira.post(
                                url(atendimento, "transferencia"),
                                Map.of("versao", versao(atendimento), "destinoId", segunda))
                        .getStatus())
                .isEqualTo(204);
        Navegador segundaSessao = logado(emailSegunda);
        assertThat(segundaSessao
                        .post(url(atendimento, "transferencia"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
        processar();
        assertThat(avisos(atendimento))
                .containsExactly(
                        "Olá! Seu atendimento agora está com Pessoa " + email + ".",
                        "Olá! Seu atendimento agora está com Pessoa " + emailSegunda + ".");
        assertThat(envios()).hasSize(2);

        // Na linha do tempo, o aviso é identificado e não tem autor.
        JsonNode itens = corpo(primeira.get(url(atendimento, "linha-do-tempo"))).get("itens");
        List<JsonNode> doAviso = new ArrayList<>();
        itens.forEach(i -> {
            if ("AVISO_RESPONSAVEL".equals(i.path("origem").asString(""))) {
                doAviso.add(i);
            }
        });
        assertThat(doAviso)
                .hasSize(2)
                .allMatch(i -> i.path("autor").isMissingNode() || i.get("autor").isNull());
    }

    @Test
    void assumirAoMesmoTempoGeraUmUnicoAviso() throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Navegador sessao = administrador();
            tarefas.add(() -> sessao.post(url(atendimento, "assuncao"), null));
        }
        int assumidos = 0;
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            assumidos += f.get().getStatus() == 204 ? 1 : 0;
        }
        assertThat(assumidos).isEqualTo(1);
        assertThat(avisos(atendimento)).hasSize(1);
    }

    @Test
    void falhaOuJanelaFechadaNoAvisoNaoDesfazAAtribuicaoENaoContaComoPrimeiraResposta() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        GRAPH.modo(Modo.ERRO_DEFINITIVO);
        processar();
        assertThat(situacaoDoAviso(atendimento)).isEqualTo("FALHA");
        JsonNode detalhe = corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento));
        assertThat(detalhe.get("resumo").get("situacao").asString()).isEqualTo("EM_ATENDIMENTO");
        assertThat(detalhe.get("resumo").get("responsavel")).isNotNull();
        assertThat(detalhe.path("primeiraRespostaHumanaEm").isMissingNode()
                        || detalhe.get("primeiraRespostaHumanaEm").isNull())
                .as("o aviso não é resposta humana")
                .isTrue();

        GRAPH.modo(Modo.ACEITAR);
        long resposta = responder(admin, atendimento, "Bom dia, já vejo sua fatura.");
        Instant criada = jdbc.sql("select criado_em from atd_mensagem where id = :id")
                .param("id", resposta)
                .query(Timestamp.class)
                .single()
                .toInstant();
        assertThat(Instant.parse(corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento))
                        .get("primeiraRespostaHumanaEm")
                        .asString()))
                .isEqualTo(criada);

        // Janela de 24 h fechada na hora do envio: o aviso fica "Não enviada"; o atendimento continua.
        long outro = assumido(administrador(), numeroUnico());
        jdbc.sql("update atd_conversa set ultima_entrada_em = :h"
                        + " where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("h", Timestamp.from(Instant.now().minus(Duration.ofHours(25))))
                .param("id", outro)
                .update();
        processar();
        assertThat(situacaoDoAviso(outro)).isEqualTo("JANELA_EXPIRADA");
        assertThat(jdbc.sql("select situacao from atd_atendimento where id = :id")
                        .param("id", outro)
                        .query(String.class)
                        .single())
                .isEqualTo("EM_ATENDIMENTO");
    }

    private List<String> avisos(long atendimento) {
        return jdbc.sql("select texto from atd_mensagem where atendimento_id = :a and origem = 'AVISO_RESPONSAVEL'"
                        + " order by id")
                .param("a", atendimento)
                .query(String.class)
                .list();
    }

    private String situacaoDoAviso(long atendimento) {
        return jdbc.sql("select situacao from atd_mensagem where atendimento_id = :a and origem = 'AVISO_RESPONSAVEL'")
                .param("a", atendimento)
                .query(String.class)
                .single();
    }

    private static List<String> envios() {
        return GRAPH.requisicoes().stream()
                .filter(r -> r.contains("/messages") && r.contains("Seu atendimento agora est"))
                .toList();
    }
}
