package br.com.emconta.atendimento.nucleo;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.associados.CatalogoAssociados;
import br.com.emconta.integracoes.WhatsAppCloud;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>vínculo com associado é explícito; trocar de associado exige motivo;
 *   <li>setor e etiquetas são do ciclo (atendimento) e só o responsável atual os define, em andamento;
 *       o ciclo encerrado guarda a classificação que tinha, e um ciclo novo começa sem nenhuma.
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
    private final AvisosAtendimento avisos;
    private final Classificacoes classificacoes;
    private final Triagem triagem;
    private final boolean avisoResponsavelHabilitado;
    private final boolean digitandoHabilitado;
    /** Último pedido de "digitando…" por atendimento (nesta instância). */
    private final Map<Long, Instant> digitando = new ConcurrentHashMap<>();

    Atendimentos(
            JdbcClient jdbc,
            Autoria autoria,
            CatalogoAssociados associados,
            WhatsAppCloud canal,
            Clock clock,
            AvisosAtendimento avisos,
            Classificacoes classificacoes,
            Triagem triagem,
            @Value("${emconta.atendimento.digitando.habilitado:true}") boolean digitandoHabilitado,
            @Value("${emconta.atendimento.aviso-responsavel.habilitado:true}") boolean avisoResponsavelHabilitado) {
        this.jdbc = jdbc;
        this.autoria = autoria;
        this.associados = associados;
        this.canal = canal;
        this.clock = clock;
        this.avisos = avisos;
        this.classificacoes = classificacoes;
        this.triagem = triagem;
        this.avisoResponsavelHabilitado = avisoResponsavelHabilitado;
        this.digitandoHabilitado = digitandoHabilitado;
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

    /**
     * Assume um atendimento da fila. A conversa é travada antes do atendimento, na mesma ordem do
     * recebimento: uma resposta do cliente ao menu da triagem e a assunção nunca se cruzam, e a que chegar
     * primeiro decide. Quem assume encerra a triagem que ainda aguardava a resposta.
     */
    @Transactional
    public void assumir(long ator, long atendimento) {
        Instant agora = clock.instant();
        jdbc.sql("select conversa_id from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query(Long.class)
                .optional()
                .ifPresent(conversa -> jdbc.sql("select id from atd_conversa where id = :c for update")
                        .param("c", conversa)
                        .query(Long.class)
                        .list());
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
        triagem.interromper(estado.conversaId(), atendimento, agora);
        avisarResponsavel(estado.conversaId(), atendimento, ator, estado.versao(), agora);
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
        avisarResponsavel(estado.conversaId(), atendimento, destino, estado.versao() + 1, agora);
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
     * Define o setor (ou nenhum, com {@code setor} nulo) e as etiquetas do atendimento, substituindo a
     * classificação anterior deste ciclo. Mesmas regras de transferir e encerrar: em andamento, pelo
     * responsável atual e sobre a versão vista. Só itens ativos podem ser escolhidos; os que o ciclo já tem
     * podem ser mantidos mesmo que tenham sido desativados depois. Pedir a classificação que já vale não
     * altera nada. A mudança registra o evento CLASSIFICADO com os nomes daquele momento.
     */
    @Transactional
    public void classificar(long ator, long atendimento, long versao, Long setor, Collection<Long> etiquetas) {
        Estado estado = travar(atendimento);
        exigirResponsavel(ator, estado, versao, "classificar");
        Set<Long> pedidas = new LinkedHashSet<>(etiquetas == null ? List.of() : etiquetas);
        if (pedidas.contains(null)) {
            throw FalhaAtendimento.campoInvalido("etiquetaIds", "Etiqueta inválida.");
        }
        if (pedidas.size() > Classificacoes.MAXIMO_ETIQUETAS) {
            throw FalhaAtendimento.campoInvalido(
                    "etiquetaIds", "Escolha no máximo " + Classificacoes.MAXIMO_ETIQUETAS + " etiquetas.");
        }
        Object setorLido = jdbc.sql("select setor_id from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query()
                .singleRow()
                .get("setor_id");
        Long setorAtual = setorLido == null ? null : ((Number) setorLido).longValue();
        Set<Long> atuais = new LinkedHashSet<>(
                jdbc.sql("select etiqueta_id from atd_atendimento_etiqueta where atendimento_id = :id")
                        .param("id", atendimento)
                        .query(Long.class)
                        .list());
        var setorEscolhido = setor == null
                ? List.<Classificacoes.Item>of()
                : classificacoes.exigirEscolhas(
                        Classificacoes.Cadastro.SETOR,
                        List.of(setor),
                        setorAtual == null ? List.of() : List.of(setorAtual),
                        "setorId");
        var etiquetasEscolhidas =
                classificacoes.exigirEscolhas(Classificacoes.Cadastro.ETIQUETA, pedidas, atuais, "etiquetaIds");
        if (java.util.Objects.equals(setorAtual, setor) && atuais.equals(pedidas)) {
            return;
        }
        jdbc.sql("update atd_atendimento set setor_id = :setor, versao = versao + 1 where id = :id")
                .param("setor", setor)
                .param("id", atendimento)
                .update();
        List<Long> remover = new ArrayList<>(atuais);
        remover.removeAll(pedidas);
        if (!remover.isEmpty()) {
            jdbc.sql("delete from atd_atendimento_etiqueta where atendimento_id = :id and etiqueta_id in (:ids)")
                    .param("id", atendimento)
                    .param("ids", remover)
                    .update();
        }
        for (Long etiqueta : pedidas) {
            if (!atuais.contains(etiqueta)) {
                jdbc.sql("insert into atd_atendimento_etiqueta (atendimento_id, etiqueta_id) values (:id, :e)")
                        .param("id", atendimento)
                        .param("e", etiqueta)
                        .update();
            }
        }
        String retrato = "Setor: "
                + (setorEscolhido.isEmpty()
                        ? "nenhum"
                        : setorEscolhido.getFirst().nome())
                + " · Etiquetas: "
                + (etiquetasEscolhidas.isEmpty()
                        ? "nenhuma"
                        : etiquetasEscolhidas.stream()
                                .map(Classificacoes.Item::nome)
                                .collect(Collectors.joining(", ")));
        evento(estado.conversaId(), atendimento, "CLASSIFICADO", ator, null, null, null, retrato, clock.instant());
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
        avisos.conversa(estado.conversaId());
        avisos.trabalho(); // envio
        respondeu(atendimento);
        return jdbc.sql("select id from atd_mensagem where chave_idempotencia = :chave")
                .param("chave", chaveIdempotencia)
                .query(Long.class)
                .single();
    }

    /**
     * Aviso ao cliente de quem passa a atendê-lo, na mesma transação da mudança de responsável (assumir ou
     * transferir para alguém; devolver à fila não avisa). Entra na fila de envio como as respostas: o
     * {@link Envio} confere a janela de 24 h na hora de enviar (fora dela, JANELA_EXPIRADA) e registra
     * falha ou resultado desconhecido sem tocar no atendimento, que já foi confirmado. Um aviso por mudança
     * efetiva: a chave {@code aviso:responsavel:<atendimento>:<versão>} é única, então reprocessar ou
     * repetir a mesma mudança não cria outro; ações concorrentes já são barradas pela versão. O caractere
     * {@code :} não é aceito nas chaves vindas da interface, que não podem ocupar a do aviso. Origem
     * AVISO_RESPONSAVEL: fica fora da primeira resposta humana e não muda a ordem da fila.
     */
    private void avisarResponsavel(long conversa, long atendimento, long responsavel, long versao, Instant agora) {
        if (!avisoResponsavelHabilitado || !canal.configurado()) {
            return;
        }
        String nome = autoria.nomes(java.util.List.of(responsavel)).get(responsavel);
        String texto = nome == null || nome.isBlank()
                ? "Olá! Seu atendimento foi encaminhado para a nossa equipe."
                : "Olá! Seu atendimento agora está com " + nome.strip() + ".";
        try {
            jdbc.sql("insert into atd_mensagem (correlacao, conversa_id, atendimento_id, direcao, tipo, texto,"
                            + " situacao, criado_em, atualizado_em, chave_idempotencia, origem) values (:correlacao, :c,"
                            + " :a, 'SAIDA', 'TEXTO', :texto, 'PENDENTE', :agora, :agora, :chave, 'AVISO_RESPONSAVEL')")
                    .param("correlacao", UUID.randomUUID().toString())
                    .param("c", conversa)
                    .param("a", atendimento)
                    .param("texto", texto)
                    .param("agora", Timestamp.from(agora))
                    .param("chave", "aviso:responsavel:" + atendimento + ":" + versao)
                    .update();
        } catch (DuplicateKeyException e) {
            return; // esta mudança já tem aviso
        }
        avisos.trabalho(); // envio
    }

    /** Intervalo mínimo entre pedidos de "digitando…" de um atendimento (o indicador dura até 25 s). */
    static final Duration INTERVALO_DIGITANDO = Duration.ofSeconds(20);

    /**
     * "Digitando…" no WhatsApp do contato enquanto o responsável escreve a resposta. Valem as regras de
     * resposta ({@link #exigirPodeResponder}: responsável atual, em atendimento, janela aberta). O indicador
     * vai junto com a leitura da última mensagem recebida neste atendimento, como a Cloud API exige: o
     * contato vê essa mensagem como lida (no Em Conta nada muda). Sem mensagem recebida no atendimento,
     * nada é pedido. No máximo um pedido a cada {@link #INTERVALO_DIGITANDO} por atendimento; uma
     * resposta registrada libera o próximo (o indicador some com a resposta).
     *
     * @return se o indicador foi pedido e aceito pela Meta
     */
    public boolean digitando(long ator, long atendimento) {
        if (!digitandoHabilitado || !canal.configurado()) {
            return false;
        }
        exigirPodeResponder(ator, atendimento, false);
        Instant agora = clock.instant();
        boolean[] liberado = {false};
        digitando.compute(atendimento, (id, ultimo) -> {
            liberado[0] = ultimo == null || !agora.isBefore(ultimo.plus(INTERVALO_DIGITANDO));
            return liberado[0] ? agora : ultimo;
        });
        if (!liberado[0]) {
            return false;
        }
        if (digitando.size() > 1000) {
            digitando.values().removeIf(ultimo -> ultimo.isBefore(agora.minus(INTERVALO_DIGITANDO)));
        }
        return jdbc.sql("select id_externo from atd_mensagem where atendimento_id = :a and direcao = 'ENTRADA'"
                        + " and id_externo is not null order by id desc limit 1")
                .param("a", atendimento)
                .query(String.class)
                .optional()
                .map(canal::indicarDigitando)
                .orElse(false);
    }

    /** Uma resposta foi registrada: o próximo "digitando…" deste atendimento já pode ser pedido. */
    void respondeu(long atendimento) {
        digitando.remove(atendimento);
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
        evento(conversa, atendimento, tipo, autor, alvo, associado, motivo, null, agora);
    }

    private void evento(
            long conversa,
            Long atendimento,
            String tipo,
            Long autor,
            Long alvo,
            Long associado,
            String motivo,
            String detalhe,
            Instant agora) {
        jdbc.sql(
                        "insert into atd_evento (conversa_id, atendimento_id, tipo, autor_usuario_id, usuario_alvo_id, associado_id,"
                                + " motivo, detalhe, ocorrido_em) values (:c, :a, :tipo, :autor, :alvo, :associado, :motivo,"
                                + " :detalhe, :agora)")
                .param("c", conversa)
                .param("a", atendimento)
                .param("tipo", tipo)
                .param("autor", autor)
                .param("alvo", alvo)
                .param("associado", associado)
                .param("motivo", motivo)
                .param("detalhe", detalhe)
                .param("agora", Timestamp.from(agora))
                .update();
        avisos.conversa(conversa);
    }

    // ------------------------------------------------------------------ presença

    /**
     * Escolha da própria pessoa (sem efeito em distribuição). Manual: Disponível ou Ausente, que vale até
     * ela voltar ao automático. Automático: mantém a situação atual (Ausente, sem registro) até a próxima
     * {@linkplain #registrarAtividade atividade de resposta}.
     */
    @Transactional
    public void definirPresenca(long ator, Boolean disponivel, boolean automatica) {
        Timestamp agora = Timestamp.from(clock.instant());
        if (automatica) {
            jdbc.sql("insert into atd_presenca (usuario_id, situacao, modo, atualizado_em)"
                            + " values (:u, 'AUSENTE', 'AUTOMATICO', :agora)"
                            + " on duplicate key update modo = 'AUTOMATICO', atualizado_em = values(atualizado_em)")
                    .param("u", ator)
                    .param("agora", agora)
                    .update();
            return;
        }
        if (disponivel == null) {
            throw FalhaAtendimento.campoInvalido("disponivel", "Informe Disponível ou Ausente.");
        }
        jdbc.sql(
                        "insert into atd_presenca (usuario_id, situacao, modo, atualizado_em) values (:u, :s, 'MANUAL', :agora)"
                                + " on duplicate key update situacao = values(situacao), modo = 'MANUAL',"
                                + " atualizado_em = values(atualizado_em)")
                .param("u", ator)
                .param("s", disponivel ? "DISPONIVEL" : "AUSENTE")
                .param("agora", agora)
                .update();
    }

    /**
     * A pessoa começou a escrever uma resposta numa conversa que pode atender (responsável atual, em
     * atendimento). Em modo automático, passa a Disponível; com escolha manual, nada muda. A condição está
     * no próprio comando: um pedido automático atrasado, que chegue depois de uma escolha manual, não a
     * sobrescreve. Não altera nada no atendimento.
     */
    @Transactional
    public void registrarAtividade(long ator, long atendimento) {
        exigirPodeResponderSemJanela(ator, atendimento);
        jdbc.sql("insert into atd_presenca (usuario_id, situacao, modo, atualizado_em)"
                        + " values (:u, 'DISPONIVEL', 'AUTOMATICO', :agora) on duplicate key update"
                        + " atualizado_em = if(modo = 'AUTOMATICO' and situacao <> 'DISPONIVEL',"
                        + " values(atualizado_em), atualizado_em),"
                        + " situacao = if(modo = 'AUTOMATICO', 'DISPONIVEL', situacao)")
                .param("u", ator)
                .param("agora", Timestamp.from(clock.instant()))
                .update();
    }
}
