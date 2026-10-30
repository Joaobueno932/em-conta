package br.com.emconta.atendimento.web;

import br.com.emconta.atendimento.nucleo.FalhaAtendimento;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Falhas do atendimento em Problem Details com {@code codigo} estável. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AtendimentoExceptionHandler {

    @ExceptionHandler(FalhaAtendimento.class)
    ResponseEntity<ProblemDetail> falha(FalhaAtendimento falha, HttpServletRequest request) {
        return problema(falha.status(), falha.codigo(), falha.getMessage(), falha.campo(), request);
    }

    /** Multipart acima do teto configurado: recusado antes de chegar ao serviço. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> muitoGrande(Exception falha, HttpServletRequest request) {
        return problema(
                HttpStatus.CONTENT_TOO_LARGE,
                "ARQUIVO_MUITO_GRANDE",
                "Arquivo acima do limite aceito.",
                "arquivo",
                request);
    }

    @ExceptionHandler(NaoEncontrado.class)
    ResponseEntity<ProblemDetail> naoEncontrado(NaoEncontrado falha, HttpServletRequest request) {
        return problema(HttpStatus.NOT_FOUND, "ATENDIMENTO_NAO_ENCONTRADO", falha.getMessage(), null, request);
    }

    private static ResponseEntity<ProblemDetail> problema(
            HttpStatus status, String codigo, String detalhe, String campo, HttpServletRequest request) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setInstance(URI.create(request.getRequestURI()));
        problema.setProperty("codigo", codigo);
        if (campo != null) {
            problema.setProperty("campos", Map.of(campo, detalhe));
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }
}
