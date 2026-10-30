package br.com.emconta.atendimento.nucleo;

import br.com.emconta.integracoes.ArmazenamentoObjetos;
import br.com.emconta.integracoes.WhatsAppCloud;
import br.com.emconta.integracoes.WhatsAppCloud.Aceita;
import br.com.emconta.integracoes.WhatsAppCloud.Desconhecido;
import br.com.emconta.integracoes.WhatsAppCloud.FalhaMidia;
import br.com.emconta.integracoes.WhatsAppCloud.MidiaEnviada;
import br.com.emconta.integracoes.WhatsAppCloud.Recusada;
import br.com.emconta.integracoes.WhatsAppCloud.ResultadoEnvio;
import br.com.emconta.integracoes.WhatsAppCloud.ResultadoMidia;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    static final String JANELA_EXPIRADA =
            "Não enviada: a janela de 24 horas expirou antes do envio. Nada foi enviado ao WhatsApp.";
    static final String JANELA_EXPIRADA_APOS_UPLOAD = "Não enviada: a janela de 24 horas expirou durante a preparação."
            + " O arquivo foi carregado no WhatsApp, mas nenhuma mensagem foi enviada ao contato.";

    private final JdbcClient jdbc;
    private final WhatsAppCloud canal;
    private final ArmazenamentoObjetos armazenamento;
    private final Clock clock;
    private final TransactionTemplate transacao;

    Envio(
            JdbcClient jdbc,
            WhatsAppCloud canal,
            ArmazenamentoObjetos armazenamento,
            Clock clock,
            PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.canal = canal;
        this.armazenamento = armazenamento;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Reserva(
            long id,
            String correlacao,
            String para,
            String texto,
            int tentativas,
            boolean janelaExpirada,
            AnexoSaida anexo) {}

    /** Anexo de uma mensagem de saída: arquivo no armazenamento e, depois do upload, o id na Meta. */
    private record AnexoSaida(long id, String categoria, String chave, String mime, String idMidia, String nome) {}

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
            processadas++;
            if (reserva.get().janelaExpirada()) {
                continue; // já registrada como JANELA_EXPIRADA, sem chamada à Cloud API
            }
            Reserva r = reserva.get();
            if (r.anexo() == null) {
                registrar(r, canal.enviarTexto(r.para(), r.texto(), r.correlacao()));
                continue;
            }
            Optional<String> idMidia = enviarArquivo(r);
            // Ler o armazenamento e subir o arquivo levam tempo: a janela é conferida de novo logo antes
            // da chamada que envia a mensagem ao contato (sobra só o intervalo inevitável até a requisição).
            if (idMidia.isPresent() && !janelaAberta(r.id())) {
                expirarDepoisDoUpload(r);
                continue;
            }
            if (idMidia.isPresent()) {
                FormatosAnexo.Categoria categoria =
                        FormatosAnexo.Categoria.valueOf(r.anexo().categoria());
                registrar(
                        r,
                        canal.enviarMidia(
                                r.para(),
                                categoria.tipoCloud,
                                idMidia.get(),
                                categoria.aceitaLegenda ? r.texto() : null,
                                r.anexo().nome(),
                                r.correlacao()));
            }
        }
        return processadas;
    }

    private Optional<Reserva> reservar() {
        Instant agora = clock.instant();
        return Optional.ofNullable(transacao.execute(status -> {
            Optional<Map<String, Object>> linha = jdbc
                    .sql("select m.id, m.correlacao, m.texto, m.tentativas, c.wa_id, m.conversa_id, a.id as anexo_id,"
                            + " a.categoria, a.chave_objeto, a.mime_type, a.id_midia_externo, a.nome"
                            + " from atd_mensagem m join atd_conversa v on v.id = m.conversa_id"
                            + " join atd_contato c on c.id = v.contato_id"
                            + " left join atd_anexo a on a.mensagem_id = m.id"
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
            // A janela é conferida de novo a cada tentativa, com a última entrada atual do contato (uma
            // mensagem nova dele renova a janela das respostas ainda pendentes).
            Instant entrada = jdbc.sql("select ultima_entrada_em from atd_conversa where id = :id")
                    .param("id", linha.get().get("conversa_id"))
                    .query((rs, n) -> rs.getTimestamp(1) == null
                            ? null
                            : rs.getTimestamp(1).toInstant())
                    .single();
            if (!Atendimentos.janelaAberta(entrada, agora)) {
                jdbc.sql(
                                "update atd_mensagem set situacao = 'JANELA_EXPIRADA', erro_codigo = null,"
                                        + " erro_titulo = :motivo, proxima_tentativa_em = null, atualizado_em = :agora where id = :id")
                        .param("motivo", JANELA_EXPIRADA)
                        .param("agora", Timestamp.from(agora))
                        .param("id", id)
                        .update();
                historico(id, "JANELA_EXPIRADA", null, JANELA_EXPIRADA, agora);
                return new Reserva(id, null, null, null, tentativas - 1, true, null);
            }
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
                    tentativas,
                    false,
                    linha.get().get("anexo_id") == null
                            ? null
                            : new AnexoSaida(
                                    ((Number) linha.get().get("anexo_id")).longValue(),
                                    (String) linha.get().get("categoria"),
                                    (String) linha.get().get("chave_objeto"),
                                    (String) linha.get().get("mime_type"),
                                    (String) linha.get().get("id_midia_externo"),
                                    (String) linha.get().get("nome")));
        }));
    }

    /**
     * Etapa de upload: o arquivo sai do armazenamento privado para um temporário e vai à Meta
     * ({@code POST /media}), fora de transação. O id da mídia é gravado no anexo, então uma nova tentativa
     * de envio não repete o upload. Falha aqui não envia nada ao contato: temporária volta para a fila
     * (até {@link #MAXIMO_TENTATIVAS}); definitiva vira FALHA com etapa UPLOAD.
     */
    private Optional<String> enviarArquivo(Reserva reserva) {
        AnexoSaida anexo = reserva.anexo();
        if (anexo.idMidia() != null) {
            return Optional.of(anexo.idMidia());
        }
        FormatosAnexo.Formato formato = FormatosAnexo.porMime(anexo.mime()).orElse(null);
        if (formato == null || anexo.chave() == null) {
            falharUpload(reserva, new FalhaMidia(null, "Anexo sem arquivo válido", false));
            return Optional.empty();
        }
        Path temporario = null;
        try {
            temporario = Files.createTempFile("emconta-envio-", ".tmp");
            Optional<ArmazenamentoObjetos.Objeto> objeto = armazenamento.abrir(anexo.chave(), null);
            if (objeto.isEmpty()) {
                falharUpload(reserva, new FalhaMidia(null, "Arquivo não encontrado no armazenamento", false));
                return Optional.empty();
            }
            try (ArmazenamentoObjetos.Objeto o = objeto.get()) {
                Files.copy(o.conteudo(), temporario, StandardCopyOption.REPLACE_EXISTING);
            }
            ResultadoMidia resultado = canal.enviarArquivo(temporario, formato.mime(), formato.extensaoPadrao());
            if (resultado instanceof MidiaEnviada enviada) {
                transacao.executeWithoutResult(status -> jdbc.sql(
                                "update atd_anexo set id_midia_externo = :midia, atualizado_em = :agora where id = :id")
                        .param("midia", enviada.idMidia())
                        .param("agora", Timestamp.from(clock.instant()))
                        .param("id", anexo.id())
                        .update());
                return Optional.of(enviada.idMidia());
            }
            falharUpload(reserva, (FalhaMidia) resultado);
            return Optional.empty();
        } catch (ArmazenamentoObjetos.FalhaArmazenamento | IOException e) {
            falharUpload(reserva, new FalhaMidia(null, "Armazenamento indisponível para o upload", true));
            return Optional.empty();
        } finally {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException ignorada) {
                    // temporário do sistema
                }
            }
        }
    }

    /** Janela de 24 h com a última entrada atual da conversa da mensagem e o relógio da aplicação. */
    private boolean janelaAberta(long mensagem) {
        Instant entrada = jdbc.sql("select v.ultima_entrada_em from atd_mensagem m join atd_conversa v"
                        + " on v.id = m.conversa_id where m.id = :id")
                .param("id", mensagem)
                .query((rs, n) ->
                        rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .single();
        return Atendimentos.janelaAberta(entrada, clock.instant());
    }

    /**
     * A janela expirou depois do upload: o arquivo já está na Meta (o id da mídia fica gravado no anexo),
     * mas nenhuma mensagem foi enviada ao contato. Situação final, sem nova tentativa automática.
     */
    private void expirarDepoisDoUpload(Reserva reserva) {
        Instant agora = clock.instant();
        transacao.executeWithoutResult(status -> {
            int alteradas = jdbc.sql("update atd_mensagem set situacao = 'JANELA_EXPIRADA', erro_codigo = null,"
                            + " erro_titulo = :motivo, etapa_falha = null, proxima_tentativa_em = null,"
                            + " atualizado_em = :agora where id = :id and situacao = 'ENVIANDO'")
                    .param("motivo", JANELA_EXPIRADA_APOS_UPLOAD)
                    .param("agora", Timestamp.from(agora))
                    .param("id", reserva.id())
                    .update();
            if (alteradas > 0) {
                historico(reserva.id(), "JANELA_EXPIRADA", null, JANELA_EXPIRADA_APOS_UPLOAD, agora);
            }
        });
    }

    private void falharUpload(Reserva reserva, FalhaMidia falha) {
        Instant agora = clock.instant();
        boolean novaTentativa = falha.temporaria() && reserva.tentativas() < MAXIMO_TENTATIVAS;
        String titulo = truncar("Upload do arquivo ao WhatsApp: " + falha.titulo());
        transacao.executeWithoutResult(status -> {
            jdbc.sql("update atd_mensagem set situacao = :s, proxima_tentativa_em = :proxima, erro_codigo = :codigo,"
                            + " erro_titulo = :titulo, etapa_falha = 'UPLOAD', atualizado_em = :agora"
                            + " where id = :id and situacao = 'ENVIANDO'")
                    .param("s", novaTentativa ? "PENDENTE" : "FALHA")
                    .param(
                            "proxima",
                            novaTentativa
                                    ? Timestamp.from(agora.plus(Duration.ofSeconds(10L << reserva.tentativas())))
                                    : null)
                    .param("codigo", falha.codigo())
                    .param("titulo", titulo)
                    .param("agora", Timestamp.from(agora))
                    .param("id", reserva.id())
                    .update();
            if (!novaTentativa) {
                historico(reserva.id(), "FALHA", falha.codigo(), titulo, agora);
            }
        });
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
                    jdbc.sql(
                                    "update atd_mensagem set situacao = 'FALHA', erro_codigo = :codigo, erro_titulo = :titulo, etapa_falha = 'ENVIO',"
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
