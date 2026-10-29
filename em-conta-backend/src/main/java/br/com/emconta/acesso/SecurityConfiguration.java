package br.com.emconta.acesso;

import br.com.emconta.acesso.sessao.SessaoUsuarioFilter;
import br.com.emconta.acesso.sessao.SessoesUsuario;
import br.com.emconta.acesso.usuarios.Permissao;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Segurança da API: tudo é negado, exceto o que está explicitamente liberado abaixo.
 *
 * <p>Autenticação por sessão no servidor (Spring Session JDBC, cookie HttpOnly). O login é feito pelo
 * {@code AutenticacaoController}; não há formulário, HTTP Basic nem usuário padrão. A cada requisição
 * o {@link SessaoUsuarioFilter} recarrega situação e permissões do banco. CSRF fica ativo em métodos
 * mutáveis, com token em cookie legível pelo SPA ({@code XSRF-TOKEN}) e enviado no cabeçalho
 * {@code X-XSRF-TOKEN}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    private static final String[] OPENAPI_PATHS = {
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    private static final String[] AUTH_PUBLICOS = {
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
        "/api/v1/auth/senha/recuperacao",
        "/api/v1/auth/senha/redefinicao",
        "/api/v1/auth/tokens/validacao",
        "/api/v1/auth/convites/aceitacao"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JsonMapper jsonMapper,
            SessoesUsuario sessoes,
            CookieCsrfTokenRepository csrfTokenRepository,
            @Value("${emconta.security.openapi-public:false}") boolean openApiPublic) {
        http.authorizeHttpRequests(auth -> {
            auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
            auth.requestMatchers(HttpMethod.GET, "/api/v1/system/status").permitAll();
            auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                    .permitAll();
            if (openApiPublic) {
                auth.requestMatchers(HttpMethod.GET, OPENAPI_PATHS).permitAll();
            }

            // Sessão e credenciais (CSRF continua exigido nos POSTs).
            auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll();
            auth.requestMatchers(HttpMethod.POST, AUTH_PUBLICOS).permitAll();
            auth.requestMatchers(HttpMethod.GET, "/api/v1/auth/sessao").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/senha/alteracao")
                    .authenticated();

            // Administração de usuários: uma permissão por ação.
            auth.requestMatchers(HttpMethod.GET, "/api/v1/usuarios", "/api/v1/usuarios/*", "/api/v1/perfis")
                    .hasAuthority(Permissao.USUARIOS_CONSULTAR.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/usuarios", "/api/v1/usuarios/*/convite")
                    .hasAuthority(Permissao.USUARIOS_CONVIDAR.autoridade());
            auth.requestMatchers(HttpMethod.PATCH, "/api/v1/usuarios/*")
                    .hasAuthority(Permissao.USUARIOS_EDITAR.autoridade());
            auth.requestMatchers(HttpMethod.PUT, "/api/v1/usuarios/*/perfis")
                    .hasAuthority(Permissao.USUARIOS_ATRIBUIR_PERFIS.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/usuarios/*/desativacao", "/api/v1/usuarios/*/reativacao")
                    .hasAuthority(Permissao.USUARIOS_ALTERAR_SITUACAO.autoridade());

            // Associados, contatos e UCs. "*" casa um único segmento do caminho.
            auth.requestMatchers(HttpMethod.GET, "/api/v1/associados", "/api/v1/associados/*")
                    .hasAuthority(Permissao.ASSOCIADOS_CONSULTAR.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/associados")
                    .hasAuthority(Permissao.ASSOCIADOS_CADASTRAR.autoridade());
            auth.requestMatchers(HttpMethod.PUT, "/api/v1/associados/*", "/api/v1/associados/*/contatos/*")
                    .hasAuthority(Permissao.ASSOCIADOS_EDITAR.autoridade());
            auth.requestMatchers(
                            HttpMethod.POST, "/api/v1/associados/*/contatos", "/api/v1/associados/*/contatos/*/remocao")
                    .hasAuthority(Permissao.ASSOCIADOS_EDITAR.autoridade());
            auth.requestMatchers(HttpMethod.GET, "/api/v1/associados/*/historico")
                    .hasAuthority(Permissao.ASSOCIADOS_CONSULTAR_HISTORICO.autoridade());
            auth.requestMatchers(HttpMethod.GET, "/api/v1/associados/*/ucs", "/api/v1/associados/*/ucs/*")
                    .hasAuthority(Permissao.UCS_CONSULTAR.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/associados/*/ucs")
                    .hasAuthority(Permissao.UCS_CADASTRAR.autoridade());
            auth.requestMatchers(HttpMethod.PUT, "/api/v1/associados/*/ucs/*")
                    .hasAuthority(Permissao.UCS_EDITAR.autoridade());

            // Vínculos de usina vistos pela ficha do associado (rota do módulo de usinas).
            auth.requestMatchers(HttpMethod.GET, "/api/v1/associados/*/vinculos-usina")
                    .hasAuthority(Permissao.VINCULOS_USINA_CONSULTAR.autoridade());

            // Qualquer outra rota de associados sem regra acima é negada, mesmo com sessão.
            auth.requestMatchers("/api/v1/associados/**").denyAll();

            // Fazendas, usinas e vínculos. Regras específicas antes das genéricas.
            auth.requestMatchers(HttpMethod.GET, "/api/v1/fazendas/*/historico", "/api/v1/usinas/*/historico")
                    .hasAuthority(Permissao.USINAS_CONSULTAR_HISTORICO.autoridade());
            auth.requestMatchers(HttpMethod.GET, "/api/v1/usinas/*/vinculos")
                    .hasAuthority(Permissao.VINCULOS_USINA_CONSULTAR.autoridade());
            auth.requestMatchers(HttpMethod.GET, "/api/v1/vinculos-usina/ucs-candidatas")
                    .hasAuthority(Permissao.VINCULOS_USINA_CRIAR.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/usinas/*/vinculos")
                    .hasAuthority(Permissao.VINCULOS_USINA_CRIAR.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/usinas/*/vinculos/*/encerramento")
                    .hasAuthority(Permissao.VINCULOS_USINA_ENCERRAR.autoridade());
            auth.requestMatchers(
                            HttpMethod.GET,
                            "/api/v1/fazendas",
                            "/api/v1/fazendas/*",
                            "/api/v1/usinas",
                            "/api/v1/usinas/*")
                    .hasAuthority(Permissao.USINAS_CONSULTAR.autoridade());
            auth.requestMatchers(HttpMethod.POST, "/api/v1/fazendas", "/api/v1/usinas")
                    .hasAuthority(Permissao.USINAS_CADASTRAR.autoridade());
            auth.requestMatchers(HttpMethod.PUT, "/api/v1/fazendas/*", "/api/v1/usinas/*")
                    .hasAuthority(Permissao.USINAS_EDITAR.autoridade());
            auth.requestMatchers("/api/v1/fazendas/**", "/api/v1/usinas/**", "/api/v1/vinculos-usina/**")
                    .denyAll();

            auth.anyRequest().authenticated();
        });
        http.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository));
        http.addFilterBefore(new SessaoUsuarioFilter(sessoes), AuthorizationFilter.class);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, e) -> naoAutenticado(jsonMapper, request, response))
                .accessDeniedHandler((request, response, e) -> {
                    if (e instanceof CsrfException) {
                        writeProblem(
                                jsonMapper,
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "CSRF_INVALIDO",
                                "Token CSRF ausente ou inválido. Recarregue a página e tente novamente.");
                    } else {
                        writeProblem(
                                jsonMapper,
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "ACESSO_NEGADO",
                                "Você não tem permissão para esta ação.");
                    }
                }));
        // Sem formulário, Basic ou logout padrão: login e logout são endpoints JSON próprios.
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.rememberMe(AbstractHttpConfigurer::disable);
        // Evita criar sessão para guardar a requisição de visitantes anônimos.
        http.requestCache(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** Diferencia ausência de sessão, sessão expirada e sessão revogada pelo servidor. */
    private static void naoAutenticado(JsonMapper jsonMapper, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (request.getAttribute(SessaoUsuarioFilter.ATRIBUTO_SESSAO_ENCERRADA) != null) {
            writeProblem(
                    jsonMapper,
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "SESSAO_ENCERRADA",
                    "Sua sessão foi encerrada. Entre novamente.");
        } else if (request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid()) {
            writeProblem(
                    jsonMapper,
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "SESSAO_EXPIRADA",
                    "Sua sessão expirou. Entre novamente.");
        } else {
            writeProblem(
                    jsonMapper,
                    request,
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "NAO_AUTENTICADO",
                    "Autenticação necessária.");
        }
    }

    private static void writeProblem(
            JsonMapper jsonMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String codigo,
            String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("codigo", codigo);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
