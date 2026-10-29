package br.com.emconta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Aplicação única do Em Conta (monólito modular).
 *
 * <p>A função do processo é escolhida por profile: {@code api} (servidor HTTP) ou {@code worker} (sem
 * servidor HTTP). O usuário em memória gerado pelo Spring Boot é desativado: a autenticação própria
 * será implementada no módulo {@code acesso}.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class EmContaApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmContaApplication.class, args);
    }
}
