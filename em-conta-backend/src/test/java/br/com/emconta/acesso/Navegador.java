package br.com.emconta.acesso;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simula um navegador sobre o MockMvc: guarda os cookies recebidos (sessão e XSRF-TOKEN), reenvia-os
 * e, em métodos mutáveis, copia o XSRF-TOKEN para o cabeçalho, como o frontend faz. Cada instância
 * usa um IP próprio para que os limites por IP de um teste não afetem outro.
 */
public final class Navegador {

    static final String SESSAO = "EMCONTA_SESSION";
    static final String XSRF = "XSRF-TOKEN";
    private static final AtomicInteger SEQUENCIA = new AtomicInteger();

    private final MockMvc mvc;
    private final JsonMapper json;
    private final String ip;
    private final Map<String, String> cookies = new ConcurrentHashMap<>();

    public Navegador(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
        int n = SEQUENCIA.incrementAndGet();
        this.ip = "10.%d.%d.%d".formatted((n >> 16) & 255, (n >> 8) & 255, n & 255);
    }

    public String ip() {
        return ip;
    }

    public MockHttpServletResponse entrar(String email, String senha) {
        return post("/api/v1/auth/login", Map.of("email", email, "senha", senha));
    }

    public MockHttpServletResponse get(String url) {
        return executar(MockMvcRequestBuilders.get(url));
    }

    public MockHttpServletResponse post(String url, Object corpo) {
        return mutavel(comCorpo(MockMvcRequestBuilders.post(url), corpo));
    }

    public MockHttpServletResponse patch(String url, Object corpo) {
        return mutavel(comCorpo(MockMvcRequestBuilders.patch(url), corpo));
    }

    public MockHttpServletResponse put(String url, Object corpo) {
        return mutavel(comCorpo(MockMvcRequestBuilders.put(url), corpo));
    }

    /** POST multipart (arquivo e campos de texto), com o cabeçalho CSRF. */
    public MockHttpServletResponse multipart(
            String url, org.springframework.mock.web.MockMultipartFile arquivo, Map<String, String> campos) {
        var builder = MockMvcRequestBuilders.multipart(url).file(arquivo);
        campos.forEach((nome, valor) -> builder.file(new org.springframework.mock.web.MockMultipartFile(
                nome, "", "text/plain", valor.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return mutavel(builder);
    }

    /** GET com cabeçalhos adicionais (ex.: Range). */
    public MockHttpServletResponse get(String url, Map<String, String> cabecalhos) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.get(url);
        cabecalhos.forEach(builder::header);
        return executar(builder);
    }

    /** POST sem cabeçalho CSRF (para verificar a proteção). */
    public MockHttpServletResponse postSemCsrf(String url, Object corpo) {
        return executar(comCorpo(MockMvcRequestBuilders.post(url), corpo));
    }

    /** POST enviando um token CSRF escolhido pelo teste. */
    MockHttpServletResponse postComCsrf(String url, Object corpo, String token) {
        return executar(comCorpo(MockMvcRequestBuilders.post(url), corpo).header("X-XSRF-TOKEN", token));
    }

    public String cookie(String nome) {
        return cookies.get(nome);
    }

    void definirCookie(String nome, String valor) {
        cookies.put(nome, valor);
    }

    private MockHttpServletRequestBuilder comCorpo(MockHttpServletRequestBuilder builder, Object corpo) {
        if (corpo == null) {
            return builder;
        }
        String texto = corpo instanceof String s ? s : json.writeValueAsString(corpo);
        return builder.contentType(MediaType.APPLICATION_JSON).content(texto);
    }

    private MockHttpServletResponse mutavel(AbstractMockHttpServletRequestBuilder<?> builder) {
        if (!cookies.containsKey(XSRF)) {
            get("/api/v1/auth/csrf");
        }
        return executar(builder.header("X-XSRF-TOKEN", cookies.get(XSRF)));
    }

    private MockHttpServletResponse executar(AbstractMockHttpServletRequestBuilder<?> builder) {
        builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
        if (!cookies.isEmpty()) {
            builder.cookie(cookies.entrySet().stream()
                    .map(c -> new Cookie(c.getKey(), c.getValue()))
                    .toArray(Cookie[]::new));
        }
        try {
            MockHttpServletResponse resposta = mvc.perform(builder).andReturn().getResponse();
            for (Cookie recebido : resposta.getCookies()) {
                if (recebido.getMaxAge() == 0
                        || recebido.getValue() == null
                        || recebido.getValue().isEmpty()) {
                    cookies.remove(recebido.getName());
                } else {
                    cookies.put(recebido.getName(), recebido.getValue());
                }
            }
            return resposta;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
