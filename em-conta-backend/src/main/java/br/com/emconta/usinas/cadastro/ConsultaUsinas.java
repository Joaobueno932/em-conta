package br.com.emconta.usinas.cadastro;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listagens paginadas de usinas, fazendas e vínculos. Uma consulta por página (contagens em
 * subconsulta, sem N+1) e outra para o total. Busca por nome (trecho, sem diferenciar acentos e caixa)
 * ou pelo código gerado ({@code USN-0001}, {@code FZD-0001}). As contagens vêm só das tabelas deste
 * módulo; nenhuma métrica energética é calculada.
 */
@Component
public class ConsultaUsinas {

    public static final int TAMANHO_MAXIMO_PAGINA = 100;
    private static final Pattern CODIGO_USINA = Pattern.compile("USN-?0*(\\d{1,18})");
    private static final Pattern CODIGO_FAZENDA = Pattern.compile("FZD-?0*(\\d{1,18})");

    private final JdbcClient jdbc;

    ConsultaUsinas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum Ordenacao {
        NOME("u.nome asc, u.id asc"),
        CADASTRO_RECENTE("u.criado_em desc, u.id desc"),
        UCS_VINCULADAS("ucs desc, u.nome asc, u.id asc");

        private final String sql;

        Ordenacao(String sql) {
            this.sql = sql;
        }
    }

    public enum SituacaoVinculo {
        VIGENTES,
        ENCERRADOS,
        TODOS
    }

    public record FiltroUsinas(String busca, Long fazendaId, String uf, Ordenacao ordenacao, int pagina, int tamanho) {}

    public record ResumoUsina(
            long id,
            String nome,
            Long fazendaId,
            String fazendaNome,
            String cidade,
            String uf,
            long ucsVinculadas,
            long associadosDistintos,
            Instant criadoEm) {}

    public record ResumoFazenda(long id, String nome, String cidade, String uf, long usinas) {}

    public record LinhaVinculo(
            long id,
            long ucId,
            long associadoId,
            LocalDate inicio,
            LocalDate fim,
            String motivoEncerramento,
            Instant criadoEm,
            long criadoPor,
            Instant encerradoEm,
            Long encerradoPor,
            long versao) {}

    @Transactional(readOnly = true)
    public Page<ResumoUsina> usinas(FiltroUsinas filtro) {
        List<String> condicoes = new ArrayList<>();
        List<Object[]> params = new ArrayList<>();
        String termo = Campos.limpar(filtro.busca());
        if (termo != null) {
            Matcher codigo = CODIGO_USINA.matcher(termo.toUpperCase(Locale.ROOT));
            if (codigo.matches()) {
                condicoes.add("u.id = :codigo");
                params.add(new Object[] {"codigo", Long.parseLong(codigo.group(1))});
            } else {
                condicoes.add("(u.nome like :trecho escape '!' or f.nome like :trecho escape '!')");
                params.add(new Object[] {"trecho", "%" + escaparLike(termo) + "%"});
            }
        }
        if (filtro.fazendaId() != null) {
            condicoes.add("u.fazenda_id = :fazenda");
            params.add(new Object[] {"fazenda", filtro.fazendaId()});
        }
        if (filtro.uf() != null) {
            condicoes.add("u.uf = :uf");
            params.add(new Object[] {"uf", Campos.uf(filtro.uf())});
        }
        String where = condicoes.isEmpty() ? "" : " where " + String.join(" and ", condicoes);
        String from = " from usina u left join fazenda f on f.id = u.fazenda_id";

        var contagem = jdbc.sql("select count(*)" + from + where);
        var pagina = jdbc.sql("select u.id, u.nome, u.fazenda_id, f.nome as fazenda_nome, u.cidade, u.uf, u.criado_em,"
                + " (select count(*) from vinculo_uc_usina v where v.usina_id = u.id and v.fim is null) as ucs,"
                + " (select count(distinct v.associado_id) from vinculo_uc_usina v"
                + " where v.usina_id = u.id and v.fim is null) as associados"
                + from + where + " order by " + filtro.ordenacao().sql + " limit :limite offset :deslocamento");
        for (Object[] p : params) {
            contagem = contagem.param((String) p[0], p[1]);
            pagina = pagina.param((String) p[0], p[1]);
        }
        long total = contagem.query(Long.class).single();
        List<ResumoUsina> itens = pagina.param("limite", filtro.tamanho())
                .param("deslocamento", (long) filtro.pagina() * filtro.tamanho())
                .query((rs, linha) -> new ResumoUsina(
                        rs.getLong("id"),
                        rs.getString("nome"),
                        rs.getObject("fazenda_id", Long.class),
                        rs.getString("fazenda_nome"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getLong("ucs"),
                        rs.getLong("associados"),
                        rs.getTimestamp("criado_em").toInstant()))
                .list();
        return new PageImpl<>(itens, PageRequest.of(filtro.pagina(), filtro.tamanho()), total);
    }

    @Transactional(readOnly = true)
    public Page<ResumoFazenda> fazendas(String busca, int pagina, int tamanho) {
        String termo = Campos.limpar(busca);
        String where = "";
        Object valor = null;
        String nome = null;
        if (termo != null) {
            Matcher codigo = CODIGO_FAZENDA.matcher(termo.toUpperCase(Locale.ROOT));
            if (codigo.matches()) {
                where = " where f.id = :valor";
                valor = Long.parseLong(codigo.group(1));
            } else {
                where = " where f.nome like :valor escape '!'";
                valor = "%" + escaparLike(termo) + "%";
            }
            nome = "valor";
        }
        var contagem = jdbc.sql("select count(*) from fazenda f" + where);
        var consulta = jdbc.sql("select f.id, f.nome, f.cidade, f.uf,"
                + " (select count(*) from usina u where u.fazenda_id = f.id) as usinas from fazenda f" + where
                + " order by f.nome, f.id limit :limite offset :deslocamento");
        if (nome != null) {
            contagem = contagem.param(nome, valor);
            consulta = consulta.param(nome, valor);
        }
        long total = contagem.query(Long.class).single();
        List<ResumoFazenda> itens = consulta.param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query((rs, linha) -> new ResumoFazenda(
                        rs.getLong("id"),
                        rs.getString("nome"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getLong("usinas")))
                .list();
        return new PageImpl<>(itens, PageRequest.of(pagina, tamanho), total);
    }

    /** Vínculos da usina, vigentes primeiro e depois os mais recentes. */
    @Transactional(readOnly = true)
    public Page<LinhaVinculo> vinculos(long usinaId, SituacaoVinculo situacao, int pagina, int tamanho) {
        String filtro = switch (situacao) {
            case VIGENTES -> " and fim is null";
            case ENCERRADOS -> " and fim is not null";
            case TODOS -> "";
        };
        long total = jdbc.sql("select count(*) from vinculo_uc_usina where usina_id = :usina" + filtro)
                .param("usina", usinaId)
                .query(Long.class)
                .single();
        List<LinhaVinculo> itens = jdbc.sql(
                        "select id, uc_id, associado_id, inicio, fim, motivo_encerramento, criado_em,"
                                + " criado_por, encerrado_em, encerrado_por, versao from vinculo_uc_usina where usina_id = :usina"
                                + filtro
                                + " order by fim is not null, inicio desc, id desc limit :limite offset :deslocamento")
                .param("usina", usinaId)
                .param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query((rs, linha) -> new LinhaVinculo(
                        rs.getLong("id"),
                        rs.getLong("uc_id"),
                        rs.getLong("associado_id"),
                        rs.getObject("inicio", LocalDate.class),
                        rs.getObject("fim", LocalDate.class),
                        rs.getString("motivo_encerramento"),
                        rs.getTimestamp("criado_em").toInstant(),
                        rs.getLong("criado_por"),
                        rs.getTimestamp("encerrado_em") == null
                                ? null
                                : rs.getTimestamp("encerrado_em").toInstant(),
                        rs.getObject("encerrado_por", Long.class),
                        rs.getLong("versao")))
                .list();
        return new PageImpl<>(itens, PageRequest.of(pagina, tamanho), total);
    }

    public record Contagens(long ucsVinculadas, long associadosDistintos, long vinculosEncerrados) {}

    /** Indicadores da visão geral, calculados só dos vínculos cadastrais registrados. */
    @Transactional(readOnly = true)
    public Contagens contagens(long usinaId) {
        return jdbc.sql(
                        "select coalesce(sum(fim is null), 0) as ucs,"
                                + " count(distinct case when fim is null then associado_id end) as associados,"
                                + " coalesce(sum(fim is not null), 0) as encerrados from vinculo_uc_usina where usina_id = :usina")
                .param("usina", usinaId)
                .query((rs, linha) ->
                        new Contagens(rs.getLong("ucs"), rs.getLong("associados"), rs.getLong("encerrados")))
                .single();
    }

    public record ReferenciaUsina(long id, String nome) {}

    /** Nome das usinas por id, para listas de vínculos. */
    @Transactional(readOnly = true)
    public Map<Long, ReferenciaUsina> referencias(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ReferenciaUsina> mapa = new HashMap<>();
        jdbc.sql("select id, nome from usina where id in (:ids)")
                .param("ids", ids)
                .query((rs, linha) -> new ReferenciaUsina(rs.getLong("id"), rs.getString("nome")))
                .list()
                .forEach(r -> mapa.put(r.id(), r));
        return mapa;
    }

    private static String escaparLike(String valor) {
        return valor.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
