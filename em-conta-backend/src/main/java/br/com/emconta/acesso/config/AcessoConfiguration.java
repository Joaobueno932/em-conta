package br.com.emconta.acesso.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AcessoProperties.class)
class AcessoConfiguration {

    /** Limpezas periódicas. Desligadas no bootstrap para o processo terminar ao final do comando. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "emconta.agendamentos.habilitados", havingValue = "true", matchIfMissing = true)
    static class Agendamentos {}

    /**
     * Argon2id (OWASP: m=19 MiB, t=2, p=1; sal de 16 e hash de 32 bytes). O prefixo {@code {argon2}}
     * permite trocar parâmetros ou algoritmo no futuro sem invalidar os hashes existentes.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2 = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
    }

    /**
     * Cookie de sessão do Spring Session declarado explicitamente, para que nome e atributos sejam os
     * mesmos com servidor embarcado, em testes (MockMvc) ou em outro container de servlet.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.name:EMCONTA_SESSION}") String nome,
            @Value("${server.servlet.session.cookie.secure:true}") boolean seguro) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(nome);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(seguro);
        return serializer;
    }

    /** Cookie {@code XSRF-TOKEN} legível pelo SPA, compartilhado entre o filtro CSRF e o login/logout. */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer((ResponseCookie.ResponseCookieBuilder cookie) -> cookie.sameSite("Lax"));
        return repository;
    }
}
