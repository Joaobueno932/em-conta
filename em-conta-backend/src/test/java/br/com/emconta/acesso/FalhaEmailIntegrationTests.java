package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import br.com.emconta.acesso.usuarios.Perfil;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** SMTP indisponível: a operação não informa envio bem-sucedido e não deixa link válido sem destinatário. */
class FalhaEmailIntegrationTests extends AcessoIntegracao {

    @MockitoBean
    JavaMailSender mailSender;

    @BeforeEach
    void smtpFora() {
        doThrow(new MailSendException("SMTP fora do ar")).when(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void conviteInformaFalhaDeEnvioEInvalidaOLink() {
        String adminEmail = emailUnico("adm-falha");
        criarAtivo(adminEmail, Perfil.ADMINISTRADOR);
        Navegador admin = logado(adminEmail);

        MockHttpServletResponse resposta =
                admin.post("/api/v1/usuarios", Map.of("nome", "Sem E-mail", "email", emailUnico("sem-email")));

        assertThat(resposta.getStatus()).isEqualTo(201);
        assertThat(corpo(resposta).get("conviteEnviado").asBoolean()).isFalse();
        long id = corpo(resposta).at("/usuario/id").asLong();
        assertThat(tokensValidos(id)).isZero();
        assertThat(eventos(id, "CONVITE_EMAIL_FALHOU")).isEqualTo(1);

        MockHttpServletResponse reenvio = admin.post("/api/v1/usuarios/" + id + "/convite", null);
        assertThat(corpo(reenvio).get("conviteEnviado").asBoolean()).isFalse();
        assertThat(tokensValidos(id)).isZero();
    }

    @Test
    void recuperacaoComFalhaDeEnvioMantemRespostaGenericaEInvalidaOToken() {
        String email = emailUnico("recupera-falha");
        long id = criarAtivo(email).getId();

        MockHttpServletResponse resposta = navegador().post("/api/v1/auth/senha/recuperacao", Map.of("email", email));
        assertThat(resposta.getStatus()).isEqualTo(202);

        Instant limite = Instant.now().plusSeconds(10);
        while (eventos(id, "RECUPERACAO_EMAIL_FALHOU") == 0 && Instant.now().isBefore(limite)) {
            Mailpit.dormir();
        }
        assertThat(eventos(id, "RECUPERACAO_EMAIL_FALHOU")).isEqualTo(1);
        assertThat(tokensValidos(id)).isZero();
    }

    private int tokensValidos(long usuarioId) {
        return jdbc.sql("select count(*) from token_acesso where usuario_id = :id"
                        + " and consumido_em is null and invalidado_em is null")
                .param("id", usuarioId)
                .query(Integer.class)
                .single();
    }

    private int eventos(long usuarioId, String tipo) {
        return jdbc.sql("select count(*) from auditoria_seguranca where alvo_usuario_id = :id and tipo = :tipo")
                .param("id", usuarioId)
                .param("tipo", tipo)
                .query(Integer.class)
                .single();
    }
}
