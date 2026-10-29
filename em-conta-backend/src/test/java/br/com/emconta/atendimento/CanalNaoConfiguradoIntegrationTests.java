package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.emconta.ApiIntegracao;
import br.com.emconta.acesso.Navegador;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Sem credenciais (situação atual do ambiente de trabalho): canal "não configurado", nenhum envio
 * fictício e webhook recusando verificação e eventos.
 */
class CanalNaoConfiguradoIntegrationTests extends ApiIntegracao {

    @Test
    void semCredenciaisNadaEEnviadoEOWebhookRecusa() throws Exception {
        Navegador admin = administrador();
        assertThat(corpo(admin.get("/api/v1/atendimento/canal"))
                        .get("configurado")
                        .asBoolean())
                .isFalse();
        assertThat(mvc.perform(get("/api/v1/integracoes/whatsapp/webhook")
                                .param("hub.mode", "subscribe")
                                .param("hub.verify_token", "")
                                .param("hub.challenge", "1"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(post("/api/v1/integracoes/whatsapp/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}")
                                .header("X-Hub-Signature-256", "sha256=00"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(503);
        assertThat(jdbc.sql("select count(*) from atd_webhook_evento")
                        .query(Long.class)
                        .single())
                .isZero();
        // Sem canal não há atendimento a responder; a rota existe e continua protegida por permissão.
        assertThat(new Navegador(mvc, json).get("/api/v1/atendimento/canal").getStatus())
                .isEqualTo(401);
    }
}
