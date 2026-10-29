package br.com.emconta.associados.cadastro;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listagem paginada de associados: uma consulta para a página (com a contagem de UCs em subconsulta,
 * sem N+1) e outra para o total. Busca por nome, razão social ou nome fantasia (trecho, sem
 * diferenciar acentos e caixa pela collation da coluna) e por CPF/CNPJ (início do documento
 * normalizado).
 */
@Component
public class ConsultaAssociados {

    public static final int TAMANHO_MAXIMO_PAGINA = 100;
    private static final int MINIMO_PARA_BUSCAR_DOCUMENTO = 3;
    private static final Pattern DOCUMENTO_PARCIAL = Pattern.compile("[0-9A-Z]+");

    private final JdbcClient jdbc;

    ConsultaAssociados(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum Ordenacao {
        NOME("a.nome asc, a.id asc"),
        CADASTRO_RECENTE("a.criado_em desc, a.id desc");

        private final String sql;

        Ordenacao(String sql) {
            this.sql = sql;
        }
    }

    public record Filtro(String busca, TipoPessoa tipo, Ordenacao ordenacao, int pagina, int tamanho) {}

    public record Resumo(
            long id,
            TipoPessoa tipo,
            String documento,
            String nome,
            String nomeFantasia,
            long quantidadeUcs,
            Instant criadoEm) {}

    @Transactional(readOnly = true)
    public Page<Resumo> listar(Filtro filtro) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        String termo = Textos.limpar(filtro.busca());
        String documento = termo == null ? "" : Documentos.normalizar(termo);
        boolean buscaDocumento = documento.length() >= MINIMO_PARA_BUSCAR_DOCUMENTO
                && DOCUMENTO_PARCIAL.matcher(documento).matches();
        if (filtro.tipo() != null) {
            where.append(" and a.tipo = :tipo");
        }
        if (termo != null) {
            where.append(" and (a.nome like :trecho escape '!' or a.nome_fantasia like :trecho escape '!'");
            if (buscaDocumento) {
                where.append(" or a.documento like :documento");
            }
            where.append(")");
        }
        var contagem = jdbc.sql("select count(*) from associado a" + where);
        var pagina = jdbc.sql("select a.id, a.tipo, a.documento, a.nome, a.nome_fantasia, a.criado_em,"
                + " (select count(*) from unidade_consumidora u where u.associado_id = a.id) as ucs"
                + " from associado a" + where + " order by " + filtro.ordenacao().sql
                + " limit :limite offset :deslocamento");
        if (filtro.tipo() != null) {
            contagem = contagem.param("tipo", filtro.tipo().name());
            pagina = pagina.param("tipo", filtro.tipo().name());
        }
        if (termo != null) {
            String trecho = "%" + escaparLike(termo) + "%";
            contagem = contagem.param("trecho", trecho);
            pagina = pagina.param("trecho", trecho);
            if (buscaDocumento) {
                contagem = contagem.param("documento", documento + "%");
                pagina = pagina.param("documento", documento + "%");
            }
        }
        long total = contagem.query(Long.class).single();
        List<Resumo> itens = pagina.param("limite", filtro.tamanho())
                .param("deslocamento", (long) filtro.pagina() * filtro.tamanho())
                .query((rs, linha) -> new Resumo(
                        rs.getLong("id"),
                        TipoPessoa.valueOf(rs.getString("tipo")),
                        rs.getString("documento"),
                        rs.getString("nome"),
                        rs.getString("nome_fantasia"),
                        rs.getLong("ucs"),
                        rs.getTimestamp("criado_em").toInstant()))
                .list();
        return new PageImpl<>(itens, PageRequest.of(filtro.pagina(), filtro.tamanho()), total);
    }

    private static String escaparLike(String valor) {
        return valor.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
