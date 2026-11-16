package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Notas internas: texto, autor e horário na linha do tempo e no histórico para quem tem acesso; nunca na
 * fila de envio nem no WhatsApp; atendimento encerrado é somente leitura.
 */
class NotasIntegrationTests extends AtendimentoIntegracao {

    @BeforeEach
    void esvaziar() {
        processar();
        GRAPH.limpar();
    }

    @Test
    void notaFicaNaLinhaDoTempoENoHistoricoENuncaVaiAoWhatsApp() throws Exception {
        Navegador responsavel = administrador();
        long atendimento = assumido(responsavel, numeroUnico());
        long mensagens = contar("atd_mensagem where atendimento_id = :c", atendimento);

        long nota = nota(
                responsavel,
                atendimento,
                "Cliente pediu retorno amanhã cedo.",
                UUID.randomUUID().toString());
        processar();

        assertThat(GRAPH.requisicoes()).as("nada vai à Meta").noneMatch(r -> r.contains("retorno amanh"));
        assertThat(contar("atd_mensagem where atendimento_id = :c", atendimento))
                .as("não entra na fila de envio")
                .isEqualTo(mensagens);
        JsonNode item = itemNota(responsavel, atendimento, "linha-do-tempo", nota);
        assertThat(item.get("texto").asString()).isEqualTo("Cliente pediu retorno amanhã cedo.");
        assertThat(item.get("autor").get("nome").asString()).startsWith("Pessoa ");
        assertThat(item.has("ocorridoEm")).isTrue();
        assertThat(item.get("cursor").asString()).matches("\\d+\\.2\\.\\d+");

        // Quem só consulta lê a nota, inclusive no histórico do ciclo, e pagina a partir do cursor dela.
        Navegador leitor = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR);
        assertThat(itemNota(leitor, atendimento, "linha-do-tempo?somenteEste=true", nota))
                .isNotNull();
        assertThat(leitor.get(url(
                                atendimento,
                                "linha-do-tempo?antes=" + item.get("cursor").asString()))
                        .getStatus())
                .isEqualTo(200);
        assertThat(codigoNota(leitor, atendimento, "não pode")).isEqualTo(403);
    }

    @Test
    void regrasDeRegistroIdempotenciaEAtendimentoEncerrado() throws Exception {
        Navegador responsavel = administrador();
        long atendimento = assumido(responsavel, numeroUnico());
        String chave = UUID.randomUUID().toString();
        long primeira = nota(responsavel, atendimento, "  Ligar após as 14h  ", chave);
        assertThat(nota(responsavel, atendimento, "Ligar após as 14h", chave))
                .as("repetição exata")
                .isEqualTo(primeira);
        MockHttpServletResponse outraOperacao =
                responsavel.post(url(atendimento, "notas"), Map.of("texto", "Outro texto", "chaveIdempotencia", chave));
        assertThat(codigo(outraOperacao)).isEqualTo("CHAVE_IDEMPOTENCIA_REUTILIZADA");
        assertThat(responsavel
                        .post(url(atendimento, "notas"), Map.of("texto", "   ", "chaveIdempotencia", "k" + chave))
                        .getStatus())
                .isEqualTo(400);

        // Outro atendente (com permissão de responder) lê, mas só o responsável atual registra.
        Navegador colega = administrador();
        MockHttpServletResponse doColega = colega.post(
                url(atendimento, "notas"),
                Map.of(
                        "texto",
                        "Conferi o cadastro.",
                        "chaveIdempotencia",
                        UUID.randomUUID().toString()));
        assertThat(codigo(doColega)).isEqualTo("NAO_E_RESPONSAVEL");
        assertThat(contar("atd_nota where atendimento_id = :c", atendimento)).isEqualTo(1);
        long aguardando = novoAtendimento(numeroUnico());
        assertThat(codigo(responsavel.post(
                        url(aguardando, "notas"),
                        Map.of(
                                "texto",
                                "Sem responsável",
                                "chaveIdempotencia",
                                UUID.randomUUID().toString()))))
                .as("atendimento na fila, sem responsável")
                .isEqualTo("NAO_E_RESPONSAVEL");

        // A janela de 24 h não se aplica: nada vai ao cliente.
        jdbc.sql("update atd_conversa set ultima_entrada_em = :h"
                        + " where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("h", java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofHours(30))))
                .param("id", atendimento)
                .update();
        nota(
                responsavel,
                atendimento,
                "Cliente sumiu; tentar de novo amanhã.",
                UUID.randomUUID().toString());
        assertThat(contar("atd_nota where atendimento_id = :c", atendimento)).isEqualTo(2);

        // Depois de transferir, quem passou a ser responsável anota; quem deixou de ser, não.
        long colegaId = corpo(colega.get("/api/v1/auth/sessao")).get("id").asLong();
        assertThat(responsavel
                        .post(
                                url(atendimento, "transferencia"),
                                Map.of("versao", versao(atendimento), "destinoId", colegaId))
                        .getStatus())
                .isEqualTo(204);
        nota(
                colega,
                atendimento,
                "Assumi pela transferência.",
                UUID.randomUUID().toString());
        assertThat(codigo(responsavel.post(
                        url(atendimento, "notas"),
                        Map.of(
                                "texto",
                                "Ainda sou eu?",
                                "chaveIdempotencia",
                                UUID.randomUUID().toString()))))
                .isEqualTo("NAO_E_RESPONSAVEL");

        assertThat(colega.post(url(atendimento, "encerramento"), Map.of("versao", versao(atendimento)))
                        .getStatus())
                .isEqualTo(204);
        MockHttpServletResponse encerrado = colega.post(
                url(atendimento, "notas"),
                Map.of("texto", "Depois", "chaveIdempotencia", UUID.randomUUID().toString()));
        assertThat(codigo(encerrado)).isEqualTo("ATENDIMENTO_ENCERRADO");
        assertThat(contar("atd_nota where atendimento_id = :c", atendimento)).isEqualTo(3);
    }

    private long nota(Navegador navegador, long atendimento, String texto, String chave) {
        MockHttpServletResponse resposta =
                navegador.post(url(atendimento, "notas"), Map.of("texto", texto, "chaveIdempotencia", chave));
        assertThat(resposta.getStatus()).isEqualTo(201);
        return corpo(resposta).get("notaId").asLong();
    }

    private int codigoNota(Navegador navegador, long atendimento, String texto) {
        return navegador
                .post(
                        url(atendimento, "notas"),
                        Map.of(
                                "texto",
                                texto,
                                "chaveIdempotencia",
                                UUID.randomUUID().toString()))
                .getStatus();
    }

    private JsonNode itemNota(Navegador navegador, long atendimento, String caminho, long nota) {
        List<JsonNode> notas = new ArrayList<>();
        corpo(navegador.get(url(atendimento, caminho))).get("itens").forEach(i -> {
            if ("NOTA".equals(i.get("tipo").asString()) && i.get("id").asLong() == nota) {
                notas.add(i);
            }
        });
        assertThat(notas).hasSize(1);
        return notas.getFirst();
    }
}
