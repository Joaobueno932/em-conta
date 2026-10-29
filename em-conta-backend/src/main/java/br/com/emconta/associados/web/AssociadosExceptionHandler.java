package br.com.emconta.associados.web;

import br.com.emconta.associados.cadastro.FalhaCadastro;
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
 * Falhas do cadastro em Problem Details com {@code codigo} estável. Erros de um campo trazem
 * {@code campos} (campo → mensagem), no mesmo formato da validação de corpo. Nunca expõe SQL.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AssociadosExceptionHandler {

    @ExceptionHandler(FalhaCadastro.class)
    ResponseEntity<ProblemDetail> falha(FalhaCadastro falha, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(falha.status(), falha.getMessage());
        problema.setInstance(URI.create(request.getRequestURI()));
        problema.setProperty("codigo", falha.codigo());
        if (falha.campo() != null) {
            problema.setProperty("campos", Map.of(falha.campo(), falha.getMessage()));
        }
        if (falha.recursoExistenteId() != null) {
            problema.setProperty("associadoExistenteId", falha.recursoExistenteId());
        }
        return ResponseEntity.status(falha.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
