package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Base dos testes da triagem inicial: setores e configuração pela API, mensagens do contato pelo webhook
 * assinado e leitura do estado no banco. A configuração da triagem é única: cada teste a desliga no fim.
 */
abstract class TriagemIntegracao extends AtendimentoIntegracao {

    protected static final String SAUDACAO = "Para direcionar sua conversa";

    @BeforeEach
    void preparar() {
        processar();
        GRAPH.limpar();
        GRAPH.modo(Modo.ACEITAR);
    }

    @AfterEach
    void desligar() {
        jdbc.sql("update atd_triagem_opcao set setor_id = null").update();
        jdbc.sql("update atd_triagem_configuracao set ativa = false where id = 1")
                .update();
    }
    // ------------------------------------------------------------------ apoio

    protected static String marca() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected long setor(Navegador admin, String nome) {
        MockHttpServletResponse resposta = admin.post("/api/v1/atendimento/setores", Map.of("nome", nome));
        assertThat(resposta.getStatus()).isEqualTo(201);
        return corpo(resposta).get("id").asLong();
    }

    protected void desativar(Navegador admin, long setor) {
        long versao = jdbc.sql("select versao from atd_setor where id = :id")
                .param("id", setor)
                .query(Long.class)
                .single();
        assertThat(admin.post("/api/v1/atendimento/setores/" + setor + "/desativacao", Map.of("versao", versao))
                        .getStatus())
                .isEqualTo(200);
    }

    protected void configurar(Navegador admin, boolean ativa, Long financeiro, Long cobranca, Long comercial) {
        assertThat(pedirConfiguracao(admin, ativa, financeiro, cobranca, comercial)
                        .getStatus())
                .isEqualTo(200);
    }

    protected MockHttpServletResponse pedirConfiguracao(
            Navegador admin, boolean ativa, Long financeiro, Long cobranca, Long comercial) {
        long versao =
                corpo(admin.get("/api/v1/atendimento/triagem")).get("versao").asLong();
        List<Map<String, Object>> opcoes = new ArrayList<>();
        opcoes.add(destino("FINANCEIRO", financeiro));
        opcoes.add(destino("COBRANCA", cobranca));
        opcoes.add(destino("COMERCIAL", comercial));
        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("versao", versao);
        pedido.put("ativa", ativa);
        pedido.put("opcoes", opcoes);
        return admin.put("/api/v1/atendimento/triagem", pedido);
    }

    protected static Map<String, Object> destino(String opcao, Long setor) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("opcao", opcao);
        d.put("setorId", setor);
        return d;
    }

    /** Mensagem de texto do contato, registrada e processada (inclusive a fila de envio). */
    protected void mensagemDoContato(String numero, String texto) throws Exception {
        String corpo = mensagem(numero, "Contato " + numero, wamid(), texto, agoraEpoch());
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
        processar();
    }

    protected long aberto(String numero) {
        return jdbc.sql("select a.id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where c.wa_id = :n and a.situacao <> 'ENCERRADO'")
                .param("n", numero)
                .query(Long.class)
                .single();
    }

    protected List<String> textosDaTriagem(long atendimento) {
        return jdbc.sql("select texto from atd_mensagem where atendimento_id = :a and origem = 'TRIAGEM' order by id")
                .param("a", atendimento)
                .query(String.class)
                .list();
    }

    protected String triagem(long atendimento) {
        return jdbc.sql("select situacao from atd_triagem where atendimento_id = :a")
                .param("a", atendimento)
                .query(String.class)
                .single();
    }

    protected Long setorDe(long atendimento) {
        Object setor = jdbc.sql("select setor_id from atd_atendimento where id = :a")
                .param("a", atendimento)
                .query()
                .singleRow()
                .get("setor_id");
        return setor == null ? null : ((Number) setor).longValue();
    }

    protected JsonNode itemDaFila(Navegador admin, String numero) {
        JsonNode pagina = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=" + numero));
        assertThat(pagina.get("itens")).hasSize(1);
        return pagina.get("itens").get(0);
    }

    protected List<String> eventos(Navegador admin, long atendimento, String tipo) {
        List<String> textos = new ArrayList<>();
        corpo(admin.get(url(atendimento, "linha-do-tempo?somenteEste=true")))
                .get("itens")
                .forEach(i -> {
                    if (i.hasNonNull("evento") && tipo.equals(i.get("evento").asString())) {
                        textos.add(i.path("texto").asString(""));
                    }
                });
        return textos;
    }

    protected static List<String> enviosCom(String trecho) {
        return GRAPH.requisicoes().stream()
                .filter(r -> r.contains("/messages") && r.contains(trecho))
                .toList();
    }
}
