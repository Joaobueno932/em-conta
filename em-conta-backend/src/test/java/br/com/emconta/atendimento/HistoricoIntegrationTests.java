package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.atendimento.GraphApiControlada.Download;
import br.com.emconta.atendimento.GraphApiControlada.Midia;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Histórico geral e consulta de atendimentos encerrados: busca por nome, telefone e protocolo, período no
 * fuso do negócio, paginação e lista vazia; cada protocolo abre só o seu ciclo; exportação da conversa;
 * permissões exigidas no servidor também em acesso direto (detalhe, linha do tempo, anexos e exportação).
 */
class HistoricoIntegrationTests extends AtendimentoIntegracao {

    private static final ZoneId FUSO = ZoneId.of("America/Campo_Grande");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 'h', 'i', 's', 't'};

    @BeforeEach
    void esvaziar() {
        processar();
        GRAPH.limpar();
    }

    @Test
    void buscaFiltrosCombinadosPeriodoNoFusoDoNegocioPaginacaoEListaVazia() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        String numeroA = numeroUnico();
        String numeroB = numeroUnico();
        long a1 = ciclo(admin, numeroA, "Hist " + marca + " Ana", "Primeiro ciclo " + marca);
        long a2 = ciclo(admin, numeroA, "Hist " + marca + " Ana", "Segundo ciclo " + marca);
        long b1 = ciclo(admin, numeroB, "Hist " + marca + " Bruno", "Outro contato " + marca);

        // Nome: os três ciclos, do encerramento mais recente para o mais antigo.
        JsonNode todos = historico(admin, "busca=Hist " + marca);
        assertThat(ids(todos)).containsExactly(b1, a2, a1);
        assertThat(todos.get("totalItens").asLong()).isEqualTo(3);
        JsonNode item = todos.get("itens").get(0);
        assertThat(item.get("codigo").asString()).isEqualTo("ATD-%06d".formatted(b1));
        assertThat(item.get("responsavel").get("nome").asString()).startsWith("Pessoa ");
        assertThat(item.get("encerradoPor").get("nome").asString()).startsWith("Pessoa ");

        // Paginação: páginas sem repetição, total e quantidade de páginas.
        JsonNode p0 = historico(admin, "busca=Hist " + marca + "&tamanho=2&pagina=0");
        JsonNode p1 = historico(admin, "busca=Hist " + marca + "&tamanho=2&pagina=1");
        assertThat(ids(p0)).containsExactly(b1, a2);
        assertThat(ids(p1)).containsExactly(a1);
        assertThat(p1.get("totalItens").asLong()).isEqualTo(3);
        assertThat(p1.get("totalPaginas").asLong()).isEqualTo(2);

        // Telefone, como o atendente digita (com máscara): os dois ciclos do mesmo contato.
        String digitado = "(" + numeroA.substring(2, 4) + ") " + numeroA.substring(4, 9) + "-" + numeroA.substring(9);
        assertThat(ids(historico(admin, "busca=" + digitado))).containsExactly(a2, a1);

        // Protocolo, nas formas aceitas; com o prefixo os dígitos não viram trecho de telefone.
        assertThat(ids(historico(admin, "busca=ATD-%06d".formatted(a1)))).containsExactly(a1);
        assertThat(ids(historico(admin, "busca=atd" + a1))).containsExactly(a1);
        assertThat(ids(historico(admin, "busca=" + a1))).contains(a1);
        assertThat(historico(admin, "busca=ATD-999999999").get("totalItens").asLong())
                .isZero();

        // Período em dias do negócio: 02:30 UTC de 10/03 ainda é 09/03 em Campo Grande.
        encerradoEm(a1, Instant.parse("2026-03-10T02:30:00Z"));
        assertThat(ids(historico(admin, "busca=Hist " + marca + "&de=2026-03-09&ate=2026-03-09")))
                .containsExactly(a1);
        assertThat(historico(admin, "busca=Hist " + marca + "&de=2026-03-10&ate=2026-03-10")
                        .get("totalItens")
                        .asLong())
                .isZero();

        // Combinados: telefone + período de hoje deixam só o segundo ciclo do contato.
        String hoje = LocalDate.now(FUSO).toString();
        assertThat(ids(historico(admin, "busca=" + digitado + "&de=" + hoje + "&ate=" + hoje)))
                .containsExactly(a2);

        // Lista vazia: zero itens, zero páginas.
        JsonNode vazia = historico(admin, "busca=" + marca + "nada");
        assertThat(vazia.get("itens")).isEmpty();
        assertThat(vazia.get("totalItens").asLong()).isZero();
        assertThat(vazia.get("totalPaginas").asLong()).isZero();

        // Período invertido é recusado com o campo identificado.
        MockHttpServletResponse invertido = admin.get("/api/v1/atendimento/historico?de=2026-03-10&ate=2026-03-09");
        assertThat(invertido.getStatus()).isEqualTo(400);
        assertThat(codigo(invertido)).isEqualTo("DADOS_INVALIDOS");
    }

    @Test
    void cadaProtocoloAbreSoOSeuCicloNoDetalheNaLinhaDoTempoNosAnexosENaExportacao() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        String numero = numeroUnico();
        long primeiro = ciclo(admin, numero, "Ciclos " + marca, "Pedido do primeiro ciclo " + marca);
        long segundo = novoAtendimento(numero);
        String idMidia = "midia" + UUID.randomUUID().toString().replace("-", "");
        GRAPH.midia(idMidia, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        receber(numero, "image", idMidia, "image/png", null, "Foto do segundo ciclo");
        midias.processarPendentes(10);
        assertThat(admin.post(url(segundo, "assuncao"), null).getStatus()).isEqualTo(204);
        encerrar(admin, segundo);

        assertThat(textos(admin, primeiro))
                .anyMatch(t -> t.contains("Pedido do primeiro ciclo"))
                .noneMatch(t -> t.contains("Foto do segundo ciclo"));
        assertThat(textos(admin, segundo))
                .anyMatch(t -> t.contains("Foto do segundo ciclo"))
                .noneMatch(t -> t.contains("Pedido do primeiro ciclo"));

        // Mídia e conteúdo do anexo: só no ciclo em que chegou.
        assertThat(corpo(admin.get(url(primeiro, "midia?somenteEste=true"))).get("itens"))
                .isEmpty();
        JsonNode midia =
                corpo(admin.get(url(segundo, "midia?somenteEste=true"))).get("itens");
        assertThat(midia).hasSize(1);
        long anexo = midia.get(0).get("id").asLong();
        assertThat(admin.get(url(segundo, "anexos/" + anexo + "/conteudo?somenteEste=true"))
                        .getStatus())
                .isEqualTo(200);
        assertThat(admin.get(url(primeiro, "anexos/" + anexo + "/conteudo?somenteEste=true"))
                        .getStatus())
                .as("anexo de outro ciclo pelo protocolo errado")
                .isEqualTo(404);

        assertThat(exportar(admin, primeiro))
                .contains("Protocolo: ATD-%06d".formatted(primeiro), "Pedido do primeiro ciclo")
                .doesNotContain("Foto do segundo ciclo");
        assertThat(exportar(admin, segundo))
                .contains("Protocolo: ATD-%06d".formatted(segundo), "[Imagem: ", "Foto do segundo ciclo")
                .doesNotContain("Pedido do primeiro ciclo");
    }

    @Test
    void exportacaoTrazMensagensSituacoesEventosNotasEAnexosComCabecalhosSeguros() throws Exception {
        Navegador admin = administrador();
        Navegador colega = administrador();
        JsonNode sessaoColega = corpo(colega.get("/api/v1/auth/sessao"));
        String nomeColega = sessaoColega.get("nome").asString();
        String marca = marca();
        String numero = numeroUnico();
        long atendimento = assumido(admin, numero);
        responder(admin, atendimento, "Resposta exportada " + marca);
        processar();
        assertThat(admin.post(
                                url(atendimento, "notas"),
                                Map.of(
                                        "texto",
                                        "Nota exportada " + marca + "\nsegunda linha",
                                        "chaveIdempotencia",
                                        UUID.randomUUID().toString()))
                        .getStatus())
                .isEqualTo(201);
        assertThat(admin.post(
                                url(atendimento, "transferencia"),
                                Map.of(
                                        "versao",
                                        versao(atendimento),
                                        "destinoId",
                                        sessaoColega.get("id").asLong(),
                                        "motivo",
                                        "Assunto financeiro"))
                        .getStatus())
                .isEqualTo(204);
        assertThat(admin.get(url(atendimento, "exportacao")).getStatus())
                .as("ciclo ainda aberto não é exportado")
                .isEqualTo(404);
        encerrar(colega, atendimento);
        long mensagensAntes = contar("atd_mensagem where atendimento_id = :c", atendimento);

        MockHttpServletResponse resposta = admin.get(url(atendimento, "exportacao"));
        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(resposta.getContentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(resposta.getHeader("Content-Disposition"))
                .startsWith("attachment")
                .contains("conversa-ATD-%06d.txt".formatted(atendimento));
        assertThat(resposta.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resposta.getHeader("Cache-Control")).contains("no-store");
        String texto = resposta.getContentAsString(StandardCharsets.UTF_8);
        assertThat(texto)
                .contains(
                        "Documento interno",
                        "Protocolo: ATD-%06d".formatted(atendimento),
                        "Situação: Encerrado",
                        "Contato: Contato " + numero + " (+" + numero + ")",
                        "Atendente: " + nomeColega,
                        "Encerrado por: " + nomeColega,
                        "Fuso dos horários: America/Campo_Grande",
                        "Contato " + numero + ": Olá",
                        "Novo atendimento na fila",
                        "assumiu o atendimento",
                        "Resposta exportada " + marca + " (",
                        "Nota interna de ",
                        "Nota exportada " + marca + "\n    segunda linha",
                        "transferiu o atendimento para " + nomeColega + " (motivo: Assunto financeiro)",
                        nomeColega + " encerrou o atendimento");
        // A ordem é a da conversa.
        assertThat(texto.indexOf("assumiu o atendimento")).isLessThan(texto.indexOf("Resposta exportada"));
        assertThat(texto.indexOf("Nota exportada")).isLessThan(texto.indexOf("transferiu o atendimento"));
        assertThat(texto.indexOf("transferiu o atendimento")).isLessThan(texto.indexOf("encerrou o atendimento"));
        // Exportar só lê: nada entra na fila de envio nem vai à Meta.
        processar();
        assertThat(contar("atd_mensagem where atendimento_id = :c", atendimento))
                .isEqualTo(mensagensAntes);
        assertThat(GRAPH.requisicoes()).noneMatch(r -> r.contains("Nota exportada"));

        assertThat(admin.get(url(999_999_999L, "exportacao")).getStatus()).isEqualTo(404);
    }

    @Test
    void semPermissaoDeConsultaNadaDoHistoricoAbreNemPorLinkDireto() throws Exception {
        Navegador admin = administrador();
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        String idMidia = "midia" + UUID.randomUUID().toString().replace("-", "");
        GRAPH.midia(idMidia, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        receber(numero, "image", idMidia, "image/png", null, null);
        midias.processarPendentes(10);
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(admin.post(
                                url(atendimento, "notas"),
                                Map.of(
                                        "texto",
                                        "Nota só da equipe",
                                        "chaveIdempotencia",
                                        UUID.randomUUID().toString()))
                        .getStatus())
                .isEqualTo(201);
        encerrar(admin, atendimento);
        long anexo = corpo(admin.get(url(atendimento, "midia?somenteEste=true")))
                .get("itens")
                .get(0)
                .get("id")
                .asLong();
        List<String> rotas = List.of(
                "/api/v1/atendimento/historico",
                url(atendimento, ""),
                url(atendimento, "linha-do-tempo?somenteEste=true"),
                url(atendimento, "midia?somenteEste=true"),
                url(atendimento, "anexos/" + anexo + "/conteudo?somenteEste=true"),
                url(atendimento, "exportacao"));

        Navegador anonimo = new Navegador(mvc, json);
        Navegador semPermissao = comPermissoes();
        Navegador outroModulo = comPermissoes(Permissao.ASSOCIADOS_CONSULTAR, Permissao.USUARIOS_CONSULTAR);
        Navegador leitor = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR);
        for (String rota : rotas) {
            String caminho = rota.endsWith("/") ? rota.substring(0, rota.length() - 1) : rota;
            assertThat(anonimo.get(caminho).getStatus())
                    .as("anônimo em " + caminho)
                    .isEqualTo(401);
            assertThat(semPermissao.get(caminho).getStatus())
                    .as("sem permissão em " + caminho)
                    .isEqualTo(403);
            assertThat(outroModulo.get(caminho).getStatus())
                    .as("outro módulo em " + caminho)
                    .isEqualTo(403);
            assertThat(leitor.get(caminho).getStatus())
                    .as("só consulta em " + caminho)
                    .isEqualTo(200);
        }
        // Quem só consulta lê a conversa inteira do ciclo, inclusive as notas, na tela e no arquivo.
        assertThat(textos(leitor, atendimento)).contains("Nota só da equipe");
        assertThat(exportar(leitor, atendimento)).contains("Nota interna de ", "Nota só da equipe", "[Imagem: ");
    }

    // ------------------------------------------------------------------ apoio

    /** Um ciclo completo: mensagem do contato, assumir, responder e encerrar. */
    private long ciclo(Navegador admin, String numero, String nome, String texto) throws Exception {
        String corpo = mensagem(numero, nome, wamid(), texto, agoraEpoch());
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
        recebimento.processarPendentes(50);
        long atendimento = jdbc.sql("select a.id from atd_atendimento a join atd_conversa v on v.id = a.conversa_id"
                        + " join atd_contato c on c.id = v.contato_id where c.wa_id = :n and a.situacao <> 'ENCERRADO'")
                .param("n", numero)
                .query(Long.class)
                .single();
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        responder(admin, atendimento, "Resposta a " + texto);
        encerrar(admin, atendimento);
        return atendimento;
    }

    private void encerrar(Navegador navegador, long atendimento) {
        assertThat(navegador
                        .post(url(atendimento, "encerramento"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
    }

    private void encerradoEm(long atendimento, Instant instante) {
        jdbc.sql("update atd_atendimento set encerrado_em = :e where id = :id")
                .param("e", Timestamp.from(instante))
                .param("id", atendimento)
                .update();
    }

    /** {@code consulta} sem codificar: o MockMvc codifica o endereço. */
    private JsonNode historico(Navegador navegador, String consulta) {
        MockHttpServletResponse resposta = navegador.get("/api/v1/atendimento/historico?" + consulta);
        assertThat(resposta.getStatus()).isEqualTo(200);
        return corpo(resposta);
    }

    private static List<Long> ids(JsonNode pagina) {
        List<Long> ids = new ArrayList<>();
        pagina.get("itens").forEach(i -> ids.add(i.get("id").asLong()));
        return ids;
    }

    private List<String> textos(Navegador navegador, long atendimento) {
        List<String> textos = new ArrayList<>();
        corpo(navegador.get(url(atendimento, "linha-do-tempo?somenteEste=true")))
                .get("itens")
                .forEach(i -> {
                    if (i.hasNonNull("texto")) textos.add(i.get("texto").asString());
                });
        return textos;
    }

    private String exportar(Navegador navegador, long atendimento) throws Exception {
        MockHttpServletResponse resposta = navegador.get(url(atendimento, "exportacao"));
        assertThat(resposta.getStatus()).isEqualTo(200);
        return resposta.getContentAsString(StandardCharsets.UTF_8);
    }

    private static String marca() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
