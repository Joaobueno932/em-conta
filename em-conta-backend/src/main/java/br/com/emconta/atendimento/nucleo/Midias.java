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
import java.util.List;
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
    /** Intenção GRAVANDO mais antiga que isto é conferida pela limpeza (a reserva já teria expirado). */
    static final Duration GRAVACAO_ABANDONADA = RESERVA_EXPIRADA.plusMinutes(1);

    static final int LOTE_LIMPEZA = 20;

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

    /** Uma aquisição do anexo: {@code token} distingue esta execução de qualquer outra, anterior ou futura. */
    private record Reserva(long id, String token, String idMidia, String mime, String shaProvedor, int tentativas) {}

    /** A reserva expirou e outra execução assumiu (ou foi recuperada): esta execução para sem gravar. */
    private static final class ReservaPerdida extends RuntimeException {
        ReservaPerdida() {
            super(null, null, false, false);
        }
    }

    /** Processa até {@code limite} downloads pendentes. Devolve quantos foram processados. */
    public int processarPendentes(int limite) {
        limparUploadsAbandonados();
        recuperarReservasExpiradas();
        limparObjetosPendentes(LOTE_LIMPEZA);
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
        String token = UUID.randomUUID().toString();
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
            jdbc.sql("update atd_anexo set situacao = 'PROCESSANDO', reserva = :token, reservado_em = :agora,"
                            + " tentativas = :t, atualizado_em = :agora where id = :id")
                    .param("token", token)
                    .param("agora", Timestamp.from(agora))
                    .param("t", tentativas)
                    .param("id", id)
                    .update();
            return new Reserva(
                    id,
                    token,
                    (String) linha.get().get("id_midia_externo"),
                    (String) linha.get().get("mime_type"),
                    (String) linha.get().get("sha256_provedor"),
                    tentativas);
        }));
    }

    /**
     * Renova a reserva antes de cada etapa externa (consulta, download, gravação). Cada etapa tem tempo
     * máximo próprio (timeouts da Graph API e do armazenamento), bem abaixo de {@link #RESERVA_EXPIRADA};
     * renovar entre elas mantém a reserva viva enquanto a execução avança e, se ela já foi perdida, para
     * antes de fazer trabalho que não será aproveitado.
     */
    private void renovar(Reserva reserva) {
        int renovadas = jdbc.sql("update atd_anexo set reservado_em = :agora where id = :id and reserva = :token"
                        + " and situacao = 'PROCESSANDO'")
                .param("agora", Timestamp.from(clock.instant()))
                .param("id", reserva.id())
                .param("token", reserva.token())
                .update();
        if (renovadas == 0) {
            throw new ReservaPerdida();
        }
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
            renovar(reserva);
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
                falhar(reserva, false, "Arquivo acima do limite de " + FormatosAnexo.tamanhoLegivel(limite));
                return;
            }
            temporario = Files.createTempFile("emconta-anexo-", ".tmp");
            renovar(reserva);
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
            registrarIntencao(reserva, chave);
            armazenamento.gravar(chave, temporario, formato.mime());
            if (!concluir(reserva, chave, validado)) {
                // A reserva foi perdida durante a gravação: o objeto desta execução sobrou; o da execução
                // vencedora (outra chave) não é tocado.
                removerPendente(chave);
            }
        } catch (ReservaPerdida e) {
            log.info("Atendimento: reserva do anexo {} perdida; execução descartada", reserva.id());
            descartarObjeto(reserva, chave);
        } catch (FalhaArmazenamento e) {
            descartarObjeto(reserva, chave);
            falhar(reserva, true, "Armazenamento indisponível");
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "Atendimento: falha ao processar anexo {} ({})",
                    reserva.id(),
                    e.getClass().getSimpleName());
            descartarObjeto(reserva, chave);
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

    /**
     * Antes de gravar o objeto: registra no diário a chave e o token, na mesma transação que confere que a
     * reserva ainda é desta execução. Se o processo cair depois da gravação, a limpeza encontra a chave.
     */
    private void registrarIntencao(Reserva reserva, String chave) {
        Instant agora = clock.instant();
        Integer registradas = transacao.execute(status -> jdbc.sql(
                        "insert into atd_objeto_pendente (chave_objeto, anexo_id, reserva, situacao, proxima_tentativa_em,"
                                + " criado_em, atualizado_em) select :chave, id, :token, 'GRAVANDO', :proxima, :agora, :agora"
                                + " from atd_anexo where id = :id and reserva = :token and situacao = 'PROCESSANDO'")
                .param("chave", chave)
                .param("token", reserva.token())
                .param("proxima", Timestamp.from(agora.plus(GRAVACAO_ABANDONADA)))
                .param("agora", Timestamp.from(agora))
                .param("id", reserva.id())
                .update());
        if (registradas == null || registradas == 0) {
            throw new ReservaPerdida();
        }
    }

    /**
     * Vincula o objeto ao anexo só se a reserva ainda for desta execução; na mesma transação, a intenção
     * sai do diário (vinculado) ou vira remoção pendente (reserva perdida).
     */
    private boolean concluir(Reserva reserva, String chave, ArquivoValidado validado) {
        Instant agora = clock.instant();
        Boolean concluido = transacao.execute(status -> {
            int vinculados = jdbc.sql("update atd_anexo set situacao = 'DISPONIVEL', chave_objeto = :chave,"
                            + " tamanho = :tamanho, sha256 = :sha, erro = null, reserva = null, reservado_em = null,"
                            + " proxima_tentativa_em = null, atualizado_em = :agora"
                            + " where id = :id and reserva = :token and situacao = 'PROCESSANDO'")
                    .param("chave", chave)
                    .param("tamanho", validado.tamanho())
                    .param("sha", validado.sha256Hex())
                    .param("agora", Timestamp.from(agora))
                    .param("id", reserva.id())
                    .param("token", reserva.token())
                    .update();
            if (vinculados == 1) {
                jdbc.sql("delete from atd_objeto_pendente where chave_objeto = :chave")
                        .param("chave", chave)
                        .update();
            } else {
                marcarRemover(reserva, chave, agora);
            }
            return vinculados == 1;
        });
        return Boolean.TRUE.equals(concluido);
    }

    private void falhar(Reserva reserva, boolean temporaria, String motivo) {
        Instant agora = clock.instant();
        boolean definitiva = !temporaria || reserva.tentativas() >= propriedades.maximoTentativas();
        transacao.executeWithoutResult(status -> jdbc.sql("update atd_anexo set situacao = :s, erro = :erro,"
                        + " proxima_tentativa_em = :proxima, reserva = null, reservado_em = null, atualizado_em = :agora"
                        + " where id = :id and reserva = :token and situacao = 'PROCESSANDO'")
                .param("s", definitiva ? "FALHA" : "FALHA_TEMPORARIA")
                .param("erro", motivo.length() > 300 ? motivo.substring(0, 300) : motivo)
                .param(
                        "proxima",
                        definitiva ? null : Timestamp.from(agora.plus(Duration.ofSeconds(30L << reserva.tentativas()))))
                .param("agora", Timestamp.from(agora))
                .param("id", reserva.id())
                .param("token", reserva.token())
                .update());
    }

    /**
     * Processo caiu ou travou no meio de um download: a reserva volta para nova tentativa (repetir é
     * seguro) e o token é apagado, o que invalida a execução anterior — ela não consegue mais renovar,
     * concluir nem registrar falha.
     */
    private void recuperarReservasExpiradas() {
        Instant agora = clock.instant();
        // proxima_tentativa_em nula = elegível já (sem depender do arredondamento de DATETIME(6)).
        jdbc.sql("update atd_anexo set situacao = 'FALHA_TEMPORARIA', reserva = null, reservado_em = null,"
                        + " proxima_tentativa_em = null, erro = 'Processamento interrompido', atualizado_em = :agora"
                        + " where situacao = 'PROCESSANDO' and reservado_em < :limite")
                .param("agora", Timestamp.from(agora))
                .param("limite", Timestamp.from(agora.minus(RESERVA_EXPIRADA)))
                .update();
    }

    // ------------------------------------------------------------------ objetos sem dono

    /**
     * Marca o objeto desta execução para remoção. É um upsert: se a limpeza já tiver tirado a intenção do
     * diário (execução que travou mais do que {@link #GRAVACAO_ABANDONADA} antes de gravar), a linha volta,
     * e o objeto gravado depois não fica sem registro.
     */
    private void marcarRemover(Reserva reserva, String chave, Instant agora) {
        jdbc.sql("insert into atd_objeto_pendente (chave_objeto, anexo_id, reserva, situacao, proxima_tentativa_em,"
                        + " criado_em, atualizado_em) values (:chave, :anexo, :token, 'REMOVER', :agora, :agora, :agora)"
                        + " on duplicate key update situacao = 'REMOVER', proxima_tentativa_em = values(proxima_tentativa_em),"
                        + " atualizado_em = values(atualizado_em)")
                .param("chave", chave)
                .param("anexo", reserva.id())
                .param("token", reserva.token())
                .param("agora", Timestamp.from(agora))
                .update();
    }

    /** Objeto desta execução que não será usado: marca para remoção e tenta remover já. */
    private void descartarObjeto(Reserva reserva, String chave) {
        if (chave == null) {
            return;
        }
        transacao.executeWithoutResult(status -> marcarRemover(reserva, chave, clock.instant()));
        removerPendente(chave);
    }

    /** Tenta remover agora um objeto já marcado; se falhar, a limpeza periódica tenta de novo. */
    private void removerPendente(String chave) {
        jdbc.sql("select id, chave_objeto, anexo_id, reserva, situacao, tentativas from atd_objeto_pendente"
                        + " where chave_objeto = :chave")
                .param("chave", chave)
                .query(Midias::pendente)
                .optional()
                .ifPresent(this::remover);
    }

    private record Pendente(long id, String chave, long anexo, String reserva, String situacao, int tentativas) {}

    private static Pendente pendente(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Pendente(
                rs.getLong("id"),
                rs.getString("chave_objeto"),
                rs.getLong("anexo_id"),
                rs.getString("reserva"),
                rs.getString("situacao"),
                rs.getInt("tentativas"));
    }

    /**
     * Limpeza do diário de objetos (várias réplicas): reivindica até {@code limite} linhas vencidas com
     * {@code SKIP LOCKED}, adiando a próxima tentativa de cada uma antes de soltar a trava; a remoção (HTTP)
     * acontece fora da transação. Remover um objeto inexistente conta como sucesso.
     *
     * @return quantas linhas foram resolvidas (removidas ou reconhecidas como vinculadas)
     */
    int limparObjetosPendentes(int limite) {
        if (!armazenamento.configurado()) {
            return 0;
        }
        Instant agora = clock.instant();
        List<Pendente> reivindicados = transacao.execute(status -> {
            List<Pendente> linhas = jdbc.sql("select id, chave_objeto, anexo_id, reserva, situacao, tentativas"
                            + " from atd_objeto_pendente where proxima_tentativa_em <= :agora"
                            + " order by proxima_tentativa_em, id limit :limite for update skip locked")
                    .param("agora", Timestamp.from(agora))
                    .param("limite", limite)
                    .query(Midias::pendente)
                    .list();
            for (Pendente p : linhas) {
                jdbc.sql("update atd_objeto_pendente set tentativas = tentativas + 1, proxima_tentativa_em = :proxima,"
                                + " atualizado_em = :agora where id = :id")
                        .param("proxima", Timestamp.from(agora.plus(espera(p.tentativas() + 1))))
                        .param("agora", Timestamp.from(agora))
                        .param("id", p.id())
                        .update();
            }
            return linhas;
        });
        int resolvidos = 0;
        for (Pendente p : reivindicados == null ? List.<Pendente>of() : reivindicados) {
            if (remover(p)) {
                resolvidos++;
            }
        }
        return resolvidos;
    }

    /**
     * Remove o objeto só se for seguro: nenhum anexo aponta para a chave e, para intenções GRAVANDO, a
     * reserva que a registrou não é mais a vigente (um token apagado nunca volta, então essa execução não
     * consegue mais vincular a chave). Objeto vinculado sai do diário sem ser removido.
     */
    private boolean remover(Pendente p) {
        Map<String, Object> estado = jdbc.sql(
                        "select (select count(*) from atd_anexo where chave_objeto = :chave) as"
                                + " vinculado, (select count(*) from atd_anexo where id = :anexo and reserva = :token) as vigente")
                .param("chave", p.chave())
                .param("anexo", p.anexo())
                .param("token", p.reserva())
                .query()
                .singleRow();
        if (((Number) estado.get("vinculado")).longValue() > 0) {
            jdbc.sql("delete from atd_objeto_pendente where id = :id")
                    .param("id", p.id())
                    .update();
            return true;
        }
        if ("GRAVANDO".equals(p.situacao()) && ((Number) estado.get("vigente")).longValue() > 0) {
            return false; // execução ainda válida: não mexe (volta a ser conferida depois)
        }
        try {
            armazenamento.remover(p.chave());
        } catch (FalhaArmazenamento e) {
            jdbc.sql("update atd_objeto_pendente set erro = :erro, atualizado_em = :agora where id = :id")
                    .param("erro", "Remoção falhou: " + e.getMessage())
                    .param("agora", Timestamp.from(clock.instant()))
                    .param("id", p.id())
                    .update();
            log.warn("Atendimento: objeto pendente {} não removido; nova tentativa depois", p.id());
            return false;
        }
        jdbc.sql("delete from atd_objeto_pendente where id = :id")
                .param("id", p.id())
                .update();
        return true;
    }

    /** Espera crescente entre tentativas de remoção: 30 s, 1 min, 2 min... até 1 h. */
    static Duration espera(int tentativas) {
        return Duration.ofSeconds(Math.min(3600L, 30L << Math.min(Math.max(tentativas - 1, 0), 7)));
    }

    /**
     * Uploads do atendente que não chegaram a virar mensagem (falha entre gravar o objeto e registrar a
     * mensagem, ou processo interrompido): linhas RECEBENDO sem mensagem, mais antigas que
     * {@code uploadAbandonado}. Primeiro a linha é reivindicada (RECEBENDO → FALHA, condicional), o que
     * impede a vinculação a uma mensagem; depois o objeto é removido e a linha apagada. Se a remoção falhar,
     * a linha FALHA sem mensagem volta aqui no próximo ciclo. Anexos com mensagem nunca entram nesta limpeza.
     */
    void limparUploadsAbandonados() {
        if (!armazenamento.configurado()) {
            return;
        }
        Instant agora = clock.instant();
        Instant limite = agora.minus(propriedades.uploadAbandonado());
        var abandonados = jdbc.sql(
                        "select id, chave_objeto from atd_anexo where direcao = 'SAIDA' and mensagem_id is null"
                                + " and ((situacao = 'RECEBENDO' and criado_em < :limite) or situacao = 'FALHA')"
                                + " order by id limit 50")
                .param("limite", Timestamp.from(limite))
                .query()
                .listOfRows();
        for (Map<String, Object> linha : abandonados) {
            jdbc.sql("update atd_anexo set situacao = 'FALHA', erro = 'Upload abandonado', atualizado_em = :agora"
                            + " where id = :id and situacao = 'RECEBENDO' and mensagem_id is null")
                    .param("agora", Timestamp.from(agora))
                    .param("id", linha.get("id"))
                    .update();
            boolean reivindicado = jdbc.sql("select count(*) from atd_anexo where id = :id and situacao = 'FALHA'"
                                    + " and mensagem_id is null")
                            .param("id", linha.get("id"))
                            .query(Long.class)
                            .single()
                    > 0;
            if (!reivindicado) {
                continue; // foi vinculado a uma mensagem nesse meio-tempo
            }
            String chave = (String) linha.get("chave_objeto");
            try {
                if (chave != null) {
                    armazenamento.remover(chave);
                }
                jdbc.sql("delete from atd_anexo where id = :id and situacao = 'FALHA' and mensagem_id is null")
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
                                + " proxima_tentativa_em = null, atualizado_em = :agora"
                                + " where id = :id and direcao = 'ENTRADA' and situacao = 'FALHA'"
                                + " and id_midia_externo is not null")
                        .param("agora", Timestamp.from(agora))
                        .param("id", anexo)
                        .update()
                > 0;
    }
}
