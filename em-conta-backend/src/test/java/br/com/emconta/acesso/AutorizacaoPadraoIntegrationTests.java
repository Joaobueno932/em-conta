package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.usuarios.Perfil;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Negação por padrão: uma rota sem regra explícita na SecurityConfiguration não é acessível nem para
 * o administrador. O controller abaixo existe só neste contexto de teste.
 */
@Import(AutorizacaoPadraoIntegrationTests.RotaSemRegra.class)
class AutorizacaoPadraoIntegrationTests extends AcessoIntegracao {

    @RestController
    static class RotaSemRegra {

        @GetMapping("/api/v1/rota-de-teste-sem-regra")
        Map<String, String> ler() {
            return Map.of("ok", "sim");
        }

        @PostMapping("/api/v1/rota-de-teste-sem-regra")
        Map<String, String> gravar() {
            return Map.of("ok", "sim");
        }
    }

    private Navegador administrador() {
        String email = emailUnico("adm-padrao");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        return logado(email);
    }

    @Test
    void rotaSemRegraENegadaMesmoParaAdministrador() {
        Navegador admin = administrador();
        MockHttpServletResponse leitura = admin.get("/api/v1/rota-de-teste-sem-regra");
        assertThat(leitura.getStatus()).isEqualTo(403);
        assertThat(codigo(leitura)).isEqualTo("ACESSO_NEGADO");
        assertThat(admin.post("/api/v1/rota-de-teste-sem-regra", Map.of()).getStatus())
                .isEqualTo(403);
        assertThat(navegador().get("/api/v1/rota-de-teste-sem-regra").getStatus())
                .as("anônimo")
                .isEqualTo(401);
        // Qualquer caminho desconhecido também é negado (não cai numa regra genérica de autenticado).
        assertThat(admin.get("/api/v1/qualquer-coisa").getStatus()).isEqualTo(403);
    }

    @Test
    void rotasConhecidasSeguemAsPermissoesEOsMetodosDeclarados() {
        Navegador admin = administrador();
        assertThat(admin.get("/api/v1/usuarios").getStatus()).isEqualTo(200);
        assertThat(admin.get("/api/v1/auth/sessao").getStatus()).isEqualTo(200);
        assertThat(admin.get("/api/v1/associados").getStatus()).isEqualTo(200);
        assertThat(admin.get("/api/v1/usinas").getStatus()).isEqualTo(200);

        // Métodos não declarados nas regras não ficam liberados por nenhuma regra genérica.
        assertThat(admin.put("/api/v1/usuarios", Map.of()).getStatus()).isEqualTo(403);
        assertThat(admin.patch("/api/v1/associados/1", Map.of()).getStatus()).isEqualTo(403);
        assertThat(admin.get("/api/v1/auth/login").getStatus()).isEqualTo(403);
        assertThat(admin.get("/api/v1/auth/senha/alteracao").getStatus()).isEqualTo(403);

        String email = emailUnico("sem-perfil");
        criarAtivo(email);
        Navegador comum = logado(email);
        assertThat(comum.get("/api/v1/auth/sessao").getStatus())
                .as("só exige sessão")
                .isEqualTo(200);
        assertThat(comum.get("/api/v1/usuarios").getStatus()).isEqualTo(403);
        assertThat(comum.get("/api/v1/usinas").getStatus()).isEqualTo(403);
    }

    @Test
    void rotasPublicasContinuamAcessiveisSemSessao() {
        Navegador anonimo = navegador();
        assertThat(anonimo.get("/api/v1/system/status").getStatus()).isEqualTo(200);
        assertThat(anonimo.get("/actuator/health").getStatus()).isEqualTo(200);
        assertThat(anonimo.get("/api/v1/auth/csrf").getStatus()).isEqualTo(200);
        assertThat(anonimo.post("/api/v1/auth/login", Map.of("email", "x@y.zz", "senha", "errada"))
                        .getStatus())
                .isEqualTo(401);
        assertThat(codigo(anonimo.post(
                        "/api/v1/auth/tokens/validacao", Map.of("token", "A".repeat(43), "finalidade", "CONVITE"))))
                .isEqualTo("TOKEN_INVALIDO");
        assertThat(anonimo.post("/api/v1/auth/senha/recuperacao", Map.of("email", "x@y.zz"))
                        .getStatus())
                .isEqualTo(202);
        assertThat(anonimo.post("/api/v1/auth/logout", null).getStatus()).isEqualTo(204);
        assertThat(anonimo.get("/v3/api-docs").getStatus())
                .as("OpenAPI fechado fora do local")
                .isEqualTo(401);
        // CSRF continua exigido nos POST públicos.
        assertThat(navegador()
                        .postSemCsrf("/api/v1/auth/login", Map.of("email", "x@y.zz", "senha", "x"))
                        .getStatus())
                .isEqualTo(403);
    }
}
