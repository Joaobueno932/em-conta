package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AutenticacaoIntegrationTests extends AcessoIntegracao {

    @Test
    void loginValidoCriaSessaoHttpOnlyERetornaPermissoesSemDadosInternos() throws Exception {
        String email = emailUnico("admin");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador navegador = navegador();

        MockHttpServletResponse login = navegador.entrar(email.toUpperCase(), SENHA);

        assertThat(login.getStatus()).isEqualTo(200);
        assertThat(corpo(login).get("email").asString()).isEqualTo(email);
        assertThat(corpo(login).get("permissoes").toString()).contains("usuarios:consultar");
        assertThat(login.getContentAsString()).doesNotContain("senha", "hash", "versao", "token");
        assertThat(login.getCookie(Navegador.SESSAO).isHttpOnly()).isTrue();
        assertThat(login.getHeader("Set-Cookie")).isNotNull();

        MockHttpServletResponse sessao = navegador.get("/api/v1/auth/sessao");
        assertThat(sessao.getStatus()).isEqualTo(200);
        assertThat(corpo(sessao).get("email").asString()).isEqualTo(email);
    }

    @Test
    void credenciaisInvalidasEContasInaptasRecebemAMesmaRespostaGenerica() {
        String ativo = emailUnico("ativo");
        criarAtivo(ativo);
        String convidado = emailUnico("convidado");
        usuarios.saveAndFlush(Usuario.convidar("Convidado", convidado, Set.of(), Instant.now()));
        String desativado = emailUnico("desativado");
        Usuario usuario = criarAtivo(desativado);
        usuario.desativar(Instant.now());
        usuarios.saveAndFlush(usuario);

        List<MockHttpServletResponse> respostas = List.of(
                navegador().entrar(ativo, "senha errada, mas comprida"),
                navegador().entrar(emailUnico("inexistente"), SENHA),
                navegador().entrar(convidado, SENHA),
                navegador().entrar(desativado, SENHA),
                navegador().entrar("nao-e-email", SENHA));

        for (MockHttpServletResponse resposta : respostas) {
            assertThat(resposta.getStatus()).isEqualTo(401);
            assertThat(codigo(resposta)).isEqualTo("CREDENCIAIS_INVALIDAS");
            assertThat(corpo(resposta).get("detail").asString()).isEqualTo("E-mail ou senha inválidos.");
            assertThat(resposta.getCookie(Navegador.SESSAO)).isNull();
        }
    }

    @Test
    void loginTrocaOIdentificadorDeSessaoContraFixacao() {
        String emailAtacante = emailUnico("atacante");
        String emailVitima = emailUnico("vitima");
        criarAtivo(emailAtacante);
        criarAtivo(emailVitima, Perfil.ADMINISTRADOR);

        Navegador atacante = logado(emailAtacante);
        String sessaoFixada = atacante.cookie(Navegador.SESSAO);

        Navegador vitima = navegador();
        vitima.definirCookie(Navegador.SESSAO, sessaoFixada);
        assertThat(vitima.entrar(emailVitima, SENHA).getStatus()).isEqualTo(200);

        assertThat(vitima.cookie(Navegador.SESSAO)).isNotEqualTo(sessaoFixada);
        // A sessão conhecida pelo atacante não passou a ser da vítima.
        MockHttpServletResponse doAtacante = atacante.get("/api/v1/auth/sessao");
        assertThat(doAtacante.getStatus()).isEqualTo(401);
        assertThat(atacante.get("/api/v1/usuarios").getStatus()).isEqualTo(401);
    }

    @Test
    void csrfEExigidoERenovadoNoLoginENoLogout() {
        String email = emailUnico("csrf");
        criarAtivo(email);
        Navegador navegador = navegador();

        MockHttpServletResponse semToken =
                navegador.postSemCsrf("/api/v1/auth/login", Map.of("email", email, "senha", SENHA));
        assertThat(semToken.getStatus()).isEqualTo(403);
        assertThat(codigo(semToken)).isEqualTo("CSRF_INVALIDO");

        navegador.get("/api/v1/auth/csrf");
        String antesDoLogin = navegador.cookie(Navegador.XSRF);
        assertThat(antesDoLogin).isNotBlank();
        assertThat(navegador.entrar(email, SENHA).getStatus()).isEqualTo(200);
        String depoisDoLogin = navegador.cookie(Navegador.XSRF);
        assertThat(depoisDoLogin).isNotBlank().isNotEqualTo(antesDoLogin);

        // O token anterior ao login não vale mais.
        MockHttpServletResponse tokenAntigo = navegador.postComCsrf("/api/v1/auth/logout", null, antesDoLogin);
        assertThat(tokenAntigo.getStatus()).isEqualTo(403);

        String sessaoAntiga = navegador.cookie(Navegador.SESSAO);
        MockHttpServletResponse logout = navegador.post("/api/v1/auth/logout", null);
        assertThat(logout.getStatus()).isEqualTo(204);
        assertThat(navegador.cookie(Navegador.SESSAO)).isNull();
        assertThat(navegador.cookie(Navegador.XSRF)).isNull();

        MockHttpServletResponse aposLogout = navegador.get("/api/v1/auth/sessao");
        assertThat(aposLogout.getStatus()).isEqualTo(401);
        assertThat(codigo(aposLogout)).isEqualTo("NAO_AUTENTICADO");

        // Reutilizar o cookie da sessão encerrada não restaura o acesso.
        navegador.definirCookie(Navegador.SESSAO, sessaoAntiga);
        MockHttpServletResponse reuso = navegador.get("/api/v1/auth/sessao");
        assertThat(reuso.getStatus()).isEqualTo(401);
        assertThat(codigo(reuso)).isEqualTo("SESSAO_EXPIRADA");
    }

    @Test
    void diferenciaSemSessaoDeSemPermissao() {
        MockHttpServletResponse anonimo = navegador().get("/api/v1/usuarios");
        assertThat(anonimo.getStatus()).isEqualTo(401);
        assertThat(codigo(anonimo)).isEqualTo("NAO_AUTENTICADO");

        String email = emailUnico("sem-perfil");
        criarAtivo(email);
        Navegador semPerfil = logado(email);
        MockHttpServletResponse proibido = semPerfil.get("/api/v1/usuarios");
        assertThat(proibido.getStatus()).isEqualTo(403);
        assertThat(codigo(proibido)).isEqualTo("ACESSO_NEGADO");
        assertThat(semPerfil.get("/api/v1/auth/sessao").getStatus()).isEqualTo(200);
    }

    @Test
    void sessaoRemovidaDoBancoEReconhecidaComoExpirada() {
        String email = emailUnico("expira");
        Usuario usuario = criarAtivo(email);
        Navegador navegador = logado(email);

        jdbc.sql("delete from SPRING_SESSION where PRINCIPAL_NAME = :id")
                .param("id", String.valueOf(usuario.getId()))
                .update();

        MockHttpServletResponse resposta = navegador.get("/api/v1/auth/sessao");
        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(codigo(resposta)).isEqualTo("SESSAO_EXPIRADA");
    }

    @Test
    void bloqueiaAContaAposCincoFalhasMesmoComSenhaCorreta() {
        String email = emailUnico("forca-bruta");
        criarAtivo(email);
        Navegador navegador = navegador();

        for (int i = 0; i < 5; i++) {
            assertThat(navegador.entrar(email, "tentativa errada numero " + i).getStatus())
                    .isEqualTo(401);
        }
        MockHttpServletResponse bloqueado = navegador().entrar(email, SENHA);
        assertThat(bloqueado.getStatus()).isEqualTo(429);
        assertThat(codigo(bloqueado)).isEqualTo("MUITAS_TENTATIVAS");
        assertThat(Long.parseLong(bloqueado.getHeader("Retry-After"))).isPositive();

        // Outra conta, do mesmo IP, continua podendo entrar.
        String outra = emailUnico("outra");
        criarAtivo(outra);
        assertThat(navegador.entrar(outra, SENHA).getStatus()).isEqualTo(200);
    }

    @Test
    void tentativasSimultaneasNaoUltrapassamOLimiteDaConta() throws Exception {
        String email = emailUnico("paralelo");
        criarAtivo(email);
        List<Callable<Integer>> tarefas = IntStream.range(0, 15)
                .<Callable<Integer>>mapToObj(i -> () ->
                        navegador().entrar(email, "errada em paralelo " + i).getStatus())
                .toList();

        List<Future<Integer>> resultados = emParalelo(tarefas);

        long avaliadas = 0;
        for (Future<Integer> resultado : resultados) {
            int status = resultado.get();
            assertThat(status).isIn(401, 429);
            if (status == 401) avaliadas++;
        }
        assertThat(avaliadas).isLessThanOrEqualTo(5);
    }

    @Test
    void bloqueiaOIpAposMuitasFalhasEmContasDiferentes() {
        Navegador mesmoIp = navegador();
        MockHttpServletResponse ultima = null;
        for (int i = 0; i < 51; i++) {
            ultima = mesmoIp.entrar(emailUnico("varredura"), "senha errada qualquer " + i);
        }
        assertThat(ultima.getStatus()).isEqualTo(429);
    }

    @Test
    void recusaCamposNaoPrevistosNoCorpo() {
        Navegador navegador = navegador();
        MockHttpServletResponse resposta = navegador.post(
                "/api/v1/auth/login",
                Map.of("email", emailUnico("x"), "senha", SENHA, "permissoes", List.of("usuarios:consultar")));
        assertThat(resposta.getStatus()).isEqualTo(400);
    }
}
