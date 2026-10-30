package br.com.emconta.atendimento.nucleo;

import br.com.emconta.integracoes.ArmazenamentoObjetos;
import br.com.emconta.integracoes.ArmazenamentoObjetos.FalhaArmazenamento;
import br.com.emconta.integracoes.WhatsAppCloud;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Anexos do atendimento: envio pelo atendente, acesso ao conteúdo e nova tentativa de download.
 *
 * <p>Envio, em etapas curtas e sem transação aberta durante a cópia do arquivo:
 *
 * <ol>
 *   <li>valida chave, legenda, tipo, extensão e as regras de resposta (conferência antecipada);
 *   <li>copia o arquivo para um temporário com limite de tamanho e valida assinatura e SHA-256;
 *   <li>repetição exata (mesma chave, autor, atendimento, arquivo e legenda) devolve a mensagem já
 *       registrada, sem gravar nada; mesma chave em outra operação é 409;
 *   <li>registra o anexo como RECEBENDO (sem mensagem) e grava o objeto no armazenamento privado;
 *   <li>em transação, trava o atendimento, confere de novo responsável, situação e janela e registra a
 *       mensagem PENDENTE com o anexo. O {@link Envio} faz o upload à Meta e o envio depois.
 * </ol>
 *
 * <p>Qualquer falha depois da gravação do objeto o remove; se até a remoção falhar, o anexo continua
 * RECEBENDO e a limpeza periódica ({@link Midias#limparUploadsAbandonados}) o apaga.
 */
@Service
public class Anexos {

    private static final Logger log = LoggerFactory.getLogger(Anexos.class);

    private final JdbcClient jdbc;
    private final Atendimentos atendimentos;
    private final Midias midias;
    private final WhatsAppCloud canal;
    private final ArmazenamentoObjetos armazenamento;
    private final AnexosProperties propriedades;
    private final Clock clock;
    private final TransactionTemplate transacao;

    Anexos(
            JdbcClient jdbc,
            Atendimentos atendimentos,
            Midias midias,
            WhatsAppCloud canal,
            ArmazenamentoObjetos armazenamento,
            AnexosProperties propriedades,
            Clock clock,
            PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.atendimentos = atendimentos;
        this.midias = midias;
        this.canal = canal;
        this.armazenamento = armazenamento;
        this.propriedades = propriedades;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transacoes);
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean armazenamentoConfigurado() {
        return armazenamento.configurado();
    }

    /** Arquivo vindo da requisição: nome e tipo declarados pelo navegador (não confiáveis) e o conteúdo. */
    public record ArquivoEnviado(String nomeOriginal, String mimeDeclarado, InputStream conteudo) {}

    private record Operacao(long autor, long atendimento, String categoria, String sha256, String legenda) {}

    /** @return id da mensagem registrada (ou da já registrada, na repetição exata) */
    public long enviar(long ator, long atendimento, ArquivoEnviado arquivo, String legenda, String chave) {
        if (chave == null || !Atendimentos.CHAVE.matcher(chave).matches()) {
            throw FalhaAtendimento.campoInvalido("chaveIdempotencia", "Chave de idempotência inválida.");
        }
        FormatosAnexo.Formato formato = FormatosAnexo.porMime(arquivo.mimeDeclarado())
                .orElseThrow(() -> FalhaAtendimento.campoInvalido(
                        "arquivo",
                        "Tipo de arquivo não suportado. Envie JPEG, PNG, PDF ou áudio AAC, AMR, MP3, M4A ou OGG."));
        String nome = FormatosAnexo.nomeSeguro(arquivo.nomeOriginal());
        String extensao = FormatosAnexo.extensao(nome).orElse("");
        if (nome == null || !formato.extensoes().contains(extensao)) {
            throw FalhaAtendimento.campoInvalido(
                    "arquivo", "A extensão do arquivo não corresponde ao tipo " + formato.mime() + ".");
        }
        String legendaLimpa = legenda == null || legenda.isBlank() ? null : legenda.strip();
        if (legendaLimpa != null && !formato.categoria().aceitaLegenda) {
            throw FalhaAtendimento.campoInvalido("legenda", "O WhatsApp não aceita legenda em áudio.");
        }
        if (legendaLimpa != null && legendaLimpa.length() > FormatosAnexo.TAMANHO_MAXIMO_LEGENDA) {
            throw FalhaAtendimento.campoInvalido(
                    "legenda", "Use no máximo " + FormatosAnexo.TAMANHO_MAXIMO_LEGENDA + " caracteres na legenda.");
        }
        long limite = propriedades.limite(formato.categoria());
        Path temporario = null;
        try {
            temporario = Files.createTempFile("emconta-upload-", ".tmp");
            copiarComLimite(arquivo.conteudo(), temporario, limite);
            ArquivoValidado validado;
            try {
                validado = ArquivoValidado.de(temporario, formato, limite);
            } catch (ArquivoValidado.Invalido e) {
                throw FalhaAtendimento.campoInvalido("arquivo", e.getMessage());
            }
            Operacao pedida =
                    new Operacao(ator, atendimento, formato.categoria().name(), validado.sha256Hex(), legendaLimpa);
            Optional<Long> repetida = repeticao(chave, pedida, false);
            if (repetida.isPresent()) {
                return repetida.get();
            }
            if (!canal.configurado()) {
                throw FalhaAtendimento.canalNaoConfigurado();
            }
            if (!armazenamento.configurado()) {
                throw FalhaAtendimento.indisponivel(
                        "ARMAZENAMENTO_NAO_CONFIGURADO", "O armazenamento de anexos não está configurado.");
            }
            Atendimentos.Estado estado = atendimentos.exigirPodeResponder(ator, atendimento, false);
            return gravarERegistrar(estado, ator, formato, nome, validado, temporario, pedida, chave);
        } catch (IOException e) {
            throw FalhaAtendimento.indisponivel(
                    "ARQUIVO_NAO_RECEBIDO", "Não foi possível receber o arquivo. Tente de novo.");
        } finally {
            apagar(temporario);
        }
    }

    private long gravarERegistrar(
            Atendimentos.Estado estado,
            long ator,
            FormatosAnexo.Formato formato,
            String nome,
            ArquivoValidado validado,
            Path temporario,
            Operacao pedida,
            String chave) {
        String chaveObjeto = Midias.novaChave();
        Instant agora = clock.instant();
        Long anexo = transacao.execute(status -> {
            jdbc.sql(
                            "insert into atd_anexo (conversa_id, atendimento_id, direcao, autor_usuario_id, categoria, mime_type,"
                                    + " nome, tamanho, sha256, chave_objeto, situacao, criado_em, atualizado_em) values (:c, :a,"
                                    + " 'SAIDA', :autor, :categoria, :mime, :nome, :tamanho, :sha, :chave, 'RECEBENDO', :agora, :agora)")
                    .param("c", estado.conversaId())
                    .param("a", pedida.atendimento())
                    .param("autor", ator)
                    .param("categoria", formato.categoria().name())
                    .param("mime", formato.mime())
                    .param("nome", nome)
                    .param("tamanho", validado.tamanho())
                    .param("sha", validado.sha256Hex())
                    .param("chave", chaveObjeto)
                    .param("agora", Timestamp.from(agora))
                    .update();
            return jdbc.sql("select id from atd_anexo where chave_objeto = :chave")
                    .param("chave", chaveObjeto)
                    .query(Long.class)
                    .single();
        });
        try {
            armazenamento.gravar(chaveObjeto, temporario, formato.mime());
        } catch (FalhaArmazenamento e) {
            descartar(anexo, chaveObjeto);
            throw FalhaAtendimento.indisponivel(
                    "ARMAZENAMENTO_INDISPONIVEL",
                    "Não foi possível armazenar o arquivo. Nada foi registrado nem enviado; tente de novo.");
        }
        try {
            Long mensagem = transacao.execute(status -> registrarMensagem(pedida, anexo, chave));
            if (!Objects.equals(
                    mensagem,
                    jdbc.sql("select mensagem_id from atd_anexo where id = :id")
                            .param("id", anexo)
                            .query(Long.class)
                            .optional()
                            .orElse(null))) {
                descartar(anexo, chaveObjeto); // a repetição simultânea venceu: este arquivo sobrou
            }
            return mensagem;
        } catch (RuntimeException e) {
            descartar(anexo, chaveObjeto);
            throw e;
        }
    }

    /** Dentro da transação: trava, confere de novo e registra a mensagem com o anexo. */
    private long registrarMensagem(Operacao pedida, long anexo, String chave) {
        Atendimentos.Estado estado = atendimentos.exigirPodeResponder(pedida.autor(), pedida.atendimento(), true);
        Instant agora = clock.instant();
        try {
            jdbc.sql(
                            "insert into atd_mensagem (correlacao, conversa_id, atendimento_id, direcao, autor_usuario_id, tipo,"
                                    + " texto, situacao, criado_em, atualizado_em, chave_idempotencia) values (:correlacao, :c, :a,"
                                    + " 'SAIDA', :autor, :tipo, :texto, 'PENDENTE', :agora, :agora, :chave)")
                    .param("correlacao", UUID.randomUUID().toString())
                    .param("c", estado.conversaId())
                    .param("a", pedida.atendimento())
                    .param("autor", pedida.autor())
                    .param("tipo", pedida.categoria())
                    .param("texto", pedida.legenda())
                    .param("agora", Timestamp.from(agora))
                    .param("chave", chave)
                    .update();
        } catch (DuplicateKeyException e) {
            return repeticao(chave, pedida, true)
                    .orElseThrow(
                            () -> FalhaAtendimento.conflito("REQUISICAO_REPETIDA", "Esta mensagem já foi registrada."));
        }
        long mensagem = jdbc.sql("select id from atd_mensagem where chave_idempotencia = :chave")
                .param("chave", chave)
                .query(Long.class)
                .single();
        int vinculados = jdbc.sql(
                        "update atd_anexo set mensagem_id = :m, situacao = 'DISPONIVEL', atualizado_em = :agora"
                                + " where id = :id and situacao = 'RECEBENDO'")
                .param("m", mensagem)
                .param("agora", Timestamp.from(agora))
                .param("id", anexo)
                .update();
        if (vinculados == 0) {
            // A limpeza de uploads abandonados reivindicou este anexo: desfaz a mensagem (rollback).
            throw FalhaAtendimento.indisponivel(
                    "UPLOAD_EXPIRADO", "O arquivo enviado expirou antes do registro. Envie de novo.");
        }
        jdbc.sql("update atd_conversa set ultima_mensagem_em = :agora where id = :id")
                .param("agora", Timestamp.from(agora))
                .param("id", estado.conversaId())
                .update();
        return mensagem;
    }

    /**
     * Mensagem já registrada com a chave, se for a mesma operação (autor, atendimento, tipo, SHA-256 do
     * arquivo e legenda); outra operação com a mesma chave é conflito, sem revelar a anterior.
     */
    private Optional<Long> repeticao(String chave, Operacao pedida, boolean atual) {
        Optional<Map<String, Object>> linha = jdbc
                .sql("select m.id, m.autor_usuario_id, m.atendimento_id, m.tipo, m.texto, x.sha256 from atd_mensagem m"
                        + " left join atd_anexo x on x.mensagem_id = m.id where m.chave_idempotencia = :chave"
                        + (atual ? " for share" : ""))
                .param("chave", chave)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (linha.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> registrada = linha.get();
        Object autor = registrada.get("autor_usuario_id");
        boolean mesma = autor != null
                && ((Number) autor).longValue() == pedida.autor()
                && ((Number) registrada.get("atendimento_id")).longValue() == pedida.atendimento()
                && pedida.categoria().equals(registrada.get("tipo"))
                && pedida.sha256().equals(registrada.get("sha256"))
                && Objects.equals(pedida.legenda(), registrada.get("texto"));
        if (!mesma) {
            throw FalhaAtendimento.conflito(
                    "CHAVE_IDEMPOTENCIA_REUTILIZADA",
                    "Esta chave de idempotência já foi usada em outra mensagem. Nada foi registrado.");
        }
        return Optional.of(((Number) registrada.get("id")).longValue());
    }

    private void copiarComLimite(InputStream origem, Path destino, long limite) throws IOException {
        long total = 0;
        byte[] bloco = new byte[64 * 1024];
        try (InputStream entrada = origem;
                OutputStream saida = Files.newOutputStream(destino)) {
            int lidos;
            while ((lidos = entrada.read(bloco)) != -1) {
                total += lidos;
                if (total > limite) {
                    throw FalhaAtendimento.arquivoMuitoGrande(
                            "Arquivo acima do limite de " + FormatosAnexo.tamanhoLegivel(limite) + " para este tipo.");
                }
                saida.write(bloco, 0, lidos);
            }
        }
    }

    private void descartar(Long anexo, String chaveObjeto) {
        try {
            armazenamento.remover(chaveObjeto);
            jdbc.sql("delete from atd_anexo where id = :id and mensagem_id is null")
                    .param("id", anexo)
                    .update();
        } catch (RuntimeException e) {
            // Continua RECEBENDO e sem mensagem: a limpeza periódica remove depois.
            log.warn(
                    "Atendimento: upload descartado fica para a limpeza ({})",
                    e.getClass().getSimpleName());
        }
    }

    private static void apagar(Path temporario) {
        if (temporario == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporario);
        } catch (IOException ignorada) {
            // temporário do sistema
        }
    }

    // ------------------------------------------------------------------ acesso

    /** Anexo disponível para leitura, já autorizado pela relação com o atendimento consultado. */
    public record AnexoLeitura(long id, String categoria, String mime, String nome, long tamanho, String chave) {}

    /**
     * Anexo da conversa do atendimento (ou só deste ciclo, com {@code somenteEste}), vinculado a uma
     * mensagem e disponível. Qualquer outra combinação de ids é "não encontrado": conhecer o id do anexo
     * não basta.
     */
    public Optional<AnexoLeitura> paraLeitura(long atendimento, long anexo, boolean somenteEste) {
        return jdbc.sql("select x.id, x.categoria, x.mime_type, x.nome, x.tamanho, x.chave_objeto from atd_anexo x"
                        + " join atd_atendimento a on a.id = :atendimento and a.conversa_id = x.conversa_id"
                        + " where x.id = :anexo and x.mensagem_id is not null and x.situacao = 'DISPONIVEL'"
                        + (somenteEste ? " and x.atendimento_id = :atendimento" : ""))
                .param("atendimento", atendimento)
                .param("anexo", anexo)
                .query((rs, n) -> new AnexoLeitura(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getLong(5),
                        rs.getString(6)))
                .optional();
    }

    public Optional<ArmazenamentoObjetos.Objeto> abrir(AnexoLeitura anexo, String faixa) {
        return armazenamento.abrir(anexo.chave(), faixa);
    }

    /**
     * Nova tentativa de download de um anexo recebido que falhou: só pelo responsável atual, com o
     * atendimento em andamento (atendimento encerrado é somente leitura). Não envia nada ao contato.
     */
    public void novaTentativaDownload(long ator, long atendimento, long anexo) {
        Atendimentos.Estado estado = atendimentos.exigirPodeResponderSemJanela(ator, atendimento);
        boolean pertence = jdbc.sql("select count(*) from atd_anexo where id = :id and conversa_id = :c")
                        .param("id", anexo)
                        .param("c", estado.conversaId())
                        .query(Long.class)
                        .single()
                > 0;
        if (!pertence) {
            throw FalhaAtendimento.naoEncontrado("ANEXO_NAO_ENCONTRADO", "Anexo não encontrado.");
        }
        if (!midias.reabrirDownload(anexo)) {
            throw FalhaAtendimento.conflito(
                    "NOVA_TENTATIVA_INDISPONIVEL", "Este anexo não está em falha ou não pode ser baixado de novo.");
        }
    }
}
