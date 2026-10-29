package br.com.emconta.usinas.web;

import br.com.emconta.usinas.cadastro.FalhaUsinas;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Falhas de fazendas, usinas e vínculos em Problem Details com {@code codigo} estável, {@code campos}
 * (campo → mensagem) quando a falha é de um campo e dados extras para a interface (ex.: o vínculo
 * vigente que impediu um novo). Nunca expõe SQL.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UsinasExceptionHandler {

    @ExceptionHandler(FalhaUsinas.class)
    ResponseEntity<ProblemDetail> falha(FalhaUsinas falha, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(falha.status(), falha.getMessage());
        problema.setInstance(URI.create(request.getRequestURI()));
        problema.setProperty("codigo", falha.codigo());
        if (falha.campo() != null) {
            problema.setProperty("campos", Map.of(falha.campo(), falha.getMessage()));
        }
        falha.extras().forEach(problema::setProperty);
        return ResponseEntity.status(falha.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
