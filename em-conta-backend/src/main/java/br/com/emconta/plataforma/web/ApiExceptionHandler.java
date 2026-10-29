package br.com.emconta.plataforma.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Erros HTTP no formato Problem Details (RFC 9457). As exceções do Spring MVC são tratadas pela
 * classe base; qualquer outra vira 500 sem expor mensagem interna ao cliente.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Exceções de segurança lançadas dentro do controller (ex.: {@code @PreAuthorize}) voltam para o
     * Spring Security, que responde 401/403 no formato padrão, em vez de virarem 500 aqui.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void handleSecurity(RuntimeException exception) {
        throw exception;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Erro não tratado", exception);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setDetail("Erro interno. Tente novamente mais tarde.");
        return problem;
    }

    /** Validação de corpo: inclui os campos inválidos, sem ecoar os valores enviados. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Dados inválidos.");
        Map<String, String> campos = new LinkedHashMap<>();
        for (FieldError erro : ex.getBindingResult().getFieldErrors()) {
            campos.putIfAbsent(erro.getField(), "inválido");
        }
        problem.setProperty("codigo", "DADOS_INVALIDOS");
        problem.setProperty("campos", campos);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
