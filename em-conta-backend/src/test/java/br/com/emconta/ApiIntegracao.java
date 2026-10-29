package br.com.emconta;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.Usuario;
import br.com.emconta.acesso.usuarios.UsuarioRepository;
import br.com.emconta.associados.cadastro.Documentos;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * Base dos testes de API sobre MySQL real: usuários, login pela API, permissões parciais controladas
 * ({@link PermissoesControladas}) e dados de cadastro válidos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
public abstract class ApiIntegracao {

    protected static final String SENHA = "uma senha de teste bem longa";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected UsuarioRepository usuarios;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PermissoesControladas permissoes;

    protected Navegador administrador() {
        String email = emailUnico("adm");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        return logado(email);
    }

    /** Usuário sem perfil que recebe exatamente as permissões informadas. */
    protected Navegador comPermissoes(Permissao... concedidas) {
        String email = emailUnico("parcial");
        Usuario usuario = criarAtivo(email);
        permissoes.conceder(usuario.getId(), concedidas);
        return logado(email);
    }

    protected Usuario criarAtivo(String email, Perfil... perfis) {
        EnumSet<Perfil> conjunto = EnumSet.noneOf(Perfil.class);
        conjunto.addAll(List.of(perfis));
        return usuarios.saveAndFlush(
                Usuario.ativo("Pessoa " + email, email, passwordEncoder.encode(SENHA), conjunto, Instant.now()));
    }

    protected Navegador logado(String email) {
        Navegador navegador = new Navegador(mvc, json);
        assertThat(navegador.entrar(email, SENHA).getStatus()).isEqualTo(200);
        return navegador;
    }

    protected static String emailUnico(String prefixo) {
        return prefixo + "-" + UUID.randomUUID().toString().substring(0, 8) + "@teste.emconta.local";
    }

    /** Associado PJ com uma UC; devolve {associadoId, ucId}. */
    protected long[] associadoComUc(Navegador admin) {
        JsonNode associado = corpo(admin.post(
                "/api/v1/associados",
                Map.of("tipo", "PJ", "documento", cnpjValido(), "nome", "Titular " + UUID.randomUUID())));
        long associadoId = associado.get("id").asLong();
        long ucId = novaUc(admin, associadoId);
        return new long[] {associadoId, ucId};
    }

    protected long novaUc(Navegador admin, long associadoId) {
        MockHttpServletResponse uc = admin.post(
                "/api/v1/associados/" + associadoId + "/ucs",
                Map.of("concessionaria", "ENERGISA_MS", "numero", numeroUc()));
        assertThat(uc.getStatus()).isEqualTo(201);
        return corpo(uc).get("id").asLong();
    }

    protected static String numeroUc() {
        return "0" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
    }

    protected static String cnpjValido() {
        while (true) {
            StringBuilder base = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                base.append(ThreadLocalRandom.current().nextInt(10));
            }
            for (int dv = 0; dv < 100; dv++) {
                String candidato = base + String.format("%02d", dv);
                if (Documentos.cnpjValido(candidato)) {
                    return candidato;
                }
            }
        }
    }

    protected JsonNode corpo(MockHttpServletResponse resposta) {
        try {
            return json.readTree(resposta.getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected String codigo(MockHttpServletResponse resposta) {
        return corpo(resposta).path("codigo").asString();
    }

    /** Executa as tarefas ao mesmo tempo (liberadas juntas por uma barreira). */
    protected static <T> List<Future<T>> emParalelo(List<Callable<T>> tarefas) throws InterruptedException {
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
