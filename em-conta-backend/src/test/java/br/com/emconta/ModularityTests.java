package br.com.emconta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(EmContaApplication.class);

    @Test
    void verificaFronteirasDosModulos() {
        modules.verify();
    }

    @Test
    void declaraOsModulosPlanejados() {
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder(
                        "acesso",
                        "associados",
                        "usinas",
                        "atendimento",
                        "comercial",
                        "contratos",
                        "faturamento",
                        "conciliacao",
                        "integracoes",
                        "sistema",
                        "plataforma");
    }

    @Test
    void geraDocumentacaoDosModulos() {
        // Saída em target/spring-modulith-docs (diagramas PlantUML e canvases).
        new Documenter(modules).writeDocumentation();
    }
}
