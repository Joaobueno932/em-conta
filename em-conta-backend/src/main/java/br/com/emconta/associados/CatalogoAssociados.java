package br.com.emconta.associados;

import br.com.emconta.associados.cadastro.Documentos;
import br.com.emconta.associados.cadastro.TipoPessoa;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * API pública do módulo de associados para outros módulos (hoje: atendimento) localizarem associados
 * sem acessar entidades internas. Devolve só o mínimo para identificação (nome, tipo e documento
 * mascarado); contatos e demais dados não saem daqui.
 *
 * <p>Telefone só <em>sugere</em> candidatos: o mesmo número pode estar em vários cadastros e não prova
 * identidade. A escolha é sempre explícita de quem atende.
 */
@Component
public class CatalogoAssociados {

    private static final Pattern NAO_DIGITOS = Pattern.compile("\\D");
    private static final Pattern DOCUMENTO_PARCIAL = Pattern.compile("[0-9A-Z]{3,}");
    private static final String SELECT = "select a.id, a.tipo, a.documento, a.nome from associado a";

    private final JdbcClient jdbc;

    CatalogoAssociados(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ResumoAssociado(long id, String tipo, String nome, String documentoMascarado) {}

    @Transactional(readOnly = true)
    public Optional<ResumoAssociado> obter(long id) {
        return jdbc.sql(SELECT + " where a.id = :id")
                .param("id", id)
                .query(CatalogoAssociados::resumo)
                .optional();
    }

    @Transactional(readOnly = true)
    public List<ResumoAssociado> porIds(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where a.id in (:ids)")
                .param("ids", ids)
                .query(CatalogoAssociados::resumo)
                .list();
    }

    /** Busca por nome/razão social (trecho) ou CPF/CNPJ (início), limitada a {@code limite}. */
    @Transactional(readOnly = true)
    public List<ResumoAssociado> buscar(String termo, int limite) {
        String limpo = termo == null ? "" : termo.strip();
        if (limpo.length() < 2) {
            return List.of();
        }
        String documento = Documentos.normalizar(limpo);
        boolean porDocumento = DOCUMENTO_PARCIAL.matcher(documento).matches();
        String trecho = "%" + limpo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.sql(SELECT + " where a.nome like :trecho escape '!'"
                        + (porDocumento ? " or a.documento like :documento" : "")
                        + " order by a.nome, a.id limit :limite")
                .param("trecho", trecho)
                .param("documento", documento.toUpperCase(Locale.ROOT) + "%")
                .param("limite", limite)
                .query(CatalogoAssociados::resumo)
                .list();
    }

    /**
     * Critério de consulta (projeção só de ids) para outro módulo filtrar pelo nome do associado sem
     * conhecer a tabela: {@code subconsulta} é um SELECT de ids, a ser usado em {@code coluna in (...)}, e
     * {@code parametros} são os seus valores nomeados. O banco resolve a semijunção, então não há corte de
     * resultados nem carga de associados em memória. Só ids saem daqui (nunca documento nem contatos).
     */
    public record CriterioIds(String subconsulta, Map<String, Object> parametros) {}

    /**
     * Associados cujo nome (PF) ou razão social (PJ), ambos no campo {@code nome}, contém o trecho (mínimo
     * de 2 caracteres). O parâmetro nomeado é {@code associadoNome}; quem usa não deve repetir esse nome.
     */
    public Optional<CriterioIds> criterioPorNome(String termo) {
        String limpo = termo == null ? "" : termo.strip();
        if (limpo.length() < 2) {
            return Optional.empty();
        }
        String trecho = "%" + limpo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return Optional.of(new CriterioIds(
                "select a.id from associado a where a.nome like :associadoNome escape '!'",
                Map.of("associadoNome", trecho)));
    }

    /**
     * Associados com algum contato ativo cujo telefone coincide com o número informado (com ou sem o
     * DDI 55). Pode devolver vários; nunca escolhe por quem atende.
     */
    @Transactional(readOnly = true)
    public List<ResumoAssociado> sugeridosPorTelefone(String telefone, int limite) {
        String digitos = telefone == null ? "" : NAO_DIGITOS.matcher(telefone).replaceAll("");
        if (digitos.length() < 10) {
            return List.of();
        }
        String semDdi = digitos.startsWith("55") && digitos.length() >= 12 ? digitos.substring(2) : digitos;
        return jdbc.sql(SELECT + " where exists (select 1 from associado_contato c where c.associado_id = a.id"
                        + " and c.removido_em is null and (c.telefone = :completo or c.telefone = :semDdi))"
                        + " order by a.nome, a.id limit :limite")
                .param("completo", digitos)
                .param("semDdi", semDdi)
                .param("limite", limite)
                .query(CatalogoAssociados::resumo)
                .list();
    }

    private static ResumoAssociado resumo(ResultSet rs, int linha) throws SQLException {
        TipoPessoa tipo = TipoPessoa.valueOf(rs.getString("tipo"));
        return new ResumoAssociado(
                rs.getLong("id"),
                tipo.name(),
                rs.getString("nome"),
                Documentos.mascarar(tipo, rs.getString("documento")));
    }
}
