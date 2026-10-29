package br.com.emconta.usinas.cadastro;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Histórico de fazendas, usinas e vínculos (somente inclusão). Guarda autor, instante UTC, ação,
 * referência, motivo (encerramento) e campos alterados; CEP e endereço registram só que mudaram.
 */
@Component
public class HistoricoUsinas {

    public enum Acao {
        FAZENDA_CADASTRADA,
        FAZENDA_ALTERADA,
        USINA_CADASTRADA,
        USINA_ALTERADA,
        VINCULO_CRIADO,
        VINCULO_ENCERRADO
    }

    public record Alteracao(String campo, String antes, String depois) {}

    public record Registro(
            long id,
            Instant ocorridoEm,
            Acao acao,
            Long vinculoId,
            String referencia,
            String motivo,
            long autorId,
            List<Alteracao> alteracoes) {}

    static final class Diferencas {

        private final List<Alteracao> itens = new ArrayList<>();

        Diferencas comparar(String campo, Object antes, Object depois) {
            String a = antes == null ? null : antes.toString();
            String d = depois == null ? null : depois.toString();
            if (!Objects.equals(a, d)) {
                itens.add(new Alteracao(campo, a, d));
            }
            return this;
        }

        Diferencas compararSemValores(String campo, Object antes, Object depois) {
            if (!Objects.equals(antes, depois)) {
                itens.add(new Alteracao(campo, null, null));
            }
            return this;
        }

        boolean vazia() {
            return itens.isEmpty();
        }

        List<Alteracao> itens() {
            return List.copyOf(itens);
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    HistoricoUsinas(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    void registrar(
            Long fazendaId,
            Long usinaId,
            Long vinculoId,
            Acao acao,
            String referencia,
            String motivo,
            long autor,
            Diferencas diferencas) {
        jdbc.sql("insert into historico_usinas (fazenda_id, usina_id, vinculo_id, ocorrido_em, autor_usuario_id, acao,"
                        + " referencia, motivo, alteracoes) values (:fazenda, :usina, :vinculo, :agora, :autor, :acao,"
                        + " :referencia, :motivo, :alteracoes)")
                .param("fazenda", fazendaId)
                .param("usina", usinaId)
                .param("vinculo", vinculoId)
                .param("agora", Timestamp.from(clock.instant()))
                .param("autor", autor)
                .param("acao", acao.name())
                .param("referencia", referencia)
                .param("motivo", motivo)
                .param("alteracoes", json.writeValueAsString(diferencas.itens()))
                .update();
    }

    public Page<Registro> daUsina(long usinaId, int pagina, int tamanho) {
        return consultar("usina_id", usinaId, pagina, tamanho);
    }

    public Page<Registro> daFazenda(long fazendaId, int pagina, int tamanho) {
        return consultar("fazenda_id", fazendaId, pagina, tamanho);
    }

    private Page<Registro> consultar(String coluna, long id, int pagina, int tamanho) {
        long total = jdbc.sql("select count(*) from historico_usinas where " + coluna + " = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        List<Registro> itens = jdbc.sql("select id, ocorrido_em, acao, vinculo_id, referencia, motivo,"
                        + " autor_usuario_id, alteracoes from historico_usinas where " + coluna + " = :id"
                        + " order by ocorrido_em desc, id desc limit :limite offset :deslocamento")
                .param("id", id)
                .param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query((rs, linha) -> new Registro(
                        rs.getLong("id"),
                        rs.getTimestamp("ocorrido_em").toInstant(),
                        Acao.valueOf(rs.getString("acao")),
                        rs.getObject("vinculo_id", Long.class),
                        rs.getString("referencia"),
                        rs.getString("motivo"),
                        rs.getLong("autor_usuario_id"),
                        Arrays.asList(json.readValue(rs.getString("alteracoes"), Alteracao[].class))))
                .list();
        return new PageImpl<>(itens, PageRequest.of(pagina, tamanho), total);
    }
}
