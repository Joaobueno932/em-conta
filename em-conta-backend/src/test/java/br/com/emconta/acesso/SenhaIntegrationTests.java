package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.usuarios.Ator;
import br.com.emconta.acesso.usuarios.GestaoUsuarios;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.Usuario;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

class SenhaIntegrationTests extends AcessoIntegracao {

    @Autowired
    GestaoUsuarios gestao;

    private MockHttpServletResponse solicitar(String email) {
        return navegador().post("/api/v1/auth/senha/recuperacao", Map.of("email", email));
    }

    @Test
    void recuperacaoNaoRevelaSeAContaExiste() {
        String ativo = emailUnico("recupera");
        criarAtivo(ativo);
        String convidado = emailUnico("recupera-convidado");
        usuarios.saveAndFlush(Usuario.convidar("Convidado", convidado, Set.of(), Instant.now()));
        String inexistente = emailUnico("ninguem");

        MockHttpServletResponse paraAtivo = solicitar(ativo);
        MockHttpServletResponse paraConvidado = solicitar(convidado);
        MockHttpServletResponse paraInexistente = solicitar(inexistente);
        MockHttpServletResponse invalido = solicitar("isto nao e email");

        for (MockHttpServletResponse resposta :
                new MockHttpServletResponse[] {paraConvidado, paraInexistente, invalido}) {
            assertThat(resposta.getStatus()).isEqualTo(paraAtivo.getStatus()).isEqualTo(202);
            assertThat(resposta.getContentAsByteArray()).isEqualTo(paraAtivo.getContentAsByteArray());
        }

        assertThat(mailpit().aguardarToken(ativo, "/redefinir-senha", 1)).isNotBlank();
        assertThat(mailpit().mensagens(ativo).getFirst()).contains("30 minutos");
        assertThat(mailpit().mensagens(convidado)).isEmpty();
        assertThat(mailpit().mensagens(inexistente)).isEmpty();
    }

    @Test
    void redefinicaoPeloLinkEncerraTodasAsSessoesEOTokenNaoReutiliza() {
        String email = emailUnico("redefine");
        criarAtivo(email);
        Navegador computador = logado(email);
        Navegador celular = logado(email);

        assertThat(solicitar(email).getStatus()).isEqualTo(202);
        String token = mailpit().aguardarToken(email, "/redefinir-senha", 1);

        MockHttpServletResponse redefinicao =
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", token, "novaSenha", NOVA_SENHA));
        assertThat(redefinicao.getStatus()).isEqualTo(204);

        assertThat(computador.get("/api/v1/auth/sessao").getStatus()).isEqualTo(401);
        assertThat(celular.get("/api/v1/auth/sessao").getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, SENHA).getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(200);

        MockHttpServletResponse reuso =
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", token, "novaSenha", SENHA));
        assertThat(reuso.getStatus()).isEqualTo(400);
        assertThat(codigo(reuso)).isEqualTo("TOKEN_INVALIDO");
    }

    @Test
    void contaDesativadaNaoRecuperaAcessoPeloFluxoDeSenha() {
        String email = emailUnico("desativada-senha");
        Usuario usuario = criarAtivo(email);
        assertThat(solicitar(email).getStatus()).isEqualTo(202);
        String token = mailpit().aguardarToken(email, "/redefinir-senha", 1);

        Usuario admin = criarAtivo(emailUnico("adm-senha"), Perfil.ADMINISTRADOR);
        gestao.desativar(new Ator(admin.getId(), EnumSet.allOf(Permissao.class)), usuario.getId(), "10.252.0.1");

        MockHttpServletResponse redefinicao =
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", token, "novaSenha", NOVA_SENHA));
        assertThat(redefinicao.getStatus()).isEqualTo(400);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(401);

        assertThat(solicitar(email).getStatus()).isEqualTo(202);
        Mailpit.dormir();
        Mailpit.dormir();
        assertThat(mailpit().mensagens(email)).hasSize(1);
    }

    @Test
    void trocaAutenticadaMantemASessaoAtualEEncerraAsDemais() {
        String email = emailUnico("troca");
        criarAtivo(email);
        Navegador atual = logado(email);
        Navegador outro = logado(email);
        String sessaoAnterior = atual.cookie(Navegador.SESSAO);

        MockHttpServletResponse errada = atual.post(
                "/api/v1/auth/senha/alteracao", Map.of("senhaAtual", "nao e a senha atual", "novaSenha", NOVA_SENHA));
        assertThat(errada.getStatus()).isEqualTo(400);
        assertThat(codigo(errada)).isEqualTo("SENHA_ATUAL_INCORRETA");

        MockHttpServletResponse troca =
                atual.post("/api/v1/auth/senha/alteracao", Map.of("senhaAtual", SENHA, "novaSenha", NOVA_SENHA));
        assertThat(troca.getStatus()).isEqualTo(204);

        assertThat(atual.cookie(Navegador.SESSAO)).isNotEqualTo(sessaoAnterior);
        assertThat(atual.get("/api/v1/auth/sessao").getStatus()).isEqualTo(200);
        assertThat(outro.get("/api/v1/auth/sessao").getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, SENHA).getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(200);
    }

    @Test
    void politicaDeSenhaPreservaEspacosEAplicaLimites() {
        String email = emailUnico("politica");
        criarAtivo(email);
        Navegador navegador = logado(email);

        MockHttpServletResponse curta = navegador.post(
                "/api/v1/auth/senha/alteracao", Map.of("senhaAtual", SENHA, "novaSenha", "14 caracteres!"));
        assertThat(curta.getStatus()).isEqualTo(400);
        assertThat(codigo(curta)).isEqualTo("SENHA_FORA_DA_POLITICA");

        MockHttpServletResponse longa = navegador.post(
                "/api/v1/auth/senha/alteracao", Map.of("senhaAtual", SENHA, "novaSenha", "x".repeat(129)));
        assertThat(longa.getStatus()).isEqualTo(400);

        String comEspacos = "  frase com espaços nas pontas  ";
        assertThat(navegador
                        .post("/api/v1/auth/senha/alteracao", Map.of("senhaAtual", SENHA, "novaSenha", comEspacos))
                        .getStatus())
                .isEqualTo(204);
        assertThat(navegador().entrar(email, comEspacos.strip()).getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, comEspacos).getStatus()).isEqualTo(200);
    }
}
