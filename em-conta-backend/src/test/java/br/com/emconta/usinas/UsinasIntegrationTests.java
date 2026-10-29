package br.com.emconta.usinas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.ApiIntegracao;
import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Fazendas, usinas e vínculos cadastrais UC → usina sobre MySQL real. */
class UsinasIntegrationTests extends ApiIntegracao {

    private static final LocalDate HOJE = LocalDate.now(ZoneId.of("America/Campo_Grande"));

    // ---------------------------------------------------------------- cadastro

    @Test
    void cadastraFazendaEUsinaComCodigoGeradoECamposOpcionais() {
        Navegador admin = administrador();
        String marca = marca();

        MockHttpServletResponse fazenda = admin.post(
                "/api/v1/fazendas", Map.of("nome", "  Fazenda   " + marca + " ", "cidade", "Sidrolândia", "uf", "ms"));
        assertThat(fazenda.getStatus()).isEqualTo(201);
        JsonNode f = corpo(fazenda);
        assertThat(f.get("nome").asString()).isEqualTo("Fazenda " + marca);
        assertThat(f.get("uf").asString()).isEqualTo("MS");
        assertThat(f.get("codigo").asString())
                .isEqualTo("FZD-%04d".formatted(f.get("id").asLong()));

        MockHttpServletResponse usina = admin.post(
                "/api/v1/usinas",
                Map.of(
                        "nome", "Usina " + marca,
                        "fazendaId", f.get("id").asLong(),
                        "cep", "79.100-000",
                        "endereco", "Rodovia MS-162, km 28",
                        "cidade", "Sidrolândia",
                        "uf", "MS"));
        assertThat(usina.getStatus()).isEqualTo(201);
        JsonNode u = corpo(usina);
        assertThat(u.get("codigo").asString())
                .isEqualTo("USN-%04d".formatted(u.get("id").asLong()));
        assertThat(u.get("cep").asString()).isEqualTo("79100-000");
        assertThat(u.get("fazenda").get("codigo").asString())
                .isEqualTo(f.get("codigo").asString());
        assertThat(u.get("ucsVinculadas").asLong()).isZero();
        assertThat(u.get("criadoPor").get("nome").asString()).startsWith("Pessoa ");

        // Só o nome é obrigatório.
        assertThat(admin.post("/api/v1/usinas", Map.of("nome", "Mínima " + marca))
                        .getStatus())
                .isEqualTo(201);
    }

    @Test
    void validaCamposObrigatoriosLimitesEFazendaInexistente() {
        Navegador admin = administrador();

        MockHttpServletResponse semNome = admin.post("/api/v1/usinas", Map.of("nome", "  "));
        assertThat(semNome.getStatus()).isEqualTo(400);
        assertThat(corpo(semNome).get("campos").get("nome").asString()).isEqualTo("Informe o nome da usina.");

        assertThat(corpo(admin.post("/api/v1/usinas", Map.of("nome", "x".repeat(151))))
                        .get("campos")
                        .get("nome")
                        .asString())
                .contains("150");
        assertThat(corpo(admin.post("/api/v1/usinas", Map.of("nome", "Cep", "cep", "7910")))
                        .get("campos")
                        .has("cep"))
                .isTrue();
        assertThat(corpo(admin.post("/api/v1/usinas", Map.of("nome", "Uf", "uf", "XX")))
                        .get("campos")
                        .has("uf"))
                .isTrue();
        MockHttpServletResponse fazendaInexistente =
                admin.post("/api/v1/usinas", Map.of("nome", "Sem fazenda", "fazendaId", 999_999_999));
        assertThat(fazendaInexistente.getStatus()).isEqualTo(400);
        assertThat(corpo(fazendaInexistente).get("campos").has("fazendaId")).isTrue();
        assertThat(admin.post("/api/v1/usinas", Map.of("nome", "Campo extra", "potencia", 100))
                        .getStatus())
                .isEqualTo(400);
        assertThat(admin.post("/api/v1/fazendas", Map.of("cidade", "Sem nome")).getStatus())
                .isEqualTo(400);
    }

    @Test
    void nomeParecidoGeraAvisoEConfirmacaoPermiteSalvar() {
        Navegador admin = administrador();
        String marca = marca();
        long existente = corpo(admin.post("/api/v1/usinas", Map.of("nome", "São Miguel " + marca)))
                .get("id")
                .asLong();

        MockHttpServletResponse aviso = admin.post("/api/v1/usinas", Map.of("nome", "sao  miguel " + marca));
        assertThat(aviso.getStatus()).isEqualTo(409);
        assertThat(codigo(aviso)).isEqualTo("NOME_POSSIVEL_DUPLICIDADE");
        assertThat(corpo(aviso).get("existentes").get(0).get("id").asLong()).isEqualTo(existente);

        MockHttpServletResponse confirmada =
                admin.post("/api/v1/usinas", Map.of("nome", "sao  miguel " + marca, "confirmarNomeRepetido", true));
        assertThat(confirmada.getStatus()).isEqualTo(201);

        admin.post("/api/v1/fazendas", Map.of("nome", "Araras " + marca));
        assertThat(codigo(admin.post("/api/v1/fazendas", Map.of("nome", "ARARAS " + marca))))
                .isEqualTo("NOME_POSSIVEL_DUPLICIDADE");
    }

    @Test
    void edicaoComVersaoAntigaNaoSobrescreve() {
        Navegador admin = administrador();
        JsonNode usina = corpo(admin.post("/api/v1/usinas", Map.of("nome", "Base " + marca())));
        String url = "/api/v1/usinas/" + usina.get("id").asLong();
        long versao = usina.get("versao").asLong();

        MockHttpServletResponse primeira = admin.put(
                url, Map.of("versao", versao, "nome", usina.get("nome").asString(), "cidade", "Dourados", "uf", "MS"));
        assertThat(primeira.getStatus()).isEqualTo(200);
        MockHttpServletResponse segunda = admin.put(
                url, Map.of("versao", versao, "nome", usina.get("nome").asString(), "cidade", "Maracaju"));
        assertThat(segunda.getStatus()).isEqualTo(409);
        assertThat(codigo(segunda)).isEqualTo("CONFLITO_CONCORRENTE");
        assertThat(corpo(admin.get(url)).get("cidade").asString()).isEqualTo("Dourados");

        JsonNode fazenda = corpo(admin.post("/api/v1/fazendas", Map.of("nome", "Faz " + marca())));
        String urlFazenda = "/api/v1/fazendas/" + fazenda.get("id").asLong();
        assertThat(admin.put(urlFazenda, Map.of("versao", 0, "nome", "Faz editada " + marca()))
                        .getStatus())
                .isEqualTo(200);
        assertThat(codigo(admin.put(urlFazenda, Map.of("versao", 0, "nome", "Outra " + marca()))))
                .isEqualTo("CONFLITO_CONCORRENTE");
    }

    // ---------------------------------------------------------------- vínculos

    @Test
    void vinculaUcExistenteEConsultaPelosDoisLados() {
        Navegador admin = administrador();
        long usina = novaUsina(admin);
        long[] titular = associadoComUc(admin);
        long segundaUc = novaUc(admin, titular[0]);

        MockHttpServletResponse vinculo = vincular(admin, usina, titular[1], HOJE.minusDays(10));
        assertThat(vinculo.getStatus()).isEqualTo(201);
        JsonNode v = corpo(vinculo);
        assertThat(v.get("vigente").asBoolean()).isTrue();
        assertThat(v.get("uc").get("associadoId").asLong()).isEqualTo(titular[0]);
        assertThat(v.get("uc").get("associadoDocumentoMascarado").asString()).contains("***");
        assertThat(vincular(admin, usina, segundaUc, HOJE).getStatus()).isEqualTo(201);

        JsonNode pelaUsina = corpo(admin.get("/api/v1/usinas/" + usina + "/vinculos"));
        assertThat(pelaUsina.get("totalItens").asLong()).isEqualTo(2);
        JsonNode resumo = corpo(admin.get("/api/v1/usinas/" + usina));
        assertThat(resumo.get("ucsVinculadas").asLong()).isEqualTo(2);
        assertThat(resumo.get("associadosDistintos").asLong()).isEqualTo(1);

        JsonNode peloAssociado = corpo(admin.get("/api/v1/associados/" + titular[0] + "/vinculos-usina"));
        assertThat(peloAssociado).hasSize(2);
        assertThat(peloAssociado.get(0).get("usina").get("id").asLong()).isEqualTo(usina);

        // A busca de UCs para vínculo mostra o vínculo vigente.
        String numero = v.get("uc").get("numero").asString();
        JsonNode candidatas = corpo(admin.get("/api/v1/vinculos-usina/ucs-candidatas?busca=" + numero));
        assertThat(candidatas
                        .get(0)
                        .get("vinculoVigente")
                        .get("usina")
                        .get("id")
                        .asLong())
                .isEqualTo(usina);
    }

    @Test
    void recusaUcInexistenteDuplicadoOutraUsinaEInicioFuturo() {
        Navegador admin = administrador();
        long usina = novaUsina(admin);
        long outraUsina = novaUsina(admin);
        long uc = associadoComUc(admin)[1];

        MockHttpServletResponse inexistente = vincular(admin, usina, 999_999_999L, HOJE);
        assertThat(inexistente.getStatus()).isEqualTo(404);
        assertThat(codigo(inexistente)).isEqualTo("UC_NAO_ENCONTRADA");
        assertThat(codigo(vincular(admin, 999_999_999L, uc, HOJE))).isEqualTo("USINA_NAO_ENCONTRADA");

        MockHttpServletResponse futuro = vincular(admin, usina, uc, HOJE.plusDays(1));
        assertThat(futuro.getStatus()).isEqualTo(400);
        assertThat(codigo(futuro)).isEqualTo("VIGENCIA_INVALIDA");

        assertThat(vincular(admin, usina, uc, HOJE).getStatus()).isEqualTo(201);
        MockHttpServletResponse duplicado = vincular(admin, usina, uc, HOJE);
        assertThat(duplicado.getStatus()).isEqualTo(409);
        assertThat(codigo(duplicado)).isEqualTo("VINCULO_JA_EXISTE");

        MockHttpServletResponse outra = vincular(admin, outraUsina, uc, HOJE);
        assertThat(outra.getStatus()).isEqualTo(409);
        assertThat(codigo(outra)).isEqualTo("UC_VINCULADA_A_OUTRA_USINA");
        assertThat(corpo(outra).get("usinaAtualId").asLong()).isEqualTo(usina);
        assertThat(corpo(admin.get("/api/v1/usinas/" + outraUsina))
                        .get("ucsVinculadas")
                        .asLong())
                .isZero();
    }

    @Test
    void vinculosSimultaneosDaMesmaUcAceitamApenasUm() throws Exception {
        Navegador admin = administrador();
        long uc = associadoComUc(admin)[1];
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long usina = novaUsina(admin);
            tarefas.add(() -> vincular(admin, usina, uc, HOJE));
        }
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            MockHttpServletResponse resposta = f.get();
            status.add(resposta.getStatus());
            if (resposta.getStatus() == 409) {
                assertThat(codigo(resposta)).isIn("UC_VINCULADA_A_OUTRA_USINA", "UC_JA_VINCULADA");
                assertThat(resposta.getContentAsString()).doesNotContainIgnoringCase("duplicate");
            }
        }
        assertThat(status).containsOnly(201, 409);
        assertThat(status).filteredOn(s -> s == 201).hasSize(1);
        assertThat(jdbc.sql("select count(*) from vinculo_uc_usina where uc_id = :uc and fim is null")
                        .param("uc", uc)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void encerramentoExigeMotivoEDatasValidasEPreservaHistorico() throws Exception {
        Navegador admin = administrador();
        long usina = novaUsina(admin);
        long outraUsina = novaUsina(admin);
        long uc = associadoComUc(admin)[1];
        JsonNode vinculo = corpo(vincular(admin, usina, uc, HOJE.minusDays(20)));
        String url =
                "/api/v1/usinas/" + usina + "/vinculos/" + vinculo.get("id").asLong() + "/encerramento";
        long versao = vinculo.get("versao").asLong();

        assertThat(corpo(admin.post(url, Map.of("versao", versao, "fim", HOJE.toString(), "motivo", "x")))
                        .get("campos")
                        .has("motivo"))
                .isTrue();
        assertThat(codigo(admin.post(
                        url,
                        Map.of("versao", versao, "fim", HOJE.minusDays(30).toString(), "motivo", "Antes do início"))))
                .isEqualTo("VIGENCIA_INVALIDA");
        assertThat(codigo(admin.post(
                        url, Map.of("versao", versao, "fim", HOJE.plusDays(1).toString(), "motivo", "Data futura"))))
                .isEqualTo("VIGENCIA_INVALIDA");

        // Dois encerramentos simultâneos: um vence.
        LocalDate fim = HOJE.minusDays(5);
        List<Callable<MockHttpServletResponse>> tarefas = List.of(
                () -> admin.post(url, Map.of("versao", versao, "fim", fim.toString(), "motivo", "Troca de usina")),
                () -> admin.post(url, Map.of("versao", versao, "fim", fim.toString(), "motivo", "Outro motivo")));
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            status.add(f.get().getStatus());
        }
        assertThat(status).containsExactlyInAnyOrder(200, 409);
        assertThat(codigo(admin.post(url, Map.of("versao", versao + 1, "fim", fim.toString(), "motivo", "De novo"))))
                .isIn("VINCULO_JA_ENCERRADO", "CONFLITO_CONCORRENTE");

        // Novo vínculo não pode sobrepor o anterior; começar no dia do fim é permitido.
        assertThat(codigo(vincular(admin, outraUsina, uc, fim.minusDays(1)))).isEqualTo("VIGENCIA_INVALIDA");
        assertThat(vincular(admin, outraUsina, uc, fim).getStatus()).isEqualTo(201);

        JsonNode encerrados = corpo(admin.get("/api/v1/usinas/" + usina + "/vinculos?situacao=ENCERRADOS"));
        assertThat(encerrados.get("totalItens").asLong()).isEqualTo(1);
        JsonNode encerrado = encerrados.get("itens").get(0);
        assertThat(encerrado.get("vigente").asBoolean()).isFalse();
        assertThat(encerrado.get("fim").asString()).isEqualTo(fim.toString());
        assertThat(encerrado.get("motivoEncerramento").asString()).isIn("Troca de usina", "Outro motivo");
        assertThat(encerrado.get("encerradoPor").get("id").asLong()).isPositive();
        assertThat(corpo(admin.get("/api/v1/usinas/" + usina))
                        .get("vinculosEncerrados")
                        .asLong())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- listagem

    @Test
    void listaPaginadaComBuscaFiltrosEOrdenacao() {
        Navegador admin = administrador();
        String marca = marca();
        long fazenda = corpo(admin.post("/api/v1/fazendas", Map.of("nome", "Fazenda " + marca)))
                .get("id")
                .asLong();
        long a = corpo(admin.post("/api/v1/usinas", Map.of("nome", "Alfa " + marca, "fazendaId", fazenda, "uf", "MS")))
                .get("id")
                .asLong();
        admin.post("/api/v1/usinas", Map.of("nome", "Beta " + marca, "uf", "MT"));
        admin.post("/api/v1/usinas", Map.of("nome", "Gama " + marca, "fazendaId", fazenda, "uf", "MS"));
        vincular(admin, a, associadoComUc(admin)[1], HOJE);

        JsonNode todos = corpo(admin.get("/api/v1/usinas?busca=" + marca));
        assertThat(todos.get("totalItens").asLong()).isEqualTo(3);
        assertThat(todos.get("itens").get(0).get("nome").asString()).isEqualTo("Alfa " + marca);
        assertThat(todos.get("itens").get(0).get("ucsVinculadas").asLong()).isEqualTo(1);

        JsonNode filtrado = corpo(admin.get("/api/v1/usinas?busca=" + marca + "&uf=MS&fazendaId=" + fazenda
                + "&tamanho=1&pagina=1&ordenacao=NOME"));
        assertThat(filtrado.get("totalItens").asLong()).isEqualTo(2);
        assertThat(filtrado.get("totalPaginas").asInt()).isEqualTo(2);
        assertThat(filtrado.get("itens").get(0).get("nome").asString()).isEqualTo("Gama " + marca);

        JsonNode porCodigo = corpo(admin.get("/api/v1/usinas?busca=usn-" + a));
        assertThat(porCodigo.get("totalItens").asLong()).isEqualTo(1);
        JsonNode porFazenda = corpo(admin.get("/api/v1/usinas?busca=Fazenda " + marca));
        assertThat(porFazenda.get("totalItens").asLong()).isEqualTo(2);

        assertThat(admin.get("/api/v1/usinas?tamanho=101").getStatus()).isEqualTo(400);
        assertThat(admin.get("/api/v1/usinas?ordenacao=POTENCIA").getStatus()).isEqualTo(400);
        assertThat(corpo(admin.get("/api/v1/fazendas?busca=" + marca))
                        .get("itens")
                        .get(0)
                        .get("usinas")
                        .asLong())
                .isEqualTo(2);
    }

    @Test
    void fazendasPaginadasNoServidorComMaisDeCem() {
        Navegador admin = administrador();
        String marca = marca();
        for (int i = 1; i <= 105; i++) {
            admin.post("/api/v1/fazendas", Map.of("nome", "%s %03d".formatted(marca, i)));
        }
        JsonNode primeira = corpo(admin.get("/api/v1/fazendas?busca=" + marca + "&tamanho=20"));
        assertThat(primeira.get("totalItens").asLong()).isEqualTo(105);
        assertThat(primeira.get("totalPaginas").asInt()).isEqualTo(6);
        JsonNode ultima = corpo(admin.get("/api/v1/fazendas?busca=" + marca + "&tamanho=20&pagina=5"));
        assertThat(ultima.get("itens")).hasSize(5);
        assertThat(ultima.get("itens").get(4).get("nome").asString()).isEqualTo(marca + " 105");
        long id = ultima.get("itens").get(4).get("id").asLong();

        // A 105ª fazenda é encontrada pela busca por identificador e pode ser vinculada à usina.
        JsonNode porCodigo = corpo(admin.get("/api/v1/fazendas?busca=FZD-" + id));
        assertThat(porCodigo.get("itens").get(0).get("id").asLong()).isEqualTo(id);
        JsonNode usina = corpo(admin.post("/api/v1/usinas", Map.of("nome", "Distante " + marca, "fazendaId", id)));
        assertThat(usina.get("fazenda").get("id").asLong()).isEqualTo(id);
        assertThat(admin.get("/api/v1/fazendas?tamanho=101").getStatus()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- histórico

    @Test
    void historicoRegistraAutorMotivoEPreservaAutoriaAposDesativacao() {
        String email = emailUnico("autor-usina");
        Usuario autor = criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador navegador = logado(email);
        JsonNode usina =
                corpo(navegador.post("/api/v1/usinas", Map.of("nome", "Histórico " + marca(), "cep", "79100000")));
        long id = usina.get("id").asLong();
        Map<String, Object> edicao =
                new HashMap<>(Map.of("versao", 0, "nome", usina.get("nome").asString()));
        edicao.put("cep", "79100001");
        edicao.put("cidade", "Terenos");
        navegador.put("/api/v1/usinas/" + id, edicao);
        long uc = associadoComUc(navegador)[1];
        JsonNode vinculo = corpo(vincular(navegador, id, uc, HOJE));
        navegador.post(
                "/api/v1/usinas/" + id + "/vinculos/" + vinculo.get("id").asLong() + "/encerramento",
                Map.of(
                        "versao",
                        vinculo.get("versao").asLong(),
                        "fim",
                        HOJE.toString(),
                        "motivo",
                        "Lançamento indevido"));

        jdbc.sql("update usuario set situacao = 'DESATIVADO', desativado_em = now(6),"
                        + " versao_seguranca = versao_seguranca + 1 where id = :id")
                .param("id", autor.getId())
                .update();

        Navegador admin = administrador();
        MockHttpServletResponse resposta = admin.get("/api/v1/usinas/" + id + "/historico");
        JsonNode pagina = corpo(resposta);
        List<String> acoes = new ArrayList<>();
        pagina.get("itens").forEach(r -> acoes.add(r.get("acao").asString()));
        assertThat(acoes).containsExactly("VINCULO_ENCERRADO", "VINCULO_CRIADO", "USINA_ALTERADA", "USINA_CADASTRADA");
        pagina.get("itens")
                .forEach(r -> assertThat(r.get("autor").get("nome").asString()).isEqualTo(autor.getNome()));
        assertThat(pagina.get("itens").get(0).get("motivo").asString()).isEqualTo("Lançamento indevido");
        JsonNode alteracao = pagina.get("itens").get(2).get("alteracoes");
        List<String> campos = new ArrayList<>();
        alteracao.forEach(a -> campos.add(a.get("campo").asString()));
        assertThat(campos).containsExactlyInAnyOrder("cep", "cidade");
        assertThat(resposta.getContentAsByteArray()).asString().doesNotContain("79100001");
        assertThat(admin.get("/api/v1/usinas/" + id + "/historico?tamanho=51").getStatus())
                .isEqualTo(400);
    }

    // ---------------------------------------------------------------- apoio

    private long novaUsina(Navegador admin) {
        MockHttpServletResponse resposta = admin.post(
                "/api/v1/usinas", Map.of("nome", "Usina " + UUID.randomUUID(), "confirmarNomeRepetido", true));
        assertThat(resposta.getStatus()).isEqualTo(201);
        return corpo(resposta).get("id").asLong();
    }

    private static MockHttpServletResponse vincular(Navegador navegador, long usina, long uc, LocalDate inicio) {
        return navegador.post("/api/v1/usinas/" + usina + "/vinculos", Map.of("ucId", uc, "inicio", inicio.toString()));
    }

    private static String marca() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }
}
