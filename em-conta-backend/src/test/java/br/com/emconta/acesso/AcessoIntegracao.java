package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.TestcontainersConfiguration;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

/** Base dos testes de acesso: MySQL e Mailpit reais (Testcontainers), API com Spring Session JDBC. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
abstract class AcessoIntegracao {

    static final String SENHA = "uma senha de teste bem longa";
    static final String NOVA_SENHA = "outra senha de teste, também longa";

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

    @Value("${mailpit.api}")
    String mailpitApi;

    Navegador navegador() {
        return new Navegador(mvc, json);
    }

    Mailpit mailpit() {
        return new Mailpit(mailpitApi, json);
    }

    static String emailUnico(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.emconta.local";
    }

    Usuario criarAtivo(String email, Perfil... perfis) {
        EnumSet<Perfil> conjunto = EnumSet.noneOf(Perfil.class);
        conjunto.addAll(List.of(perfis));
        return usuarios.saveAndFlush(
                Usuario.ativo("Pessoa " + email, email, passwordEncoder.encode(SENHA), conjunto, Instant.now()));
    }

    Navegador logado(String email) {
        Navegador navegador = navegador();
        assertThat(navegador.entrar(email, SENHA).getStatus()).isEqualTo(200);
        return navegador;
    }

    JsonNode corpo(MockHttpServletResponse resposta) {
        try {
            return json.readTree(resposta.getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String codigo(MockHttpServletResponse resposta) {
        return corpo(resposta).path("codigo").asString();
    }

    /** Executa as tarefas ao mesmo tempo (liberadas juntas por uma barreira). */
    static <T> List<Future<T>> emParalelo(List<Callable<T>> tarefas) throws InterruptedException {
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
