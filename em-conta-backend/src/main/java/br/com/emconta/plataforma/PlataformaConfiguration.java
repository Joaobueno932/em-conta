package br.com.emconta.plataforma;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlataformaConfiguration {

    /**
     * Relógio técnico em UTC. Instantes ({@link java.time.Instant}) são gravados e trafegados em UTC;
     * datas de negócio (vencimento, competência) devem usar {@link java.time.LocalDate} explicitamente.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI emContaOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        return new OpenAPI()
                .info(new Info().title("Em Conta API").version(build != null ? build.getVersion() : "dev"))
                // Mesma origem do frontend: o contrato não fixa host.
                .servers(List.of(new Server().url("/")));
    }
}
