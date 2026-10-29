package br.com.emconta;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** MySQL e Mailpit reais, nas mesmas versões do Docker Compose local. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String URL_PUBLICA = "http://localhost:5173";

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    }

    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.6")).withExposedPorts(1025, 8025);
    }

    /**
     * SMTP apontando para o Mailpit do teste. Declarado como bean porque a autoconfiguração de e-mail
     * decide pela existência de {@code spring.mail.host} antes de propriedades dinâmicas existirem.
     */
    @Bean
    JavaMailSender mailSender(GenericContainer<?> mailpitContainer) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mailpitContainer.getHost());
        sender.setPort(mailpitContainer.getMappedPort(1025));
        return sender;
    }

    /** Permissões parciais para usuários de teste registrados (ver {@link PermissoesControladas}). */
    @Bean
    PermissoesControladas permissoesControladas() {
        return new PermissoesControladas();
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add(
                    "mailpit.api",
                    () -> "http://" + mailpitContainer.getHost() + ":" + mailpitContainer.getMappedPort(8025));
            registry.add("emconta.acesso.url-publica", () -> URL_PUBLICA);
        };
    }
}
