package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.usuarios.Ator;
import br.com.emconta.acesso.usuarios.GestaoUsuarios;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.Usuario;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

class GestaoUsuariosIntegrationTests extends AcessoIntegracao {

    @Autowired
    GestaoUsuarios gestao;

    private Navegador administrador() {
        String email = emailUnico("adm-gestao");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        return logado(email);
    }

    @Test
    void listaPaginadaSemDadosInternos() throws Exception {
        Navegador admin = administrador();
        criarAtivo(emailUnico("listado"));

        MockHttpServletResponse pagina = admin.get("/api/v1/usuarios?pagina=0&tamanho=1");
        assertThat(pagina.getStatus()).isEqualTo(200);
        assertThat(corpo(pagina).get("itens")).hasSize(1);
        assertThat(corpo(pagina).get("totalItens").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(pagina.getContentAsString()).doesNotContain("senha", "hash", "versao");

        assertThat(admin.get("/api/v1/usuarios?tamanho=1000").getStatus()).isEqualTo(400);
        assertThat(admin.get("/api/v1/usuarios/999999999").getStatus()).isEqualTo(404);
        assertThat(corpo(admin.get("/api/v1/perfis")).get(0).get("codigo").asString())
                .isEqualTo("ADMINISTRADOR");
    }

    @Test
    void edicaoAlteraSomenteONome() {
        Navegador admin = administrador();
        Usuario alvo = criarAtivo(emailUnico("editado"));

        MockHttpServletResponse editado =
                admin.patch("/api/v1/usuarios/" + alvo.getId(), Map.of("nome", "  Nome Novo  "));
        assertThat(editado.getStatus()).isEqualTo(200);
        assertThat(corpo(editado).get("nome").asString()).isEqualTo("Nome Novo");

        MockHttpServletResponse comSituacao = admin.patch(
                "/api/v1/usuarios/" + alvo.getId(),
                Map.of("nome", "Outro", "situacao", "DESATIVADO", "perfis", List.of("ADMINISTRADOR")));
        assertThat(comSituacao.getStatus()).isEqualTo(400);
        Usuario recarregado = usuarios.findById(alvo.getId()).orElseThrow();
        assertThat(recarregado.getNome()).isEqualTo("Nome Novo");
        assertThat(recarregado.getPerfis()).isEmpty();

        Navegador comum = logado(alvo.getEmail());
        assertThat(comum.patch("/api/v1/usuarios/" + alvo.getId(), Map.of("nome", "Eu mesmo"))
                        .getStatus())
                .isEqualTo(403);
        assertThat(comum.put("/api/v1/usuarios/" + alvo.getId() + "/perfis", Map.of("perfis", List.of("ADMINISTRADOR")))
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void mudancaDePerfisValeParaASessaoJaAberta() {
        Navegador admin = administrador();
        Usuario alvo = criarAtivo(emailUnico("promovido"));
        Navegador sessaoDoAlvo = logado(alvo.getEmail());
        assertThat(sessaoDoAlvo.get("/api/v1/usuarios").getStatus()).isEqualTo(403);

        assertThat(admin.put("/api/v1/usuarios/" + alvo.getId() + "/perfis", Map.of("perfis", List.of("ADMINISTRADOR")))
                        .getStatus())
                .isEqualTo(200);
        assertThat(sessaoDoAlvo.get("/api/v1/usuarios").getStatus()).isEqualTo(200);

        assertThat(admin.put("/api/v1/usuarios/" + alvo.getId() + "/perfis", Map.of("perfis", List.of()))
                        .getStatus())
                .isEqualTo(200);
        assertThat(sessaoDoAlvo.get("/api/v1/usuarios").getStatus()).isEqualTo(403);
    }

    @Test
    void desativacaoEncerraAsSessoesEReativacaoDevolveOAcesso() {
        Navegador admin = administrador();
        Usuario alvo = criarAtivo(emailUnico("desativado"));
        Navegador sessaoDoAlvo = logado(alvo.getEmail());

        MockHttpServletResponse desativado = admin.post("/api/v1/usuarios/" + alvo.getId() + "/desativacao", null);
        assertThat(desativado.getStatus()).isEqualTo(200);
        assertThat(corpo(desativado).get("situacao").asString()).isEqualTo("DESATIVADO");

        MockHttpServletResponse sessao = sessaoDoAlvo.get("/api/v1/auth/sessao");
        assertThat(sessao.getStatus()).isEqualTo(401);
        assertThat(codigo(sessao)).isIn("SESSAO_EXPIRADA", "SESSAO_ENCERRADA");
        assertThat(navegador().entrar(alvo.getEmail(), SENHA).getStatus()).isEqualTo(401);

        MockHttpServletResponse reativado = admin.post("/api/v1/usuarios/" + alvo.getId() + "/reativacao", null);
        assertThat(corpo(reativado).get("situacao").asString()).isEqualTo("ATIVO");
        assertThat(navegador().entrar(alvo.getEmail(), SENHA).getStatus()).isEqualTo(200);
    }

    @Test
    void sessaoComVersaoDeSegurancaAntigaERecusadaMesmoSemRemocaoDaSessao() {
        Usuario alvo = criarAtivo(emailUnico("versao"));
        Navegador sessaoDoAlvo = logado(alvo.getEmail());

        // Revogação só pela versão de segurança, sem apagar as linhas do Spring Session.
        jdbc.sql("update usuario set versao_seguranca = versao_seguranca + 1 where id = :id")
                .param("id", alvo.getId())
                .update();

        MockHttpServletResponse resposta = sessaoDoAlvo.get("/api/v1/auth/sessao");
        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(codigo(resposta)).isEqualTo("SESSAO_ENCERRADA");
    }

    @Test
    void naoConcedePerfilCujasPermissoesOAtorNaoTem() {
        Usuario alvo = criarAtivo(emailUnico("escalada"));
        Usuario ator = criarAtivo(emailUnico("ator-limitado"));
        Ator limitado = new Ator(ator.getId(), Set.of(Permissao.USUARIOS_ATRIBUIR_PERFIS));

        assertThatThrownBy(() -> gestao.definirPerfis(limitado, alvo.getId(), Set.of(Perfil.ADMINISTRADOR), null))
                .isInstanceOf(FalhaAcesso.class)
                .extracting(e -> ((FalhaAcesso) e).codigo())
                .isEqualTo("PERFIL_NAO_CONCEDIVEL");
        assertThatThrownBy(() -> gestao.convidar(
                        new Ator(ator.getId(), Set.of(Permissao.USUARIOS_CONVIDAR)),
                        "Novo",
                        emailUnico("novo"),
                        Set.of(Perfil.ADMINISTRADOR),
                        null))
                .isInstanceOf(FalhaAcesso.class);
    }

    @Test
    void auditoriaRegistraOperacoesSemSegredos() {
        Navegador admin = administrador();
        String email = emailUnico("auditado");
        long id = corpo(admin.post("/api/v1/usuarios", Map.of("nome", "Auditado", "email", email)))
                .at("/usuario/id")
                .asLong();

        List<String> eventos = jdbc.sql("select tipo from auditoria_seguranca where alvo_usuario_id = :id")
                .param("id", id)
                .query(String.class)
                .list();
        assertThat(eventos).contains("CONVITE_CRIADO");
        String token = mailpit().aguardarToken(email, "/convite", 1);
        Integer vazamentos = jdbc.sql(
                        "select count(*) from auditoria_seguranca where detalhes like :t or detalhes like :s")
                .param("t", "%" + token + "%")
                .param("s", "%senha%")
                .query(Integer.class)
                .single();
        assertThat(vazamentos).isZero();
        Integer tokensEmClaro = jdbc.sql("select count(*) from token_acesso where hash_token = :t")
                .param("t", token)
                .query(Integer.class)
                .single();
        assertThat(tokensEmClaro).isZero();
    }
}
