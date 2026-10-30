package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Usuario;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Busca da fila e do histórico pelo nome do associado vinculado sem corte silencioso: mais de 200
 * associados com o mesmo termo (PF e PJ), com atendimentos só nos que ficariam além de um limite fixo.
 * Associados criados em volume direto no banco isolado de testes.
 */
class BuscaAssociadoIntegrationTests extends AtendimentoIntegracao {

    @Test
    void encontraAtendimentosDeAssociadosAlemDosPrimeiros200ComTotalEPaginasCorretos() throws Exception {
        String email = emailUnico("busca");
        Usuario autor = criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador admin = logado(email);
        String termo = "Massa" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> associados = new ArrayList<>();
        for (int i = 0; i < 230; i++) {
            associados.add(criarAssociado(
                    autor.getId(), i % 2 == 0 ? "PF" : "PJ", i == 225 ? termo + "Exclusivo" : termo + " " + i));
        }
        // Só associados que ficariam fora de um corte nos 200 primeiros (por id) têm atendimento.
        long pf = associados.get(210);
        long pj = associados.get(225);
        long outroPf = associados.get(228);
        long atendimentoPf = vinculado(admin, pf);
        long atendimentoPj = vinculado(admin, pj);
        long atendimentoOutro = vinculado(admin, outroPf);

        JsonNode semBusca = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&tamanho=1"));
        JsonNode pagina0 =
                corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&tamanho=2&busca=" + termo));
        assertThat(pagina0.get("totalItens").asLong()).isEqualTo(3);
        assertThat(pagina0.get("totalPaginas").asLong()).isEqualTo(2);
        assertThat(pagina0.get("itens")).hasSize(2);
        JsonNode pagina1 =
                corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&tamanho=2&pagina=1&busca=" + termo));
        assertThat(pagina1.get("itens")).hasSize(1);
        Set<Long> encontrados = new HashSet<>();
        for (JsonNode pagina : List.of(pagina0, pagina1)) {
            pagina.get("itens")
                    .forEach(i ->
                            assertThat(encontrados.add(i.get("id").asLong())).isTrue());
        }
        assertThat(encontrados).containsExactlyInAnyOrder(atendimentoPf, atendimentoPj, atendimentoOutro);
        assertThat(pagina0.get("contagens"))
                .as("contagens não dependem da busca")
                .isEqualTo(semBusca.get("contagens"));
        assertThat(pagina0.toString()).doesNotContain("documento\":\"");

        // Um termo que só um associado além do corte tem (PJ).
        JsonNode exato =
                corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=" + termo + "Exclusivo"));
        assertThat(exato.get("totalItens").asLong()).isEqualTo(1);
        assertThat(exato.get("itens").get(0).get("id").asLong()).isEqualTo(atendimentoPj);

        // Histórico: encerrados, pelos mesmos critérios.
        for (long atendimento : List.of(atendimentoPf, atendimentoPj)) {
            assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus())
                    .isEqualTo(204);
            assertThat(admin.post(url(atendimento, "encerramento"), Map.of("versao", versao(atendimento)))
                            .getStatus())
                    .isEqualTo(204);
        }
        JsonNode historico = corpo(admin.get("/api/v1/atendimento/historico?tamanho=1&busca=" + termo));
        assertThat(historico.get("totalItens").asLong()).isEqualTo(2);
        assertThat(historico.get("totalPaginas").asLong()).isEqualTo(2);
        JsonNode historico1 = corpo(admin.get("/api/v1/atendimento/historico?tamanho=1&pagina=1&busca=" + termo));
        assertThat(List.of(
                        historico.get("itens").get(0).get("id").asLong(),
                        historico1.get("itens").get(0).get("id").asLong()))
                .containsExactlyInAnyOrder(atendimentoPf, atendimentoPj);
        // A fila continua achando o que segue aberto.
        JsonNode fila = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=" + termo));
        assertThat(fila.get("totalItens").asLong()).isEqualTo(1);
        assertThat(fila.get("itens").get(0).get("id").asLong()).isEqualTo(atendimentoOutro);
    }

    private long criarAssociado(long autor, String tipo, String nome) {
        String documento = tipo.equals("PF") ? digitos(11) : digitos(14);
        Timestamp agora = Timestamp.from(Instant.now());
        jdbc.sql("insert into associado (tipo, documento, nome, criado_em, criado_por, atualizado_em, atualizado_por)"
                        + " values (:tipo, :doc, :nome, :agora, :autor, :agora, :autor)")
                .param("tipo", tipo)
                .param("doc", documento)
                .param("nome", nome)
                .param("agora", agora)
                .param("autor", autor)
                .update();
        return jdbc.sql("select id from associado where documento = :doc")
                .param("doc", documento)
                .query(Long.class)
                .single();
    }

    /** Documento só com dígitos, no formato da restrição da tabela (sem validar dígitos verificadores). */
    private static String digitos(int quantos) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < quantos; i++) {
            s.append(ThreadLocalRandom.current().nextInt(10));
        }
        return s.toString();
    }

    private long vinculado(Navegador admin, long associado) throws Exception {
        long atendimento = novoAtendimento(numeroUnico());
        long versaoConversa = corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento))
                .get("versaoConversa")
                .asLong();
        assertThat(admin.put(
                                url(atendimento, "associado"),
                                Map.of("versaoConversa", versaoConversa, "associadoId", associado))
                        .getStatus())
                .isEqualTo(204);
        return atendimento;
    }
}
