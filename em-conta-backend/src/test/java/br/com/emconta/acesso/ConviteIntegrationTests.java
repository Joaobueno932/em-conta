package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.tokens.FinalidadeToken;
import br.com.emconta.acesso.tokens.TokensAcesso;
import br.com.emconta.acesso.usuarios.Ator;
import br.com.emconta.acesso.usuarios.Credenciais;
import br.com.emconta.acesso.usuarios.GestaoUsuarios;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.SituacaoConta;
import br.com.emconta.acesso.usuarios.Usuario;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

class ConviteIntegrationTests extends AcessoIntegracao {

    @Autowired
    GestaoUsuarios gestao;

    @Autowired
    Credenciais credenciais;

    @Autowired
    TokensAcesso tokens;

    private Navegador administrador() {
        String email = emailUnico("adm-convite");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        return logado(email);
    }

    @Test
    void jornadaCompletaDeConvite() {
        Navegador admin = administrador();
        String email = emailUnico("convidada");

        MockHttpServletResponse criado =
                admin.post("/api/v1/usuarios", Map.of("nome", "Maria Convidada", "email", email));
        assertThat(criado.getStatus()).isEqualTo(201);
        assertThat(corpo(criado).get("conviteEnviado").asBoolean()).isTrue();
        assertThat(corpo(criado).at("/usuario/situacao").asString()).isEqualTo("CONVIDADO");

        String token = mailpit().aguardarToken(email, "/convite", 1);
        assertThat(mailpit().mensagens(email).getFirst()).contains("Em Conta", "3 dias", "Maria Convidada");

        Navegador convidada = navegador();
        assertThat(convidada.entrar(email, SENHA).getStatus()).isEqualTo(401);

        MockHttpServletResponse validacao =
                convidada.post("/api/v1/auth/tokens/validacao", Map.of("token", token, "finalidade", "CONVITE"));
        assertThat(validacao.getStatus()).isEqualTo(200);
        assertThat(corpo(validacao).get("nome").asString()).isEqualTo("Maria Convidada");

        MockHttpServletResponse fraca =
                convidada.post("/api/v1/auth/convites/aceitacao", Map.of("token", token, "senha", "curta"));
        assertThat(fraca.getStatus()).isEqualTo(400);
        assertThat(codigo(fraca)).isEqualTo("SENHA_FORA_DA_POLITICA");

        // A senha recusada não consumiu o token.
        MockHttpServletResponse aceite =
                convidada.post("/api/v1/auth/convites/aceitacao", Map.of("token", token, "senha", SENHA));
        assertThat(aceite.getStatus()).isEqualTo(204);
        assertThat(convidada.entrar(email, SENHA).getStatus()).isEqualTo(200);

        MockHttpServletResponse reuso =
                navegador().post("/api/v1/auth/convites/aceitacao", Map.of("token", token, "senha", NOVA_SENHA));
        assertThat(reuso.getStatus()).isEqualTo(400);
        assertThat(codigo(reuso)).isEqualTo("TOKEN_INVALIDO");
    }

    @Test
    void reenvioInvalidaOLinkAnteriorESoValeParaConvitePendente() {
        Navegador admin = administrador();
        String email = emailUnico("reenvio");
        long id = corpo(admin.post("/api/v1/usuarios", Map.of("nome", "Reenvio", "email", email)))
                .at("/usuario/id")
                .asLong();
        String primeiro = mailpit().aguardarToken(email, "/convite", 1);

        MockHttpServletResponse reenvio = admin.post("/api/v1/usuarios/" + id + "/convite", null);
        assertThat(reenvio.getStatus()).isEqualTo(202);
        assertThat(corpo(reenvio).get("conviteEnviado").asBoolean()).isTrue();
        String segundo = mailpit().aguardarToken(email, "/convite", 2);
        assertThat(segundo).isNotEqualTo(primeiro);

        assertThat(navegador()
                        .post("/api/v1/auth/convites/aceitacao", Map.of("token", primeiro, "senha", SENHA))
                        .getStatus())
                .isEqualTo(400);
        assertThat(navegador()
                        .post("/api/v1/auth/convites/aceitacao", Map.of("token", segundo, "senha", SENHA))
                        .getStatus())
                .isEqualTo(204);

        MockHttpServletResponse ativo = admin.post("/api/v1/usuarios/" + id + "/convite", null);
        assertThat(ativo.getStatus()).isEqualTo(409);
        assertThat(codigo(ativo)).isEqualTo("CONVITE_NAO_APLICAVEL");
    }

    @Test
    void tokensExpiradosMalformadosOuDeOutraFinalidadeSaoRecusados() {
        Usuario convidado =
                usuarios.saveAndFlush(Usuario.convidar("Expirado", emailUnico("expirado"), Set.of(), Instant.now()));
        String expirado = tokens.emitir(convidado.getId(), FinalidadeToken.CONVITE, Duration.ofSeconds(-1))
                .valor();
        Navegador navegador = navegador();

        for (String token : List.of(expirado, "curto", "A".repeat(43), "<script>")) {
            MockHttpServletResponse resposta =
                    navegador.post("/api/v1/auth/tokens/validacao", Map.of("token", token, "finalidade", "CONVITE"));
            assertThat(resposta.getStatus()).isEqualTo(400);
            assertThat(codigo(resposta)).isEqualTo("TOKEN_INVALIDO");
        }

        String convite = tokens.emitir(convidado.getId(), FinalidadeToken.CONVITE, Duration.ofHours(1))
                .valor();
        MockHttpServletResponse finalidadeErrada =
                navegador.post("/api/v1/auth/senha/redefinicao", Map.of("token", convite, "novaSenha", NOVA_SENHA));
        assertThat(finalidadeErrada.getStatus()).isEqualTo(400);
        assertThat(usuarios.findById(convidado.getId()).orElseThrow().getSituacao())
                .isEqualTo(SituacaoConta.CONVIDADO);
    }

    @Test
    void consumoConcorrenteDoMesmoTokenTemUmUnicoSucesso() throws Exception {
        Usuario convidado = usuarios.saveAndFlush(
                Usuario.convidar("Concorrente", emailUnico("concorrente"), Set.of(), Instant.now()));
        String token = tokens.emitir(convidado.getId(), FinalidadeToken.CONVITE, Duration.ofHours(1))
                .valor();

        List<Callable<Boolean>> tarefas = IntStream.range(0, 6)
                .<Callable<Boolean>>mapToObj(i -> () -> {
                    credenciais.aceitarConvite(token, SENHA + " " + i, "10.250.0." + i);
                    return true;
                })
                .toList();

        assertThat(sucessos(emParalelo(tarefas))).isEqualTo(1);
        assertThat(usuarios.findById(convidado.getId()).orElseThrow().getSituacao())
                .isEqualTo(SituacaoConta.ATIVO);
    }

    @Test
    void emailDuplicadoERecusadoInclusiveComVariacaoECriacaoSimultanea() throws Exception {
        Navegador admin = administrador();
        String email = emailUnico("duplicado");
        assertThat(admin.post("/api/v1/usuarios", Map.of("nome", "Primeiro", "email", email))
                        .getStatus())
                .isEqualTo(201);

        MockHttpServletResponse variacao =
                admin.post("/api/v1/usuarios", Map.of("nome", "Segundo", "email", "  " + email.toUpperCase() + " "));
        assertThat(variacao.getStatus()).isEqualTo(409);
        assertThat(codigo(variacao)).isEqualTo("CONVITE_PENDENTE");

        Usuario ator = criarAtivo(emailUnico("ator"), Perfil.ADMINISTRADOR);
        Ator administrador = new Ator(ator.getId(), EnumSet.allOf(Permissao.class));
        String simultaneo = emailUnico("simultaneo");
        List<Callable<Boolean>> tarefas = IntStream.range(0, 6)
                .<Callable<Boolean>>mapToObj(i -> () -> {
                    gestao.convidar(administrador, "Simultâneo " + i, simultaneo, Set.of(), "10.251.0." + i);
                    return true;
                })
                .toList();
        List<Future<Boolean>> resultados = emParalelo(tarefas);

        assertThat(sucessos(resultados)).isEqualTo(1);
        for (Future<Boolean> resultado : resultados) {
            if (!resultado.isDone() || falhou(resultado)) {
                assertThat(causa(resultado)).isInstanceOf(FalhaAcesso.class);
                assertThat(((FalhaAcesso) causa(resultado)).status().value()).isEqualTo(409);
            }
        }
    }

    @Test
    void conviteNaoReativaContaDesativada() {
        Navegador admin = administrador();
        String email = emailUnico("desativada");
        Usuario usuario = criarAtivo(email);
        usuario.desativar(Instant.now());
        usuarios.saveAndFlush(usuario);

        MockHttpServletResponse resposta =
                admin.post("/api/v1/usuarios", Map.of("nome", "Tentativa", "email", email, "perfis", List.of()));
        assertThat(resposta.getStatus()).isEqualTo(409);
        assertThat(codigo(resposta)).isEqualTo("USUARIO_DESATIVADO");
        assertThat(usuarios.findById(usuario.getId()).orElseThrow().getSituacao())
                .isEqualTo(SituacaoConta.DESATIVADO);
    }

    private static long sucessos(List<Future<Boolean>> resultados) {
        return resultados.stream().filter(r -> !falhou(r)).count();
    }

    private static boolean falhou(Future<Boolean> resultado) {
        try {
            resultado.get();
            return false;
        } catch (InterruptedException | ExecutionException e) {
            return true;
        }
    }

    private static Throwable causa(Future<Boolean> resultado) {
        try {
            resultado.get();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            return e;
        }
    }
}
