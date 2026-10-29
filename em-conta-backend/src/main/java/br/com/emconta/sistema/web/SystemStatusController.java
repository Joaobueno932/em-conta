package br.com.emconta.sistema.web;

import br.com.emconta.sistema.web.SystemStatusResponse.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "Sistema", description = "Verificações técnicas")
class SystemStatusController {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);
    private static final int DATABASE_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final Clock clock;
    private final String version;

    SystemStatusController(DataSource dataSource, Clock clock, ObjectProvider<BuildProperties> buildProperties) {
        this.dataSource = dataSource;
        this.clock = clock;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSystemStatus", summary = "Situação técnica da API e da conexão com o banco")
    @ApiResponse(responseCode = "200", description = "API e banco disponíveis")
    @ApiResponse(
            responseCode = "503",
            description = "API ativa, mas banco indisponível",
            content = @Content(schema = @Schema(implementation = SystemStatusResponse.class)))
    ResponseEntity<SystemStatusResponse> status() {
        Status database = databaseStatus();
        var body = new SystemStatusResponse("em-conta-backend", version, database, database, Instant.now(clock));
        HttpStatus httpStatus = database == Status.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(body);
    }

    private Status databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DATABASE_TIMEOUT_SECONDS) ? Status.UP : Status.DOWN;
        } catch (SQLException e) {
            // Somente a classe do erro: a mensagem do driver pode conter host e usuário.
            log.warn("Banco de dados indisponível: {}", e.getClass().getSimpleName());
            return Status.DOWN;
        }
    }
}
