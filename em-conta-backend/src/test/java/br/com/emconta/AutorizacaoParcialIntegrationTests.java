package br.com.emconta;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Permissões parciais por chamada direta à API, com sessão real e as regras de rota da aplicação.
 * Os usuários de teste recebem conjuntos controlados ({@link PermissoesControladas}); nenhum perfil
 * de produção é criado.
 */
class AutorizacaoParcialIntegrationTests extends ApiIntegracao {

    private static final String HOJE =
            LocalDate.now(ZoneId.of("America/Campo_Grande")).toString();

    private long associado;
    private long uc;
    private long usina;
    private JsonNode vinculo;

    @BeforeEach
    void dados() {
        Navegador admin = administrador();
        long[] titular = associadoComUc(admin);
        associado = titular[0];
        uc = titular[1];
        usina = corpo(admin.post(
                        "/api/v1/usinas",
                        Map.of("nome", "Parcial " + UUID.randomUUID(), "confirmarNomeRepetido", true)))
                .get("id")
                .asLong();
        vinculo = corpo(admin.post("/api/v1/usinas/" + usina + "/vinculos", Map.of("ucId", uc, "inicio", HOJE)));
    }

    @Test
    void consultaDeUsinasSemEscritaVinculoOuHistorico() {
        Navegador leitor = comPermissoes(Permissao.USINAS_CONSULTAR);

        assertThat(leitor.get("/api/v1/usinas").getStatus()).isEqualTo(200);
        assertThat(leitor.get("/api/v1/usinas/" + usina).getStatus()).isEqualTo(200);
        assertThat(leitor.get("/api/v1/fazendas").getStatus()).isEqualTo(200);

        negado(leitor.post("/api/v1/usinas", Map.of("nome", "Negada")));
        negado(leitor.post("/api/v1/fazendas", Map.of("nome", "Negada")));
        negado(leitor.put("/api/v1/usinas/" + usina, Map.of("versao", 0, "nome", "Editada")));
        negado(leitor.get("/api/v1/usinas/" + usina + "/vinculos"));
        negado(leitor.get("/api/v1/associados/" + associado + "/vinculos-usina"));
        negado(leitor.post("/api/v1/usinas/" + usina + "/vinculos", Map.of("ucId", uc, "inicio", HOJE)));
        negado(leitor.get("/api/v1/vinculos-usina/ucs-candidatas?busca=Titular"));
        negado(leitor.get("/api/v1/usinas/" + usina + "/historico"));
        negado(leitor.get("/api/v1/associados/" + associado));
        assertThat(jdbc.sql("select count(*) from usina where nome = 'Negada'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void cadastroSemEdicaoEEdicaoSemCadastro() {
        Navegador cadastra = comPermissoes(Permissao.USINAS_CADASTRAR);
        assertThat(cadastra.post("/api/v1/usinas", Map.of("nome", "Cadastrada " + UUID.randomUUID()))
                        .getStatus())
                .isEqualTo(201);
        negado(cadastra.put("/api/v1/usinas/" + usina, Map.of("versao", 0, "nome", "x")));
        negado(cadastra.get("/api/v1/usinas/" + usina));

        Navegador edita = comPermissoes(Permissao.USINAS_EDITAR);
        negado(edita.post("/api/v1/usinas", Map.of("nome", "Negada")));
        MockHttpServletResponse editada = edita.put(
                "/api/v1/usinas/" + usina,
                Map.of("versao", 0, "nome", "Editada " + UUID.randomUUID(), "confirmarNomeRepetido", true));
        assertThat(editada.getStatus()).isEqualTo(200);
    }

    @Test
    void vinculosPorPermissaoDeConsultaCriacaoEEncerramento() {
        Navegador consulta = comPermissoes(Permissao.VINCULOS_USINA_CONSULTAR);
        assertThat(consulta.get("/api/v1/usinas/" + usina + "/vinculos").getStatus())
                .isEqualTo(200);
        assertThat(consulta.get("/api/v1/associados/" + associado + "/vinculos-usina")
                        .getStatus())
                .isEqualTo(200);
        String encerramento =
                "/api/v1/usinas/" + usina + "/vinculos/" + vinculo.get("id").asLong() + "/encerramento";
        Map<String, Object> corpoEncerramento =
                Map.of("versao", vinculo.get("versao").asLong(), "fim", HOJE, "motivo", "Teste de permissão");
        negado(consulta.post(encerramento, corpoEncerramento));
        negado(consulta.post("/api/v1/usinas/" + usina + "/vinculos", Map.of("ucId", uc, "inicio", HOJE)));

        Navegador cria = comPermissoes(Permissao.VINCULOS_USINA_CRIAR);
        assertThat(cria.get("/api/v1/vinculos-usina/ucs-candidatas?busca=Titular")
                        .getStatus())
                .isEqualTo(200);
        negado(cria.post(encerramento, corpoEncerramento));
        negado(cria.get("/api/v1/usinas/" + usina + "/vinculos"));

        Navegador encerra = comPermissoes(Permissao.VINCULOS_USINA_ENCERRAR);
        assertThat(encerra.post(encerramento, corpoEncerramento).getStatus()).isEqualTo(200);
    }

    @Test
    void historicoSoComPermissaoPropria() {
        Navegador semHistorico = comPermissoes(Permissao.USINAS_CONSULTAR, Permissao.VINCULOS_USINA_CONSULTAR);
        negado(semHistorico.get("/api/v1/usinas/" + usina + "/historico"));

        Navegador comHistorico = comPermissoes(Permissao.USINAS_CONSULTAR_HISTORICO);
        assertThat(comHistorico.get("/api/v1/usinas/" + usina + "/historico").getStatus())
                .isEqualTo(200);
        negado(comHistorico.get("/api/v1/usinas/" + usina));
    }

    @Test
    void permissoesParciaisDeAssociadosEUcs() {
        Navegador leitor = comPermissoes(Permissao.ASSOCIADOS_CONSULTAR);
        assertThat(leitor.get("/api/v1/associados").getStatus()).isEqualTo(200);
        assertThat(leitor.get("/api/v1/associados/" + associado).getStatus()).isEqualTo(200);
        negado(leitor.post(
                "/api/v1/associados", Map.of("tipo", "PJ", "documento", cnpjValido(), "nome", "Negado parcial")));
        negado(leitor.put("/api/v1/associados/" + associado, Map.of("versao", 0, "nome", "x")));
        negado(leitor.post("/api/v1/associados/" + associado + "/contatos", Map.of("nome", "x", "email", "a@b.co")));
        negado(leitor.get("/api/v1/associados/" + associado + "/ucs"));
        negado(leitor.get("/api/v1/associados/" + associado + "/historico"));

        Navegador editor = comPermissoes(Permissao.ASSOCIADOS_EDITAR);
        assertThat(editor.post(
                                "/api/v1/associados/" + associado + "/contatos",
                                Map.of("nome", "Contato parcial", "email", "parcial@exemplo.com.br"))
                        .getStatus())
                .isEqualTo(201);
        negado(editor.get("/api/v1/associados/" + associado));

        Navegador ucs = comPermissoes(Permissao.UCS_CONSULTAR);
        assertThat(ucs.get("/api/v1/associados/" + associado + "/ucs").getStatus())
                .isEqualTo(200);
        assertThat(ucs.get("/api/v1/associados/" + associado + "/ucs/" + uc).getStatus())
                .isEqualTo(200);
        negado(ucs.post(
                "/api/v1/associados/" + associado + "/ucs", Map.of("concessionaria", "ENERGISA_MS", "numero", "1")));
        negado(ucs.put("/api/v1/associados/" + associado + "/ucs/" + uc, Map.of("versao", 0, "denominacao", "x")));

        Navegador cadastraUc = comPermissoes(Permissao.UCS_CADASTRAR);
        assertThat(cadastraUc
                        .post(
                                "/api/v1/associados/" + associado + "/ucs",
                                Map.of("concessionaria", "ENERGISA_MS", "numero", numeroUc()))
                        .getStatus())
                .isEqualTo(201);
        negado(cadastraUc.get("/api/v1/associados/" + associado + "/ucs"));

        Navegador historico = comPermissoes(Permissao.ASSOCIADOS_CONSULTAR_HISTORICO);
        assertThat(historico
                        .get("/api/v1/associados/" + associado + "/historico")
                        .getStatus())
                .isEqualTo(200);

        // A sessão expõe exatamente as permissões concedidas.
        JsonNode sessao = corpo(historico.get("/api/v1/auth/sessao"));
        assertThat(sessao.get("permissoes")).hasSize(1);
        assertThat(sessao.get("permissoes").get(0).asString()).isEqualTo("associados:consultar-historico");
        assertThat(jdbc.sql("select count(*) from associado where nome = 'Negado parcial'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private void negado(MockHttpServletResponse resposta) {
        assertThat(resposta.getStatus()).isEqualTo(403);
        assertThat(codigo(resposta)).isEqualTo("ACESSO_NEGADO");
    }
}
