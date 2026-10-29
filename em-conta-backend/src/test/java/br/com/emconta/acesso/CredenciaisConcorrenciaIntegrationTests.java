package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import br.com.emconta.acesso.auditoria.Auditoria;
import br.com.emconta.acesso.auditoria.TipoEvento;
import br.com.emconta.acesso.sessao.SessoesUsuario;
import br.com.emconta.acesso.tokens.FinalidadeToken;
import br.com.emconta.acesso.tokens.TokensAcesso;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Corridas na troca e na redefinição de senha e no uso de tokens públicos. As etapas são controladas
 * por latches em pontos exatos (Argon2 da nova senha; logo após o commit da troca), sem sleeps.
 */
class CredenciaisConcorrenciaIntegrationTests extends AcessoIntegracao {

    private static final String TERCEIRA_SENHA = "terceira senha de teste, longa o suficiente";

    @MockitoSpyBean
    PasswordEncoder encoder;

    @MockitoSpyBean
    SessoesUsuario sessoes;

    @MockitoSpyBean
    Auditoria auditoria;

    @Autowired
    TokensAcesso tokens;

    @AfterEach
    void restaurar() {
        reset(encoder, sessoes, auditoria);
    }

    /** Pausa a thread que calcular o hash de {@code senha} até {@link #liberar}. */
    private static final class Pausa {
        final CountDownLatch chegou = new CountDownLatch(1);
        final CountDownLatch liberar = new CountDownLatch(1);

        void aguardarChegada() throws InterruptedException {
            assertThat(chegou.await(30, TimeUnit.SECONDS)).as("etapa alcançada").isTrue();
        }
    }

    private Pausa pausarEncode(String senha) {
        Pausa pausa = new Pausa();
        doAnswer(inv -> {
                    pausa.chegou.countDown();
                    assertThat(pausa.liberar.await(30, TimeUnit.SECONDS)).isTrue();
                    return inv.callRealMethod();
                })
                .when(encoder)
                .encode(argThat(s -> senha.contentEquals((CharSequence) s)));
        return pausa;
    }

    private static <T> Future<T> emSegundoPlano(Callable<T> tarefa) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return tarefa.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private String token(Usuario usuario, FinalidadeToken finalidade) {
        return tokens.emitir(usuario.getId(), finalidade, Duration.ofHours(1)).valor();
    }

    private int auditorias(Usuario usuario, String tipo) {
        return jdbc.sql("select count(*) from auditoria_seguranca where alvo_usuario_id = :id and tipo = :tipo")
                .param("id", usuario.getId())
                .param("tipo", tipo)
                .query(Integer.class)
                .single();
    }

    private String hashAtual(Usuario usuario) {
        return usuarios.findById(usuario.getId()).orElseThrow().getSenhaHash();
    }

    private Map<String, String> alteracao(String nova) {
        return Map.of("senhaAtual", SENHA, "novaSenha", nova);
    }

    // ------------------------------------------------------------------ troca × redefinição

    @Test
    void trocaAutenticadaNaoSobrescreveRedefinicaoConcluidaNoIntervalo() throws Exception {
        String email = emailUnico("corrida-redef");
        Usuario usuario = criarAtivo(email);
        Navegador sessao = logado(email);
        String link = token(usuario, FinalidadeToken.REDEFINICAO_SENHA);
        Pausa pausa = pausarEncode(NOVA_SENHA);

        Future<MockHttpServletResponse> troca =
                emSegundoPlano(() -> sessao.post("/api/v1/auth/senha/alteracao", alteracao(NOVA_SENHA)));
        pausa.aguardarChegada(); // senha atual já conferida; nova ainda não gravada
        MockHttpServletResponse redefinicao =
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", link, "novaSenha", TERCEIRA_SENHA));
        assertThat(redefinicao.getStatus()).isEqualTo(204);
        pausa.liberar.countDown();

        MockHttpServletResponse resposta = troca.get(30, TimeUnit.SECONDS);
        assertThat(resposta.getStatus()).isEqualTo(409);
        assertThat(codigo(resposta)).isEqualTo("CREDENCIAL_ALTERADA");
        assertThat(resposta.getContentAsString()).doesNotContainIgnoringCase("exception");

        assertThat(navegador().entrar(email, TERCEIRA_SENHA).getStatus())
                .as("vencedora")
                .isEqualTo(200);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus())
                .as("perdedora")
                .isEqualTo(401);
        assertThat(navegador().entrar(email, SENHA).getStatus()).as("antiga").isEqualTo(401);
        assertThat(auditorias(usuario, "SENHA_ALTERADA")).isZero();
        assertThat(auditorias(usuario, "SENHA_REDEFINIDA")).isEqualTo(1);
        assertThat(sessao.get("/api/v1/auth/sessao").getStatus())
                .as("redefinição encerra tudo")
                .isEqualTo(401);
    }

    @Test
    void duasTrocasComAMesmaSenhaAntigaAceitamApenasUma() throws Exception {
        String email = emailUnico("corrida-trocas");
        Usuario usuario = criarAtivo(email);
        Navegador primeira = logado(email);
        Navegador segunda = logado(email);
        Pausa pausa = pausarEncode(NOVA_SENHA);

        Future<MockHttpServletResponse> lenta =
                emSegundoPlano(() -> primeira.post("/api/v1/auth/senha/alteracao", alteracao(NOVA_SENHA)));
        pausa.aguardarChegada();
        MockHttpServletResponse rapida = segunda.post("/api/v1/auth/senha/alteracao", alteracao(TERCEIRA_SENHA));
        assertThat(rapida.getStatus()).isEqualTo(204);
        pausa.liberar.countDown();

        MockHttpServletResponse resposta = lenta.get(30, TimeUnit.SECONDS);
        assertThat(resposta.getStatus()).isIn(401, 409);
        assertThat(codigo(resposta)).isIn("CREDENCIAL_ALTERADA", "SESSAO_ENCERRADA");
        assertThat(navegador().entrar(email, TERCEIRA_SENHA).getStatus()).isEqualTo(200);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(401);
        assertThat(auditorias(usuario, "SENHA_ALTERADA")).isEqualTo(1);
        assertThat(segunda.get("/api/v1/auth/sessao").getStatus())
                .as("sessão vencedora")
                .isEqualTo(200);
        assertThat(primeira.get("/api/v1/auth/sessao").getStatus())
                .as("outra sessão")
                .isEqualTo(401);
    }

    @Test
    void desativacaoNoIntervaloImpedeATrocaEMantemOBloqueio() throws Exception {
        String email = emailUnico("corrida-desativ");
        Usuario usuario = criarAtivo(email);
        Navegador sessao = logado(email);
        String emailAdmin = emailUnico("adm-corrida");
        criarAtivo(emailAdmin, Perfil.ADMINISTRADOR);
        Navegador admin = logado(emailAdmin);
        String hashAntes = hashAtual(usuario);
        Pausa pausa = pausarEncode(NOVA_SENHA);

        Future<MockHttpServletResponse> troca =
                emSegundoPlano(() -> sessao.post("/api/v1/auth/senha/alteracao", alteracao(NOVA_SENHA)));
        pausa.aguardarChegada();
        assertThat(admin.post("/api/v1/usuarios/" + usuario.getId() + "/desativacao", null)
                        .getStatus())
                .isEqualTo(200);
        pausa.liberar.countDown();

        MockHttpServletResponse resposta = troca.get(30, TimeUnit.SECONDS);
        assertThat(resposta.getStatus()).isEqualTo(409);
        assertThat(codigo(resposta)).isEqualTo("CREDENCIAL_ALTERADA");
        assertThat(hashAtual(usuario)).as("senha não gravada").isEqualTo(hashAntes);
        assertThat(usuarios.findById(usuario.getId())
                        .orElseThrow()
                        .getSituacao()
                        .name())
                .isEqualTo("DESATIVADO");
        assertThat(sessao.get("/api/v1/auth/sessao").getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(401);
        assertThat(auditorias(usuario, "SENHA_ALTERADA")).isZero();
    }

    // ------------------------------------------------------------------ sessão atual

    @Test
    void requisicaoDaMesmaSessaoDuranteATrocaNaoEncerraASessaoAtual() throws Exception {
        String email = emailUnico("mesma-sessao");
        Usuario usuario = criarAtivo(email);
        Navegador sessao = logado(email);
        Navegador outra = logado(email);
        // Pausa logo após o commit da nova versão de segurança, antes de a sessão ser regravada.
        CountDownLatch commitFeito = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        doAnswer(inv -> {
                    commitFeito.countDown();
                    assertThat(liberar.await(30, TimeUnit.SECONDS)).isTrue();
                    return inv.callRealMethod();
                })
                .when(sessoes)
                .encerrarOutras(anyLong(), anyString());

        Future<MockHttpServletResponse> troca =
                emSegundoPlano(() -> sessao.post("/api/v1/auth/senha/alteracao", alteracao(NOVA_SENHA)));
        assertThat(commitFeito.await(30, TimeUnit.SECONDS)).isTrue();
        int versaoNova = usuarios.findById(usuario.getId()).orElseThrow().getVersaoSeguranca();
        assertThat(versaoNova).as("versão já incrementada e confirmada").isEqualTo(1);

        // Mesma sessão, ainda com o principal na versão antiga: aceita (promovida), não encerrada.
        MockHttpServletResponse concorrente = sessao.get("/api/v1/auth/sessao");
        assertThat(concorrente.getStatus()).isEqualTo(200);
        assertThat(corpo(concorrente).get("email").asString()).isEqualTo(email);
        // Outra sessão do mesmo usuário na mesma janela: recusada.
        assertThat(outra.get("/api/v1/auth/sessao").getStatus()).isEqualTo(401);
        assertThat(codigo(outra.get("/api/v1/auth/sessao")))
                .isIn("SESSAO_ENCERRADA", "SESSAO_EXPIRADA", "NAO_AUTENTICADO");

        liberar.countDown();
        assertThat(troca.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(204);
        assertThat(sessao.get("/api/v1/auth/sessao").getStatus())
                .as("sessão atual após a troca")
                .isEqualTo(200);
        assertThat(sessao.get("/api/v1/auth/sessao").getStatus()).isEqualTo(200);
        assertThat(navegador().entrar(email, SENHA).getStatus()).isEqualTo(401);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(200);
    }

    @Test
    void autorizacaoDaSessaoAtualNaoSobreviveARedefinicaoNemADesativacao() {
        String email = emailUnico("autorizacao");
        Usuario usuario = criarAtivo(email);
        Navegador sessao = logado(email);
        assertThat(sessao.post("/api/v1/auth/senha/alteracao", alteracao(NOVA_SENHA))
                        .getStatus())
                .isEqualTo(204);
        assertThat(sessao.get("/api/v1/auth/sessao").getStatus()).isEqualTo(200);

        String link = token(usuario, FinalidadeToken.REDEFINICAO_SENHA);
        assertThat(navegador()
                        .post("/api/v1/auth/senha/redefinicao", Map.of("token", link, "novaSenha", TERCEIRA_SENHA))
                        .getStatus())
                .isEqualTo(204);
        assertThat(sessao.get("/api/v1/auth/sessao").getStatus())
                .as("redefinição encerra a sessão")
                .isEqualTo(401);
        assertThat(jdbc.sql("select count(*) from sessao_renovada where usuario_id = :id")
                        .param("id", usuario.getId())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------------ tokens públicos

    @Test
    void tokenInvalidoExpiradoOuConsumidoNaoCalculaHash() {
        String email = emailUnico("token-caro");
        Usuario usuario = criarAtivo(email);
        String consumido = token(usuario, FinalidadeToken.REDEFINICAO_SENHA);
        assertThat(navegador()
                        .post("/api/v1/auth/senha/redefinicao", Map.of("token", consumido, "novaSenha", NOVA_SENHA))
                        .getStatus())
                .isEqualTo(204);
        String expirado = token(usuario, FinalidadeToken.REDEFINICAO_SENHA);
        jdbc.sql("update token_acesso set expira_em = :passado where hash_token = sha2(:token, 256)")
                .param("passado", Timestamp.from(Instant.now().minusSeconds(60)))
                .param("token", expirado)
                .update();
        clearInvocations(encoder);

        for (String token : List.of("curto", "A".repeat(43), consumido, expirado)) {
            MockHttpServletResponse resposta = navegador()
                    .post("/api/v1/auth/senha/redefinicao", Map.of("token", token, "novaSenha", TERCEIRA_SENHA));
            assertThat(resposta.getStatus()).isEqualTo(400);
            assertThat(codigo(resposta)).isEqualTo("TOKEN_INVALIDO");
            MockHttpServletResponse convite = navegador()
                    .post("/api/v1/auth/convites/aceitacao", Map.of("token", token, "senha", TERCEIRA_SENHA));
            assertThat(codigo(convite)).isEqualTo("TOKEN_INVALIDO");
        }
        verify(encoder, never()).encode(any());
    }

    @Test
    void tokenValidoConcluiEUsoSimultaneoTemUmUnicoSucesso() throws Exception {
        String email = emailUnico("token-duplo");
        Usuario usuario = criarAtivo(email);
        String link = token(usuario, FinalidadeToken.REDEFINICAO_SENHA);
        Pausa pausa = pausarEncode(NOVA_SENHA);

        // Ambas passam pela validação prévia antes de qualquer uma consumir o token.
        Future<MockHttpServletResponse> primeira = emSegundoPlano(() ->
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", link, "novaSenha", NOVA_SENHA)));
        pausa.aguardarChegada();
        Future<MockHttpServletResponse> segunda = emSegundoPlano(() ->
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", link, "novaSenha", NOVA_SENHA)));
        pausa.liberar.countDown();

        List<Integer> status = new ArrayList<>(List.of(
                primeira.get(30, TimeUnit.SECONDS).getStatus(),
                segunda.get(30, TimeUnit.SECONDS).getStatus()));
        assertThat(status).containsExactlyInAnyOrder(204, 400);
        assertThat(auditorias(usuario, "SENHA_REDEFINIDA")).isEqualTo(1);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(200);
    }

    @Test
    void falhaNaTransacaoDesfazConsumoDoTokenESenha() {
        String email = emailUnico("rollback");
        Usuario usuario = criarAtivo(email);
        String hashAntes = hashAtual(usuario);
        String link = token(usuario, FinalidadeToken.REDEFINICAO_SENHA);
        // Falha depois de consumir o token e trocar a senha, ainda dentro da transação.
        doThrow(new IllegalStateException("falha simulada"))
                .when(auditoria)
                .registrar(eq(TipoEvento.SENHA_REDEFINIDA), any(), any(), any(), any());

        MockHttpServletResponse resposta =
                navegador().post("/api/v1/auth/senha/redefinicao", Map.of("token", link, "novaSenha", NOVA_SENHA));
        assertThat(resposta.getStatus()).isEqualTo(500);
        assertThat(resposta.getContentAsByteArray()).asString().doesNotContain("falha simulada");

        assertThat(hashAtual(usuario)).isEqualTo(hashAntes);
        assertThat(auditorias(usuario, "SENHA_REDEFINIDA")).isZero();
        assertThat(jdbc.sql("select consumido_em is null from token_acesso where hash_token = sha2(:token, 256)")
                        .param("token", link)
                        .query(Boolean.class)
                        .single())
                .as("rollback desfez o consumo do token")
                .isTrue();

        // Sem a falha, o mesmo token ainda conclui a redefinição.
        reset(auditoria);
        assertThat(navegador()
                        .post("/api/v1/auth/senha/redefinicao", Map.of("token", link, "novaSenha", NOVA_SENHA))
                        .getStatus())
                .isEqualTo(204);
        assertThat(navegador().entrar(email, NOVA_SENHA).getStatus()).isEqualTo(200);
    }

    @Test
    void limitePorIpNosTokensPublicosResponde429MesmoEmParalelo() throws Exception {
        Navegador navegador = navegador();
        navegador.get("/api/v1/auth/csrf"); // um único token CSRF para as requisições paralelas
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tarefas.add(() -> navegador.post(
                    "/api/v1/auth/tokens/validacao", Map.of("token", "A".repeat(43), "finalidade", "CONVITE")));
        }
        int invalidos = 0;
        int bloqueados = 0;
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            MockHttpServletResponse resposta = f.get();
            if (resposta.getStatus() == 429) {
                bloqueados++;
                assertThat(resposta.getHeader("Retry-After")).isNotNull();
                assertThat(codigo(resposta)).isEqualTo("MUITAS_TENTATIVAS");
            } else {
                assertThat(codigo(resposta)).isEqualTo("TOKEN_INVALIDO");
                invalidos++;
            }
        }
        assertThat(invalidos).as("limite padrão de 30 por IP").isEqualTo(30);
        assertThat(bloqueados).isEqualTo(10);

        // O bloqueio vale para aceite e redefinição do mesmo IP, e não alcança outro IP.
        MockHttpServletResponse redefinicao = navegador.post(
                "/api/v1/auth/senha/redefinicao", Map.of("token", "A".repeat(43), "novaSenha", NOVA_SENHA));
        assertThat(redefinicao.getStatus()).isEqualTo(429);
        assertThat(navegador
                        .post("/api/v1/auth/convites/aceitacao", Map.of("token", "A".repeat(43), "senha", SENHA))
                        .getStatus())
                .isEqualTo(429);
        assertThat(codigo(navegador()
                        .post(
                                "/api/v1/auth/tokens/validacao",
                                Map.of("token", "A".repeat(43), "finalidade", "CONVITE"))))
                .isEqualTo("TOKEN_INVALIDO");
        verify(encoder, never()).encode(eq(NOVA_SENHA));
        // O contador guarda só hash: nenhum token ou IP em claro.
        assertThat(jdbc.sql("select count(*) from limite_tentativa where chave like :ip")
                        .param("ip", "%" + navegador.ip() + "%")
                        .query(Integer.class)
                        .single())
                .isZero();
    }
}
