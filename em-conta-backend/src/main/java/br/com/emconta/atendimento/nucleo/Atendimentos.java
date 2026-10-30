package br.com.emconta.atendimento.nucleo;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.associados.CatalogoAssociados;
import br.com.emconta.integracoes.WhatsAppCloud;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ações humanas no atendimento. Regras aprovadas (29/10/2026, Figma AT-03/AT-04/HG-03):
 *
 * <ul>
 *   <li>AGUARDANDO (sem responsável) → EM_ATENDIMENTO (assumido) → ENCERRADO (somente leitura);
 *   <li>só o responsável responde ("Assuma o atendimento para responder");
 *   <li>transferência direta, sem aceite, para usuário ativo que pode responder; motivo opcional; também
 *       é possível devolver à fila;
 *   <li>mensagem do cliente após o encerramento abre um novo atendimento AGUARDANDO (no recebimento);
 *   <li>vínculo com associado é explícito; trocar de associado exige motivo.
 * </ul>
 *
 * <p>Concorrência: as transições usam UPDATE condicional (situação e versão), então duas pessoas não
 * assumem a mesma conversa e nenhuma ação sobrescreve outra em silêncio (409 com código estável).
 * O estado do atendimento não altera situação comercial, contratual ou financeira do associado.
 */
@Service
public class Atendimentos {

    public static final String PERMISSAO_RESPONDER = "atendimento:responder";
    static final int TAMANHO_MAXIMO_TEXTO = 4096;
    /** Janela de atendimento da Cloud API: texto livre só até 24 h após a última mensagem do cliente. */
    static final Duration JANELA = Duration.ofHours(24);

    static final Pattern CHAVE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final JdbcClient jdbc;
    private final Autoria autoria;
    private final CatalogoAssociados associados;
    private final WhatsAppCloud canal;
    private final Clock clock;

    Atendimentos(JdbcClient jdbc, Autoria autoria, CatalogoAssociados associados, WhatsAppCloud canal, Clock clock) {
        this.jdbc = jdbc;
        this.autoria = autoria;
        this.associados = associados;
        this.canal = canal;
        this.clock = clock;
    }

    record Estado(long id, long conversaId, String situacao, Long responsavel, long versao) {}

    private Estado travar(long atendimento) {
        return jdbc.sql(
                        "select id, conversa_id, situacao, responsavel_id, versao from atd_atendimento where id = :id for update")
                .param("id", atendimento)
                .query((rs, n) -> new Estado(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4, Long.class), rs.getLong(5)))
                .optional()
                .orElseThrow(() ->
                        FalhaAtendimento.naoEncontrado("ATENDIMENTO_NAO_ENCONTRADO", "Atendimento não encontrado."));
    }

    @Transactional
    public void assumir(long ator, long atendimento) {
        Instant agora = clock.instant();
        int alterados = jdbc.sql("update atd_atendimento set situacao = 'EM_ATENDIMENTO', responsavel_id = :ator,"
                        + " assumido_em = :agora, versao = versao + 1 where id = :id and situacao = 'AGUARDANDO'")
                .param("ator", ator)
                .param("agora", Timestamp.from(agora))
                .param("id", atendimento)
                .update();
        Estado estado = travar(atendimento);
        if (alterados == 0) {
            throw "ENCERRADO".equals(estado.situacao())
                    ? FalhaAtendimento.conflito("ATENDIMENTO_ENCERRADO", "Este atendimento já foi encerrado.")
                    : FalhaAtendimento.conflito(
                            "ATENDIMENTO_JA_ASSUMIDO", "Este atendimento já foi assumido por outra pessoa.");
        }
        evento(estado.conversaId(), atendimento, "ASSUMIDO", ator, null, null, null, agora);
    }

    /** Transfere para outro responsável ou, com {@code destino} nulo, devolve à fila. */
    @Transactional
    public void transferir(long ator, long atendimento, long versao, Long destino, String motivo) {
        Estado estado = travar(atendimento);
        exigirResponsavel(ator, estado, versao, "transferir");
        String motivoLimpo = limpar(motivo, 500, "motivo");
        Instant agora = clock.instant();
        if (destino == null) {
            jdbc.sql("update atd_atendimento set situacao = 'AGUARDANDO', responsavel_id = null, assumido_em = null,"
                            + " versao = versao + 1 where id = :id")
                    .param("id", atendimento)
                    .update();
            evento(
                    estado.conversaId(),
                    atendimento,
                    "DEVOLVIDO_A_FILA",
                    ator,
                    estado.responsavel(),
                    null,
                    motivoLimpo,
                    agora);
            return;
        }
        if (destino.equals(estado.responsavel())) {
            throw FalhaAtendimento.campoInvalido("destino", "O atendimento já está com esta pessoa.");
        }
        if (!autoria.ativoComPermissao(destino, PERMISSAO_RESPONDER)) {
            throw FalhaAtendimento.campoInvalido(
                    "destino", "Destinatário inválido: precisa estar ativo e poder responder atendimentos.");
        }
        jdbc.sql(
                        "update atd_atendimento set responsavel_id = :destino, assumido_em = :agora, versao = versao + 1 where id = :id")
                .param("destino", destino)
                .param("agora", Timestamp.from(agora))
                .param("id", atendimento)
                .update();
        evento(estado.conversaId(), atendimento, "TRANSFERIDO", ator, destino, null, motivoLimpo, agora);
    }

    @Transactional
    public void encerrar(long ator, long atendimento, long versao) {
        Estado estado = travar(atendimento);
        exigirResponsavel(ator, estado, versao, "encerrar");
        Instant agora = clock.instant();
        jdbc.sql("update atd_atendimento set situacao = 'ENCERRADO', encerrado_em = :agora, encerrado_por = :ator,"
                        + " versao = versao + 1 where id = :id")
                .param("agora", Timestamp.from(agora))
                .param("ator", ator)
                .param("id", atendimento)
                .update();
        evento(estado.conversaId(), atendimento, "ENCERRADO", ator, null, null, null, agora);
    }

    /**
     * Enfileira uma resposta de texto (outbox). Não envia: o {@link Envio} envia depois.
     *
     * <p>Idempotência: a chave do cliente identifica uma operação (autor + atendimento + texto já
     * normalizado com {@code strip()}, o mesmo texto que é gravado e enviado). A repetição exata devolve a
     * mesma mensagem, sem nova gravação nem novo envio, inclusive depois de transferência ou encerramento.
     * A mesma chave com outro autor, atendimento ou texto é recusada com 409
     * {@code CHAVE_IDEMPOTENCIA_REUTILIZADA}. A garantia é a restrição única do banco: na disputa entre
     * requisições simultâneas, a perdedora recebe chave duplicada, relê a vencedora e aplica a mesma
     * comparação.
     *
     * @return id da mensagem
     */
    @Transactional
    public long responder(long ator, long atendimento, String texto, String chaveIdempotencia) {
        if (chaveIdempotencia == null || !CHAVE.matcher(chaveIdempotencia).matches()) {
            throw FalhaAtendimento.campoInvalido("chaveIdempotencia", "Chave de idempotência inválida.");
        }
        String conteudo = texto == null ? "" : texto.strip();
        if (conteudo.isEmpty() || conteudo.length() > TAMANHO_MAXIMO_TEXTO) {
            throw FalhaAtendimento.campoInvalido(
                    "texto", "Escreva uma mensagem de 1 a " + TAMANHO_MAXIMO_TEXTO + " caracteres.");
        }
        Operacao pedida = new Operacao(ator, atendimento, conteudo);
        Optional<Long> repetida = repeticao(chaveIdempotencia, pedida, false);
        if (repetida.isPresent()) {
            return repetida.get();
        }
        if (!canal.configurado()) {
            throw FalhaAtendimento.canalNaoConfigurado();
        }
        Estado estado = exigirPodeResponder(ator, atendimento, true);
        Instant agora = clock.instant();
        try {
            jdbc.sql(
                            "insert into atd_mensagem (correlacao, conversa_id, atendimento_id, direcao, autor_usuario_id, tipo,"
                                    + " texto, situacao, criado_em, atualizado_em, chave_idempotencia)"
                                    + " values (:correlacao, :conversa, :atendimento, 'SAIDA', :ator, 'TEXTO', :texto, 'PENDENTE',"
                                    + " :agora, :agora, :chave)")
                    .param("correlacao", UUID.randomUUID().toString())
                    .param("conversa", estado.conversaId())
                    .param("atendimento", atendimento)
                    .param("ator", ator)
                    .param("texto", conteudo)
                    .param("agora", Timestamp.from(agora))
                    .param("chave", chaveIdempotencia)
                    .update();
        } catch (DuplicateKeyException e) {
            // Outra requisição com a mesma chave gravou primeiro (o INSERT esperou a confirmação dela).
            return repeticao(chaveIdempotencia, pedida, true)
                    .orElseThrow(
                            () -> FalhaAtendimento.conflito("REQUISICAO_REPETIDA", "Esta mensagem já foi registrada."));
        }
        jdbc.sql("update atd_conversa set ultima_mensagem_em = :agora where id = :id")
                .param("agora", Timestamp.from(agora))
                .param("id", estado.conversaId())
                .update();
        return jdbc.sql("select id from atd_mensagem where chave_idempotencia = :chave")
                .param("chave", chaveIdempotencia)
                .query(Long.class)
                .single();
    }

    /**
     * Regras para registrar uma resposta (texto ou anexo): atendimento EM_ATENDIMENTO, o ator é o
     * responsável atual e a janela de 24 h está aberta. Com {@code travar}, a linha do atendimento fica
     * travada até o fim da transação (use ao gravar); sem, é só uma conferência antecipada.
     */
    Estado exigirPodeResponder(long ator, long atendimento, boolean travar) {
        Estado estado = travar ? travar(atendimento) : ler(atendimento);
        if (!"EM_ATENDIMENTO".equals(estado.situacao())
                || estado.responsavel() == null
                || estado.responsavel() != ator) {
            throw FalhaAtendimento.conflito("NAO_E_RESPONSAVEL", "Assuma o atendimento para responder esta conversa.");
        }
        Instant ultimaEntrada = jdbc.sql("select ultima_entrada_em from atd_conversa where id = :id")
                .param("id", estado.conversaId())
                .query((rs, n) ->
                        rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                .single();
        if (!janelaAberta(ultimaEntrada, clock.instant())) {
            throw FalhaAtendimento.conflito(
                    "JANELA_ENCERRADA",
                    "Passaram mais de 24 horas desde a última mensagem do cliente. Pelas regras do WhatsApp, só é"
                            + " possível responder com um modelo de mensagem aprovado, que ainda não está disponível.");
        }
        return estado;
    }

    /** Só situação e responsável (ações que não enviam nada ao contato, como baixar de novo um anexo). */
    Estado exigirPodeResponderSemJanela(long ator, long atendimento) {
        Estado estado = ler(atendimento);
        if (!"EM_ATENDIMENTO".equals(estado.situacao())
                || estado.responsavel() == null
                || estado.responsavel() != ator) {
            throw FalhaAtendimento.conflito("NAO_E_RESPONSAVEL", "Só o responsável atual pode fazer isto.");
        }
        return estado;
    }

    private Estado ler(long atendimento) {
        return jdbc.sql("select id, conversa_id, situacao, responsavel_id, versao from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query((rs, n) -> new Estado(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4, Long.class), rs.getLong(5)))
                .optional()
                .orElseThrow(() ->
                        FalhaAtendimento.naoEncontrado("ATENDIMENTO_NAO_ENCONTRADO", "Atendimento não encontrado."));
    }

    /** Janela de 24 h: aberta até o instante exato {@code ultimaEntrada + 24 h}, inclusive. */
    static boolean janelaAberta(Instant ultimaEntrada, Instant agora) {
        return ultimaEntrada != null && !agora.isAfter(ultimaEntrada.plus(JANELA));
    }

    private record Operacao(long autor, long atendimento, String texto) {}

    /**
     * Mensagem já registrada com a chave, se for a mesma operação; outra operação com a mesma chave é
     * conflito. {@code atual} usa leitura com trava, que enxerga a linha confirmada por outra transação
     * depois do início desta.
     */
    private Optional<Long> repeticao(String chave, Operacao pedida, boolean atual) {
        Optional<Map<String, Object>> linha = jdbc
                .sql("select id, autor_usuario_id, atendimento_id, tipo, texto from atd_mensagem"
                        + " where chave_idempotencia = :chave" + (atual ? " for share" : ""))
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
                && "TEXTO".equals(registrada.get("tipo"))
                && ((Number) autor).longValue() == pedida.autor()
                && ((Number) registrada.get("atendimento_id")).longValue() == pedida.atendimento()
                && pedida.texto().equals(registrada.get("texto"));
        if (!mesma) {
            throw FalhaAtendimento.conflito(
                    "CHAVE_IDEMPOTENCIA_REUTILIZADA",
                    "Esta chave de idempotência já foi usada em outra mensagem. Nada foi registrado.");
        }
        return Optional.of(((Number) registrada.get("id")).longValue());
    }

    /**
     * Vincula (ou desvincula, com {@code associado} nulo) a conversa a um associado existente. Trocar um
     * vínculo existente por outro associado exige motivo (correção registrada no histórico).
     */
    @Transactional
    public void vincularAssociado(long ator, long atendimento, long versaoConversa, Long associado, String motivo) {
        Estado estado = travar(atendimento);
        Map<String, Object> conversa = jdbc.sql(
                        "select associado_id, versao from atd_conversa where id = :id for update")
                .param("id", estado.conversaId())
                .query()
                .singleRow();
        if (((Number) conversa.get("versao")).longValue() != versaoConversa) {
            throw conflitoConcorrente();
        }
        Long atual = conversa.get("associado_id") == null ? null : ((Number) conversa.get("associado_id")).longValue();
        String motivoLimpo = limpar(motivo, 500, "motivo");
        if (associado != null && associados.obter(associado).isEmpty()) {
            throw FalhaAtendimento.campoInvalido("associadoId", "Associado não encontrado.");
        }
        if (java.util.Objects.equals(atual, associado)) {
            return;
        }
        if (atual != null && motivoLimpo == null) {
            throw FalhaAtendimento.campoInvalido("motivo", "Informe o motivo para alterar ou remover o vínculo.");
        }
        Instant agora = clock.instant();
        jdbc.sql("update atd_conversa set associado_id = :associado, versao = versao + 1 where id = :id")
                .param("associado", associado)
                .param("id", estado.conversaId())
                .update();
        String tipo = associado == null
                ? "ASSOCIADO_DESVINCULADO"
                : atual == null ? "ASSOCIADO_VINCULADO" : "ASSOCIADO_ALTERADO";
        evento(
                estado.conversaId(),
                atendimento,
                tipo,
                ator,
                null,
                associado != null ? associado : atual,
                motivoLimpo,
                agora);
    }

    /**
     * Transferir, devolver e encerrar: só em EM_ATENDIMENTO, só pelo responsável atual e só sobre a versão
     * que a pessoa viu. A linha já está travada ({@link #travar}) até o fim da transação, então nada muda
     * entre esta verificação e a gravação. Não há exceção por perfil: administrador também precisa ser o
     * responsável.
     */
    private static void exigirResponsavel(long ator, Estado estado, long versao, String acao) {
        switch (estado.situacao()) {
            case "ENCERRADO" ->
                throw FalhaAtendimento.conflito("ATENDIMENTO_ENCERRADO", "Este atendimento já foi encerrado.");
            case "AGUARDANDO" ->
                throw FalhaAtendimento.conflito(
                        "ATENDIMENTO_SEM_RESPONSAVEL",
                        "Só é possível " + acao + " um atendimento em andamento. Assuma-o primeiro.");
            default -> {}
        }
        if (estado.responsavel() == null || estado.responsavel() != ator) {
            throw FalhaAtendimento.conflito(
                    "NAO_E_RESPONSAVEL", "Só o responsável atual pode " + acao + " este atendimento.");
        }
        if (estado.versao() != versao) {
            throw conflitoConcorrente();
        }
    }

    private static FalhaAtendimento conflitoConcorrente() {
        return FalhaAtendimento.conflito(
                "CONFLITO_CONCORRENTE",
                "O atendimento foi alterado por outra pessoa. Recarregue para ver a situação atual.");
    }

    private static String limpar(String valor, int maximo, String campo) {
        String limpo = valor == null ? null : valor.strip();
        if (limpo == null || limpo.isEmpty()) {
            return null;
        }
        if (limpo.length() > maximo) {
            throw FalhaAtendimento.campoInvalido(campo, "Use no máximo " + maximo + " caracteres.");
        }
        return limpo;
    }

    private void evento(
            long conversa,
            Long atendimento,
            String tipo,
            Long autor,
            Long alvo,
            Long associado,
            String motivo,
            Instant agora) {
        jdbc.sql(
                        "insert into atd_evento (conversa_id, atendimento_id, tipo, autor_usuario_id, usuario_alvo_id, associado_id,"
                                + " motivo, ocorrido_em) values (:c, :a, :tipo, :autor, :alvo, :associado, :motivo, :agora)")
                .param("c", conversa)
                .param("a", atendimento)
                .param("tipo", tipo)
                .param("autor", autor)
                .param("alvo", alvo)
                .param("associado", associado)
                .param("motivo", motivo)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    // ------------------------------------------------------------------ presença

    /** Presença manual do atendente (estado real informado por ele; sem efeito em distribuição). */
    @Transactional
    public void definirPresenca(long ator, boolean disponivel) {
        jdbc.sql("insert into atd_presenca (usuario_id, situacao, atualizado_em) values (:u, :s, :agora)"
                        + " on duplicate key update situacao = values(situacao), atualizado_em = values(atualizado_em)")
                .param("u", ator)
                .param("s", disponivel ? "DISPONIVEL" : "AUSENTE")
                .param("agora", Timestamp.from(clock.instant()))
                .update();
    }
}
