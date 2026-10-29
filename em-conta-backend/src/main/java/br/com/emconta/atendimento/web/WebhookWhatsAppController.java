package br.com.emconta.atendimento.web;

import br.com.emconta.atendimento.nucleo.Recebimento;
import br.com.emconta.integracoes.WhatsAppCloud;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook da WhatsApp Cloud API. Público (a Meta não tem sessão), mas cada evento é autenticado pela
 * assinatura {@code X-Hub-Signature-256} calculada sobre o corpo bruto com o App Secret; a verificação
 * inicial usa o verify token, que é outro segredo. Sem canal configurado, tudo é recusado.
 *
 * <p>O corpo autenticado é gravado na caixa de entrada antes do 200; o processamento é assíncrono.
 * Fora do contrato OpenAPI da aplicação (é contrato da Meta).
 */
@Hidden
@RestController
@RequestMapping("/api/v1/integracoes/whatsapp/webhook")
class WebhookWhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WebhookWhatsAppController.class);
    /** A Meta documenta payloads de até 3 MB. */
    private static final int TAMANHO_MAXIMO = 3 * 1024 * 1024;

    private final WhatsAppCloud canal;
    private final Recebimento recebimento;

    WebhookWhatsAppController(WhatsAppCloud canal, Recebimento recebimento) {
        this.canal = canal;
        this.recebimento = recebimento;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> verificar(
            @RequestParam(name = "hub.mode", required = false) String modo,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String desafio) {
        return canal.desafio(modo, token, desafio)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> receber(
            @RequestBody(required = false) byte[] corpo,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String assinatura) {
        if (!canal.configurado()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (corpo == null || corpo.length == 0 || corpo.length > TAMANHO_MAXIMO) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!canal.assinaturaValida(corpo, assinatura)) {
            log.warn("WhatsApp: evento do webhook com assinatura inválida recusado");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        recebimento.registrar(corpo);
        return ResponseEntity.ok().build();
    }
}
