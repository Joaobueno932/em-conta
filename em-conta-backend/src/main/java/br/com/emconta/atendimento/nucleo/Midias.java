package br.com.emconta.atendimento.nucleo;

import br.com.emconta.integracoes.ArmazenamentoObjetos;
import br.com.emconta.integracoes.ArmazenamentoObjetos.FalhaArmazenamento;
import br.com.emconta.integracoes.WhatsAppCloud;
import br.com.emconta.integracoes.WhatsAppCloud.FalhaMidia;
import br.com.emconta.integracoes.WhatsAppCloud.InfoMidia;
import br.com.emconta.integracoes.WhatsAppCloud.MidiaBaixada;
import br.com.emconta.integracoes.WhatsAppCloud.ResultadoMidia;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Download dos anexos recebidos e limpeza de uploads abandonados. A mensagem e o anexo PENDENTE já foram
 * gravados pelo {@link Recebimento}; aqui, para cada anexo:
 *
 * <ol>
 *   <li>reserva (PENDENTE/FALHA_TEMPORARIA → PROCESSANDO) em transação curta, com {@code SKIP LOCKED};
 *   <li>fora de transação: consulta a URL temporária pelo id da mídia (uma nova a cada tentativa, então
 *       URL expirada só atrasa), baixa para arquivo temporário com limite, valida assinatura, tamanho e
 *       hash informado pela Meta, e grava no armazenamento privado sob chave opaca;
 *   <li>marca DISPONIVEL (ou FALHA_TEMPORARIA com espera crescente, ou FALHA definitiva) só se a reserva
 *       ainda for desta instância.
 * </ol>
 *
 * <p>Reinício no meio do processo: a reserva expira e volta para nova tentativa; repetir o download é
 * seguro. Nada é baixado dentro da requisição do webhook.
 */
@Service
@EnableConfigurationProperties(AnexosProperties.class)
public class Midias {

    private static final Logger log = LoggerFactory.getLogger(Midias.class);

    static final Duration RESERVA_EXPIRADA = Duration.ofMinutes(5);
    static final String PREFIXO = "atendimento/anexos/";

    private final JdbcClient jdbc;
    private final WhatsAppCloud canal;
    private final ArmazenamentoObjetos armazenamento;
    private final AnexosProperties propriedades;
    private final Clock clock;
    private final TransactionTemplate transacao;

    Midias(
            JdbcClient jdbc,
            WhatsAppCloud canal,
            ArmazenamentoObjetos armazenamento,
            AnexosProperties propriedades,
            Clock clock,
            PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.canal = canal;
        this.armazenamento = armazenamento;
        this.propriedades = propriedades;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Chave opaca nova para um objeto de anexo. */
    static String novaChave() {
        return PREFIXO + UUID.randomUUID();
    }

    private record Reserva(long id, String idMidia, String mime, String shaProvedor, int tentativas) {}

    /** Processa até {@code limite} downloads pendentes. Devolve quantos foram processados. */
    public int processarPendentes(int limite) {
        limparUploadsAbandonados();
        recuperarReservasExpiradas();
        if (!canal.configurado() || !armazenamento.configurado()) {
            return 0;
        }
        int processados = 0;
        for (int i = 0; i < limite; i++) {
            Optional<Reserva> reserva = reservar();
            if (reserva.isEmpty()) {
                break;
            }
            processar(reserva.get());
            processados++;
        }
        return processados;
    }

    private Optional<Reserva> reservar() {
        Instant agora = clock.instant();
        return Optional.ofNullable(transacao.execute(status -> {
            Optional<Map<String, Object>> linha = jdbc
                    .sql("select id, id_midia_externo, mime_type, sha256_provedor, tentativas from atd_anexo"
                            + " where situacao in ('PENDENTE', 'FALHA_TEMPORARIA') and direcao = 'ENTRADA'"
                            + " and (proxima_tentativa_em is null or proxima_tentativa_em <= :agora)"
                            + " order by id limit 1 for update skip locked")
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
            jdbc.sql("update atd_anexo set situacao = 'PROCESSANDO', reservado_em = :agora, tentativas = :t,"
                            + " atualizado_em = :agora where id = :id")
                    .param("agora", Timestamp.from(agora))
                    .param("t", tentativas)
                    .param("id", id)
                    .update();
            return new Reserva(
                    id,
                    (String) linha.get().get("id_midia_externo"),
                    (String) linha.get().get("mime_type"),
                    (String) linha.get().get("sha256_provedor"),
                    tentativas);
        }));
    }

    private void processar(Reserva reserva) {
        Path temporario = null;
        String chave = null;
        try {
            FormatosAnexo.Formato formato =
                    FormatosAnexo.porMime(reserva.mime()).orElse(null);
            if (formato == null || reserva.idMidia() == null) {
                falhar(reserva, false, "Formato não suportado nesta versão");
                return;
            }
            long limite = propriedades.limite(formato.categoria());
            ResultadoMidia consulta = canal.consultarMidia(reserva.idMidia());
            if (consulta instanceof FalhaMidia falha) {
                falhar(reserva, falha.temporaria(), "Consulta da mídia: " + falha.titulo());
                return;
            }
            InfoMidia info = (InfoMidia) consulta;
            if (info.mimeType() != null && !formato.mime().equals(FormatosAnexo.mimeBase(info.mimeType()))) {
                falhar(reserva, false, "Tipo informado pela Meta diverge do recebido");
                return;
            }
            if (info.tamanho() != null && info.tamanho() > limite) {
                falhar(reserva, false, "Arquivo acima do limite de " + FormatosAnexo.tamanhoLegivel(limite) + "");
                return;
            }
            temporario = Files.createTempFile("emconta-anexo-", ".tmp");
            ResultadoMidia download = canal.baixarMidia(info.url(), temporario, limite);
            if (download instanceof FalhaMidia falha) {
                falhar(reserva, falha.temporaria(), "Download: " + falha.titulo());
                return;
            }
            long baixados = ((MidiaBaixada) download).bytes();
            ArquivoValidado validado;
            try {
                validado = ArquivoValidado.de(temporario, formato, limite);
            } catch (ArquivoValidado.Invalido e) {
                falhar(reserva, false, e.getMessage());
                return;
            }
            if (info.tamanho() != null && info.tamanho() != baixados) {
                falhar(reserva, false, "Tamanho baixado diverge do informado pela Meta");
                return;
            }
            String shaEsperado = reserva.shaProvedor() != null ? reserva.shaProvedor() : info.sha256();
            if (!FormatosAnexo.hashConfere(shaEsperado, validado.sha256())) {
                falhar(reserva, false, "Hash do arquivo não confere com o informado pela Meta");
                return;
            }
            chave = novaChave();
            armazenamento.gravar(chave, temporario, formato.mime());
            String chaveGravada = chave;
            Integer atualizados = transacao.execute(status -> jdbc.sql(
                            "update atd_anexo set situacao = 'DISPONIVEL', chave_objeto = :chave, tamanho = :tamanho,"
                                    + " sha256 = :sha, erro = null, reservado_em = null, proxima_tentativa_em = null,"
                                    + " atualizado_em = :agora where id = :id and situacao = 'PROCESSANDO'")
                    .param("chave", chaveGravada)
                    .param("tamanho", validado.tamanho())
                    .param("sha", validado.sha256Hex())
                    .param("agora", Timestamp.from(clock.instant()))
                    .param("id", reserva.id())
                    .update());
            if (atualizados == null || atualizados == 0) {
                // A reserva expirou e outra instância assumiu: este objeto não fica sem dono.
                removerSilenciosamente(chave);
            }
        } catch (FalhaArmazenamento e) {
            removerSilenciosamente(chave);
            falhar(reserva, true, "Armazenamento indisponível");
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "Atendimento: falha ao processar anexo {} ({})",
                    reserva.id(),
                    e.getClass().getSimpleName());
            removerSilenciosamente(chave);
            falhar(reserva, true, "Falha inesperada no processamento");
        } finally {
            if (temporario != null) {
                try {
                    Files.deleteIfExists(temporario);
                } catch (IOException ignorada) {
                    // arquivo temporário do sistema; o sistema operacional limpa
                }
            }
        }
    }

    private void falhar(Reserva reserva, boolean temporaria, String motivo) {
        Instant agora = clock.instant();
        boolean definitiva = !temporaria || reserva.tentativas() >= propriedades.maximoTentativas();
        transacao.executeWithoutResult(status -> jdbc.sql("update atd_anexo set situacao = :s, erro = :erro,"
                        + " proxima_tentativa_em = :proxima, reservado_em = null, atualizado_em = :agora"
                        + " where id = :id and situacao = 'PROCESSANDO'")
                .param("s", definitiva ? "FALHA" : "FALHA_TEMPORARIA")
                .param("erro", motivo.length() > 300 ? motivo.substring(0, 300) : motivo)
                .param(
                        "proxima",
                        definitiva ? null : Timestamp.from(agora.plus(Duration.ofSeconds(30L << reserva.tentativas()))))
                .param("agora", Timestamp.from(agora))
                .param("id", reserva.id())
                .update());
    }

    /** Processo caiu no meio de um download: a reserva volta para nova tentativa (repetir é seguro). */
    private void recuperarReservasExpiradas() {
        Instant agora = clock.instant();
        jdbc.sql(
                        "update atd_anexo set situacao = 'FALHA_TEMPORARIA', reservado_em = null, proxima_tentativa_em = :agora,"
                                + " erro = 'Processamento interrompido', atualizado_em = :agora"
                                + " where situacao = 'PROCESSANDO' and reservado_em < :limite")
                .param("agora", Timestamp.from(agora))
                .param("limite", Timestamp.from(agora.minus(RESERVA_EXPIRADA)))
                .update();
    }

    /**
     * Uploads do atendente que não chegaram a virar mensagem (falha entre gravar o objeto e registrar a
     * mensagem, ou processo interrompido): linhas RECEBENDO sem mensagem, mais antigas que
     * {@code uploadAbandonado}. O objeto é removido antes da linha; anexos vinculados a mensagem nunca
     * entram aqui.
     */
    void limparUploadsAbandonados() {
        if (!armazenamento.configurado()) {
            return;
        }
        Instant limite = clock.instant().minus(propriedades.uploadAbandonado());
        var abandonados = jdbc.sql("select id, chave_objeto from atd_anexo where situacao = 'RECEBENDO'"
                        + " and mensagem_id is null and criado_em < :limite order by id limit 50")
                .param("limite", Timestamp.from(limite))
                .query()
                .listOfRows();
        for (Map<String, Object> linha : abandonados) {
            String chave = (String) linha.get("chave_objeto");
            try {
                if (chave != null) {
                    armazenamento.remover(chave);
                }
                jdbc.sql("delete from atd_anexo where id = :id and situacao = 'RECEBENDO' and mensagem_id is null")
                        .param("id", linha.get("id"))
                        .update();
            } catch (FalhaArmazenamento e) {
                return; // tenta de novo no próximo ciclo
            }
        }
    }

    /**
     * Nova tentativa manual de um download que falhou definitivamente (o mesmo arquivo; nada é enviado ao
     * contato e nenhuma mensagem é duplicada).
     *
     * @return se o anexo voltou para a fila
     */
    boolean reabrirDownload(long anexo) {
        Instant agora = clock.instant();
        return jdbc.sql("update atd_anexo set situacao = 'PENDENTE', tentativas = 0, erro = null,"
                                + " proxima_tentativa_em = :agora, atualizado_em = :agora"
                                + " where id = :id and direcao = 'ENTRADA' and situacao = 'FALHA'"
                                + " and id_midia_externo is not null")
                        .param("agora", Timestamp.from(agora))
                        .param("id", anexo)
                        .update()
                > 0;
    }

    private void removerSilenciosamente(String chave) {
        if (chave == null) {
            return;
        }
        try {
            armazenamento.remover(chave);
        } catch (RuntimeException e) {
            log.warn(
                    "Atendimento: objeto de anexo não removido ({}); fica para a limpeza",
                    e.getClass().getSimpleName());
        }
    }
}
