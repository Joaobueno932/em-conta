package br.com.emconta.atendimento.nucleo;

import br.com.emconta.integracoes.WhatsAppCloud;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triagem inicial pelo WhatsApp: ao abrir um ciclo, uma saudação com menu numerado; a opção escolhida
 * define o setor do ciclo antes de alguém assumir (evento {@code TRIAGEM_CLASSIFICADA}). Sem destino
 * válido, o ciclo continua na fila sem setor (evento {@code TRIAGEM_ENCAMINHADA}): opção "Outro assunto",
 * opção sem setor configurado ou com o setor desativado, resposta inválida depois de uma nova tentativa e
 * falha no envio de uma mensagem da triagem. O ciclo fica em Aguardando o tempo todo: a triagem nunca
 * impede alguém de assumir, e quem assume encerra a triagem (as mensagens seguintes seguem o fluxo
 * normal). A correção manual do setor continua com o responsável ({@link Atendimentos#classificar}).
 *
 * <p>Configuração única, alterada por quem tem {@code triagem:configurar}: ligada ou não e o setor de cada
 * opção (duas opções podem ter o mesmo). Começa desligada.
 *
 * <p>Mensagens ao cliente pela fila de envio, como o aviso de responsável: origem {@code TRIAGEM} (fora da
 * primeira resposta humana) e chave única {@code triagem:<atendimento>:<etapa>}, então reprocessar um
 * evento não envia de novo. Recebimento e assunção travam a conversa antes do atendimento: a resposta do
 * cliente e a assunção por um operador nunca se cruzam, e a primeira que chegar decide.
 */
@Service
public class Triagem {

    /** Respostas inválidas aceitas: na primeira o menu é repetido; na segunda, fila sem setor. */
    static final int RESPOSTAS_INVALIDAS = 2;

    /** Opções do menu, na ordem exibida. Só as três primeiras têm destino configurável. */
    public enum Opcao {
        FINANCEIRO(1, "Financeiro"),
        COBRANCA(2, "Cobrança ou segunda via"),
        COMERCIAL(3, "Quero conhecer o Em Conta"),
        HUMANO(4, "Outro assunto ou falar com um atendente");

        private final int numero;
        private final String rotulo;

        Opcao(int numero, String rotulo) {
            this.numero = numero;
            this.rotulo = rotulo;
        }

        public int numero() {
            return numero;
        }

        public String rotulo() {
            return rotulo;
        }

        /** Destino configurável (setor); "Outro assunto" vai sempre para a fila sem setor. */
        public boolean configuravel() {
            return this != HUMANO;
        }

        static Optional<Opcao> porNumero(int numero) {
            return Arrays.stream(values()).filter(o -> o.numero == numero).findFirst();
        }

        String descricao() {
            return "Opção " + numero + " (" + rotulo + ")";
        }
    }

    public record Configuracao(
            boolean ativa, long versao, Map<Opcao, Long> destinos, Instant atualizadoEm, Long atualizadoPor) {}

    /** Número da opção, sozinho ou com pontuação ("1", "1.", "1)", "opção 1"); o emoji 1️⃣ também vale. */
    private static final Pattern RESPOSTA = Pattern.compile("(?iu)(?:op[cç][aã]o\\s*)?([1-9])\\s*[.)\\-–]?");

    private static final String MENU =
            Arrays.stream(Opcao.values()).map(o -> o.numero + " - " + o.rotulo).collect(Collectors.joining("\n"));

    public static final String SAUDACAO = "Olá! Você está no atendimento do Em Conta. Para direcionar sua conversa,"
            + " responda com o número de uma das opções:\n\n" + MENU;
    static final String NOVA_TENTATIVA = "Não identifiquei a opção. Responda só com o número (1 a 4):\n\n" + MENU;
    static final String ENCAMINHADA =
            "Obrigado! Sua conversa foi encaminhada. Em breve alguém da nossa equipe vai" + " atender você por aqui.";
    static final String CANCELADA = "Não enviada: a triagem terminou antes do envio (atendimento assumido ou opção"
            + " já escolhida). Nada foi enviado ao WhatsApp.";
    static final String ENCAMINHADA_SEM_OPCAO = "Não consegui identificar a opção. Sua conversa foi encaminhada para"
            + " a nossa equipe, e em breve alguém vai atender você por aqui.";

    private final JdbcClient jdbc;
    private final WhatsAppCloud canal;
    private final Clock clock;
    private final AvisosAtendimento avisos;
    private final Classificacoes classificacoes;

    Triagem(
            JdbcClient jdbc,
            WhatsAppCloud canal,
            Clock clock,
            AvisosAtendimento avisos,
            Classificacoes classificacoes) {
        this.jdbc = jdbc;
        this.canal = canal;
        this.clock = clock;
        this.avisos = avisos;
        this.classificacoes = classificacoes;
    }

    // ------------------------------------------------------------------ configuração

    @Transactional(readOnly = true)
    public Configuracao configuracao() {
        return ler(false);
    }

    /**
     * Liga ou desliga a triagem e define o setor de cada opção configurável, sobre a versão vista. Opção
     * ausente do pedido fica sem destino (fila sem setor). Só setores ativos podem ser escolhidos; manter o
     * setor que a opção já tinha, mesmo desativado depois, não é escolha nova.
     */
    /** Destino pedido para uma opção; {@code setor} nulo = fila sem setor. */
    public record Destino(Opcao opcao, Long setor) {}

    @Transactional
    public Configuracao configurar(long ator, long versao, boolean ativa, List<Destino> destinos) {
        Configuracao atual = ler(true);
        if (atual.versao() != versao) {
            throw FalhaAtendimento.conflito(
                    "CONFLITO_CONCORRENTE",
                    "A triagem foi alterada por outra pessoa. Recarregue para ver a configuração atual antes de salvar.");
        }
        Map<Opcao, Long> novos = new EnumMap<>(Opcao.class);
        for (Destino destino : destinos) {
            Opcao opcao = destino.opcao();
            String campo = "opcoes." + opcao.name();
            if (!opcao.configuravel()) {
                throw FalhaAtendimento.campoInvalido(
                        campo, "A opção \"" + opcao.rotulo + "\" vai sempre para a fila sem setor.");
            }
            if (novos.containsKey(opcao)) {
                throw FalhaAtendimento.campoInvalido(campo, "Informe cada opção uma única vez.");
            }
            Long setor = destino.setor();
            if (setor != null && !setor.equals(atual.destinos().get(opcao))) {
                var item = classificacoes.obter(Classificacoes.Cadastro.SETOR, setor);
                if (item.isEmpty()) {
                    throw FalhaAtendimento.campoInvalido(campo, "Setor não encontrado.");
                }
                if (!item.get().ativo()) {
                    throw FalhaAtendimento.campoInvalido(campo, "Escolha um setor ativo.");
                }
            }
            novos.put(opcao, setor);
        }
        for (Opcao opcao : Opcao.values()) {
            if (opcao.configuravel()) {
                jdbc.sql("update atd_triagem_opcao set setor_id = :setor where opcao = :opcao")
                        .param("setor", novos.get(opcao))
                        .param("opcao", opcao.name())
                        .update();
            }
        }
        jdbc.sql("update atd_triagem_configuracao set ativa = :ativa, versao = versao + 1, atualizado_em = :agora,"
                        + " atualizado_por = :ator where id = 1")
                .param("ativa", ativa)
                .param("agora", Timestamp.from(clock.instant()))
                .param("ator", ator)
                .update();
        return ler(false);
    }

    private Configuracao ler(boolean travar) {
        Map<Opcao, Long> destinos = new EnumMap<>(Opcao.class);
        Configuracao lida = jdbc.sql(
                        "select ativa, versao, atualizado_em, atualizado_por from atd_triagem_configuracao where id = 1"
                                + (travar ? " for update" : ""))
                .query((rs, n) -> new Configuracao(
                        rs.getBoolean("ativa"),
                        rs.getLong("versao"),
                        destinos,
                        rs.getTimestamp("atualizado_em") == null
                                ? null
                                : rs.getTimestamp("atualizado_em").toInstant(),
                        rs.getObject("atualizado_por", Long.class)))
                .single();
        jdbc.sql("select opcao, setor_id from atd_triagem_opcao")
                .query((rs, n) -> destinos.put(Opcao.valueOf(rs.getString(1)), rs.getObject(2, Long.class)))
                .list();
        return lida;
    }

    // ------------------------------------------------------------------ ciclo

    /**
     * Ciclo novo aberto por uma mensagem do cliente: com a triagem ligada e o canal configurado, grava a
     * triagem do ciclo e enfileira a saudação. Na transação do recebimento, com a conversa travada.
     */
    void iniciar(long conversa, long atendimento, Instant agora) {
        if (!canal.configurado() || !ler(false).ativa()) {
            return;
        }
        jdbc.sql(
                        "insert into atd_triagem (atendimento_id, situacao, iniciada_em) values (:a, 'AGUARDANDO_RESPOSTA', :agora)")
                .param("a", atendimento)
                .param("agora", Timestamp.from(agora))
                .update();
        enfileirar(conversa, atendimento, SAUDACAO, "saudacao", agora);
    }

    /**
     * Mensagem do cliente num ciclo que já estava aberto. Só tem efeito com a triagem do ciclo aguardando a
     * resposta; senão é uma mensagem como outra qualquer. Na transação do recebimento, com a conversa
     * travada; a linha do atendimento também é travada aqui, na mesma ordem da assunção.
     */
    void receber(long conversa, long atendimento, String texto, Instant agora) {
        Optional<Map<String, Object>> linha = jdbc
                .sql("select t.situacao, t.respostas_invalidas, a.situacao as situacao_atendimento"
                        + " from atd_atendimento a join atd_triagem t on t.atendimento_id = a.id"
                        + " where a.id = :a for update")
                .param("a", atendimento)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (linha.isEmpty() || !"AGUARDANDO_RESPOSTA".equals(linha.get().get("situacao"))) {
            return;
        }
        if (!"AGUARDANDO".equals(linha.get().get("situacao_atendimento"))) {
            concluir(atendimento, "INTERROMPIDA", null, "ASSUMIDO", agora); // defensivo: assumir já encerra
            return;
        }
        if (mensagemDaTriagemFalhou(atendimento)) {
            // O cliente não recebeu o menu: esta mensagem não é uma resposta a ele.
            encaminhar(conversa, atendimento, null, "FALHA_ENVIO", "Falha no envio da triagem", null, agora);
            return;
        }
        Optional<Opcao> escolhida = interpretar(texto);
        if (escolhida.isEmpty()) {
            int invalidas = ((Number) linha.get().get("respostas_invalidas")).intValue() + 1;
            if (invalidas >= RESPOSTAS_INVALIDAS) {
                encaminhar(
                        conversa,
                        atendimento,
                        null,
                        "RESPOSTA_INVALIDA",
                        "Resposta inválida após nova tentativa",
                        ENCAMINHADA_SEM_OPCAO,
                        agora);
                return;
            }
            jdbc.sql("update atd_triagem set respostas_invalidas = :n where atendimento_id = :a")
                    .param("n", invalidas)
                    .param("a", atendimento)
                    .update();
            enfileirar(conversa, atendimento, NOVA_TENTATIVA, "nova-tentativa", agora);
            return;
        }
        Opcao opcao = escolhida.get();
        if (!opcao.configuravel()) {
            encaminhar(conversa, atendimento, opcao, "OPCAO_HUMANO", opcao.descricao(), ENCAMINHADA, agora);
            return;
        }
        Object destino = jdbc.sql("select setor_id from atd_triagem_opcao where opcao = :opcao")
                .param("opcao", opcao.name())
                .query()
                .singleRow()
                .get("setor_id");
        Long setor = destino == null ? null : ((Number) destino).longValue();
        var item = setor == null
                ? Optional.<Classificacoes.Item>empty()
                : classificacoes.obter(Classificacoes.Cadastro.SETOR, setor);
        if (item.isEmpty()) {
            encaminhar(
                    conversa,
                    atendimento,
                    opcao,
                    "SEM_DESTINO",
                    opcao.descricao() + ": sem setor configurado",
                    ENCAMINHADA,
                    agora);
            return;
        }
        if (!item.get().ativo()) {
            encaminhar(
                    conversa,
                    atendimento,
                    opcao,
                    "DESTINO_INATIVO",
                    opcao.descricao() + ": setor " + item.get().nome() + " inativo",
                    ENCAMINHADA,
                    agora);
            return;
        }
        jdbc.sql("update atd_atendimento set setor_id = :setor, versao = versao + 1 where id = :a")
                .param("setor", setor)
                .param("a", atendimento)
                .update();
        concluir(atendimento, "CLASSIFICADA", opcao, null, agora);
        evento(
                conversa,
                atendimento,
                "TRIAGEM_CLASSIFICADA",
                opcao.descricao() + " · Setor: " + item.get().nome(),
                agora);
        enfileirar(conversa, atendimento, ENCAMINHADA, "encaminhamento", agora);
    }

    /**
     * Alguém assumiu o ciclo: a triagem que ainda aguardava a resposta termina, e as próximas mensagens
     * seguem o fluxo normal. A saudação e a repetição do menu que ainda aguardam envio (inclusive à espera
     * de nova tentativa) são canceladas. Na transação da assunção, com a conversa e o atendimento travados.
     *
     * <p>A mensagem já reservada pelo {@link Envio} (ENVIANDO) não é cancelada: a chamada à Meta está em
     * andamento, fora de transação, e o resultado é registrado como o de qualquer envio. A leitura travada
     * espera a reserva em curso terminar e então a enxerga como ENVIANDO; do outro lado, a reserva pula a
     * mensagem travada aqui ({@code SKIP LOCKED}) e, depois da confirmação, a encontra CANCELADA.
     */
    void interromper(long conversa, long atendimento, Instant agora) {
        List<Long> pendentes = jdbc.sql("select id from atd_mensagem where chave_idempotencia in (:chaves)"
                        + " and situacao = 'PENDENTE' for update")
                .param("chaves", chavesDoMenu(atendimento))
                .query(Long.class)
                .list();
        pendentes.forEach(mensagem -> cancelar(mensagem, agora));
        if (!pendentes.isEmpty()) {
            avisos.conversa(conversa);
        }
        if (concluir(atendimento, "INTERROMPIDA", null, "ASSUMIDO", agora) > 0) {
            evento(
                    conversa,
                    atendimento,
                    "TRIAGEM_INTERROMPIDA",
                    "Atendimento assumido antes da resposta do cliente",
                    agora);
        }
    }

    /**
     * Uma mensagem de saída falhou de vez (recusa, janela de 24 h ou {@code failed} da Meta). Se for da
     * triagem e ela ainda aguardava a resposta, a triagem termina e o ciclo segue na fila sem setor.
     */
    void envioFalhou(long mensagem, Instant agora) {
        jdbc
                .sql("select conversa_id, atendimento_id from atd_mensagem where id = :id and origem = 'TRIAGEM'")
                .param("id", mensagem)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .ifPresent(m -> {
                    long conversa = ((Number) m.get("conversa_id")).longValue();
                    long atendimento = ((Number) m.get("atendimento_id")).longValue();
                    if (concluir(atendimento, "ENCAMINHADA", null, "FALHA_ENVIO", agora) > 0) {
                        evento(
                                conversa,
                                atendimento,
                                "TRIAGEM_ENCAMINHADA",
                                "Falha no envio da triagem · fila sem setor",
                                agora);
                    }
                });
    }

    /**
     * Na reserva do envio, com a mensagem travada: a saudação e a repetição do menu só saem enquanto a
     * triagem do ciclo aguarda a resposta. Se ela já terminou (assumido, opção escolhida ou encaminhado),
     * a mensagem é CANCELADA sem chamar a Meta e devolve {@code true}.
     */
    boolean cancelarMenuObsoleto(long mensagem, Instant agora) {
        Optional<Map<String, Object>> linha = jdbc
                .sql("select m.atendimento_id, m.chave_idempotencia, t.situacao from atd_mensagem m"
                        + " join atd_triagem t on t.atendimento_id = m.atendimento_id"
                        + " where m.id = :id and m.origem = 'TRIAGEM'")
                .param("id", mensagem)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (linha.isEmpty()
                || "AGUARDANDO_RESPOSTA".equals(linha.get().get("situacao"))
                || !chavesDoMenu(((Number) linha.get().get("atendimento_id")).longValue())
                        .contains((String) linha.get().get("chave_idempotencia"))) {
            return false;
        }
        cancelar(mensagem, agora);
        return true;
    }

    /** Saudação e repetição do menu do ciclo: as mensagens que só fazem sentido antes da resposta. */
    private static List<String> chavesDoMenu(long atendimento) {
        return List.of("triagem:" + atendimento + ":saudacao", "triagem:" + atendimento + ":nova-tentativa");
    }

    /** Situação final, sem chamada à Meta; entra no histórico de situações como as demais. */
    private void cancelar(long mensagem, Instant agora) {
        int alteradas = jdbc.sql(
                        "update atd_mensagem set situacao = 'CANCELADA', erro_codigo = null, erro_titulo = :motivo,"
                                + " proxima_tentativa_em = null, atualizado_em = :agora where id = :id and situacao = 'PENDENTE'")
                .param("motivo", CANCELADA)
                .param("agora", Timestamp.from(agora))
                .param("id", mensagem)
                .update();
        if (alteradas > 0) {
            jdbc.sql("insert ignore into atd_mensagem_situacao (mensagem_id, situacao, erro_titulo, registrado_em)"
                            + " values (:m, 'CANCELADA', :motivo, :agora)")
                    .param("m", mensagem)
                    .param("motivo", CANCELADA)
                    .param("agora", Timestamp.from(agora))
                    .update();
        }
    }

    /** Número da opção na resposta do cliente; vazio se não for uma opção do menu. */
    static Optional<Opcao> interpretar(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        // 1️⃣ = "1" + seletor de variação (U+FE0F) + capa de tecla (U+20E3).
        String limpo = texto.replace("️", "").replace("⃣", "").strip();
        Matcher m = RESPOSTA.matcher(limpo);
        return m.matches() ? Opcao.porNumero(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private void encaminhar(
            long conversa,
            long atendimento,
            Opcao opcao,
            String motivo,
            String detalhe,
            String mensagemAoCliente,
            Instant agora) {
        concluir(atendimento, "ENCAMINHADA", opcao, motivo, agora);
        evento(conversa, atendimento, "TRIAGEM_ENCAMINHADA", detalhe + " · fila sem setor", agora);
        if (mensagemAoCliente != null) {
            enfileirar(conversa, atendimento, mensagemAoCliente, "encaminhamento", agora);
        }
    }

    /** Desfecho da triagem; só sai de AGUARDANDO_RESPOSTA (devolve 0 se ela já tinha terminado). */
    private int concluir(long atendimento, String situacao, Opcao opcao, String motivo, Instant agora) {
        return jdbc.sql("update atd_triagem set situacao = :s, opcao = :opcao, motivo = :motivo, concluida_em = :agora"
                        + " where atendimento_id = :a and situacao = 'AGUARDANDO_RESPOSTA'")
                .param("s", situacao)
                .param("opcao", opcao == null ? null : opcao.name())
                .param("motivo", motivo)
                .param("agora", Timestamp.from(agora))
                .param("a", atendimento)
                .update();
    }

    private boolean mensagemDaTriagemFalhou(long atendimento) {
        return jdbc.sql("select count(*) from atd_mensagem where atendimento_id = :a and origem = 'TRIAGEM'"
                                + " and situacao in ('FALHA', 'JANELA_EXPIRADA')")
                        .param("a", atendimento)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Mensagem automática na fila de envio; a chave única impede repetir a mesma etapa do ciclo. */
    private void enfileirar(long conversa, long atendimento, String texto, String etapa, Instant agora) {
        try {
            jdbc.sql("insert into atd_mensagem (correlacao, conversa_id, atendimento_id, direcao, tipo, texto,"
                            + " situacao, criado_em, atualizado_em, chave_idempotencia, origem) values (:correlacao, :c,"
                            + " :a, 'SAIDA', 'TEXTO', :texto, 'PENDENTE', :agora, :agora, :chave, 'TRIAGEM')")
                    .param("correlacao", UUID.randomUUID().toString())
                    .param("c", conversa)
                    .param("a", atendimento)
                    .param("texto", texto)
                    .param("agora", Timestamp.from(agora))
                    .param("chave", "triagem:" + atendimento + ":" + etapa)
                    .update();
        } catch (DuplicateKeyException e) {
            return; // esta etapa já foi enfileirada
        }
        avisos.conversa(conversa);
        avisos.trabalho(); // envio
    }

    private void evento(long conversa, long atendimento, String tipo, String detalhe, Instant agora) {
        jdbc.sql("insert into atd_evento (conversa_id, atendimento_id, tipo, detalhe, ocorrido_em)"
                        + " values (:c, :a, :tipo, :detalhe, :agora)")
                .param("c", conversa)
                .param("a", atendimento)
                .param("tipo", tipo)
                .param("detalhe", detalhe)
                .param("agora", Timestamp.from(agora))
                .update();
        avisos.conversa(conversa);
    }
}
