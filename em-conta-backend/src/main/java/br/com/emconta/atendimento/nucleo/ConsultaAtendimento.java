package br.com.emconta.atendimento.nucleo;

import br.com.emconta.associados.CatalogoAssociados;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas do atendimento: fila, detalhe, linha do tempo e histórico geral. Uma consulta por página
 * e outra para o total; busca no servidor por nome do perfil ou número (trecho).
 */
@Component
public class ConsultaAtendimento {

    public static final int TAMANHO_MAXIMO_PAGINA = 100;

    private final JdbcClient jdbc;
    private final Clock clock;

    /** Máximo de associados considerados na busca por nome (evita listas enormes no {@code in}). */
    static final int MAXIMO_ASSOCIADOS_BUSCA = 200;

    private final CatalogoAssociados associados;

    ConsultaAtendimento(JdbcClient jdbc, Clock clock, CatalogoAssociados associados) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.associados = associados;
    }

    public enum Visao {
        AGUARDANDO,
        EM_ANDAMENTO,
        MEUS,
        ENCERRADOS
    }

    public record Resumo(
            long id,
            String situacao,
            long conversaId,
            String waId,
            String nomePerfil,
            Long associadoId,
            Long responsavelId,
            Instant iniciadoEm,
            Instant ultimaMensagemEm,
            Instant encerradoEm,
            Long encerradoPor) {}

    private static final String SELECT_RESUMO = "select a.id, a.situacao, a.conversa_id, c.wa_id, c.nome_perfil,"
            + " v.associado_id, a.responsavel_id, a.iniciado_em, v.ultima_mensagem_em, a.encerrado_em, a.encerrado_por"
            + " from atd_atendimento a join atd_conversa v on v.id = a.conversa_id join atd_contato c on c.id = v.contato_id";

    @Transactional(readOnly = true)
    public Page<Resumo> fila(Visao visao, String busca, long ator, boolean maisAntigos, int pagina, int tamanho) {
        List<String> condicoes = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        switch (visao) {
            case AGUARDANDO -> condicoes.add("a.situacao = 'AGUARDANDO'");
            case EM_ANDAMENTO -> condicoes.add("a.situacao = 'EM_ATENDIMENTO'");
            case MEUS -> {
                condicoes.add("a.situacao = 'EM_ATENDIMENTO' and a.responsavel_id = :ator");
                params.put("ator", ator);
            }
            case ENCERRADOS -> condicoes.add("a.situacao = 'ENCERRADO'");
        }
        adicionarBusca(busca, condicoes, params);
        String where = " where " + String.join(" and ", condicoes);
        String ordem = visao == Visao.ENCERRADOS
                ? " order by a.encerrado_em desc, a.id desc"
                : " order by a.iniciado_em " + (maisAntigos ? "asc" : "desc") + ", a.id";
        return pagina(where, ordem, params, pagina, tamanho);
    }

    /** Contagem por visão, para os indicadores da fila. */
    @Transactional(readOnly = true)
    public Map<Visao, Long> contagens(long ator) {
        Map<Visao, Long> mapa = new LinkedHashMap<>();
        Map<String, Object> linha = jdbc.sql("select coalesce(sum(situacao = 'AGUARDANDO'), 0) aguardando,"
                        + " coalesce(sum(situacao = 'EM_ATENDIMENTO'), 0) andamento,"
                        + " coalesce(sum(situacao = 'EM_ATENDIMENTO' and responsavel_id = :ator), 0) meus"
                        + " from atd_atendimento where situacao <> 'ENCERRADO'")
                .param("ator", ator)
                .query()
                .singleRow();
        mapa.put(Visao.AGUARDANDO, ((Number) linha.get("aguardando")).longValue());
        mapa.put(Visao.EM_ANDAMENTO, ((Number) linha.get("andamento")).longValue());
        mapa.put(Visao.MEUS, ((Number) linha.get("meus")).longValue());
        return mapa;
    }

    /** Histórico geral: atendimentos encerrados, com busca e período (datas em UTC). */
    @Transactional(readOnly = true)
    public Page<Resumo> historico(String busca, LocalDate de, LocalDate ate, int pagina, int tamanho) {
        List<String> condicoes = new ArrayList<>(List.of("a.situacao = 'ENCERRADO'"));
        Map<String, Object> params = new LinkedHashMap<>();
        adicionarBusca(busca, condicoes, params);
        if (de != null) {
            condicoes.add("a.encerrado_em >= :de");
            params.put("de", Timestamp.from(de.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (ate != null) {
            condicoes.add("a.encerrado_em < :ate");
            params.put("ate", Timestamp.from(ate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        return pagina(
                " where " + String.join(" and ", condicoes),
                " order by a.encerrado_em desc, a.id desc",
                params,
                pagina,
                tamanho);
    }

    /**
     * Busca por nome do perfil do WhatsApp, número (trecho com 4+ dígitos) ou nome do associado vinculado
     * (PF ou PJ). Os associados vêm da API pública do módulo de associados, limitados a
     * {@link #MAXIMO_ASSOCIADOS_BUSCA}; cada atendimento aparece uma vez (filtro, não junção).
     */
    private void adicionarBusca(String busca, List<String> condicoes, Map<String, Object> params) {
        String termo = busca == null ? "" : busca.strip();
        if (termo.isEmpty()) {
            return;
        }
        List<String> alternativas = new ArrayList<>(List.of("c.nome_perfil like :busca escape '!'"));
        params.put("busca", "%" + termo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        String digitos = termo.replaceAll("\\D", "");
        if (digitos.length() >= 4) {
            alternativas.add("c.wa_id like :digitos");
            params.put("digitos", "%" + digitos + "%");
        }
        List<Long> encontrados = associados.idsPorNome(termo, MAXIMO_ASSOCIADOS_BUSCA);
        if (!encontrados.isEmpty()) {
            alternativas.add("v.associado_id in (:associados)");
            params.put("associados", encontrados);
        }
        condicoes.add("(" + String.join(" or ", alternativas) + ")");
    }

    private Page<Resumo> pagina(String where, String ordem, Map<String, Object> params, int pagina, int tamanho) {
        var contagem = jdbc.sql("select count(*) from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                + " join atd_contato c on c.id = v.contato_id" + where);
        var consulta = jdbc.sql(SELECT_RESUMO + where + ordem + " limit :limite offset :deslocamento");
        for (var p : params.entrySet()) {
            contagem = contagem.param(p.getKey(), p.getValue());
            consulta = consulta.param(p.getKey(), p.getValue());
        }
        long total = contagem.query(Long.class).single();
        List<Resumo> itens = consulta.param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query(ConsultaAtendimento::resumo)
                .list();
        return new PageImpl<>(itens, PageRequest.of(pagina, tamanho), total);
    }

    public record Detalhe(Resumo resumo, long versao, long conversaVersao, Instant ultimaEntradaEm, Long assumidoPor) {}

    @Transactional(readOnly = true)
    public Optional<Detalhe> detalhe(long atendimento) {
        return jdbc.sql(SELECT_RESUMO.replace(
                                "select a.id,",
                                "select a.versao as versao_at, v.versao as versao_conv,"
                                        + " v.ultima_entrada_em, a.id,")
                        + " where a.id = :id")
                .param("id", atendimento)
                .query((rs, n) -> new Detalhe(
                        resumo(rs, n),
                        rs.getLong("versao_at"),
                        rs.getLong("versao_conv"),
                        instante(rs, "ultima_entrada_em"),
                        rs.getObject("responsavel_id", Long.class)))
                .optional();
    }

    /**
     * Item da linha do tempo: mensagem ou evento. {@code cursor} é a posição do item na ordem da linha do
     * tempo (ver {@link Cursor}); {@code ocorridoEm} é o horário exibido (o do WhatsApp, para mensagens
     * recebidas).
     */
    public record ItemLinhaDoTempo(
            String tipo,
            long id,
            String cursor,
            Instant ocorridoEm,
            Long atendimentoId,
            String direcao,
            String tipoMensagem,
            String tipoExterno,
            String texto,
            String situacao,
            Integer erroCodigo,
            String erroTitulo,
            Long autorId,
            String evento,
            Long usuarioAlvoId,
            Long associadoId,
            String motivo,
            String etapaFalha,
            Instant atualizadoEm,
            AnexoResumo anexo) {

        ItemLinhaDoTempo comAnexo(AnexoResumo novo) {
            return new ItemLinhaDoTempo(
                    tipo,
                    id,
                    cursor,
                    ocorridoEm,
                    atendimentoId,
                    direcao,
                    tipoMensagem,
                    tipoExterno,
                    texto,
                    situacao,
                    erroCodigo,
                    erroTitulo,
                    autorId,
                    evento,
                    usuarioAlvoId,
                    associadoId,
                    motivo,
                    etapaFalha,
                    atualizadoEm,
                    novo);
        }
    }

    /** Anexo de uma mensagem, sem conteúdo nem endereço de armazenamento. */
    public record AnexoResumo(
            long id, String categoria, String mime, String nome, Long tamanho, String situacao, String erro) {}

    /** Página da linha do tempo, em ordem cronológica; {@code anterior} é nulo no início do histórico. */
    public record PaginaLinhaDoTempo(List<ItemLinhaDoTempo> itens, String anterior) {}

    /**
     * Posição na linha do tempo: (instante de registro em microssegundos, ordem do tipo, id). Mensagens e
     * eventos usam o instante em que foram gravados ({@code criado_em}/{@code ocorrido_em}, que só cresce
     * para registros novos), não o horário informado pelo WhatsApp, para que um registro novo nunca caia
     * no meio de páginas já lidas. Empates no mesmo instante: evento antes de mensagem, depois o id.
     * Texto: {@code <micros>.<0|1>.<id>}, comparável e validado na entrada.
     */
    public record Cursor(long micros, int ordem, long id) {

        private static final Pattern FORMATO = Pattern.compile("(\\d{1,18})\\.([01])\\.(\\d{1,18})");

        public static Optional<Cursor> ler(String texto) {
            if (texto == null) {
                return Optional.empty();
            }
            var m = FORMATO.matcher(texto);
            if (!m.matches()) {
                throw FalhaAtendimento.campoInvalido("antes", "Cursor inválido.");
            }
            return Optional.of(
                    new Cursor(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3))));
        }

        static Cursor de(Instant registro, int ordem, long id) {
            return new Cursor(registro.getEpochSecond() * 1_000_000L + registro.getNano() / 1_000, ordem, id);
        }

        Instant instante() {
            return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
        }

        @Override
        public String toString() {
            return micros + "." + ordem + "." + id;
        }
    }

    private static final int ORDEM_EVENTO = 0;
    private static final int ORDEM_MENSAGEM = 1;

    /**
     * Uma página da linha do tempo da conversa (todos os ciclos) ou só de um atendimento: os {@code
     * tamanho} itens imediatamente anteriores a {@code antes} (ou os mais recentes, sem cursor). Mensagens
     * e eventos entram na mesma ordenação; cada tabela contribui no máximo {@code tamanho + 1} linhas,
     * lidas pelos índices {@code (conversa_id, criado_em, id)} e {@code (conversa_id, ocorrido_em, id)}.
     */
    @Transactional(readOnly = true)
    public PaginaLinhaDoTempo linhaDoTempo(long conversa, Long somenteAtendimento, Cursor antes, int tamanho) {
        int limite = tamanho + 1;
        String filtroAtendimento = somenteAtendimento == null ? "" : " and atendimento_id = :atendimento";
        String sql = "(select 'MENSAGEM' as tipo, id, criado_em as registro, coalesce(horario_externo, criado_em)"
                + " as ocorrido, atendimento_id, direcao, tipo as tipo_mensagem, tipo_externo, texto, situacao,"
                + " erro_codigo, erro_titulo, autor_usuario_id, null as evento, null as usuario_alvo_id,"
                + " null as associado_id, null as motivo, etapa_falha, atualizado_em from atd_mensagem"
                + " where conversa_id = :c" + filtroAtendimento
                + antes("criado_em", ORDEM_MENSAGEM, antes) + " order by criado_em desc, id desc limit :limite)"
                + " union all"
                + " (select 'EVENTO', id, ocorrido_em, ocorrido_em, atendimento_id, null, null, null, null, null,"
                + " null, null, autor_usuario_id, tipo, usuario_alvo_id, associado_id, motivo, null, null from atd_evento"
                + " where conversa_id = :c" + filtroAtendimento + antes("ocorrido_em", ORDEM_EVENTO, antes)
                + " order by ocorrido_em desc, id desc limit :limite)"
                + " order by registro desc, tipo desc, id desc limit :limite";
        var consulta = jdbc.sql(sql).param("c", conversa).param("limite", limite);
        if (somenteAtendimento != null) {
            consulta = consulta.param("atendimento", somenteAtendimento);
        }
        if (antes != null) {
            consulta = consulta.param("antesEm", Timestamp.from(antes.instante()))
                    .param("antesOrdem", antes.ordem())
                    .param("antesId", antes.id());
        }
        List<ItemLinhaDoTempo> itens = new ArrayList<>(consulta.query((rs, n) -> {
                    boolean mensagem = "MENSAGEM".equals(rs.getString("tipo"));
                    long id = rs.getLong("id");
                    String cursor = Cursor.de(instante(rs, "registro"), mensagem ? ORDEM_MENSAGEM : ORDEM_EVENTO, id)
                            .toString();
                    return new ItemLinhaDoTempo(
                            mensagem ? "MENSAGEM" : "EVENTO",
                            id,
                            cursor,
                            instante(rs, "ocorrido"),
                            rs.getObject("atendimento_id", Long.class),
                            rs.getString("direcao"),
                            rs.getString("tipo_mensagem"),
                            rs.getString("tipo_externo"),
                            rs.getString("texto"),
                            rs.getString("situacao"),
                            rs.getObject("erro_codigo", Integer.class),
                            rs.getString("erro_titulo"),
                            rs.getObject("autor_usuario_id", Long.class),
                            rs.getString("evento"),
                            rs.getObject("usuario_alvo_id", Long.class),
                            rs.getObject("associado_id", Long.class),
                            rs.getString("motivo"),
                            rs.getString("etapa_falha"),
                            instante(rs, "atualizado_em"),
                            null);
                })
                .list());
        comAnexos(itens);
        String anterior = null;
        if (itens.size() > tamanho) {
            itens.removeLast();
            anterior = itens.getLast().cursor();
        }
        Collections.reverse(itens);
        return new PaginaLinhaDoTempo(itens, anterior);
    }

    /** Completa as mensagens da página com o resumo do anexo (uma consulta para a página toda). */
    private void comAnexos(List<ItemLinhaDoTempo> itens) {
        List<Long> mensagens = itens.stream()
                .filter(i -> "MENSAGEM".equals(i.tipo()))
                .map(ItemLinhaDoTempo::id)
                .toList();
        if (mensagens.isEmpty()) {
            return;
        }
        Map<Long, AnexoResumo> porMensagem = new java.util.HashMap<>();
        jdbc.sql("select mensagem_id, id, categoria, mime_type, nome, tamanho, situacao, erro from atd_anexo"
                        + " where mensagem_id in (:ids)")
                .param("ids", mensagens)
                .query((rs, n) -> {
                    porMensagem.put(
                            rs.getLong("mensagem_id"),
                            new AnexoResumo(
                                    rs.getLong("id"),
                                    rs.getString("categoria"),
                                    rs.getString("mime_type"),
                                    rs.getString("nome"),
                                    rs.getObject("tamanho", Long.class),
                                    rs.getString("situacao"),
                                    rs.getString("erro")));
                    return null;
                })
                .list();
        itens.replaceAll(i -> "MENSAGEM".equals(i.tipo()) && porMensagem.containsKey(i.id())
                ? i.comAnexo(porMensagem.get(i.id()))
                : i);
    }

    /** Máximo de mensagens por consulta de situações. */
    public static final int MAXIMO_SITUACOES = 100;

    /** Situação atual de uma mensagem (e do anexo), para atualizar itens já carregados fora da página recente. */
    public record SituacaoMensagem(
            long id,
            String situacao,
            Integer erroCodigo,
            String erroTitulo,
            String etapaFalha,
            Instant atualizadoEm,
            AnexoResumo anexo) {}

    /**
     * Situações atuais das mensagens informadas, restritas à conversa (ou ao ciclo). Ids de outra conversa
     * são ignorados. Uma consulta para as mensagens e outra para os anexos, no máximo
     * {@link #MAXIMO_SITUACOES} ids.
     */
    @Transactional(readOnly = true)
    public List<SituacaoMensagem> situacoes(long conversa, Long somenteAtendimento, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        var consulta = jdbc.sql("select id, criado_em, situacao, erro_codigo, erro_titulo, etapa_falha, atualizado_em"
                        + " from atd_mensagem where conversa_id = :c and id in (:ids)"
                        + (somenteAtendimento == null ? "" : " and atendimento_id = :a"))
                .param("c", conversa)
                .param("ids", ids);
        if (somenteAtendimento != null) {
            consulta = consulta.param("a", somenteAtendimento);
        }
        List<ItemLinhaDoTempo> itens = new ArrayList<>(consulta.query((rs, n) -> new ItemLinhaDoTempo(
                        "MENSAGEM",
                        rs.getLong("id"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        rs.getString("situacao"),
                        rs.getObject("erro_codigo", Integer.class),
                        rs.getString("erro_titulo"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        rs.getString("etapa_falha"),
                        instante(rs, "atualizado_em"),
                        null))
                .list());
        comAnexos(itens);
        return itens.stream()
                .map(i -> new SituacaoMensagem(
                        i.id(),
                        i.situacao(),
                        i.erroCodigo(),
                        i.erroTitulo(),
                        i.etapaFalha(),
                        i.atualizadoEm(),
                        i.anexo()))
                .toList();
    }

    /** Item da área Mídia: anexo com a mensagem a que pertence. */
    public record ItemMidia(
            long id,
            long mensagemId,
            long atendimentoId,
            String direcao,
            String categoria,
            String mime,
            String nome,
            Long tamanho,
            String situacao,
            Instant registradoEm,
            String texto) {}

    public record PaginaMidia(List<ItemMidia> itens, Long anterior) {}

    /**
     * Anexos da conversa (ou de um ciclo), do mais recente para o mais antigo, por cursor de id. Só anexos
     * já vinculados a mensagem (uploads em andamento não aparecem). Filtro opcional por categoria.
     */
    @Transactional(readOnly = true)
    public PaginaMidia midia(long conversa, Long somenteAtendimento, String categoria, Long antes, int tamanho) {
        StringBuilder sql = new StringBuilder(
                "select x.id, x.mensagem_id, x.atendimento_id, x.direcao, x.categoria, x.mime_type, x.nome, x.tamanho,"
                        + " x.situacao, m.criado_em, m.texto from atd_anexo x join atd_mensagem m on m.id = x.mensagem_id"
                        + " where x.conversa_id = :c");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("c", conversa);
        if (somenteAtendimento != null) {
            sql.append(" and x.atendimento_id = :a");
            params.put("a", somenteAtendimento);
        }
        if (categoria != null) {
            sql.append(" and x.categoria = :categoria");
            params.put("categoria", categoria);
        }
        if (antes != null) {
            sql.append(" and x.id < :antes");
            params.put("antes", antes);
        }
        sql.append(" order by x.id desc limit :limite");
        params.put("limite", tamanho + 1);
        var consulta = jdbc.sql(sql.toString());
        for (var p : params.entrySet()) {
            consulta = consulta.param(p.getKey(), p.getValue());
        }
        List<ItemMidia> itens = new ArrayList<>(consulta.query((rs, n) -> new ItemMidia(
                        rs.getLong("id"),
                        rs.getLong("mensagem_id"),
                        rs.getLong("atendimento_id"),
                        rs.getString("direcao"),
                        rs.getString("categoria"),
                        rs.getString("mime_type"),
                        rs.getString("nome"),
                        rs.getObject("tamanho", Long.class),
                        rs.getString("situacao"),
                        instante(rs, "criado_em"),
                        rs.getString("texto")))
                .list());
        Long anterior = null;
        if (itens.size() > tamanho) {
            itens.removeLast();
            anterior = itens.getLast().id();
        }
        return new PaginaMidia(itens, anterior);
    }

    /** Registros estritamente anteriores ao cursor, na ordem (registro, ordem do tipo, id). */
    private static String antes(String coluna, int ordem, Cursor antes) {
        if (antes == null) {
            return "";
        }
        return " and (" + coluna + " < :antesEm or (" + coluna + " = :antesEm and (" + ordem + " < :antesOrdem or ("
                + ordem + " = :antesOrdem and id < :antesId))))";
    }

    @Transactional(readOnly = true)
    public boolean disponivel(long usuario) {
        return jdbc.sql("select situacao from atd_presenca where usuario_id = :u")
                .param("u", usuario)
                .query(String.class)
                .optional()
                .map("DISPONIVEL"::equals)
                .orElse(false);
    }

    public Instant agora() {
        return clock.instant();
    }

    private static Resumo resumo(ResultSet rs, int n) throws SQLException {
        return new Resumo(
                rs.getLong("id"),
                rs.getString("situacao"),
                rs.getLong("conversa_id"),
                rs.getString("wa_id"),
                rs.getString("nome_perfil"),
                rs.getObject("associado_id", Long.class),
                rs.getObject("responsavel_id", Long.class),
                instante(rs, "iniciado_em"),
                instante(rs, "ultima_mensagem_em"),
                instante(rs, "encerrado_em"),
                rs.getObject("encerrado_por", Long.class));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        Timestamp ts = rs.getTimestamp(coluna);
        return ts == null ? null : ts.toInstant();
    }
}
