package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Linha do tempo paginada por cursor: mais de 200 mensagens, eventos intercalados, dois ciclos na mesma
 * conversa, vários registros no mesmo instante e mensagens chegando durante a navegação.
 */
class LinhaDoTempoIntegrationTests extends AtendimentoIntegracao {

    private record Conversa(long primeiro, long segundo, long conversa) {}

    @Test
    void percorreTodoOHistoricoSemLacunasNemDuplicacoes() throws Exception {
        Navegador admin = administrador();
        Conversa c = conversaComHistoricoLongo(admin);
        long total = registros(c.conversa(), null);
        assertThat(total).isGreaterThan(260);

        List<JsonNode> todos = percorrer(admin, c.segundo(), false, 37, null);
        assertThat(todos).hasSize((int) total);
        assertOrdemEstritaSemRepeticao(todos);
        assertThat(todos.stream().map(i -> i.get("atendimentoId").asLong()).distinct())
                .as("todos os ciclos da conversa")
                .contains(c.primeiro(), c.segundo());

        // Só o atendimento selecionado (detalhe do histórico).
        List<JsonNode> soPrimeiro = percorrer(admin, c.primeiro(), true, 50, null);
        assertThat(soPrimeiro).hasSize((int) registros(c.conversa(), c.primeiro()));
        assertThat(soPrimeiro).allMatch(i -> i.get("atendimentoId").asLong() == c.primeiro());
        assertOrdemEstritaSemRepeticao(soPrimeiro);
    }

    @Test
    void paginaPadraoLimitadaEParametrosValidados() throws Exception {
        Navegador admin = administrador();
        Conversa c = conversaComHistoricoLongo(admin);
        JsonNode recente = corpo(admin.get(linha(c.segundo(), false, null, null)));
        assertThat(recente.get("itens")).as("não carrega tudo de uma vez").hasSize(50);
        assertThat(recente.get("anterior").asString()).isNotBlank();

        for (String invalido : List.of("?tamanho=0", "?tamanho=101", "?antes=abc", "?antes=1.2.3", "?antes=-1.0.1")) {
            MockHttpServletResponse resposta =
                    admin.get("/api/v1/atendimento/atendimentos/" + c.segundo() + "/linha-do-tempo" + invalido);
            assertThat(resposta.getStatus()).as(invalido).isEqualTo(400);
        }
        assertThat(codigo(admin.get(linha(c.segundo(), false, "abc", null)))).isEqualTo("DADOS_INVALIDOS");
    }

    @Test
    void mensagensQueChegamDuranteANavegacaoNaoDeslocamAsPaginasAnteriores() throws Exception {
        Navegador admin = administrador();
        Conversa c = conversaComHistoricoLongo(admin);
        long total = registros(c.conversa(), null);

        JsonNode primeira = corpo(admin.get(linha(c.segundo(), false, null, 40)));
        String numero = numero(c.segundo());
        for (int i = 0; i < 5; i++) {
            String corpo = mensagem(numero, "Contato", wamid(), "chegou durante a leitura " + i, agoraEpoch());
            enviarWebhook(corpo, assinar(corpo));
        }
        recebimento.processarPendentes(50);
        List<JsonNode> anteriores = percorrer(
                admin, c.segundo(), false, 40, primeira.get("anterior").asString());
        List<JsonNode> lidos = new ArrayList<>();
        primeira.get("itens").forEach(lidos::add);
        lidos.addAll(0, anteriores);
        assertThat(lidos).as("o que existia ao começar, sem lacunas").hasSize((int) total);
        assertOrdemEstritaSemRepeticao(lidos);
        assertThat(lidos.toString()).doesNotContain("chegou durante a leitura");

        // A página recente traz as novas, depois de tudo o que já foi lido.
        JsonNode recente = corpo(admin.get(linha(c.segundo(), false, null, 40)));
        List<JsonNode> novos = new ArrayList<>();
        recente.get("itens").forEach(novos::add);
        assertThat(recente.toString()).contains("chegou durante a leitura 4");
        assertThat(chave(novos.getLast())).isGreaterThan(chave(lidos.getLast()));
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Dois ciclos na mesma conversa (o primeiro encerrado) e, em cada um, mensagens e eventos gravados em
     * massa direto no banco de testes, em grupos de três registros no mesmo instante.
     */
    private Conversa conversaComHistoricoLongo(Navegador admin) throws Exception {
        String numero = numeroUnico();
        long primeiro = assumido(admin, numero);
        assertThat(admin.post(url(primeiro, "encerramento"), Map.of("versao", versao(primeiro)))
                        .getStatus())
                .isEqualTo(204);
        String retorno = mensagem(numero, "Retorno", wamid(), "voltei", agoraEpoch());
        enviarWebhook(retorno, assinar(retorno));
        recebimento.processarPendentes(50);
        long segundo = novoAtendimentoAberto(numero);
        long conversa = jdbc.sql("select conversa_id from atd_atendimento where id = :id")
                .param("id", primeiro)
                .query(Long.class)
                .single();

        Instant base = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 240; i++) {
            long ciclo = i < 120 ? primeiro : segundo;
            Instant instante = base.plusSeconds(i / 3); // três registros por instante
            jdbc.sql("insert into atd_mensagem (correlacao, conversa_id, atendimento_id, direcao, tipo, texto,"
                            + " situacao, criado_em, atualizado_em) values (:corr, :c, :a, 'ENTRADA', 'TEXTO', :t,"
                            + " 'RECEBIDA', :em, :em)")
                    .param("corr", UUID.randomUUID().toString())
                    .param("c", conversa)
                    .param("a", ciclo)
                    .param("t", "mensagem antiga " + i)
                    .param("em", Timestamp.from(instante))
                    .update();
            if (i % 8 == 0) {
                jdbc.sql("insert into atd_evento (conversa_id, atendimento_id, tipo, motivo, ocorrido_em)"
                                + " values (:c, :a, 'ASSOCIADO_VINCULADO', :m, :em)")
                        .param("c", conversa)
                        .param("a", ciclo)
                        .param("m", "evento intercalado " + i)
                        .param("em", Timestamp.from(instante))
                        .update();
            }
        }
        return new Conversa(primeiro, segundo, conversa);
    }

    private long novoAtendimentoAberto(String numero) {
        return jdbc.sql("select a.id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where c.wa_id = :n and a.situacao = 'AGUARDANDO'")
                .param("n", numero)
                .query(Long.class)
                .single();
    }

    private String numero(long atendimento) {
        return jdbc.sql("select c.wa_id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where a.id = :id")
                .param("id", atendimento)
                .query(String.class)
                .single();
    }

    private long registros(long conversa, Long atendimento) {
        String filtro = atendimento == null ? "" : " and atendimento_id = " + atendimento;
        return jdbc.sql("select (select count(*) from atd_mensagem where conversa_id = :c" + filtro
                        + ") + (select count(*) from atd_evento where conversa_id = :c" + filtro + ")")
                .param("c", conversa)
                .query(Long.class)
                .single();
    }

    private static String linha(long atendimento, boolean somenteEste, String antes, Integer tamanho) {
        StringBuilder url = new StringBuilder(
                "/api/v1/atendimento/atendimentos/" + atendimento + "/linha-do-tempo?somenteEste=" + somenteEste);
        if (antes != null) {
            url.append("&antes=").append(antes);
        }
        if (tamanho != null) {
            url.append("&tamanho=").append(tamanho);
        }
        return url.toString();
    }

    /** Lê da página mais recente (ou de {@code antes}) até o início; devolve em ordem cronológica. */
    private List<JsonNode> percorrer(
            Navegador admin, long atendimento, boolean somenteEste, int tamanho, String antes) {
        List<JsonNode> itens = new ArrayList<>();
        String cursor = antes;
        boolean primeira = true;
        for (int paginas = 0; paginas < 100; paginas++) {
            if (!primeira && cursor == null) {
                return itens;
            }
            if (primeira && antes == null) {
                cursor = null;
            }
            JsonNode pagina = corpo(admin.get(linha(atendimento, somenteEste, cursor, tamanho)));
            assertThat(pagina.get("itens").size()).isLessThanOrEqualTo(tamanho);
            List<JsonNode> desta = new ArrayList<>();
            pagina.get("itens").forEach(desta::add);
            itens.addAll(0, desta);
            JsonNode anterior = pagina.get("anterior");
            cursor = anterior == null || anterior.isNull() ? null : anterior.asString();
            if (cursor != null) {
                assertThat(cursor).isEqualTo(desta.getFirst().get("cursor").asString());
            }
            primeira = false;
        }
        throw new AssertionError("paginação não terminou");
    }

    private static void assertOrdemEstritaSemRepeticao(List<JsonNode> itens) {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < itens.size(); i++) {
            String identidade = itens.get(i).get("tipo").asString() + "-"
                    + itens.get(i).get("id").asLong();
            assertThat(vistos.add(identidade)).as("repetido: " + identidade).isTrue();
            if (i > 0) {
                assertThat(chave(itens.get(i))).isGreaterThan(chave(itens.get(i - 1)));
            }
        }
    }

    /** Cursor como chave comparável: micros, ordem do tipo e id, com largura fixa. */
    private static String chave(JsonNode item) {
        String[] partes = item.get("cursor").asString().split("\\.");
        return String.format("%020d.%s.%020d", Long.parseLong(partes[0]), partes[1], Long.parseLong(partes[2]));
    }
}
