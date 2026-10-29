package br.com.emconta.associados;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.TestcontainersConfiguration;
import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import br.com.emconta.associados.cadastro.Documentos;
import br.com.emconta.associados.cadastro.TipoPessoa;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cadastro de associados, contatos, UCs e histórico sobre MySQL real (Testcontainers). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class AssociadosIntegrationTests {

    private static final String SENHA = "uma senha de teste bem longa";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    UsuarioRepository usuarios;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcClient jdbc;

    // ---------------------------------------------------------------- cadastro

    @Test
    void cadastraPfComContatosEPjComCnpjAlfanumerico() {
        Navegador admin = administrador();
        String cpf = cpfValido();

        MockHttpServletResponse pf = admin.post(
                "/api/v1/associados",
                Map.of(
                        "tipo",
                        "PF",
                        "documento",
                        Documentos.formatar(TipoPessoa.PF, cpf),
                        "nome",
                        "  Lúcia   Prado Nogueira ",
                        "contatos",
                        List.of(
                                Map.of("nome", "Lúcia", "telefone", "+55 (67) 99812-3344"),
                                Map.of("nome", "Financeiro", "email", "Financeiro@Exemplo.com.br"))));
        assertThat(pf.getStatus()).isEqualTo(201);
        JsonNode corpoPf = corpo(pf);
        assertThat(corpoPf.get("tipo").asString()).isEqualTo("PF");
        assertThat(corpoPf.get("nome").asString()).isEqualTo("Lúcia Prado Nogueira");
        assertThat(corpoPf.get("documento").asString()).isEqualTo(Documentos.formatar(TipoPessoa.PF, cpf));
        assertThat(corpoPf.get("contatos")).hasSize(2);
        assertThat(corpoPf.get("contatos").get(0).get("telefone").asString()).isEqualTo("5567998123344");
        assertThat(corpoPf.get("contatos").get(1).get("email").asString()).isEqualTo("financeiro@exemplo.com.br");
        assertThat(corpoPf.get("criadoPor").get("nome").asString()).startsWith("Pessoa ");
        assertThat(corpoPf.has("versao")).isTrue();

        String cnpj = cnpjAlfanumericoValido();
        MockHttpServletResponse pj = admin.post(
                "/api/v1/associados",
                Map.of(
                        "tipo", "PJ",
                        "documento", cnpj.toLowerCase(),
                        "nome", "Agropecuária Aurora Ltda.",
                        "nomeFantasia", "Aurora"));
        assertThat(pj.getStatus()).isEqualTo(201);
        assertThat(corpo(pj).get("documento").asString()).isEqualTo(Documentos.formatar(TipoPessoa.PJ, cnpj));
        assertThat(corpo(pj).get("nomeFantasia").asString()).isEqualTo("Aurora");
        assertThat(jdbc.sql("select documento from associado where id = :id")
                        .param("id", corpo(pj).get("id").asLong())
                        .query(String.class)
                        .single())
                .as("forma canônica")
                .isEqualTo(cnpj);
    }

    @Test
    void recusaDocumentoInvalidoECamposAusentesComMensagemPorCampo() {
        Navegador admin = administrador();

        MockHttpServletResponse cpfInvalido =
                admin.post("/api/v1/associados", Map.of("tipo", "PF", "documento", "529.982.247-24", "nome", "X"));
        assertThat(cpfInvalido.getStatus()).isEqualTo(400);
        assertThat(codigo(cpfInvalido)).isEqualTo("DOCUMENTO_INVALIDO");
        assertThat(corpo(cpfInvalido).get("campos").get("documento").asString()).isEqualTo("CPF inválido.");
        assertThat(cpfInvalido.getContentAsByteArray()).asString().doesNotContain("52998224724");

        MockHttpServletResponse cnpjComoPf =
                admin.post("/api/v1/associados", Map.of("tipo", "PF", "documento", "12ABC34501DE35", "nome", "X"));
        assertThat(codigo(cnpjComoPf)).isEqualTo("DOCUMENTO_INVALIDO");

        MockHttpServletResponse semNome =
                admin.post("/api/v1/associados", Map.of("tipo", "PJ", "documento", cnpjNumericoValido(), "nome", " "));
        assertThat(semNome.getStatus()).isEqualTo(400);
        assertThat(corpo(semNome).get("campos").get("nome").asString()).isEqualTo("Informe a razão social.");

        MockHttpServletResponse fantasiaEmPf = admin.post(
                "/api/v1/associados",
                Map.of("tipo", "PF", "documento", cpfValido(), "nome", "Fulano", "nomeFantasia", "Loja"));
        assertThat(corpo(fantasiaEmPf).get("campos").has("nomeFantasia")).isTrue();

        MockHttpServletResponse campoDesconhecido = admin.post(
                "/api/v1/associados",
                Map.of("tipo", "PF", "documento", cpfValido(), "nome", "Fulano", "situacao", "ATIVO"));
        assertThat(campoDesconhecido.getStatus()).isEqualTo(400);

        assertThat(jdbc.sql("select count(*) from associado where nome = 'Fulano'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void validaContatosSemPerderOsDemais() {
        Navegador admin = administrador();
        Map<String, Object> base = new HashMap<>(Map.of("tipo", "PF", "documento", cpfValido(), "nome", "Contatos"));

        base.put("contatos", List.of(Map.of("nome", "Ok", "email", "ok@x.com.br"), Map.of("nome", "Sem meio")));
        MockHttpServletResponse semMeio = admin.post("/api/v1/associados", base);
        assertThat(semMeio.getStatus()).isEqualTo(400);
        assertThat(corpo(semMeio).get("campos").has("contatos[1].email")).isTrue();

        base.put("contatos", List.of(Map.of("nome", "Tel", "telefone", "9999-123")));
        assertThat(corpo(admin.post("/api/v1/associados", base)).get("campos").has("contatos[0].telefone"))
                .isTrue();

        base.put("contatos", List.of(Map.of("nome", "Mail", "email", "sem-arroba")));
        assertThat(corpo(admin.post("/api/v1/associados", base)).get("campos").has("contatos[0].email"))
                .isTrue();

        // Nada foi gravado parcialmente.
        assertThat(jdbc.sql("select count(*) from associado where nome = 'Contatos'")
                        .query(Long.class)
                        .single())
                .isZero();

        base.put("contatos", List.of(Map.of("nome", "Ok", "email", "ok@x.com.br")));
        long id = corpo(admin.post("/api/v1/associados", base)).get("id").asLong();

        MockHttpServletResponse adicionado = admin.post(
                "/api/v1/associados/" + id + "/contatos", Map.of("nome", "Segundo", "telefone", "(67) 3321-0000"));
        assertThat(adicionado.getStatus()).isEqualTo(201);
        long contatoId = corpo(adicionado).get("id").asLong();
        long versaoContato = corpo(adicionado).get("versao").asLong();

        // E-mail e telefone podem se repetir em outro associado (sem unicidade global).
        Map<String, Object> outro = new HashMap<>(Map.of("tipo", "PF", "documento", cpfValido(), "nome", "Outro"));
        outro.put("contatos", List.of(Map.of("nome", "Ok", "email", "ok@x.com.br")));
        assertThat(admin.post("/api/v1/associados", outro).getStatus()).isEqualTo(201);

        MockHttpServletResponse editado = admin.put(
                "/api/v1/associados/" + id + "/contatos/" + contatoId,
                Map.of("versao", versaoContato, "nome", "Segundo", "email", "segundo@x.com.br"));
        assertThat(editado.getStatus()).isEqualTo(200);
        assertThat(corpo(editado).path("telefone").isNull())
                .as("telefone removido na edição")
                .isTrue();

        MockHttpServletResponse obsoleto = admin.put(
                "/api/v1/associados/" + id + "/contatos/" + contatoId,
                Map.of("versao", versaoContato, "nome", "Outro nome", "email", "segundo@x.com.br"));
        assertThat(obsoleto.getStatus()).isEqualTo(409);
        assertThat(codigo(obsoleto)).isEqualTo("CONFLITO_CONCORRENTE");

        MockHttpServletResponse removido = admin.post(
                "/api/v1/associados/" + id + "/contatos/" + contatoId + "/remocao",
                Map.of("versao", corpo(editado).get("versao").asLong()));
        assertThat(removido.getStatus()).isEqualTo(204);
        assertThat(corpo(admin.get("/api/v1/associados/" + id)).get("contatos")).hasSize(1);
        assertThat(jdbc.sql("select removido_em is not null from associado_contato where id = :id")
                        .param("id", contatoId)
                        .query(Boolean.class)
                        .single())
                .as("remoção lógica")
                .isTrue();
    }

    @Test
    void bloqueiaDocumentoDuplicadoAposNormalizacao() {
        Navegador admin = administrador();
        String cpf = cpfValido();
        long id = corpo(admin.post("/api/v1/associados", Map.of("tipo", "PF", "documento", cpf, "nome", "Primeiro")))
                .get("id")
                .asLong();

        MockHttpServletResponse duplicado = admin.post(
                "/api/v1/associados",
                Map.of(
                        "tipo", "PF",
                        "documento", " " + Documentos.formatar(TipoPessoa.PF, cpf),
                        "nome", "Segundo"));
        assertThat(duplicado.getStatus()).isEqualTo(409);
        assertThat(codigo(duplicado)).isEqualTo("DOCUMENTO_JA_CADASTRADO");
        assertThat(corpo(duplicado).get("associadoExistenteId").asLong()).isEqualTo(id);
        assertThat(corpo(admin.get("/api/v1/associados/" + id)).get("nome").asString())
                .as("sem sobrescrita")
                .isEqualTo("Primeiro");

        String cnpj = cnpjAlfanumericoValido();
        admin.post("/api/v1/associados", Map.of("tipo", "PJ", "documento", cnpj, "nome", "Empresa"));
        MockHttpServletResponse minusculas = admin.post(
                "/api/v1/associados", Map.of("tipo", "PJ", "documento", cnpj.toLowerCase(), "nome", "Empresa 2"));
        assertThat(codigo(minusculas)).isEqualTo("DOCUMENTO_JA_CADASTRADO");
    }

    @Test
    void cadastrosSimultaneosDoMesmoDocumentoAceitamApenasUm() throws Exception {
        Navegador admin = administrador();
        String cpf = cpfValido();
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String nome = "Concorrente " + i;
            tarefas.add(() -> admin.post("/api/v1/associados", Map.of("tipo", "PF", "documento", cpf, "nome", nome)));
        }
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> resultado : emParalelo(tarefas)) {
            MockHttpServletResponse resposta = resultado.get();
            status.add(resposta.getStatus());
            if (resposta.getStatus() == 409) {
                assertThat(codigo(resposta)).isEqualTo("DOCUMENTO_JA_CADASTRADO");
                assertThat(resposta.getContentAsString())
                        .doesNotContainIgnoringCase("duplicate")
                        .doesNotContain("SQL");
            }
        }
        assertThat(status).containsOnly(201, 409);
        assertThat(status).filteredOn(s -> s == 201).hasSize(1);
        assertThat(jdbc.sql("select count(*) from associado where documento = :d")
                        .param("d", cpf)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- edição concorrente

    @Test
    void edicaoComVersaoAntigaNaoSobrescreve() throws Exception {
        Navegador admin = administrador();
        JsonNode criado = corpo(admin.post(
                "/api/v1/associados", Map.of("tipo", "PJ", "documento", cnpjNumericoValido(), "nome", "Base")));
        long id = criado.get("id").asLong();
        long versao = criado.get("versao").asLong();

        MockHttpServletResponse primeira =
                admin.put("/api/v1/associados/" + id, Map.of("versao", versao, "nome", "Edição A"));
        assertThat(primeira.getStatus()).isEqualTo(200);
        assertThat(corpo(primeira).get("versao").asLong()).isGreaterThan(versao);

        MockHttpServletResponse segunda =
                admin.put("/api/v1/associados/" + id, Map.of("versao", versao, "nome", "Edição B"));
        assertThat(segunda.getStatus()).isEqualTo(409);
        assertThat(codigo(segunda)).isEqualTo("CONFLITO_CONCORRENTE");
        assertThat(corpo(admin.get("/api/v1/associados/" + id)).get("nome").asString())
                .isEqualTo("Edição A");

        // Mesma versão em paralelo: no máximo uma vence.
        long atual = corpo(primeira).get("versao").asLong();
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String nome = "Paralela " + i;
            tarefas.add(() -> admin.put("/api/v1/associados/" + id, Map.of("versao", atual, "nome", nome)));
        }
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            status.add(f.get().getStatus());
        }
        assertThat(status).containsOnly(200, 409);
        assertThat(status).filteredOn(s -> s == 200).hasSize(1);
    }

    // ---------------------------------------------------------------- UCs

    @Test
    void variasUcsPorAssociadoComZerosAEsquerdaEUnicidadePorConcessionaria() {
        Navegador admin = administrador();
        long id = novoAssociado(admin);
        String numero = "000" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);

        MockHttpServletResponse primeira = admin.post(
                "/api/v1/associados/" + id + "/ucs",
                Map.of("concessionaria", "ENERGISA_MS", "numero", " " + numero + " ", "denominacao", "Sede"));
        assertThat(primeira.getStatus()).isEqualTo(201);
        assertThat(corpo(primeira).get("numero").asString()).isEqualTo(numero);
        assertThat(corpo(primeira).get("concessionariaNome").asString()).isEqualTo("Energisa MS");

        MockHttpServletResponse segunda = admin.post(
                "/api/v1/associados/" + id + "/ucs",
                Map.of("concessionaria", "ENERGISA_MS", "numero", numero + "1", "endereco", "Rod. MS-080, km 12"));
        assertThat(segunda.getStatus()).isEqualTo(201);

        MockHttpServletResponse lista = admin.get("/api/v1/associados/" + id + "/ucs");
        assertThat(corpo(lista)).hasSize(2);
        assertThat(corpo(admin.get("/api/v1/associados/" + id))
                        .get("quantidadeUcs")
                        .asLong())
                .isEqualTo(2);

        MockHttpServletResponse repetida = admin.post(
                "/api/v1/associados/" + id + "/ucs", Map.of("concessionaria", "ENERGISA_MS", "numero", numero));
        assertThat(repetida.getStatus()).isEqualTo(409);
        assertThat(codigo(repetida)).isEqualTo("UC_JA_CADASTRADA");

        // Outro associado também não pode cadastrar a mesma UC (sem transferência silenciosa).
        long outro = novoAssociado(admin);
        assertThat(codigo(admin.post(
                        "/api/v1/associados/" + outro + "/ucs",
                        Map.of("concessionaria", "ENERGISA_MS", "numero", numero))))
                .isEqualTo("UC_JA_CADASTRADA");
        // Sem os zeros à esquerda é outro número.
        assertThat(admin.post(
                                "/api/v1/associados/" + outro + "/ucs",
                                Map.of("concessionaria", "ENERGISA_MS", "numero", numero.substring(3)))
                        .getStatus())
                .isEqualTo(201);

        assertThat(codigo(admin.post(
                        "/api/v1/associados/" + id + "/ucs",
                        Map.of("concessionaria", "ENERGISA_MS", "numero", "12 34"))))
                .isEqualTo("DADOS_INVALIDOS");
        assertThat(admin.post("/api/v1/associados/" + id + "/ucs", Map.of("concessionaria", "OUTRA", "numero", "123"))
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void ucExigeVinculoValidoENaoTrocaDeTitular() {
        Navegador admin = administrador();
        long id = novoAssociado(admin);
        long outro = novoAssociado(admin);

        MockHttpServletResponse semAssociado =
                admin.post("/api/v1/associados/999999999/ucs", Map.of("concessionaria", "ENERGISA_MS", "numero", "1"));
        assertThat(semAssociado.getStatus()).isEqualTo(404);
        assertThat(codigo(semAssociado)).isEqualTo("ASSOCIADO_NAO_ENCONTRADO");

        JsonNode uc = corpo(admin.post(
                "/api/v1/associados/" + id + "/ucs", Map.of("concessionaria", "ENERGISA_MS", "numero", numeroUc())));
        long ucId = uc.get("id").asLong();

        assertThat(codigo(admin.get("/api/v1/associados/" + outro + "/ucs/" + ucId)))
                .isEqualTo("UC_NAO_ENCONTRADA");
        assertThat(admin.put(
                                "/api/v1/associados/" + outro + "/ucs/" + ucId,
                                Map.of("versao", uc.get("versao").asLong(), "denominacao", "x"))
                        .getStatus())
                .isEqualTo(404);
        // Titular, número e concessionária não são editáveis.
        assertThat(admin.put(
                                "/api/v1/associados/" + id + "/ucs/" + ucId,
                                Map.of("versao", uc.get("versao").asLong(), "associadoId", outro))
                        .getStatus())
                .isEqualTo(400);
        assertThat(admin.put(
                                "/api/v1/associados/" + id + "/ucs/" + ucId,
                                Map.of("versao", uc.get("versao").asLong(), "numero", "999"))
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void cadastrosSimultaneosDaMesmaUcAceitamApenasUm() throws Exception {
        Navegador admin = administrador();
        String numero = numeroUc();
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long associado = novoAssociado(admin);
            tarefas.add(() -> admin.post(
                    "/api/v1/associados/" + associado + "/ucs",
                    Map.of("concessionaria", "ENERGISA_MS", "numero", numero)));
        }
        List<Integer> status = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            MockHttpServletResponse resposta = f.get();
            status.add(resposta.getStatus());
            if (resposta.getStatus() == 409) {
                assertThat(codigo(resposta)).isEqualTo("UC_JA_CADASTRADA");
            }
        }
        assertThat(status).containsOnly(201, 409);
        assertThat(status).filteredOn(s -> s == 201).hasSize(1);
    }

    @Test
    void edicaoConcorrenteDeUc() {
        Navegador admin = administrador();
        long id = novoAssociado(admin);
        JsonNode uc = corpo(admin.post(
                "/api/v1/associados/" + id + "/ucs", Map.of("concessionaria", "ENERGISA_MS", "numero", numeroUc())));
        String url = "/api/v1/associados/" + id + "/ucs/" + uc.get("id").asLong();
        long versao = uc.get("versao").asLong();

        assertThat(admin.put(url, Map.of("versao", versao, "denominacao", "Silo 1"))
                        .getStatus())
                .isEqualTo(200);
        MockHttpServletResponse obsoleta = admin.put(url, Map.of("versao", versao, "denominacao", "Silo 2"));
        assertThat(obsoleta.getStatus()).isEqualTo(409);
        assertThat(codigo(obsoleta)).isEqualTo("CONFLITO_CONCORRENTE");
        assertThat(corpo(admin.get(url)).get("denominacao").asString()).isEqualTo("Silo 1");
    }

    // ---------------------------------------------------------------- listagem

    @Test
    void listaPaginadaComBuscaPorNomeEDocumentoSemDadosDesnecessarios() {
        Navegador admin = administrador();
        String marcador = "Busca" + UUID.randomUUID().toString().substring(0, 6);
        String cpf = cpfValido();
        admin.post("/api/v1/associados", Map.of("tipo", "PF", "documento", cpf, "nome", "Ana " + marcador));
        admin.post("/api/v1/associados", Map.of("tipo", "PF", "documento", cpfValido(), "nome", "Bruno " + marcador));
        admin.post(
                "/api/v1/associados",
                Map.of("tipo", "PJ", "documento", cnpjNumericoValido(), "nome", "Razão", "nomeFantasia", marcador));

        MockHttpServletResponse todos = admin.get("/api/v1/associados?busca=" + marcador.toLowerCase());
        assertThat(corpo(todos).get("totalItens").asLong()).isEqualTo(3);
        assertThat(corpo(todos).get("itens").get(0).get("nome").asString()).isEqualTo("Ana " + marcador);

        MockHttpServletResponse pagina =
                admin.get("/api/v1/associados?busca=" + marcador + "&tipo=PF&tamanho=1&pagina=1");
        assertThat(corpo(pagina).get("totalItens").asLong()).isEqualTo(2);
        assertThat(corpo(pagina).get("totalPaginas").asInt()).isEqualTo(2);
        assertThat(corpo(pagina).get("itens")).hasSize(1);
        assertThat(corpo(pagina).get("itens").get(0).get("nome").asString()).isEqualTo("Bruno " + marcador);

        String formatado = Documentos.formatar(TipoPessoa.PF, cpf);
        JsonNode porDocumento = corpo(admin.get("/api/v1/associados?busca=" + formatado));
        assertThat(porDocumento.get("totalItens").asLong()).isEqualTo(1);
        JsonNode item = porDocumento.get("itens").get(0);
        assertThat(item.get("documentoMascarado").asString()).isEqualTo(Documentos.mascarar(TipoPessoa.PF, cpf));
        assertThat(item.has("contatos")).isFalse();
        assertThat(item.has("documento")).isFalse();

        assertThat(corpo(admin.get("/api/v1/associados?busca=" + marcador + "%25"))
                        .get("totalItens")
                        .asLong())
                .as("curinga do LIKE é texto literal")
                .isZero();
        assertThat(admin.get("/api/v1/associados?tamanho=101").getStatus()).isEqualTo(400);
        assertThat(admin.get("/api/v1/associados?ordenacao=SENHA").getStatus()).isEqualTo(400);
        assertThat(admin.get("/api/v1/associados?ordenacao=CADASTRO_RECENTE").getStatus())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- autorização

    @Test
    void negaAcessoSemSessaoESemPermissao() {
        Navegador admin = administrador();
        long id = novoAssociado(admin);
        Navegador anonimo = new Navegador(mvc, json);
        Navegador semPerfil = logado(criarAtivo(emailUnico("sem-perfil")).getEmail());

        assertThat(anonimo.get("/api/v1/associados").getStatus()).isEqualTo(401);
        assertThat(anonimo.post("/api/v1/associados", Map.of()).getStatus()).isEqualTo(401);

        Map<String, Object> novo = Map.of("tipo", "PF", "documento", cpfValido(), "nome", "Negado");
        List<MockHttpServletResponse> respostas = List.of(
                semPerfil.get("/api/v1/associados"),
                semPerfil.get("/api/v1/associados/" + id),
                semPerfil.post("/api/v1/associados", novo),
                semPerfil.put("/api/v1/associados/" + id, Map.of("versao", 0, "nome", "x")),
                semPerfil.post("/api/v1/associados/" + id + "/contatos", Map.of("nome", "x", "email", "a@b.co")),
                semPerfil.get("/api/v1/associados/" + id + "/ucs"),
                semPerfil.post(
                        "/api/v1/associados/" + id + "/ucs", Map.of("concessionaria", "ENERGISA_MS", "numero", "1")),
                semPerfil.put("/api/v1/associados/" + id + "/ucs/1", Map.of("versao", 0)),
                semPerfil.get("/api/v1/associados/" + id + "/historico"),
                semPerfil.get("/api/v1/associados/" + id + "/rota-inexistente"));
        for (MockHttpServletResponse resposta : respostas) {
            assertThat(resposta.getStatus()).isEqualTo(403);
            assertThat(codigo(resposta)).isEqualTo("ACESSO_NEGADO");
        }
        assertThat(jdbc.sql("select count(*) from associado where nome = 'Negado'")
                        .query(Long.class)
                        .single())
                .isZero();

        // Permissões da sessão do administrador.
        JsonNode sessao = corpo(admin.get("/api/v1/auth/sessao"));
        List<String> permissoes = new ArrayList<>();
        sessao.get("permissoes").forEach(p -> permissoes.add(p.asString()));
        assertThat(permissoes)
                .contains(
                        "associados:consultar",
                        "associados:cadastrar",
                        "associados:editar",
                        "associados:consultar-historico",
                        "ucs:consultar",
                        "ucs:cadastrar",
                        "ucs:editar");
    }

    // ---------------------------------------------------------------- histórico

    @Test
    void historicoRegistraAutorCamposEMascaraDadosPessoais() throws Exception {
        String emailAutor = emailUnico("autor");
        Usuario autor = criarAtivo(emailAutor, Perfil.ADMINISTRADOR);
        Navegador navegadorAutor = logado(emailAutor);
        String cpf = cpfValido();

        Map<String, Object> novo = new HashMap<>(Map.of("tipo", "PF", "documento", cpf, "nome", "Histórico"));
        novo.put(
                "contatos",
                List.of(Map.of("nome", "Joana", "email", "joana.silva@exemplo.com", "telefone", "67998123344")));
        JsonNode criado = corpo(navegadorAutor.post("/api/v1/associados", novo));
        long id = criado.get("id").asLong();
        navegadorAutor.put(
                "/api/v1/associados/" + id,
                Map.of("versao", criado.get("versao").asLong(), "nome", "Histórico Editado"));
        navegadorAutor.post(
                "/api/v1/associados/" + id + "/ucs",
                Map.of("concessionaria", "ENERGISA_MS", "numero", numeroUc(), "endereco", "Rua Secreta, 10"));

        // O autor é desativado depois: a autoria continua legível.
        jdbc.sql("update usuario set situacao = 'DESATIVADO', desativado_em = now(6), versao_seguranca ="
                        + " versao_seguranca + 1 where id = :id")
                .param("id", autor.getId())
                .update();

        Navegador admin = administrador();
        MockHttpServletResponse resposta = admin.get("/api/v1/associados/" + id + "/historico");
        assertThat(resposta.getStatus()).isEqualTo(200);
        JsonNode pagina = corpo(resposta);
        assertThat(pagina.get("totalItens").asLong()).isEqualTo(4);
        List<String> acoes = new ArrayList<>();
        pagina.get("itens").forEach(r -> acoes.add(r.get("acao").asString()));
        assertThat(acoes)
                .containsExactly("UC_CADASTRADA", "ASSOCIADO_ALTERADO", "CONTATO_ADICIONADO", "ASSOCIADO_CADASTRADO");
        pagina.get("itens").forEach(r -> {
            assertThat(r.get("autor").get("id").asLong()).isEqualTo(autor.getId());
            assertThat(r.get("autor").get("nome").asString()).isEqualTo(autor.getNome());
        });

        JsonNode edicao = pagina.get("itens").get(1).get("alteracoes").get(0);
        assertThat(edicao.get("campo").asString()).isEqualTo("nome");
        assertThat(edicao.get("antes").asString()).isEqualTo("Histórico");
        assertThat(edicao.get("depois").asString()).isEqualTo("Histórico Editado");

        String texto = resposta.getContentAsString();
        assertThat(texto)
                .doesNotContain(cpf)
                .doesNotContain("joana.silva@exemplo.com")
                .doesNotContain("67998123344")
                .doesNotContain("Rua Secreta")
                .contains("j***@exemplo.com")
                .contains("****3344");

        JsonNode contatos = corpo(admin.get("/api/v1/associados/" + id + "/historico?categoria=CONTATOS"));
        assertThat(contatos.get("totalItens").asLong()).isEqualTo(1);
        assertThat(contatos.get("itens").get(0).get("referencia").asString()).isEqualTo("Joana");

        JsonNode paginado = corpo(admin.get("/api/v1/associados/" + id + "/historico?tamanho=3&pagina=1"));
        assertThat(paginado.get("itens")).hasSize(1);
        assertThat(admin.get("/api/v1/associados/" + id + "/historico?tamanho=51")
                        .getStatus())
                .isEqualTo(400);
        assertThat(admin.get("/api/v1/associados/999999999/historico").getStatus())
                .isEqualTo(404);
    }

    // ---------------------------------------------------------------- apoio

    private Navegador administrador() {
        String email = emailUnico("adm-associados");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        return logado(email);
    }

    private Usuario criarAtivo(String email, Perfil... perfis) {
        EnumSet<Perfil> conjunto = EnumSet.noneOf(Perfil.class);
        conjunto.addAll(List.of(perfis));
        return usuarios.saveAndFlush(
                Usuario.ativo("Pessoa " + email, email, passwordEncoder.encode(SENHA), conjunto, Instant.now()));
    }

    private Navegador logado(String email) {
        Navegador navegador = new Navegador(mvc, json);
        assertThat(navegador.entrar(email, SENHA).getStatus()).isEqualTo(200);
        return navegador;
    }

    private long novoAssociado(Navegador admin) {
        MockHttpServletResponse resposta = admin.post(
                "/api/v1/associados", Map.of("tipo", "PJ", "documento", cnpjNumericoValido(), "nome", "Empresa"));
        assertThat(resposta.getStatus()).isEqualTo(201);
        return corpo(resposta).get("id").asLong();
    }

    private static String emailUnico(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.emconta.local";
    }

    private static String numeroUc() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    /** CPF aleatório com dígitos verificadores corretos. */
    static String cpfValido() {
        while (true) {
            String base = String.format("%09d", ThreadLocalRandom.current().nextLong(1, 999_999_999L));
            for (int dv = 0; dv < 100; dv++) {
                String candidato = base + String.format("%02d", dv);
                if (Documentos.cpfValido(candidato)) {
                    return candidato;
                }
            }
        }
    }

    static String cnpjNumericoValido() {
        return cnpj("0123456789");
    }

    static String cnpjAlfanumericoValido() {
        return cnpj("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    }

    private static String cnpj(String alfabeto) {
        while (true) {
            StringBuilder base = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                base.append(alfabeto.charAt(ThreadLocalRandom.current().nextInt(alfabeto.length())));
            }
            for (int dv = 0; dv < 100; dv++) {
                String candidato = base + String.format("%02d", dv);
                if (Documentos.cnpjValido(candidato)) {
                    return candidato;
                }
            }
        }
    }

    private JsonNode corpo(MockHttpServletResponse resposta) {
        try {
            return json.readTree(resposta.getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String codigo(MockHttpServletResponse resposta) {
        return corpo(resposta).path("codigo").asString();
    }

    private static <T> List<Future<T>> emParalelo(List<Callable<T>> tarefas) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tarefas.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<T>> resultados = tarefas.stream()
                    .map(t -> executor.submit(() -> {
                        largada.await();
                        return t.call();
                    }))
                    .toList();
            largada.countDown();
            for (Future<T> resultado : resultados) {
                try {
                    resultado.get();
                } catch (Exception ignorada) {
                    // o teste inspeciona cada Future
                }
            }
            return resultados;
        } finally {
            executor.shutdownNow();
        }
    }
}
