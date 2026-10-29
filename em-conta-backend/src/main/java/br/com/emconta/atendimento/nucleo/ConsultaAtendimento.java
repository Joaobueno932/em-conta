package br.com.emconta.atendimento.nucleo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    ConsultaAtendimento(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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

    private void adicionarBusca(String busca, List<String> condicoes, Map<String, Object> params) {
        String termo = busca == null ? "" : busca.strip();
        if (termo.isEmpty()) {
            return;
        }
        String escapado = "%" + termo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String digitos = termo.replaceAll("\\D", "");
        if (digitos.length() >= 4) {
            condicoes.add("(c.nome_perfil like :busca escape '!' or c.wa_id like :digitos)");
            params.put("digitos", "%" + digitos + "%");
        } else {
            condicoes.add("c.nome_perfil like :busca escape '!'");
        }
        params.put("busca", escapado);
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

    /** Item da linha do tempo: mensagem ou evento, em ordem cronológica. */
    public record ItemLinhaDoTempo(
            String tipo,
            long id,
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
            String motivo) {}

    /**
     * Linha do tempo da conversa (todos os ciclos) ou só de um atendimento. Limitada às últimas
     * {@code limite} mensagens e aos eventos do mesmo período.
     */
    @Transactional(readOnly = true)
    public List<ItemLinhaDoTempo> linhaDoTempo(long conversa, Long somenteAtendimento, int limite) {
        String filtroAtendimento = somenteAtendimento == null ? "" : " and atendimento_id = :atendimento";
        var mensagens = jdbc.sql(
                        "select id, criado_em, horario_externo, atendimento_id, direcao, tipo, tipo_externo, texto,"
                                + " situacao, erro_codigo, erro_titulo, autor_usuario_id from atd_mensagem where conversa_id = :c"
                                + filtroAtendimento + " order by criado_em desc, id desc limit :limite")
                .param("c", conversa)
                .param("limite", limite);
        if (somenteAtendimento != null) {
            mensagens = mensagens.param("atendimento", somenteAtendimento);
        }
        List<ItemLinhaDoTempo> itens = new ArrayList<>(mensagens
                .query((rs, n) -> new ItemLinhaDoTempo(
                        "MENSAGEM",
                        rs.getLong("id"),
                        Optional.ofNullable(instante(rs, "horario_externo")).orElse(instante(rs, "criado_em")),
                        rs.getLong("atendimento_id"),
                        rs.getString("direcao"),
                        rs.getString("tipo"),
                        rs.getString("tipo_externo"),
                        rs.getString("texto"),
                        rs.getString("situacao"),
                        rs.getObject("erro_codigo", Integer.class),
                        rs.getString("erro_titulo"),
                        rs.getObject("autor_usuario_id", Long.class),
                        null,
                        null,
                        null,
                        null))
                .list());
        Instant desde = itens.stream()
                .map(ItemLinhaDoTempo::ocorridoEm)
                .min(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        if (itens.size() < limite) {
            desde = Instant.EPOCH;
        }
        var eventos = jdbc.sql(
                        "select id, ocorrido_em, atendimento_id, tipo, autor_usuario_id, usuario_alvo_id, associado_id,"
                                + " motivo from atd_evento where conversa_id = :c and ocorrido_em >= :desde"
                                + filtroAtendimento)
                .param("c", conversa)
                .param("desde", Timestamp.from(desde));
        if (somenteAtendimento != null) {
            eventos = eventos.param("atendimento", somenteAtendimento);
        }
        itens.addAll(eventos.query((rs, n) -> new ItemLinhaDoTempo(
                        "EVENTO",
                        rs.getLong("id"),
                        instante(rs, "ocorrido_em"),
                        rs.getObject("atendimento_id", Long.class),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        rs.getObject("autor_usuario_id", Long.class),
                        rs.getString("tipo"),
                        rs.getObject("usuario_alvo_id", Long.class),
                        rs.getObject("associado_id", Long.class),
                        rs.getString("motivo")))
                .list());
        itens.sort(Comparator.comparing(ItemLinhaDoTempo::ocorridoEm)
                .thenComparing(ItemLinhaDoTempo::tipo)
                .thenComparing(ItemLinhaDoTempo::id));
        return itens;
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
