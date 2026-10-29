package br.com.emconta.sistema.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Situação técnica da aplicação. Não expõe hosts, credenciais nem detalhes de infraestrutura. */
@Schema(name = "SystemStatus")
public record SystemStatusResponse(
        @Schema(
                description = "Nome da aplicação",
                example = "em-conta-backend",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String application,

        @Schema(
                description = "Versão da aplicação",
                example = "0.1.0-SNAPSHOT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String version,

        @Schema(description = "Situação geral", requiredMode = Schema.RequiredMode.REQUIRED)
        Status status,

        @Schema(description = "Situação da conexão com o banco", requiredMode = Schema.RequiredMode.REQUIRED)
        Status database,

        @Schema(description = "Horário do servidor em UTC (ISO-8601)", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant serverTime) {

    public enum Status {
        UP,
        DOWN
    }
}
