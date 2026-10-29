package br.com.emconta.atendimento.nucleo;

import br.com.emconta.integracoes.WhatsAppCloud;
import br.com.emconta.integracoes.WhatsAppCloud.MensagemRecebida;
import br.com.emconta.integracoes.WhatsAppCloud.Notificacao;
import br.com.emconta.integracoes.WhatsAppCloud.SituacaoRecebida;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Caixa de entrada do webhook (inbox) e processamento das notificações.
 *
 * <p>Recebimento durável: o corpo bruto já autenticado é gravado em {@code atd_webhook_evento} e só
 * então o webhook responde 200; a Meta reentrega o que não recebeu 200 por até 7 dias. Reentregas
 * idênticas são descartadas pelo hash do corpo; reentregas com corpo diferente são seguras porque
 * mensagens são únicas por {@code id_externo} e situações nunca regridem.
 *
 * <p>Processamento assíncrono por poller no MySQL ({@code FOR UPDATE SKIP LOCKED}), seguro com várias
 * réplicas e após reinício: o que estava pendente continua pendente. Falhas são retentadas com espera
 * crescente até {@link #MAXIMO_TENTATIVAS} e então ficam em {@code FALHA} para reprocessamento manual.
 */
@Service
public class Recebimento {

    private static final Logger log = LoggerFactory.getLogger(Recebimento.class);
    static final int MAXIMO_TENTATIVAS = 5;

    /** Ordem de confirmação: uma situação só substitui outra de posição menor (sem regressão). */
    private static final Map<String, Integer> POSICAO = Map.of(
            "PENDENTE", 0, "ENVIANDO", 0, "DESCONHECIDA", 0, "ACEITA", 1, "ENVIADA", 2, "ENTREGUE", 3, "LIDA", 4);

    private final JdbcClient jdbc;
    private final WhatsAppCloud canal;
    private final Clock clock;
    private final TransactionTemplate transacao;

    Recebimento(JdbcClient jdbc, WhatsAppCloud canal, Clock clock, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.canal = canal;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Grava o corpo autenticado (idempotente). Devolve true se era novo. */
    public boolean registrar(byte[] corpo) {
        Instant agora = clock.instant();
        try {
            jdbc.sql("insert into atd_webhook_evento (hash_corpo, corpo, recebido_em, situacao, proxima_tentativa_em)"
                            + " values (:hash, :corpo, :agora, 'PENDENTE', :agora)")
                    .param("hash", sha256(corpo))
                    .param("corpo", new String(corpo, StandardCharsets.UTF_8))
                    .param("agora", Timestamp.from(agora))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Processa até {@code limite} eventos pendentes. Devolve quantos foram concluídos. */
    public int processarPendentes(int limite) {
        int concluidos = 0;
        for (int i = 0; i < limite; i++) {
            Optional<Boolean> resultado = processarProximo();
            if (resultado.isEmpty()) {
                break;
            }
            if (resultado.get()) {
                concluidos++;
            }
        }
        return concluidos;
    }

    /** Vazio se não havia evento disponível; true se processado, false se falhou nesta tentativa. */
    private Optional<Boolean> processarProximo() {
        Instant agora = clock.instant();
        AtomicLong emProcessamento = new AtomicLong(-1);
        try {
            Optional<Long> id = Optional.ofNullable(transacao.execute(status -> {
                Optional<Map<String, Object>> linha = jdbc
                        .sql("select id, corpo from atd_webhook_evento"
                                + " where situacao = 'PENDENTE' and proxima_tentativa_em <= :agora"
                                + " order by id limit 1 for update skip locked")
                        .param("agora", Timestamp.from(agora))
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst();
                if (linha.isEmpty()) {
                    return null;
                }
                long evento = ((Number) linha.get().get("id")).longValue();
                emProcessamento.set(evento);
                Notificacao notificacao = canal.interpretar((String) linha.get().get("corpo"));
                for (MensagemRecebida m : notificacao.mensagens()) {
                    registrarMensagem(m, agora);
                }
                for (SituacaoRecebida s : notificacao.situacoes()) {
                    aplicarSituacao(s, agora);
                }
                jdbc.sql("update atd_webhook_evento set situacao = 'PROCESSADO', processado_em = :agora,"
                                + " ignorados = :ignorados, erro = null where id = :id")
                        .param("agora", Timestamp.from(agora))
                        .param("ignorados", notificacao.ignorados())
                        .param("id", evento)
                        .update();
                return evento;
            }));
            return id.isEmpty() ? Optional.empty() : Optional.of(true);
        } catch (RuntimeException e) {
            // A transação do evento foi desfeita; registra a tentativa numa transação separada.
            log.warn(
                    "Atendimento: falha ao processar evento do webhook ({})",
                    e.getClass().getSimpleName());
            if (emProcessamento.get() < 0) {
                throw e; // falha antes de obter um evento (ex.: banco indisponível)
            }
            registrarFalha(emProcessamento.get(), agora, e);
            return Optional.of(false);
        }
    }

    private void registrarFalha(long evento, Instant agora, RuntimeException erro) {
        transacao.executeWithoutResult(status -> jdbc
                .sql("select id, tentativas from atd_webhook_evento"
                        + " where id = :id and situacao = 'PENDENTE' for update")
                .param("id", evento)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .ifPresent(linha -> {
                    int tentativas = ((Number) linha.get("tentativas")).intValue() + 1;
                    boolean definitiva = tentativas >= MAXIMO_TENTATIVAS;
                    jdbc.sql("update atd_webhook_evento set tentativas = :t, situacao = :s,"
                                    + " proxima_tentativa_em = :proxima, erro = :erro where id = :id")
                            .param("t", tentativas)
                            .param("s", definitiva ? "FALHA" : "PENDENTE")
                            .param("proxima", Timestamp.from(agora.plus(Duration.ofSeconds(30L << tentativas))))
                            .param("erro", erro.getClass().getSimpleName())
                            .param("id", linha.get("id"))
                            .update();
                }));
    }

    /** Volta eventos em FALHA para a fila (ação manual autorizada). */
    public int reprocessar(long id) {
        return jdbc.sql("update atd_webhook_evento set situacao = 'PENDENTE', tentativas = 0,"
                        + " proxima_tentativa_em = :agora where id = :id and situacao = 'FALHA'")
                .param("agora", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }

    // ------------------------------------------------------------------ mensagens recebidas

    private void registrarMensagem(MensagemRecebida m, Instant agora) {
        if (m.idExterno() == null || m.waId() == null || m.waId().isBlank()) {
            return;
        }
        if (jdbc.sql("select count(*) from atd_mensagem where id_externo = :id")
                        .param("id", m.idExterno())
                        .query(Long.class)
                        .single()
                > 0) {
            return; // evento repetido
        }
        long contato = contato(m.waId(), m.nomePerfil(), agora);
        long conversa = conversa(contato, agora);
        long atendimento = atendimentoAberto(conversa, agora);
        boolean texto = "text".equals(m.tipo()) && m.texto() != null;
        try {
            jdbc.sql("insert into atd_mensagem (correlacao, conversa_id, atendimento_id, direcao, tipo, tipo_externo,"
                            + " texto, id_externo, situacao, horario_externo, criado_em, atualizado_em)"
                            + " values (:correlacao, :conversa, :atendimento, 'ENTRADA', :tipo, :tipoExterno, :texto,"
                            + " :idExterno, 'RECEBIDA', :horario, :agora, :agora)")
                    .param("correlacao", UUID.randomUUID().toString())
                    .param("conversa", conversa)
                    .param("atendimento", atendimento)
                    .param("tipo", texto ? "TEXTO" : "NAO_SUPORTADO")
                    .param("tipoExterno", m.tipo())
                    .param("texto", texto ? m.texto() : null)
                    .param("idExterno", m.idExterno())
                    .param("horario", m.horario() == null ? null : Timestamp.from(m.horario()))
                    .param("agora", Timestamp.from(agora))
                    .update();
        } catch (DuplicateKeyException e) {
            return; // a mesma mensagem chegou por outra entrega ao mesmo tempo
        }
        Instant horario = m.horario() != null ? m.horario() : agora;
        jdbc.sql("update atd_conversa set ultima_entrada_em = greatest(coalesce(ultima_entrada_em, :h), :h),"
                        + " ultima_mensagem_em = greatest(coalesce(ultima_mensagem_em, :agora), :agora) where id = :id")
                .param("h", Timestamp.from(horario))
                .param("agora", Timestamp.from(agora))
                .param("id", conversa)
                .update();
    }

    private long contato(String waId, String nome, Instant agora) {
        jdbc.sql(
                        "insert into atd_contato (wa_id, nome_perfil, criado_em, atualizado_em) values (:wa, :nome, :agora, :agora)"
                                + " on duplicate key update nome_perfil = coalesce(values(nome_perfil), nome_perfil),"
                                + " atualizado_em = values(atualizado_em)")
                .param("wa", waId)
                .param("nome", nome)
                .param("agora", Timestamp.from(agora))
                .update();
        return jdbc.sql("select id from atd_contato where wa_id = :wa")
                .param("wa", waId)
                .query(Long.class)
                .single();
    }

    private long conversa(long contato, Instant agora) {
        jdbc.sql("insert ignore into atd_conversa (contato_id, criado_em) values (:contato, :agora)")
                .param("contato", contato)
                .param("agora", Timestamp.from(agora))
                .update();
        return jdbc.sql("select id from atd_conversa where contato_id = :contato for update")
                .param("contato", contato)
                .query(Long.class)
                .single();
    }

    /**
     * Ciclo aberto da conversa ou, se não houver (nunca houve ou o último foi encerrado), um novo ciclo
     * AGUARDANDO sem responsável: regra aprovada para mensagens após o encerramento. A conversa já está
     * travada por {@link #conversa}, e o índice único garante um só ciclo aberto.
     */
    private long atendimentoAberto(long conversa, Instant agora) {
        Optional<Long> aberto = jdbc.sql(
                        "select id from atd_atendimento where conversa_id = :c and situacao <> 'ENCERRADO'")
                .param("c", conversa)
                .query(Long.class)
                .optional();
        if (aberto.isPresent()) {
            return aberto.get();
        }
        jdbc.sql("insert into atd_atendimento (conversa_id, situacao, iniciado_em) values (:c, 'AGUARDANDO', :agora)")
                .param("c", conversa)
                .param("agora", Timestamp.from(agora))
                .update();
        long id = jdbc.sql("select id from atd_atendimento where conversa_id = :c and situacao = 'AGUARDANDO'")
                .param("c", conversa)
                .query(Long.class)
                .single();
        jdbc.sql(
                        "insert into atd_evento (conversa_id, atendimento_id, tipo, ocorrido_em) values (:c, :a, 'ATENDIMENTO_INICIADO', :agora)")
                .param("c", conversa)
                .param("a", id)
                .param("agora", Timestamp.from(agora))
                .update();
        return id;
    }

    // ------------------------------------------------------------------ situações de envio

    private void aplicarSituacao(SituacaoRecebida s, Instant agora) {
        String nova = switch (s.situacao()) {
            case "sent" -> "ENVIADA";
            case "delivered" -> "ENTREGUE";
            case "read" -> "LIDA";
            case "failed" -> "FALHA";
            default -> null;
        };
        if (nova == null) {
            return;
        }
        Optional<Map<String, Object>> mensagem = jdbc
                .sql("select id, situacao, id_externo from atd_mensagem"
                        + " where direcao = 'SAIDA' and (id_externo = :externo or correlacao = :correlacao) for update")
                .param("externo", s.idExterno())
                .param("correlacao", s.correlacao() == null ? "" : s.correlacao())
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (mensagem.isEmpty()) {
            return; // mensagem que não foi enviada por este sistema
        }
        long id = ((Number) mensagem.get().get("id")).longValue();
        jdbc.sql("insert ignore into atd_mensagem_situacao (mensagem_id, situacao, horario_externo, erro_codigo,"
                        + " erro_titulo, registrado_em) values (:m, :s, :h, :codigo, :titulo, :agora)")
                .param("m", id)
                .param("s", nova)
                .param("h", s.horario() == null ? null : Timestamp.from(s.horario()))
                .param("codigo", s.erroCodigo())
                .param("titulo", truncar(s.erroTitulo()))
                .param("agora", Timestamp.from(agora))
                .update();
        String atual = (String) mensagem.get().get("situacao");
        if (!avanca(atual, nova)) {
            // Fora de ordem ou repetido: o histórico guarda o evento, a situação atual não regride.
            preencherIdExterno(id, mensagem.get(), s.idExterno(), agora);
            return;
        }
        jdbc.sql("update atd_mensagem set situacao = :s, id_externo = coalesce(id_externo, :externo),"
                        + " erro_codigo = coalesce(:codigo, erro_codigo), erro_titulo = coalesce(:titulo, erro_titulo),"
                        + " atualizado_em = :agora where id = :id")
                .param("s", nova)
                .param("externo", s.idExterno())
                .param("codigo", "FALHA".equals(nova) ? s.erroCodigo() : null)
                .param("titulo", "FALHA".equals(nova) ? truncar(s.erroTitulo()) : null)
                .param("agora", Timestamp.from(agora))
                .param("id", id)
                .update();
    }

    private void preencherIdExterno(long id, Map<String, Object> mensagem, String externo, Instant agora) {
        if (mensagem.get("id_externo") == null && externo != null) {
            jdbc.sql("update atd_mensagem set id_externo = :externo, atualizado_em = :agora where id = :id")
                    .param("externo", externo)
                    .param("agora", Timestamp.from(agora))
                    .param("id", id)
                    .update();
        }
    }

    /**
     * Regras: avança apenas para posição maior; FALHA vale enquanto a entrega não foi confirmada
     * (não apaga ENTREGUE/LIDA) e não é removida por eventos atrasados de "enviada".
     */
    static boolean avanca(String atual, String nova) {
        if ("FALHA".equals(nova)) {
            return POSICAO.getOrDefault(atual, 0) < 3 && !"FALHA".equals(atual);
        }
        if ("FALHA".equals(atual)) {
            return POSICAO.get(nova) >= 3;
        }
        return POSICAO.get(nova) > POSICAO.getOrDefault(atual, 0);
    }

    private static String truncar(String valor) {
        return valor == null || valor.length() <= 300 ? valor : valor.substring(0, 300);
    }

    static String sha256(byte[] corpo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Evento do webhook em falha definitiva (só metadados; o corpo não sai daqui). */
    public record FalhaWebhook(long id, Instant recebidoEm, int tentativas, String erro) {}

    public List<FalhaWebhook> falhas(int limite) {
        return jdbc.sql("select id, recebido_em, tentativas, erro from atd_webhook_evento where situacao = 'FALHA'"
                        + " order by id desc limit :limite")
                .param("limite", limite)
                .query((rs, n) -> new FalhaWebhook(
                        rs.getLong("id"),
                        rs.getTimestamp("recebido_em").toInstant(),
                        rs.getInt("tentativas"),
                        rs.getString("erro")))
                .list();
    }
}
