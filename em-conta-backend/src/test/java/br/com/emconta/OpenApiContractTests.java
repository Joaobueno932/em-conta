package br.com.emconta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Garante que o contrato versionado em {@code contract/openapi.json} corresponde à API.
 *
 * <p>Para atualizar após mudar a API: {@code ./mvnw test -Dtest=OpenApiContractTests -Dcontract.update=true}
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "emconta.security.openapi-public=true"})
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class OpenApiContractTests {

    private static final Path CONTRACT = Path.of("contract", "openapi.json");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void contratoVersionadoCorrespondeAApi() throws Exception {
        String body =
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode atual = jsonMapper.readTree(body);

        if (Boolean.getBoolean("contract.update")) {
            Files.writeString(
                    CONTRACT, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(atual) + "\n");
        }

        assertThat(CONTRACT)
                .as("contract/openapi.json ausente; rode com -Dcontract.update=true")
                .exists();
        JsonNode versionado = jsonMapper.readTree(Files.readString(CONTRACT));
        assertThat(atual)
                .as("contract/openapi.json desatualizado; rode com -Dcontract.update=true")
                .isEqualTo(versionado);
    }
}
