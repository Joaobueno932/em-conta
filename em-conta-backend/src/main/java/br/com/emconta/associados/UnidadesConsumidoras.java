package br.com.emconta.associados;

import br.com.emconta.associados.cadastro.Concessionaria;
import br.com.emconta.associados.cadastro.Documentos;
import br.com.emconta.associados.cadastro.TipoPessoa;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * API pública do módulo de associados para outros módulos (hoje: usinas) consultarem UCs e seus
 * titulares sem acessar entidades ou repositórios internos. Somente leitura; o documento do titular
 * sai parcialmente mascarado.
 */
@Component
public class UnidadesConsumidoras {

    private static final int MINIMO_PARA_BUSCAR = 2;
    private static final Pattern DOCUMENTO_PARCIAL = Pattern.compile("[0-9A-Z]{3,}");
    private static final String SELECT = "select u.id, u.associado_id, a.nome, a.tipo, a.documento, u.concessionaria,"
            + " u.numero, u.denominacao from unidade_consumidora u join associado a on a.id = u.associado_id";

    private final JdbcClient jdbc;

    UnidadesConsumidoras(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** UC com dados suficientes para identificá-la sem ambiguidade (titular, concessionária, número). */
    public record ResumoUc(
            long id,
            long associadoId,
            String associadoNome,
            String associadoTipo,
            String associadoDocumentoMascarado,
            String concessionariaNome,
            String numero,
            String denominacao) {}

    @Transactional(readOnly = true)
    public Optional<ResumoUc> obter(long ucId) {
        return jdbc.sql(SELECT + " where u.id = :id")
                .param("id", ucId)
                .query(UnidadesConsumidoras::resumo)
                .optional();
    }

    /**
     * Lê a UC travando a sua linha ({@code SELECT ... FOR UPDATE}) até o fim da transação do chamador.
     * Serve para outro módulo serializar operações sobre a mesma UC (ex.: vínculos com usina) sem
     * travas de intervalo, que causam deadlock em inserções simultâneas. Exige transação ativa.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ResumoUc> travar(long ucId) {
        return jdbc.sql(SELECT + " where u.id = :id for update of u")
                .param("id", ucId)
                .query(UnidadesConsumidoras::resumo)
                .optional();
    }

    @Transactional(readOnly = true)
    public List<ResumoUc> porIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where u.id in (:ids)")
                .param("ids", ids)
                .query(UnidadesConsumidoras::resumo)
                .list();
    }

    @Transactional(readOnly = true)
    public boolean associadoExiste(long associadoId) {
        return jdbc.sql("select count(*) from associado where id = :id")
                        .param("id", associadoId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Busca por número da UC (trecho), nome ou razão social do titular (trecho) ou CPF/CNPJ (início),
     * limitada a {@code limite} resultados.
     */
    @Transactional(readOnly = true)
    public List<ResumoUc> buscar(String termo, int limite) {
        String limpo = termo == null ? "" : termo.strip();
        if (limpo.length() < MINIMO_PARA_BUSCAR) {
            return List.of();
        }
        String documento = Documentos.normalizar(limpo);
        boolean porDocumento = DOCUMENTO_PARCIAL.matcher(documento).matches();
        String trecho = "%" + limpo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.sql(SELECT + " where u.numero like :trechoMaiusculo escape '!' or a.nome like :trecho escape '!'"
                        + (porDocumento ? " or a.documento like :documento" : "")
                        + " order by a.nome, u.numero limit :limite")
                .param("trecho", trecho)
                .param("trechoMaiusculo", trecho.toUpperCase(java.util.Locale.ROOT))
                .param("documento", documento + "%")
                .param("limite", limite)
                .query(UnidadesConsumidoras::resumo)
                .list();
    }

    private static ResumoUc resumo(ResultSet rs, int linha) throws SQLException {
        TipoPessoa tipo = TipoPessoa.valueOf(rs.getString("tipo"));
        return new ResumoUc(
                rs.getLong("id"),
                rs.getLong("associado_id"),
                rs.getString("nome"),
                tipo.name(),
                Documentos.mascarar(tipo, rs.getString("documento")),
                Concessionaria.valueOf(rs.getString("concessionaria")).nome(),
                rs.getString("numero"),
                rs.getString("denominacao"));
    }
}
