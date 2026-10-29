package br.com.emconta.acesso.email;

import br.com.emconta.acesso.config.AcessoProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * E-mails de convite e de redefinição de senha, em texto simples, enviados por SMTP (Mailpit no
 * ambiente local). O envio é síncrono e ocorre fora de transação; quem chama decide o que fazer em
 * caso de falha. Links e endereços nunca vão para o log.
 */
@Component
public class EmailsAcesso {

    private static final Logger log = LoggerFactory.getLogger(EmailsAcesso.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AcessoProperties properties;

    EmailsAcesso(ObjectProvider<JavaMailSender> mailSender, AcessoProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public boolean enviarConvite(String destinatario, String nome, String link, Duration validade) {
        String texto = """
                Olá, %s.

                Você foi convidado(a) para acessar o Em Conta.

                Para definir sua senha e ativar o acesso, abra o link abaixo:
                %s

                O link é pessoal, pode ser usado uma única vez e vale por %s.
                Se ele expirar, peça um novo convite ao administrador do sistema.

                Se você não esperava este convite, ignore esta mensagem.

                Em Conta
                """.formatted(nome, link, descrever(validade));
        return enviar("convite", destinatario, "Em Conta: convite de acesso", texto);
    }

    public boolean enviarRedefinicao(String destinatario, String nome, String link, Duration validade) {
        String texto = """
                Olá, %s.

                Recebemos uma solicitação para redefinir a sua senha do Em Conta.

                Para criar uma nova senha, abra o link abaixo:
                %s

                O link pode ser usado uma única vez e vale por %s. Ao redefinir a senha, as sessões
                abertas em outros dispositivos serão encerradas.

                Se você não fez esta solicitação, ignore esta mensagem: sua senha atual continua valendo.

                Em Conta
                """.formatted(nome, link, descrever(validade));
        return enviar("redefinição de senha", destinatario, "Em Conta: redefinição de senha", texto);
    }

    private boolean enviar(String tipo, String destinatario, String assunto, String texto) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.error("E-mail de {} não enviado: SMTP não configurado (spring.mail.host)", tipo);
            return false;
        }
        SimpleMailMessage mensagem = new SimpleMailMessage();
        mensagem.setFrom(properties.email().remetente());
        mensagem.setTo(destinatario);
        mensagem.setSubject(assunto);
        mensagem.setText(texto);
        try {
            sender.send(mensagem);
            return true;
        } catch (MailException e) {
            // Apenas o tipo do erro: a mensagem do servidor SMTP pode conter o destinatário.
            log.warn("Falha ao enviar e-mail de {}: {}", tipo, e.getClass().getSimpleName());
            return false;
        }
    }

    static String descrever(Duration validade) {
        long horas = validade.toHours();
        if (horas >= 24 && validade.toHoursPart() == 0 && horas % 24 == 0) {
            long dias = horas / 24;
            return dias == 1 ? "1 dia" : dias + " dias";
        }
        if (horas >= 1 && validade.toMinutesPart() == 0) {
            return horas == 1 ? "1 hora" : horas + " horas";
        }
        long minutos = validade.toMinutes();
        return minutos == 1 ? "1 minuto" : minutos + " minutos";
    }
}
