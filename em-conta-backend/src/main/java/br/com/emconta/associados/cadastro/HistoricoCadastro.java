package br.com.emconta.associados.cadastro;

import br.com.emconta.associados.cadastro.AcaoHistorico.CategoriaHistorico;
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
 * Histórico de alterações do associado, de seus contatos e UCs. Somente inclusão: não há edição nem
 * exclusão de registros. Cada registro guarda autor, instante UTC, ação e os campos alterados com os
 * valores já mascarados quando são dados pessoais (documento, e-mail, telefone, endereço), nunca o
 * corpo da requisição.
 */
@Component
public class HistoricoCadastro {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    HistoricoCadastro(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** Campo alterado. {@code antes}/{@code depois} nulos significam vazio ou valor não exibido. */
    public record Alteracao(String campo, String antes, String depois) {}

    public record Registro(
            long id,
            Instant ocorridoEm,
            AcaoHistorico acao,
            Long ucId,
            Long contatoId,
            String referencia,
            long autorId,
            List<Alteracao> alteracoes) {}

    /** Acumula as diferenças de uma operação. */
    static final class Diferencas {

        private final List<Alteracao> itens = new ArrayList<>();

        Diferencas comparar(String campo, String antes, String depois) {
            if (!Objects.equals(antes, depois)) {
                itens.add(new Alteracao(campo, antes, depois));
            }
            return this;
        }

        /** Registra que o campo mudou sem guardar os valores (ex.: endereço). */
        Diferencas compararSemValores(String campo, String antes, String depois) {
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

    void registrar(
            long associadoId,
            Long ucId,
            Long contatoId,
            String referencia,
            AcaoHistorico acao,
            long autor,
            Diferencas diferencas) {
        jdbc.sql("insert into historico_cadastro"
                        + " (associado_id, uc_id, contato_id, ocorrido_em, autor_usuario_id, acao, categoria,"
                        + " referencia, alteracoes)"
                        + " values (:associado, :uc, :contato, :agora, :autor, :acao, :categoria, :referencia,"
                        + " :alteracoes)")
                .param("associado", associadoId)
                .param("uc", ucId)
                .param("contato", contatoId)
                .param("agora", Timestamp.from(clock.instant()))
                .param("autor", autor)
                .param("acao", acao.name())
                .param("categoria", acao.categoria().name())
                .param("referencia", referencia)
                .param("alteracoes", json.writeValueAsString(diferencas.itens()))
                .update();
    }

    /** Página do histórico, do mais recente para o mais antigo. */
    public Page<Registro> consultar(long associadoId, CategoriaHistorico categoria, int pagina, int tamanho) {
        String filtro = categoria == null ? "" : " and categoria = :categoria";
        var contagem = jdbc.sql("select count(*) from historico_cadastro where associado_id = :associado" + filtro)
                .param("associado", associadoId);
        var consulta = jdbc.sql("select id, ocorrido_em, acao, uc_id, contato_id, referencia, autor_usuario_id,"
                        + " alteracoes from historico_cadastro where associado_id = :associado" + filtro
                        + " order by ocorrido_em desc, id desc limit :limite offset :deslocamento")
                .param("associado", associadoId)
                .param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho);
        if (categoria != null) {
            contagem = contagem.param("categoria", categoria.name());
            consulta = consulta.param("categoria", categoria.name());
        }
        long total = contagem.query(Long.class).single();
        List<Registro> registros = consulta.query((rs, linha) -> new Registro(
                        rs.getLong("id"),
                        rs.getTimestamp("ocorrido_em").toInstant(),
                        AcaoHistorico.valueOf(rs.getString("acao")),
                        (Long) rs.getObject("uc_id", Long.class),
                        (Long) rs.getObject("contato_id", Long.class),
                        rs.getString("referencia"),
                        rs.getLong("autor_usuario_id"),
                        Arrays.asList(json.readValue(rs.getString("alteracoes"), Alteracao[].class))))
                .list();
        return new PageImpl<>(registros, PageRequest.of(pagina, tamanho), total);
    }
}
