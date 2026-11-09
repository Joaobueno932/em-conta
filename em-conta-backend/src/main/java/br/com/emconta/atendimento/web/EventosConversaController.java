package br.com.emconta.atendimento.web;

import br.com.emconta.atendimento.nucleo.ConsultaAtendimento;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Avisos de mudança da conversa aberta, por Server-Sent Events ({@link NotificadorConversas}). A
 * permissão (atendimento:consultar) é exigida na SecurityConfiguration; o atendimento precisa existir.
 */
@RestController
@RequestMapping("/api/v1/atendimento/atendimentos/{id}")
@Tag(name = "Atendimento", description = "Conversas do WhatsApp, fila e histórico")
class EventosConversaController {

    private final ConsultaAtendimento consulta;
    private final NotificadorConversas notificador;

    EventosConversaController(ConsultaAtendimento consulta, NotificadorConversas notificador) {
        this.consulta = consulta;
        this.notificador = notificador;
    }

    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            operationId = "eventosAtendimento",
            summary = "Avisos de mudança na conversa (SSE): \"pronto\" ao conectar e \"mudanca\" a cada alteração"
                    + " confirmada; sem conteúdo, a tela relê a linha do tempo (atendimento:consultar)")
    @ApiResponse(
            responseCode = "200",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "Atendimento não encontrado")
    SseEmitter eventos(@PathVariable long id, HttpServletResponse resposta) {
        var detalhe = consulta.detalhe(id).orElseThrow(NaoEncontrado::new);
        // Proxies (Nginx) não podem acumular o fluxo.
        resposta.setHeader("X-Accel-Buffering", "no");
        return notificador.assinar(detalhe.resumo().conversaId());
    }
}
