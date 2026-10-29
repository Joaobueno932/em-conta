package br.com.emconta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class ApiFoundationIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void statusRespondeComContratoTipadoEBancoDisponivel() throws Exception {
        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.application").value("em-conta-backend"))
                .andExpect(jsonPath("$.version").isString())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.serverTime").value(endsWith("Z")))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void healthCheckLiberadoSemDetalhes() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void endpointNaoLiberadoExigeAutenticacao() throws Exception {
        mvc.perform(get("/api/v1/associados"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/associados"));
    }

    @Test
    void openApiNaoFicaPublicoForaDoProfileLocal() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void requisicaoMutavelSemTokenCsrfERejeitada() throws Exception {
        mvc.perform(post("/api/v1/system/status"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @WithMockUser
    void requisicaoMutavelComTokenCsrfPassaPelaSeguranca() throws Exception {
        // Fluxo do SPA: o cookie XSRF-TOKEN emitido pela API volta no cabeçalho X-XSRF-TOKEN.
        Cookie xsrf = mvc.perform(get("/api/v1/system/status"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("cookie XSRF-TOKEN emitido").isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();

        // Método sem regra explícita é negado (negação por padrão), mesmo autenticado e com CSRF válido.
        mvc.perform(post("/api/v1/system/status").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        // Com token válido, a requisição passa pela segurança e chega ao controller (logout: 204).
        mvc.perform(post("/api/v1/auth/logout").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void migrationsAplicadasComUtf8mb4ETabelasDeSessao() {
        Integer falhas = jdbc.sql("select count(*) from flyway_schema_history where success = 0")
                .query(Integer.class)
                .single();
        assertThat(falhas).isZero();
        assertThat(jdbc.sql("select version from flyway_schema_history where version is not null")
                        .query(String.class)
                        .list())
                .contains("1", "2", "3", "4", "5");
        assertThat(jdbc.sql("select table_name from information_schema.tables where table_schema = database()")
                        .query(String.class)
                        .list())
                .contains(
                        "SPRING_SESSION",
                        "SPRING_SESSION_ATTRIBUTES",
                        "associado",
                        "associado_contato",
                        "unidade_consumidora",
                        "historico_cadastro",
                        "fazenda",
                        "usina",
                        "vinculo_uc_usina",
                        "historico_usinas",
                        "sessao_renovada");
        assertThat(jdbc.sql("select default_character_set_name from information_schema.schemata"
                                + " where schema_name = database()")
                        .query(String.class)
                        .single())
                .isEqualTo("utf8mb4");
    }
}
