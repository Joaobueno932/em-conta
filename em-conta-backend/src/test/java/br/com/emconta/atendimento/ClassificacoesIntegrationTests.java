package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Setores e etiquetas: cadastros (validação, nome único, versão, histórico, inativação), permissões por
 * perfil também em chamada direta, classificação do ciclo atual pelo responsável, preservação do ciclo
 * anterior do mesmo contato e filtros do Histórico geral combinados com busca, período e paginação,
 * inclusive para atendimentos sem classificação e itens desativados ou renomeados depois.
 */
class ClassificacoesIntegrationTests extends AtendimentoIntegracao {

    private static final ZoneId FUSO = ZoneId.of("America/Campo_Grande");
    private static final String SETORES = "/api/v1/atendimento/setores";
    private static final String ETIQUETAS = "/api/v1/atendimento/etiquetas";

    @BeforeEach
    void esvaziar() {
        processar();
        GRAPH.limpar();
    }

    @Test
    void cadastroValidaNomeUnicoSemDiferenciarAcentoExigeVersaoEGuardaHistorico() throws Exception {
        Navegador admin = administrador();
        String marca = marca();

        MockHttpServletResponse criado = admin.post(SETORES, Map.of("nome", "  Cobrança   " + marca + " "));
        assertThat(criado.getStatus()).isEqualTo(201);
        JsonNode setor = corpo(criado);
        long id = setor.get("id").asLong();
        assertThat(setor.get("nome").asString()).isEqualTo("Cobrança " + marca);
        assertThat(setor.get("ativo").asBoolean()).isTrue();
        assertThat(setor.get("versao").asLong()).isZero();

        // Mesmo nome sem acento e em maiúsculas: já existe.
        MockHttpServletResponse repetido = admin.post(SETORES, Map.of("nome", "COBRANCA " + marca.toUpperCase()));
        assertThat(repetido.getStatus()).isEqualTo(409);
        assertThat(codigo(repetido)).isEqualTo("NOME_EM_USO");
        assertThat(admin.post(SETORES, Map.of("nome", "a")).getStatus()).isEqualTo(400);
        assertThat(admin.post(SETORES, Map.of("nome", "x".repeat(61))).getStatus())
                .isEqualTo(400);
        assertThat(admin.post(ETIQUETAS, Map.of("nome", "x".repeat(41))).getStatus())
                .isEqualTo(400);

        // Renomear exige a versão vista.
        assertThat(admin.put(SETORES + "/" + id, Map.of("nome", "Financeiro " + marca))
                        .getStatus())
                .isEqualTo(400);
        JsonNode renomeado = corpo(admin.put(SETORES + "/" + id, Map.of("nome", "Financeiro " + marca, "versao", 0)));
        assertThat(renomeado.get("nome").asString()).isEqualTo("Financeiro " + marca);
        assertThat(renomeado.get("versao").asLong()).isEqualTo(1);
        MockHttpServletResponse antiga = admin.put(SETORES + "/" + id, Map.of("nome", "Outro " + marca, "versao", 0));
        assertThat(antiga.getStatus()).isEqualTo(409);
        assertThat(codigo(antiga)).isEqualTo("CONFLITO_CONCORRENTE");
        // O nome antigo fica livre para outro setor.
        assertThat(admin.post(SETORES, Map.of("nome", "Cobrança " + marca)).getStatus())
                .isEqualTo(201);

        // Desativar preserva o cadastro; a lista filtra pela situação.
        JsonNode inativo = corpo(admin.post(SETORES + "/" + id + "/desativacao", Map.of("versao", 1)));
        assertThat(inativo.get("ativo").asBoolean()).isFalse();
        assertThat(nomes(admin, SETORES + "?situacao=ATIVOS")).doesNotContain("Financeiro " + marca);
        assertThat(nomes(admin, SETORES + "?situacao=INATIVOS")).contains("Financeiro " + marca);
        assertThat(nomes(admin, SETORES)).contains("Financeiro " + marca, "Cobrança " + marca);
        assertThat(corpo(admin.post(SETORES + "/" + id + "/ativacao", Map.of("versao", 2)))
                        .get("ativo")
                        .asBoolean())
                .isTrue();

        assertThat(jdbc.sql("select concat(acao, ':', coalesce(nome_anterior, '')) from atd_classificacao_historico"
                                + " where tipo = 'SETOR' and item_id = :id order by id")
                        .param("id", id)
                        .query(String.class)
                        .list())
                .containsExactly("CRIADO:", "RENOMEADO:Cobrança " + marca, "DESATIVADO:", "ATIVADO:");

        long etiqueta = criar(admin, ETIQUETAS, "Segunda via " + marca);
        MockHttpServletResponse etiquetaRepetida = admin.post(ETIQUETAS, Map.of("nome", "segunda VIA " + marca));
        assertThat(etiquetaRepetida.getStatus()).isEqualTo(409);
        assertThat(codigo(etiquetaRepetida)).isEqualTo("NOME_EM_USO");
        assertThat(admin.post(ETIQUETAS + "/" + etiqueta + "/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(200);
        assertThat(admin.post(ETIQUETAS + "/999999999/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void cadaAcaoDoCadastroEDaClassificacaoExigeSuaPermissaoTambemEmChamadaDireta() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        long setor = criar(admin, SETORES, "Setor permissões " + marca);
        long etiqueta = criar(admin, ETIQUETAS, "Etiqueta permissões " + marca);

        Navegador anonimo = new Navegador(mvc, json);
        Navegador semPermissao = comPermissoes();
        Navegador leitor = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR);
        for (String lista : List.of(SETORES, ETIQUETAS)) {
            assertThat(anonimo.get(lista).getStatus()).as("anônimo em " + lista).isEqualTo(401);
            assertThat(semPermissao.get(lista).getStatus())
                    .as("sem permissão em " + lista)
                    .isEqualTo(403);
            assertThat(leitor.get(lista).getStatus())
                    .as("só consulta em " + lista)
                    .isEqualTo(200);
        }
        // Quem só consulta não altera os cadastros.
        assertThat(leitor.post(SETORES, Map.of("nome", "Leitor " + marca)).getStatus())
                .isEqualTo(403);
        assertThat(leitor.put(SETORES + "/" + setor, Map.of("nome", "Leitor " + marca, "versao", 0))
                        .getStatus())
                .isEqualTo(403);
        assertThat(leitor.post(SETORES + "/" + setor + "/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(403);
        assertThat(leitor.post(ETIQUETAS, Map.of("nome", "Leitor " + marca)).getStatus())
                .isEqualTo(403);

        // Cada permissão libera só a sua ação, e a de setores não vale para etiquetas.
        Navegador cadastraSetor = comPermissoes(Permissao.SETORES_CADASTRAR);
        assertThat(cadastraSetor
                        .post(SETORES, Map.of("nome", "Cadastrado " + marca))
                        .getStatus())
                .isEqualTo(201);
        assertThat(cadastraSetor
                        .post(ETIQUETAS, Map.of("nome", "Cadastrada " + marca))
                        .getStatus())
                .isEqualTo(403);
        assertThat(cadastraSetor
                        .put(SETORES + "/" + setor, Map.of("nome", "Renomeado " + marca, "versao", 0))
                        .getStatus())
                .isEqualTo(403);
        Navegador editaSetor = comPermissoes(Permissao.SETORES_EDITAR);
        assertThat(editaSetor
                        .put(SETORES + "/" + setor, Map.of("nome", "Renomeado " + marca, "versao", 0))
                        .getStatus())
                .isEqualTo(200);
        assertThat(editaSetor
                        .post(SETORES + "/" + setor + "/desativacao", Map.of("versao", 1))
                        .getStatus())
                .isEqualTo(403);
        Navegador situacaoSetor = comPermissoes(Permissao.SETORES_ALTERAR_SITUACAO);
        assertThat(situacaoSetor
                        .post(SETORES + "/" + setor + "/desativacao", Map.of("versao", 1))
                        .getStatus())
                .isEqualTo(200);
        assertThat(situacaoSetor
                        .post(ETIQUETAS + "/" + etiqueta + "/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(403);
        Navegador etiquetas = comPermissoes(
                Permissao.ETIQUETAS_CADASTRAR, Permissao.ETIQUETAS_EDITAR, Permissao.ETIQUETAS_ALTERAR_SITUACAO);
        assertThat(etiquetas.post(ETIQUETAS, Map.of("nome", "Nova " + marca)).getStatus())
                .isEqualTo(201);
        assertThat(etiquetas
                        .put(ETIQUETAS + "/" + etiqueta, Map.of("nome", "Renomeada " + marca, "versao", 0))
                        .getStatus())
                .isEqualTo(200);
        assertThat(etiquetas
                        .post(ETIQUETAS + "/" + etiqueta + "/desativacao", Map.of("versao", 1))
                        .getStatus())
                .isEqualTo(200);
        assertThat(etiquetas.post(SETORES, Map.of("nome", "Indevido " + marca)).getStatus())
                .isEqualTo(403);

        // Classificar: a permissão própria e, além dela, ser o responsável atual.
        long ativo = criar(admin, SETORES, "Setor ativo " + marca);
        Navegador semClassificar = comPermissoes(
                Permissao.ATENDIMENTO_CONSULTAR, Permissao.ATENDIMENTO_ASSUMIR, Permissao.ATENDIMENTO_RESPONDER);
        long atendimento = novoAtendimento(numeroUnico());
        assertThat(semClassificar.post(url(atendimento, "assuncao"), null).getStatus())
                .isEqualTo(204);
        assertThat(classificar(semClassificar, atendimento, ativo).getStatus()).isEqualTo(403);
        MockHttpServletResponse naoResponsavel = classificar(admin, atendimento, ativo);
        assertThat(naoResponsavel.getStatus()).isEqualTo(409);
        assertThat(codigo(naoResponsavel)).isEqualTo("NAO_E_RESPONSAVEL");
        assertThat(detalhe(admin, atendimento).get("setor").isNull()).isTrue();
    }

    @Test
    void responsavelClassificaOCicloAtualComItensAtivosEOEventoGuardaOsNomesDaquelaHora() throws Exception {
        Navegador admin = administrador();
        Navegador colega = administrador();
        long colegaId = corpo(colega.get("/api/v1/auth/sessao")).get("id").asLong();
        String marca = marca();
        long financeiro = criar(admin, SETORES, "Financeiro " + marca);
        long suporte = criar(admin, SETORES, "Suporte " + marca);
        long segundaVia = criar(admin, ETIQUETAS, "Segunda via " + marca);
        long contrato = criar(admin, ETIQUETAS, "Contrato " + marca);
        long atendimento = assumido(admin, numeroUnico());

        long versao = versao(atendimento);
        assertThat(classificar(admin, atendimento, financeiro, segundaVia, contrato)
                        .getStatus())
                .isEqualTo(204);
        JsonNode resumo = detalhe(admin, atendimento);
        assertThat(resumo.get("setor").get("nome").asString()).isEqualTo("Financeiro " + marca);
        assertThat(nomesDe(resumo.get("etiquetas"))).containsExactly("Contrato " + marca, "Segunda via " + marca);
        assertThat(versao(atendimento)).isEqualTo(versao + 1);
        String retrato = "Setor: Financeiro " + marca + " · Etiquetas: Contrato " + marca + ", Segunda via " + marca;
        assertThat(eventos(admin, atendimento, "CLASSIFICADO")).containsExactly(retrato);

        // A mesma classificação de novo não muda nada.
        assertThat(classificar(admin, atendimento, financeiro, contrato, segundaVia)
                        .getStatus())
                .isEqualTo(204);
        assertThat(versao(atendimento)).isEqualTo(versao + 1);
        assertThat(eventos(admin, atendimento, "CLASSIFICADO")).hasSize(1);

        // Versão antiga, item inexistente, inativo ou etiquetas demais: nada é gravado.
        MockHttpServletResponse antiga =
                admin.put(url(atendimento, "classificacao"), corpoClassificacao(versao, suporte, List.of()));
        assertThat(antiga.getStatus()).isEqualTo(409);
        assertThat(codigo(antiga)).isEqualTo("CONFLITO_CONCORRENTE");
        assertThat(classificar(admin, atendimento, 999_999_999L).getStatus()).isEqualTo(400);
        assertThat(admin.put(
                                url(atendimento, "classificacao"),
                                corpoClassificacao(versao(atendimento), financeiro, List.of(999_999_999L)))
                        .getStatus())
                .isEqualTo(400);
        assertThat(admin.post(SETORES + "/" + suporte + "/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(200);
        MockHttpServletResponse inativo = classificar(admin, atendimento, suporte);
        assertThat(inativo.getStatus()).isEqualTo(400);
        assertThat(corpo(inativo).path("campos").has("setorId")).isTrue();
        List<Long> onze = new ArrayList<>();
        for (int i = 0; i < 11; i++) onze.add(criar(admin, ETIQUETAS, "Muitas " + i + " " + marca));
        assertThat(admin.put(url(atendimento, "classificacao"), corpoClassificacao(versao(atendimento), null, onze))
                        .getStatus())
                .isEqualTo(400);
        assertThat(detalhe(admin, atendimento).get("setor").get("id").asLong()).isEqualTo(financeiro);

        // Item que o ciclo já tem pode ficar mesmo depois de desativado; escolher outro inativo, não.
        assertThat(admin.post(ETIQUETAS + "/" + segundaVia + "/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(200);
        assertThat(classificar(admin, atendimento, financeiro, segundaVia).getStatus())
                .isEqualTo(204);
        JsonNode mantida = detalhe(admin, atendimento).get("etiquetas");
        assertThat(nomesDe(mantida)).containsExactly("Segunda via " + marca);
        assertThat(mantida.get(0).get("ativo").asBoolean()).isFalse();

        // Transferir mantém a classificação; quem passa a responder pode reclassificar, quem saiu não.
        assertThat(admin.post(
                                url(atendimento, "transferencia"),
                                Map.of("versao", versao(atendimento), "destinoId", colegaId))
                        .getStatus())
                .isEqualTo(204);
        assertThat(detalhe(colega, atendimento).get("setor").get("id").asLong()).isEqualTo(financeiro);
        assertThat(classificar(admin, atendimento, financeiro).getStatus()).isEqualTo(409);
        assertThat(classificar(colega, atendimento, null).getStatus()).isEqualTo(204);
        assertThat(detalhe(colega, atendimento).get("setor").isNull()).isTrue();
        assertThat(detalhe(colega, atendimento).get("etiquetas")).isEmpty();
        assertThat(eventos(admin, atendimento, "CLASSIFICADO")).last().isEqualTo("Setor: nenhum · Etiquetas: nenhuma");

        // Devolvido à fila, o setor continua no ciclo, mas sem responsável ninguém o altera.
        assertThat(classificar(colega, atendimento, financeiro).getStatus()).isEqualTo(204);
        assertThat(colega.post(url(atendimento, "transferencia"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
        assertThat(detalhe(admin, atendimento).get("setor").get("id").asLong()).isEqualTo(financeiro);
        MockHttpServletResponse naFila = classificar(admin, atendimento, null);
        assertThat(naFila.getStatus()).isEqualTo(409);
        assertThat(codigo(naFila)).isEqualTo("ATENDIMENTO_SEM_RESPONSAVEL");

        // Renomear depois muda o nome exibido no ciclo, não o retrato do evento.
        assertThat(admin.put(SETORES + "/" + financeiro, Map.of("nome", "Cobrança " + marca, "versao", 0))
                        .getStatus())
                .isEqualTo(200);
        assertThat(detalhe(admin, atendimento).get("setor").get("nome").asString())
                .isEqualTo("Cobrança " + marca);
        assertThat(eventos(admin, atendimento, "CLASSIFICADO")).first().isEqualTo(retrato);
    }

    @Test
    void cicloEncerradoGuardaSuaClassificacaoEONovoCicloDoMesmoContatoComecaSemNenhuma() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        String numero = numeroUnico();
        long financeiro = criar(admin, SETORES, "Financeiro " + marca);
        long comercial = criar(admin, SETORES, "Comercial " + marca);
        long segundaVia = criar(admin, ETIQUETAS, "Segunda via " + marca);
        long contrato = criar(admin, ETIQUETAS, "Contrato " + marca);

        long primeiro = assumido(admin, numero);
        assertThat(classificar(admin, primeiro, financeiro, segundaVia).getStatus())
                .isEqualTo(204);
        encerrar(admin, primeiro);
        MockHttpServletResponse encerrado = classificar(admin, primeiro, comercial);
        assertThat(encerrado.getStatus()).isEqualTo(409);
        assertThat(codigo(encerrado)).isEqualTo("ATENDIMENTO_ENCERRADO");

        long segundo = novoAtendimento(numero);
        assertThat(segundo).isNotEqualTo(primeiro);
        JsonNode novo = detalhe(admin, segundo);
        assertThat(novo.get("setor").isNull()).isTrue();
        assertThat(novo.get("etiquetas")).isEmpty();
        assertThat(admin.post(url(segundo, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(classificar(admin, segundo, comercial, contrato).getStatus()).isEqualTo(204);
        encerrar(admin, segundo);

        JsonNode anterior = detalhe(admin, primeiro);
        assertThat(anterior.get("setor").get("nome").asString()).isEqualTo("Financeiro " + marca);
        assertThat(nomesDe(anterior.get("etiquetas"))).containsExactly("Segunda via " + marca);
        assertThat(eventos(admin, primeiro, "CLASSIFICADO")).hasSize(1);
        assertThat(ids(historico(admin, "busca=" + numero + "&setor=" + financeiro)))
                .containsExactly(primeiro);
        assertThat(ids(historico(admin, "busca=" + numero + "&setor=" + comercial)))
                .containsExactly(segundo);
        assertThat(ids(historico(admin, "busca=" + numero + "&etiqueta=" + contrato)))
                .containsExactly(segundo);

        String exportado = exportar(admin, primeiro);
        assertThat(exportado)
                .contains(
                        "Setor: Financeiro " + marca,
                        "Etiquetas: Segunda via " + marca,
                        "classificou o atendimento: Setor: Financeiro " + marca)
                .doesNotContain("Comercial " + marca, "Contrato " + marca);
        assertThat(exportar(admin, segundo)).contains("Setor: Comercial " + marca, "Etiquetas: Contrato " + marca);
    }

    @Test
    void historicoFiltraSetorEEtiquetaJuntosComBuscaPeriodoEPaginacaoInclusiveSemClassificacao() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        long a = criar(admin, SETORES, "Setor A " + marca);
        long b = criar(admin, SETORES, "Setor B " + marca);
        long x = criar(admin, ETIQUETAS, "Etiqueta X " + marca);
        long y = criar(admin, ETIQUETAS, "Etiqueta Y " + marca);
        String nome = "Classif " + marca;

        long c1 = classificado(admin, nome + " 1", a, x);
        long c2 = classificado(admin, nome + " 2", a, x, y);
        long c3 = classificado(admin, nome + " 3", b, y);
        long c4 = classificado(admin, nome + " 4", null); // como os ciclos anteriores a esta versão
        long c5 = classificado(admin, nome + " 5", a);

        String busca = "busca=" + nome;
        assertThat(ids(historico(admin, busca))).containsExactly(c5, c4, c3, c2, c1);
        assertThat(ids(historico(admin, busca + "&setor=" + a))).containsExactly(c5, c2, c1);
        assertThat(ids(historico(admin, busca + "&etiqueta=" + x))).containsExactly(c2, c1);
        assertThat(ids(historico(admin, busca + "&setor=" + a + "&etiqueta=" + y)))
                .containsExactly(c2);
        assertThat(ids(historico(admin, busca + "&setor=SEM"))).containsExactly(c4);
        assertThat(ids(historico(admin, busca + "&etiqueta=sem"))).containsExactly(c5, c4);
        assertThat(ids(historico(admin, busca + "&setor=SEM&etiqueta=SEM"))).containsExactly(c4);

        // Combinação sem resultado: lista vazia com total zero.
        JsonNode vazia = historico(admin, busca + "&setor=" + b + "&etiqueta=" + x);
        assertThat(vazia.get("itens")).isEmpty();
        assertThat(vazia.get("totalItens").asLong()).isZero();
        assertThat(vazia.get("totalPaginas").asInt()).isZero();

        // Paginação sobre o resultado filtrado.
        JsonNode primeira = historico(admin, busca + "&setor=" + a + "&tamanho=2");
        assertThat(ids(primeira)).containsExactly(c5, c2);
        assertThat(primeira.get("totalItens").asLong()).isEqualTo(3);
        assertThat(primeira.get("totalPaginas").asInt()).isEqualTo(2);
        assertThat(ids(historico(admin, busca + "&setor=" + a + "&tamanho=2&pagina=1")))
                .containsExactly(c1);

        // Período do negócio junto com setor e etiqueta.
        LocalDate hoje = LocalDate.now(FUSO);
        assertThat(ids(historico(admin, busca + "&de=" + hoje + "&ate=" + hoje + "&setor=" + a + "&etiqueta=" + x)))
                .containsExactly(c2, c1);
        assertThat(ids(historico(admin, busca + "&de=" + hoje.plusDays(1) + "&setor=" + a)))
                .isEmpty();
        // Protocolo e setor: os dois valem.
        assertThat(ids(historico(admin, "busca=ATD-%06d&setor=%d".formatted(c3, b))))
                .containsExactly(c3);
        assertThat(ids(historico(admin, "busca=ATD-%06d&setor=%d".formatted(c3, a))))
                .isEmpty();

        // A lista traz setor e etiquetas de cada ciclo; o ciclo sem classificação vem vazio.
        JsonNode itens = historico(admin, busca).get("itens");
        JsonNode linha2 = itemDe(itens, c2);
        assertThat(linha2.get("setor").get("nome").asString()).isEqualTo("Setor A " + marca);
        assertThat(nomesDe(linha2.get("etiquetas"))).containsExactly("Etiqueta X " + marca, "Etiqueta Y " + marca);
        assertThat(itemDe(itens, c4).get("setor").isNull()).isTrue();
        assertThat(itemDe(itens, c4).get("etiquetas")).isEmpty();

        // Desativar e renomear depois: o histórico continua consultável pelo mesmo item.
        assertThat(admin.post(SETORES + "/" + a + "/desativacao", Map.of("versao", 0))
                        .getStatus())
                .isEqualTo(200);
        assertThat(admin.put(ETIQUETAS + "/" + x, Map.of("nome", "Etiqueta Z " + marca, "versao", 0))
                        .getStatus())
                .isEqualTo(200);
        JsonNode depois = historico(admin, busca + "&setor=" + a);
        assertThat(ids(depois)).containsExactly(c5, c2, c1);
        assertThat(itemDe(depois.get("itens"), c1).get("setor").get("ativo").asBoolean())
                .isFalse();
        assertThat(nomesDe(itemDe(depois.get("itens"), c1).get("etiquetas"))).containsExactly("Etiqueta Z " + marca);
        assertThat(ids(historico(admin, busca + "&etiqueta=" + x))).containsExactly(c2, c1);
        assertThat(eventos(admin, c1, "CLASSIFICADO"))
                .containsExactly("Setor: Setor A " + marca + " · Etiquetas: Etiqueta X " + marca);

        // Valor de filtro inválido.
        MockHttpServletResponse invalido = admin.get("/api/v1/atendimento/historico?setor=abc");
        assertThat(invalido.getStatus()).isEqualTo(400);
        assertThat(corpo(invalido).path("campos").has("setor")).isTrue();
        assertThat(admin.get("/api/v1/atendimento/historico?etiqueta=1;2").getStatus())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ apoio

    /** Ciclo encerrado com o contato {@code nome}: assume, classifica (se houver setor ou etiquetas) e encerra. */
    private long classificado(Navegador admin, String nome, Long setor, Long... etiquetas) throws Exception {
        String numero = numeroUnico();
        String corpo = mensagem(numero, nome, wamid(), "Olá", agoraEpoch());
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
        recebimento.processarPendentes(50);
        long atendimento = jdbc.sql("select a.id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where c.wa_id = :n and a.situacao <> 'ENCERRADO'")
                .param("n", numero)
                .query(Long.class)
                .single();
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        if (setor != null || etiquetas.length > 0) {
            assertThat(classificar(admin, atendimento, setor, etiquetas).getStatus())
                    .isEqualTo(204);
        }
        encerrar(admin, atendimento);
        return atendimento;
    }

    private MockHttpServletResponse classificar(Navegador navegador, long atendimento, Long setor, Long... etiquetas) {
        return navegador.put(
                url(atendimento, "classificacao"),
                corpoClassificacao(versao(atendimento), setor, Arrays.asList(etiquetas)));
    }

    private static Map<String, Object> corpoClassificacao(long versao, Long setor, List<Long> etiquetas) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("versao", versao);
        corpo.put("setorId", setor);
        corpo.put("etiquetaIds", etiquetas);
        return corpo;
    }

    private long criar(Navegador navegador, String cadastro, String nome) {
        MockHttpServletResponse resposta = navegador.post(cadastro, Map.of("nome", nome));
        assertThat(resposta.getStatus()).isEqualTo(201);
        return corpo(resposta).get("id").asLong();
    }

    private void encerrar(Navegador navegador, long atendimento) {
        assertThat(navegador
                        .post(url(atendimento, "encerramento"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
    }

    private JsonNode detalhe(Navegador navegador, long atendimento) {
        MockHttpServletResponse resposta = navegador.get("/api/v1/atendimento/atendimentos/" + atendimento);
        assertThat(resposta.getStatus()).isEqualTo(200);
        return corpo(resposta).get("resumo");
    }

    /** Texto dos eventos {@code tipo} deste ciclo, em ordem. */
    private List<String> eventos(Navegador navegador, long atendimento, String tipo) {
        List<String> textos = new ArrayList<>();
        corpo(navegador.get(url(atendimento, "linha-do-tempo?somenteEste=true")))
                .get("itens")
                .forEach(i -> {
                    if (i.hasNonNull("evento") && tipo.equals(i.get("evento").asString())) {
                        textos.add(i.get("texto").asString());
                    }
                });
        return textos;
    }

    /** {@code consulta} sem codificar: o MockMvc codifica o endereço. */
    private JsonNode historico(Navegador navegador, String consulta) {
        MockHttpServletResponse resposta = navegador.get("/api/v1/atendimento/historico?" + consulta);
        assertThat(resposta.getStatus()).isEqualTo(200);
        return corpo(resposta);
    }

    private String exportar(Navegador navegador, long atendimento) throws Exception {
        MockHttpServletResponse resposta = navegador.get(url(atendimento, "exportacao"));
        assertThat(resposta.getStatus()).isEqualTo(200);
        return resposta.getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> nomes(Navegador navegador, String lista) {
        return nomesDe(corpo(navegador.get(lista)).get("itens"));
    }

    private static List<String> nomesDe(JsonNode itens) {
        List<String> nomes = new ArrayList<>();
        itens.forEach(i -> nomes.add(i.get("nome").asString()));
        return nomes;
    }

    private static List<Long> ids(JsonNode pagina) {
        List<Long> ids = new ArrayList<>();
        pagina.get("itens").forEach(i -> ids.add(i.get("id").asLong()));
        return ids;
    }

    private static JsonNode itemDe(JsonNode itens, long id) {
        for (JsonNode item : itens) {
            if (item.get("id").asLong() == id) return item;
        }
        throw new AssertionError("Atendimento " + id + " fora da página");
    }

    private static String marca() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
