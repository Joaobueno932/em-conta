package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Catálogo de respostas rápidas pela API real (MySQL real): permissões, unicidade do atalho (inclusive
 * concorrente), validação, versão, situação, busca e independência das mensagens já registradas.
 */
class RespostasRapidasIntegrationTests extends AtendimentoIntegracao {

    private static final String BASE = "/api/v1/atendimento/respostas-rapidas";

    private static String unico() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> dados(String titulo, String atalho, String conteudo) {
        Map<String, Object> m = new HashMap<>();
        m.put("titulo", titulo);
        m.put("atalho", atalho);
        m.put("conteudo", conteudo);
        return m;
    }

    private JsonNode criar(Navegador n, String titulo, String atalho, String conteudo) {
        MockHttpServletResponse r = n.post(BASE, dados(titulo, atalho, conteudo));
        assertThat(r.getStatus()).as(r.getContentAsByteArray().length + "").isEqualTo(201);
        return corpo(r);
    }

    @Test
    void cadastraEditaDesativaEReativaComHistoricoSemConteudo() {
        Navegador admin = administrador();
        String marca = unico();
        String conteudo = "Olá! 😊 Segue a 2ª via.\n\nQualquer dúvida, estamos à disposição.";
        JsonNode criada = criar(admin, "Segunda via " + marca, "  /Segunda-Via-" + marca + " ", conteudo);
        long id = criada.get("id").asLong();
        assertThat(criada.get("atalho").asString()).isEqualTo("segunda-via-" + marca);
        assertThat(criada.get("conteudo").asString())
                .as("Unicode e quebras de linha preservados")
                .isEqualTo(conteudo);
        assertThat(criada.get("ativa").asBoolean()).isTrue();
        assertThat(criada.get("criadoPor").get("nome").asString()).isNotBlank();

        Map<String, Object> edicao =
                dados("Segunda via da fatura " + marca, "segunda-via-" + marca, conteudo + " Até!");
        edicao.put("versao", criada.get("versao").asLong());
        JsonNode editada = corpo(admin.put(BASE + "/" + id, edicao));
        assertThat(editada.get("versao").asLong())
                .isEqualTo(criada.get("versao").asLong() + 1);

        JsonNode inativa = corpo(admin.post(
                BASE + "/" + id + "/desativacao",
                Map.of("versao", editada.get("versao").asLong())));
        assertThat(inativa.get("ativa").asBoolean()).isFalse();
        assertThat(corpo(admin.get(BASE + "/" + id)).get("titulo").asString())
                .as("cadastro preservado")
                .isEqualTo("Segunda via da fatura " + marca);
        JsonNode ativa = corpo(admin.post(
                BASE + "/" + id + "/ativacao",
                Map.of("versao", inativa.get("versao").asLong())));
        assertThat(ativa.get("ativa").asBoolean()).isTrue();

        List<Map<String, Object>> historico = jdbc.sql(
                        "select acao, campos from atd_resposta_rapida_historico where resposta_id = :id order by id")
                .param("id", id)
                .query()
                .listOfRows();
        assertThat(historico)
                .extracting(h -> h.get("acao"))
                .containsExactly("CRIADA", "ALTERADA", "DESATIVADA", "ATIVADA");
        assertThat(historico.get(1).get("campos")).isEqualTo("titulo,conteudo");
        assertThat(historico.toString()).doesNotContain("disposição");
    }

    @Test
    void atalhoUnicoInclusiveEntreInativasEEmCriacaoConcorrente() throws Exception {
        Navegador admin = administrador();
        String marca = unico();
        JsonNode primeira = criar(admin, "Primeira", "boas-vindas-" + marca, "Bem-vindo!");
        admin.post(
                BASE + "/" + primeira.get("id").asLong() + "/desativacao",
                Map.of("versao", primeira.get("versao").asLong()));
        MockHttpServletResponse repetida = admin.post(BASE, dados("Outra", "/BOAS-VINDAS-" + marca, "Oi"));
        assertThat(repetida.getStatus()).isEqualTo(409);
        assertThat(codigo(repetida)).isEqualTo("ATALHO_EM_USO");
        assertThat(corpo(repetida).get("campos").has("atalho")).isTrue();

        // Edição para um atalho já usado também é recusada.
        JsonNode segunda = criar(admin, "Segunda", "outra-" + marca, "Texto");
        Map<String, Object> edicao = dados("Segunda", "boas-vindas-" + marca, "Texto");
        edicao.put("versao", segunda.get("versao").asLong());
        assertThat(codigo(admin.put(BASE + "/" + segunda.get("id").asLong(), edicao)))
                .isEqualTo("ATALHO_EM_USO");

        String concorrente = "simultanea-" + marca;
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Navegador aba = administrador();
            int n = i;
            tarefas.add(() -> aba.post(BASE, dados("Simultânea " + n, concorrente, "Texto " + n)));
        }
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            status.add(f.get().getStatus());
        }
        assertThat(status).containsOnlyOnce(201);
        assertThat(status.stream().filter(s -> s == 409)).hasSize(3);
        assertThat(jdbc.sql("select count(*) from atd_resposta_rapida where atalho = :a")
                        .param("a", concorrente)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void validaCamposELimites() {
        Navegador admin = administrador();
        String marca = unico();
        record Caso(Map<String, Object> corpo, String campo) {}
        for (Caso caso : List.of(
                new Caso(dados("", "a-" + marca, "x"), "titulo"),
                new Caso(dados("x".repeat(81), "b-" + marca, "x"), "titulo"),
                new Caso(dados("T", "", "x"), "atalho"),
                new Caso(dados("T", "a", "x"), "atalho"),
                new Caso(dados("T", "ação-" + marca, "x"), "atalho"),
                new Caso(dados("T", "com espaco", "x"), "atalho"),
                new Caso(dados("T", "-inicio", "x"), "atalho"),
                new Caso(dados("T", "a".repeat(31), "x"), "atalho"),
                new Caso(dados("T", "c-" + marca, "   \n "), "conteudo"),
                new Caso(dados("T", "d-" + marca, "x".repeat(4097)), "conteudo"))) {
            MockHttpServletResponse r = admin.post(BASE, caso.corpo());
            assertThat(r.getStatus()).as(caso.toString().substring(0, 40)).isEqualTo(400);
            assertThat(corpo(r).get("campos").has(caso.campo()))
                    .as(caso.campo())
                    .isTrue();
        }
        assertThat(admin.post(BASE, dados("T", "limite-" + marca, "x".repeat(4096)))
                        .getStatus())
                .isEqualTo(201);
        assertThat(admin.post(BASE, dados("T".repeat(80), "a".repeat(30 - marca.length()) + marca, "x"))
                        .getStatus())
                .isEqualTo(201);
    }

    @Test
    void edicoesSimultaneasNaoSeSobrescrevem() {
        Navegador a = administrador();
        Navegador b = administrador();
        JsonNode criada = criar(a, "Prazo", "prazo-" + unico(), "Prazo de 5 dias.");
        long id = criada.get("id").asLong();
        long versao = criada.get("versao").asLong();
        Map<String, Object> deA = dados("Prazo", criada.get("atalho").asString(), "Prazo de 3 dias.");
        deA.put("versao", versao);
        Map<String, Object> deB = dados("Prazo", criada.get("atalho").asString(), "Prazo de 10 dias.");
        deB.put("versao", versao);
        assertThat(a.put(BASE + "/" + id, deA).getStatus()).isEqualTo(200);
        MockHttpServletResponse conflito = b.put(BASE + "/" + id, deB);
        assertThat(conflito.getStatus()).isEqualTo(409);
        assertThat(codigo(conflito)).isEqualTo("CONFLITO_CONCORRENTE");
        assertThat(corpo(a.get(BASE + "/" + id)).get("conteudo").asString()).isEqualTo("Prazo de 3 dias.");
        assertThat(codigo(b.post(BASE + "/" + id + "/desativacao", Map.of("versao", versao))))
                .isEqualTo("CONFLITO_CONCORRENTE");
    }

    @Test
    void buscaSituacaoEPaginacao() {
        Navegador admin = administrador();
        String marca = unico();
        for (int i = 0; i < 5; i++) {
            criar(admin, "Catálogo " + marca + " " + i, "cat-" + i + "-" + marca, "Texto comum " + i);
        }
        JsonNode inativa =
                criar(admin, "Catálogo " + marca + " inativa", "cat-x-" + marca, "Palavra" + marca + " no conteúdo");
        admin.post(
                BASE + "/" + inativa.get("id").asLong() + "/desativacao",
                Map.of("versao", inativa.get("versao").asLong()));

        JsonNode p0 = corpo(admin.get(BASE + "?situacao=ATIVAS&tamanho=2&busca=" + marca));
        assertThat(p0.get("totalItens").asLong()).isEqualTo(5);
        assertThat(p0.get("totalPaginas").asInt()).isEqualTo(3);
        JsonNode p2 = corpo(admin.get(BASE + "?situacao=ATIVAS&tamanho=2&pagina=2&busca=" + marca));
        assertThat(p2.get("itens")).hasSize(1);
        assertThat(p2.toString()).doesNotContain("inativa");
        assertThat(corpo(admin.get(BASE + "?situacao=INATIVAS&busca=" + marca))
                        .get("totalItens")
                        .asLong())
                .isEqualTo(1);
        assertThat(corpo(admin.get(BASE + "?situacao=TODAS&busca=" + marca))
                        .get("totalItens")
                        .asLong())
                .isEqualTo(6);
        // Conteúdo só entra na busca quando pedido (o seletor busca título e atalho).
        assertThat(corpo(admin.get(BASE + "?busca=Palavra" + marca))
                        .get("totalItens")
                        .asLong())
                .isEqualTo(1);
        assertThat(corpo(admin.get(BASE + "?noConteudo=false&busca=Palavra" + marca))
                        .get("totalItens")
                        .asLong())
                .isZero();
        assertThat(corpo(admin.get(BASE + "?noConteudo=false&situacao=ATIVAS&busca=/CAT-3-" + marca))
                        .get("totalItens")
                        .asLong())
                .isEqualTo(1);
        assertThat(admin.get(BASE + "?tamanho=101").getStatus()).isEqualTo(400);
        assertThat(admin.get(BASE + "?situacao=OUTRA").getStatus()).isEqualTo(400);
    }

    @Test
    void permissoesSeparadasNoServidor() {
        Navegador admin = administrador();
        JsonNode criada = criar(admin, "Permissões", "perm-" + unico(), "Texto");
        String id = BASE + "/" + criada.get("id").asLong();
        Map<String, Object> edicao = dados("Permissões 2", criada.get("atalho").asString(), "Texto");
        edicao.put("versao", criada.get("versao").asLong());
        Map<String, Object> versao = Map.of("versao", criada.get("versao").asLong());

        Navegador semNada = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR, Permissao.ATENDIMENTO_RESPONDER);
        assertThat(semNada.get(BASE).getStatus()).isEqualTo(403);
        assertThat(semNada.get(id).getStatus()).isEqualTo(403);

        Navegador leitor = comPermissoes(Permissao.RESPOSTAS_RAPIDAS_CONSULTAR);
        assertThat(leitor.get(BASE).getStatus()).isEqualTo(200);
        for (MockHttpServletResponse negada : List.of(
                leitor.post(BASE, dados("X", "x-" + unico(), "x")),
                leitor.put(id, edicao),
                leitor.post(id + "/desativacao", versao))) {
            assertThat(negada.getStatus()).isEqualTo(403);
            assertThat(codigo(negada)).isEqualTo("ACESSO_NEGADO");
        }

        Navegador cadastra = comPermissoes(Permissao.RESPOSTAS_RAPIDAS_CADASTRAR);
        assertThat(cadastra.post(BASE, dados("Nova", "nova-" + unico(), "x")).getStatus())
                .isEqualTo(201);
        assertThat(cadastra.put(id, edicao).getStatus()).isEqualTo(403);
        assertThat(cadastra.get(BASE).getStatus()).isEqualTo(403);

        Navegador edita = comPermissoes(Permissao.RESPOSTAS_RAPIDAS_EDITAR);
        assertThat(edita.post(id + "/desativacao", versao).getStatus()).isEqualTo(403);
        assertThat(edita.put(id, edicao).getStatus()).isEqualTo(200);

        Navegador situacao = comPermissoes(Permissao.RESPOSTAS_RAPIDAS_ALTERAR_SITUACAO);
        assertThat(situacao.post(
                                id + "/desativacao",
                                Map.of("versao", criada.get("versao").asLong() + 1))
                        .getStatus())
                .isEqualTo(200);
        assertThat(new Navegador(mvc, json).get(BASE).getStatus()).isEqualTo(401);
    }

    @Test
    void usarRespostaNaoMudaRegrasDeEnvioNemMensagensRegistradas() throws Exception {
        Navegador admin = administrador();
        JsonNode resposta = criar(admin, "Fatura", "fatura-" + unico(), "Sua fatura vence dia 10.");
        long atendimento = assumido(admin, numeroUnico());

        // Quem pode consultar o catálogo, mas não é o responsável, continua sem poder responder.
        Navegador outro = comPermissoes(
                Permissao.RESPOSTAS_RAPIDAS_CONSULTAR,
                Permissao.ATENDIMENTO_CONSULTAR,
                Permissao.ATENDIMENTO_RESPONDER);
        assertThat(codigo(outro.post(
                        url(atendimento, "mensagens"),
                        Map.of(
                                "texto",
                                resposta.get("conteudo").asString(),
                                "chaveIdempotencia",
                                UUID.randomUUID().toString()))))
                .isEqualTo("NAO_E_RESPONSAVEL");

        // O responsável envia o texto revisado; editar o catálogo depois não altera a mensagem.
        String revisado = resposta.get("conteudo").asString() + " Pode pagar pelo Pix.";
        MockHttpServletResponse enviada = admin.post(
                url(atendimento, "mensagens"),
                Map.of("texto", revisado, "chaveIdempotencia", UUID.randomUUID().toString()));
        assertThat(enviada.getStatus()).isEqualTo(202);
        long mensagem = corpo(enviada).get("mensagemId").asLong();
        Map<String, Object> edicao = dados("Fatura", resposta.get("atalho").asString(), "Texto totalmente novo.");
        edicao.put("versao", resposta.get("versao").asLong());
        assertThat(admin.put(BASE + "/" + resposta.get("id").asLong(), edicao).getStatus())
                .isEqualTo(200);
        admin.post(
                BASE + "/" + resposta.get("id").asLong() + "/desativacao",
                Map.of("versao", resposta.get("versao").asLong() + 1));
        assertThat(jdbc.sql("select texto from atd_mensagem where id = :id")
                        .param("id", mensagem)
                        .query(String.class)
                        .single())
                .isEqualTo(revisado);
    }
}
