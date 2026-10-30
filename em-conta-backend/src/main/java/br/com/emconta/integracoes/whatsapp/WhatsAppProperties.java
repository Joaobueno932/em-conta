package br.com.emconta.integracoes.whatsapp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração da WhatsApp Cloud API (integração direta com a Meta). Valores vêm de variáveis de
 * ambiente ou arquivo local ignorado pelo Git; nunca do repositório. Sem os quatro valores
 * obrigatórios o canal fica "não configurado": nada é enviado e o webhook recusa eventos.
 *
 * @param accessToken token de acesso do sistema (envio)
 * @param phoneNumberId identificador do número na Cloud API; eventos de outros números são ignorados
 * @param appSecret App Secret, usado só para validar a assinatura dos eventos do webhook
 * @param verifyToken token da verificação inicial do webhook (diferente do App Secret)
 * @param graphUrl URL base da Graph API (substituível em testes por um servidor controlado)
 * @param graphVersion versão da Graph API usada nas chamadas
 * @param hostsMidia hosts HTTPS de onde a mídia pode ser baixada com o token (URLs devolvidas pela
 *     consulta de mídia); qualquer outro destino é recusado sem enviar o token
 * @param timeoutDownload tempo máximo de um download de mídia
 */
@ConfigurationProperties("emconta.whatsapp")
public record WhatsAppProperties(
        String accessToken,
        String phoneNumberId,
        String appSecret,
        String verifyToken,
        @DefaultValue("https://graph.facebook.com") URI graphUrl,
        @DefaultValue("v23.0") String graphVersion,
        @DefaultValue("5s") Duration timeoutConexao,
        @DefaultValue("20s") Duration timeoutResposta,
        @DefaultValue("lookaside.fbsbx.com") java.util.List<String> hostsMidia,
        @DefaultValue("60s") Duration timeoutDownload) {

    public boolean configurado() {
        return preenchido(accessToken) && preenchido(phoneNumberId) && preenchido(appSecret) && preenchido(verifyToken);
    }

    private static boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }
}
