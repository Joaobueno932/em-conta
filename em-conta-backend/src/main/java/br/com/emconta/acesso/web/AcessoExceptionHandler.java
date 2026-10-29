package br.com.emconta.acesso.web;

import br.com.emconta.acesso.erros.FalhaAcesso;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Falhas do módulo de acesso em Problem Details, com o campo {@code codigo} para o frontend. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AcessoExceptionHandler {

    @ExceptionHandler(FalhaAcesso.class)
    ResponseEntity<ProblemDetail> falha(FalhaAcesso falha, HttpServletRequest request) {
        ResponseEntity.BodyBuilder resposta = ResponseEntity.status(falha.status());
        if (falha.tentarNovamenteEm() != null) {
            long segundos = Math.max(1, (falha.tentarNovamenteEm().toMillis() + 999) / 1000);
            resposta.header(HttpHeaders.RETRY_AFTER, String.valueOf(segundos));
        }
        return resposta.contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema(falha.status(), falha.codigo(), falha.getMessage(), request));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> concorrencia(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema(
                        HttpStatus.CONFLICT,
                        "CONFLITO_CONCORRENTE",
                        "Os dados foram alterados por outra operação. Recarregue e tente novamente.",
                        request));
    }

    static ProblemDetail problema(HttpStatus status, String codigo, String detalhe, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setInstance(URI.create(request.getRequestURI()));
        problema.setProperty("codigo", codigo);
        return problema;
    }
}
