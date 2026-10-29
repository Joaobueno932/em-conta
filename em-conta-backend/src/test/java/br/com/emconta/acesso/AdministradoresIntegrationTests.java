package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.usuarios.Ator;
import br.com.emconta.acesso.usuarios.BootstrapAdministrador;
import br.com.emconta.acesso.usuarios.GestaoUsuarios;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.SituacaoConta;
import br.com.emconta.acesso.usuarios.Usuario;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * Bootstrap e proteção do último administrador. Usa banco próprio (contexto isolado pela
 * propriedade abaixo), pois depende de quantos administradores existem.
 */
@TestPropertySource(properties = "emconta.teste.banco=administradores")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdministradoresIntegrationTests extends AcessoIntegracao {

    private static final Set<Permissao> TODAS = EnumSet.allOf(Permissao.class);

    @Autowired
    BootstrapAdministrador bootstrap;

    @Autowired
    GestaoUsuarios gestao;

    private static long primeiro;

    @Test
    @Order(1)
    void bootstrapCriaOPrimeiroAdministradorUmaUnicaVez() {
        assertThatThrownBy(() -> bootstrap.criar("Admin", "admin@teste.emconta.local", "curta"))
                .isInstanceOf(FalhaAcesso.class);

        primeiro = bootstrap.criar(" Primeira Admin ", "Admin@Teste.EmConta.local", SENHA);
        Usuario admin = usuarios.findById(primeiro).orElseThrow();
        assertThat(admin.getEmail()).isEqualTo("admin@teste.emconta.local");
        assertThat(admin.getNome()).isEqualTo("Primeira Admin");
        assertThat(admin.getSituacao()).isEqualTo(SituacaoConta.ATIVO);
        assertThat(admin.getPerfis()).containsExactly(Perfil.ADMINISTRADOR);
        assertThat(admin.getSenhaHash()).startsWith("{argon2}$argon2id$").doesNotContain(SENHA);

        assertThatThrownBy(() -> bootstrap.criar("Outra", "outra@teste.emconta.local", SENHA))
                .isInstanceOf(IllegalStateException.class);
        assertThat(navegador().entrar("admin@teste.emconta.local", SENHA).getStatus())
                .isEqualTo(200);
    }

    @Test
    @Order(2)
    void ultimoAdministradorNaoPerdeOPerfilNemSeDesativa() {
        Navegador admin = logado("admin@teste.emconta.local");

        MockHttpServletResponse semPerfil =
                admin.put("/api/v1/usuarios/" + primeiro + "/perfis", Map.of("perfis", List.of()));
        assertThat(semPerfil.getStatus()).isEqualTo(409);
        assertThat(codigo(semPerfil)).isEqualTo("ULTIMO_ADMINISTRADOR");

        MockHttpServletResponse propria = admin.post("/api/v1/usuarios/" + primeiro + "/desativacao", null);
        assertThat(propria.getStatus()).isEqualTo(409);
        assertThat(codigo(propria)).isEqualTo("PROPRIA_CONTA");
        assertThat(usuarios.findById(primeiro).orElseThrow().administradorAtivo())
                .isTrue();
    }

    @Test
    @Order(3)
    void desativacoesSimultaneasDeDoisAdministradoresDeixamUmAtivo() throws Exception {
        Usuario segundo = criarAtivo(emailUnico("segundo-admin"), Perfil.ADMINISTRADOR);
        Ator primeiroAtor = new Ator(primeiro, TODAS);
        Ator segundoAtor = new Ator(segundo.getId(), TODAS);

        List<Callable<Boolean>> tarefas = List.of(
                () -> {
                    gestao.desativar(segundoAtor, primeiro, "10.253.0.1");
                    return true;
                },
                () -> {
                    gestao.desativar(primeiroAtor, segundo.getId(), "10.253.0.2");
                    return true;
                });
        List<Future<Boolean>> resultados = emParalelo(tarefas);

        int sucessos = 0;
        for (Future<Boolean> resultado : resultados) {
            try {
                resultado.get();
                sucessos++;
            } catch (ExecutionException e) {
                assertThat(((FalhaAcesso) e.getCause()).codigo()).isEqualTo("ULTIMO_ADMINISTRADOR");
            }
        }
        assertThat(sucessos).isEqualTo(1);
        long ativos =
                usuarios.findAll().stream().filter(Usuario::administradorAtivo).count();
        assertThat(ativos).isEqualTo(1);
    }

    @Test
    @Order(4)
    void remocoesSimultaneasDePerfilTambemPreservamUmAdministrador() throws Exception {
        Usuario remanescente = usuarios.findAll().stream()
                .filter(Usuario::administradorAtivo)
                .findFirst()
                .orElseThrow();
        Usuario terceiro = criarAtivo(emailUnico("terceiro-admin"), Perfil.ADMINISTRADOR);
        Ator a = new Ator(remanescente.getId(), TODAS);
        Ator b = new Ator(terceiro.getId(), TODAS);

        List<Future<Boolean>> resultados = emParalelo(List.of(
                () -> {
                    gestao.definirPerfis(b, remanescente.getId(), Set.of(), null);
                    return true;
                },
                () -> {
                    gestao.definirPerfis(a, terceiro.getId(), Set.of(), null);
                    return true;
                }));

        long sucessos = resultados.stream()
                .filter(r -> {
                    try {
                        r.get();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .count();
        assertThat(sucessos).isEqualTo(1);
        assertThat(usuarios.findAll().stream()
                        .filter(Usuario::administradorAtivo)
                        .count())
                .isEqualTo(1);
    }
}
