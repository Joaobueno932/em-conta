package br.com.emconta.atendimento.nucleo;

import br.com.emconta.integracoes.WhatsAppCloud;
import br.com.emconta.integracoes.WhatsAppCloud.Aceita;
import br.com.emconta.integracoes.WhatsAppCloud.Desconhecido;
import br.com.emconta.integracoes.WhatsAppCloud.Recusada;
import br.com.emconta.integracoes.WhatsAppCloud.ResultadoEnvio;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Envio das mensagens enfileiradas (outbox no MySQL). A resposta do atendente grava a mensagem como
 * PENDENTE na mesma transação que valida as regras; este serviço a envia depois, sem transação aberta
 * durante a chamada HTTP:
 *
 * <ol>
 *   <li>reserva (PENDENTE → ENVIANDO) em transação curta, com {@code FOR UPDATE SKIP LOCKED};
 *   <li>chama a Cloud API fora de transação;
 *   <li>grava o resultado: ACEITA (com o wamid; não é confirmação de entrega), FALHA definitiva,
 *       PENDENTE com espera crescente (erro temporário, até {@link #MAXIMO_TENTATIVAS}) ou DESCONHECIDA.
 * </ol>
 *
 * <p>DESCONHECIDA (tempo esgotado ou conexão interrompida depois do envio) nunca é reenviada
 * automaticamente: a Cloud API não oferece chave de idempotência, e reenviar poderia duplicar a mensagem
 * no celular do cliente. A correlação vem depois pelo webhook ({@code biz_opaque_callback_data} = nossa
 * correlação), que preenche o wamid e a situação real. Mensagem que ficou em ENVIANDO por mais de
 * {@link #RESERVA_EXPIRADA} (processo reiniciado no meio do envio) também vira DESCONHECIDA.
 */
@Service
public class Envio {

    static final int MAXIMO_TENTATIVAS = 3;
    static final Duration RESERVA_EXPIRADA = Duration.ofMinutes(2);

    private final JdbcClient jdbc;
    private final WhatsAppCloud canal;
    private final Clock clock;
    private final TransactionTemplate transacao;

    Envio(JdbcClient jdbc, WhatsAppCloud canal, Clock clock, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.canal = canal;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Reserva(long id, String correlacao, String para, String texto, int tentativas) {}

    /** Envia até {@code limite} mensagens pendentes. Devolve quantas foram processadas. */
    public int enviarPendentes(int limite) {
        recuperarReservasExpiradas();
        if (!canal.configurado()) {
            return 0;
        }
        int processadas = 0;
        for (int i = 0; i < limite; i++) {
            Optional<Reserva> reserva = reservar();
            if (reserva.isEmpty()) {
                break;
            }
            ResultadoEnvio resultado = canal.enviarTexto(
                    reserva.get().para(), reserva.get().texto(), reserva.get().correlacao());
            registrar(reserva.get(), resultado);
            processadas++;
        }
        return processadas;
    }

    private Optional<Reserva> reservar() {
        Instant agora = clock.instant();
        return Optional.ofNullable(transacao.execute(status -> {
            Optional<Map<String, Object>> linha = jdbc
                    .sql("select m.id, m.correlacao, m.texto, m.tentativas, c.wa_id"
                            + " from atd_mensagem m join atd_conversa v on v.id = m.conversa_id"
                            + " join atd_contato c on c.id = v.contato_id"
                            + " where m.situacao = 'PENDENTE' and m.direcao = 'SAIDA'"
                            + " and (m.proxima_tentativa_em is null or m.proxima_tentativa_em <= :agora)"
                            + " order by m.id limit 1 for update of m skip locked")
                    .param("agora", Timestamp.from(agora))
                    .query()
                    .listOfRows()
                    .stream()
                    .findFirst();
            if (linha.isEmpty()) {
                return null;
            }
            long id = ((Number) linha.get().get("id")).longValue();
            int tentativas = ((Number) linha.get().get("tentativas")).intValue() + 1;
            jdbc.sql(
                            "update atd_mensagem set situacao = 'ENVIANDO', tentativas = :t, atualizado_em = :agora where id = :id")
                    .param("t", tentativas)
                    .param("agora", Timestamp.from(agora))
                    .param("id", id)
                    .update();
            return new Reserva(
                    id,
                    (String) linha.get().get("correlacao"),
                    (String) linha.get().get("wa_id"),
                    (String) linha.get().get("texto"),
                    tentativas);
        }));
    }

    private void registrar(Reserva reserva, ResultadoEnvio resultado) {
        Instant agora = clock.instant();
        transacao.executeWithoutResult(status -> {
            switch (resultado) {
                case Aceita aceita -> {
                    // Um evento de situação pode ter chegado antes desta gravação (correlação): não regride.
                    jdbc.sql("update atd_mensagem set id_externo = coalesce(id_externo, :externo),"
                                    + " situacao = if(situacao in ('ENVIANDO', 'DESCONHECIDA'), 'ACEITA', situacao),"
                                    + " atualizado_em = :agora where id = :id")
                            .param("externo", aceita.idExterno())
                            .param("agora", Timestamp.from(agora))
                            .param("id", reserva.id())
                            .update();
                    historico(reserva.id(), "ACEITA", null, null, agora);
                }
                case Recusada recusada
                when recusada.temporaria() && reserva.tentativas() < MAXIMO_TENTATIVAS ->
                    jdbc.sql("update atd_mensagem set situacao = 'PENDENTE', proxima_tentativa_em = :proxima,"
                                    + " erro_codigo = :codigo, erro_titulo = :titulo, atualizado_em = :agora"
                                    + " where id = :id and situacao = 'ENVIANDO'")
                            .param(
                                    "proxima",
                                    Timestamp.from(agora.plus(Duration.ofSeconds(10L << reserva.tentativas()))))
                            .param("codigo", recusada.codigo())
                            .param("titulo", truncar(recusada.titulo()))
                            .param("agora", Timestamp.from(agora))
                            .param("id", reserva.id())
                            .update();
                case Recusada recusada -> {
                    jdbc.sql("update atd_mensagem set situacao = 'FALHA', erro_codigo = :codigo, erro_titulo = :titulo,"
                                    + " atualizado_em = :agora where id = :id and situacao = 'ENVIANDO'")
                            .param("codigo", recusada.codigo())
                            .param("titulo", truncar(recusada.titulo()))
                            .param("agora", Timestamp.from(agora))
                            .param("id", reserva.id())
                            .update();
                    historico(reserva.id(), "FALHA", recusada.codigo(), recusada.titulo(), agora);
                }
                case Desconhecido desconhecido -> {
                    jdbc.sql("update atd_mensagem set situacao = 'DESCONHECIDA', erro_titulo = :motivo,"
                                    + " atualizado_em = :agora where id = :id and situacao = 'ENVIANDO'")
                            .param("motivo", truncar(desconhecido.motivo()))
                            .param("agora", Timestamp.from(agora))
                            .param("id", reserva.id())
                            .update();
                    historico(reserva.id(), "DESCONHECIDA", null, desconhecido.motivo(), agora);
                }
            }
        });
    }

    /** Reservas antigas (processo caiu durante o envio): resultado desconhecido, sem reenvio. */
    private void recuperarReservasExpiradas() {
        Instant agora = clock.instant();
        jdbc.sql("update atd_mensagem set situacao = 'DESCONHECIDA', erro_titulo = :motivo, atualizado_em = :agora"
                        + " where situacao = 'ENVIANDO' and atualizado_em < :limite")
                .param("motivo", "Envio interrompido; resultado desconhecido")
                .param("agora", Timestamp.from(agora))
                .param("limite", Timestamp.from(agora.minus(RESERVA_EXPIRADA)))
                .update();
    }

    private void historico(long mensagem, String situacao, Integer codigo, String titulo, Instant agora) {
        jdbc.sql(
                        "insert ignore into atd_mensagem_situacao (mensagem_id, situacao, erro_codigo, erro_titulo, registrado_em)"
                                + " values (:m, :s, :codigo, :titulo, :agora)")
                .param("m", mensagem)
                .param("s", situacao)
                .param("codigo", codigo)
                .param("titulo", truncar(titulo))
                .param("agora", Timestamp.from(agora))
                .update();
    }

    private static String truncar(String valor) {
        return valor == null || valor.length() <= 300 ? valor : valor.substring(0, 300);
    }
}
