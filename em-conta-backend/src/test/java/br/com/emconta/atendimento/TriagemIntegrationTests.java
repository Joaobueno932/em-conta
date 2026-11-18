package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * Triagem inicial pelo WhatsApp com a Graph controlada: saudação única por ciclo, cada opção do menu,
 * opções com o mesmo setor, destino sem setor ou inativo, resposta inválida, falha no envio, evento
 * repetido, assunção durante a triagem (inclusive ao mesmo tempo que a resposta), dois ciclos do mesmo
 * contato e a configuração por permissão. A configuração é única no banco: cada teste a desliga no fim.
 */
class TriagemIntegrationTests extends TriagemIntegracao {

    @Test
    void cadaOpcaoDefineOSetorAntesDeAlguemAssumirEDuasOpcoesPodemTerOMesmoSetor() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        long financeiro = setor(admin, "Financeiro " + marca);
        long comercial = setor(admin, "Comercial " + marca);
        configurar(admin, true, financeiro, financeiro, comercial);

        Map<String, Long> setorPorResposta = new LinkedHashMap<>();
        setorPorResposta.put("1", financeiro);
        setorPorResposta.put("2.", financeiro); // mesma destinação da opção 1
        setorPorResposta.put("3️⃣", comercial); // 3️⃣
        setorPorResposta.put("opção 4", null);
        for (var caso : setorPorResposta.entrySet()) {
            String numero = numeroUnico();
            long atendimento = novoAtendimento(numero);
            assertThat(textosDaTriagem(atendimento)).as("uma saudação").hasSize(1);
            assertThat(textosDaTriagem(atendimento).getFirst())
                    .contains(SAUDACAO, "1 - Financeiro", "4 - Outro assunto");
            processar();
            assertThat(enviosCom(SAUDACAO)).as("saudação enviada à Graph").hasSize(1);
            JsonNode naFila = itemDaFila(admin, numero);
            assertThat(naFila.get("emTriagem").asBoolean()).isTrue();
            assertThat(naFila.path("setor").isMissingNode()
                            || naFila.get("setor").isNull())
                    .isTrue();

            mensagemDoContato(numero, caso.getKey());

            naFila = itemDaFila(admin, numero);
            assertThat(naFila.get("situacao").asString()).as(caso.getKey()).isEqualTo("AGUARDANDO");
            assertThat(naFila.get("emTriagem").asBoolean()).isFalse();
            if (caso.getValue() == null) {
                assertThat(naFila.path("setor").isMissingNode()
                                || naFila.get("setor").isNull())
                        .isTrue();
                assertThat(eventos(admin, atendimento, "TRIAGEM_ENCAMINHADA"))
                        .containsExactly("Opção 4 (Outro assunto ou falar com um atendente) · fila sem setor");
            } else {
                assertThat(naFila.get("setor").get("id").asLong())
                        .as(caso.getKey())
                        .isEqualTo(caso.getValue());
                assertThat(eventos(admin, atendimento, "TRIAGEM_CLASSIFICADA"))
                        .singleElement()
                        .asString()
                        .contains("· Setor: ");
            }
            assertThat(textosDaTriagem(atendimento))
                    .as("saudação e encaminhamento")
                    .hasSize(2);
            assertThat(triagem(atendimento)).isEqualTo(caso.getValue() == null ? "ENCAMINHADA" : "CLASSIFICADA");
            GRAPH.limpar();
        }
    }

    @Test
    void semSetorConfiguradoOuComSetorInativoOCicloFicaNaFilaSemSetor() throws Exception {
        Navegador admin = administrador();
        String marca = marca();
        long financeiro = setor(admin, "Financeiro " + marca);
        configurar(admin, true, financeiro, null, null);

        // Opção sem destino: fila sem setor.
        String semDestino = numeroUnico();
        long a1 = novoAtendimento(semDestino);
        mensagemDoContato(semDestino, "2");
        assertThat(setorDe(a1)).isNull();
        assertThat(eventos(admin, a1, "TRIAGEM_ENCAMINHADA"))
                .containsExactly("Opção 2 (Cobrança ou segunda via): sem setor configurado · fila sem setor");

        // Setor desativado depois de configurado: destino indisponível.
        desativar(admin, financeiro);
        String inativo = numeroUnico();
        long a2 = novoAtendimento(inativo);
        mensagemDoContato(inativo, "1");
        assertThat(setorDe(a2)).isNull();
        assertThat(eventos(admin, a2, "TRIAGEM_ENCAMINHADA"))
                .singleElement()
                .asString()
                .contains("setor Financeiro " + marca + " inativo");
        assertThat(itemDaFila(admin, inativo).get("situacao").asString()).isEqualTo("AGUARDANDO");

        // Configurar: manter o inativo que já estava vale; escolher outro inativo não.
        assertThat(pedirConfiguracao(admin, true, financeiro, null, null).getStatus())
                .isEqualTo(200);
        long outroInativo = setor(admin, "Outro " + marca);
        desativar(admin, outroInativo);
        MockHttpServletResponse recusada = pedirConfiguracao(admin, true, financeiro, outroInativo, null);
        assertThat(recusada.getStatus()).isEqualTo(400);
        assertThat(corpo(recusada).path("campos").has("opcoes.COBRANCA")).isTrue();
    }

    @Test
    void respostaInvalidaRepeteOMenuUmaVezEDepoisEncaminhaParaAtendimentoHumano() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        configurar(admin, true, financeiro, null, null);
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);

        mensagemDoContato(numero, "quero falar sobre minha conta");
        assertThat(textosDaTriagem(atendimento)).hasSize(2);
        assertThat(textosDaTriagem(atendimento).get(1)).startsWith("Não identifiquei a opção");
        assertThat(itemDaFila(admin, numero).get("emTriagem").asBoolean()).isTrue();

        mensagemDoContato(numero, "9");
        assertThat(triagem(atendimento)).isEqualTo("ENCAMINHADA");
        assertThat(textosDaTriagem(atendimento).get(2)).startsWith("Não consegui identificar a opção");
        assertThat(eventos(admin, atendimento, "TRIAGEM_ENCAMINHADA"))
                .containsExactly("Resposta inválida após nova tentativa · fila sem setor");

        // Depois do desfecho, "1" é uma mensagem como outra qualquer.
        mensagemDoContato(numero, "1");
        assertThat(setorDe(atendimento)).isNull();
        assertThat(textosDaTriagem(atendimento)).hasSize(3);
        assertThat(contar("atd_mensagem where atendimento_id = :c and direcao = 'ENTRADA'", atendimento))
                .isEqualTo(4);
    }

    @Test
    void falhaNoEnvioDaSaudacaoEncerraATriagemEDeixaOCicloAcessivelNaFila() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        configurar(admin, true, financeiro, null, null);
        GRAPH.modo(Modo.ERRO_DEFINITIVO);
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        processar();

        assertThat(jdbc.sql("select situacao from atd_mensagem where atendimento_id = :a and origem = 'TRIAGEM'")
                        .param("a", atendimento)
                        .query(String.class)
                        .single())
                .isEqualTo("FALHA");
        assertThat(triagem(atendimento)).isEqualTo("ENCAMINHADA");
        assertThat(eventos(admin, atendimento, "TRIAGEM_ENCAMINHADA"))
                .containsExactly("Falha no envio da triagem · fila sem setor");
        JsonNode naFila = itemDaFila(admin, numero);
        assertThat(naFila.get("emTriagem").asBoolean()).isFalse();

        // O cliente não viu o menu: "1" não classifica, e o ciclo pode ser assumido normalmente.
        GRAPH.modo(Modo.ACEITAR);
        mensagemDoContato(numero, "1");
        assertThat(setorDe(atendimento)).isNull();
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
    }

    @Test
    void eventoRepetidoNaoRepeteASaudacaoNemAClassificacao() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        configurar(admin, true, financeiro, null, null);
        String numero = numeroUnico();
        String primeira = wamid();
        String corpo = mensagem(numero, "Contato " + numero, primeira, "Olá", agoraEpoch());
        enviarWebhook(corpo, assinar(corpo));
        enviarWebhook(corpo, assinar(corpo)); // reentrega idêntica (mesmo corpo)
        String outroCorpo = mensagem(numero, "Contato " + numero, primeira, "Olá", agoraEpoch() + 1);
        enviarWebhook(outroCorpo, assinar(outroCorpo)); // mesma mensagem em outra entrega
        processar();
        long atendimento = aberto(numero);
        assertThat(textosDaTriagem(atendimento)).hasSize(1);
        assertThat(enviosCom(SAUDACAO)).hasSize(1);

        String resposta = wamid();
        for (long segundos = 0; segundos < 3; segundos++) {
            String r = mensagem(numero, "Contato " + numero, resposta, "1", agoraEpoch() + segundos);
            enviarWebhook(r, assinar(r));
        }
        processar();
        processar(); // reprocessar não muda nada
        assertThat(setorDe(atendimento)).isEqualTo(financeiro);
        assertThat(eventos(admin, atendimento, "TRIAGEM_CLASSIFICADA")).hasSize(1);
        assertThat(textosDaTriagem(atendimento)).hasSize(2);
        assertThat(contar("atd_mensagem where atendimento_id = :c and direcao = 'ENTRADA'", atendimento))
                .isEqualTo(2);
    }

    @Test
    void assumirDuranteATriagemEncerraEAsProximasMensagensSeguemOFluxoNormal() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        long comercial = setor(admin, "Comercial " + marca());
        configurar(admin, true, financeiro, null, comercial);

        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        processar();
        assertThat(admin.post(url(atendimento, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(triagem(atendimento)).isEqualTo("INTERROMPIDA");
        assertThat(eventos(admin, atendimento, "TRIAGEM_INTERROMPIDA")).hasSize(1);

        mensagemDoContato(numero, "1");
        assertThat(setorDe(atendimento)).isNull();
        assertThat(textosDaTriagem(atendimento)).as("só a saudação").hasSize(1);

        // Primeira resposta humana: a saudação não conta; a resposta do atendente, sim.
        JsonNode detalhe = corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento));
        assertThat(detalhe.path("primeiraRespostaHumanaEm").isMissingNode()
                        || detalhe.get("primeiraRespostaHumanaEm").isNull())
                .isTrue();
        responder(admin, atendimento, "Bom dia! Como posso ajudar?");
        assertThat(corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento))
                        .get("primeiraRespostaHumanaEm")
                        .isNull())
                .isFalse();

        // Classificado pela triagem e assumido depois: o responsável corrige o setor.
        String outro = numeroUnico();
        long classificado = novoAtendimento(outro);
        mensagemDoContato(outro, "1");
        assertThat(setorDe(classificado)).isEqualTo(financeiro);
        assertThat(admin.post(url(classificado, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(eventos(admin, classificado, "TRIAGEM_INTERROMPIDA")).isEmpty();
        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("versao", versao(classificado));
        pedido.put("setorId", comercial);
        pedido.put("etiquetaIds", List.of());
        assertThat(admin.put(url(classificado, "classificacao"), pedido).getStatus())
                .isEqualTo(204);
        assertThat(setorDe(classificado)).isEqualTo(comercial);
    }

    @Test
    void respostaDoClienteEAssuncaoAoMesmoTempoTemUmUnicoDesfecho() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        configurar(admin, true, financeiro, null, null);
        for (int rodada = 0; rodada < 6; rodada++) {
            String numero = numeroUnico();
            long atendimento = novoAtendimento(numero);
            String r = mensagem(numero, "Contato " + numero, wamid(), "1", agoraEpoch());
            enviarWebhook(r, assinar(r)); // registrada; o processamento disputa com a assunção
            Navegador sessao = administrador();
            List<Callable<Integer>> tarefas = new ArrayList<>();
            tarefas.add(() -> {
                recebimento.processarPendentes(50);
                return 0;
            });
            tarefas.add(() -> sessao.post(url(atendimento, "assuncao"), null).getStatus());
            List<Future<Integer>> resultados = emParalelo(tarefas);
            assertThat(resultados.get(1).get()).as("a assunção sempre vale").isEqualTo(204);
            // Se a resposta tivesse falhado nesta tentativa, voltaria mais tarde: processa adiante no tempo.
            relogio.fixar(Instant.now().plus(Duration.ofMinutes(10)));
            recebimento.processarPendentes(50);
            relogio.liberar();

            String desfecho = triagem(atendimento);
            assertThat(desfecho).isIn("CLASSIFICADA", "INTERROMPIDA");
            assertThat(setorDe(atendimento)).isEqualTo("CLASSIFICADA".equals(desfecho) ? financeiro : null);
            assertThat(jdbc.sql("select count(*) from atd_evento where atendimento_id = :a and tipo like 'TRIAGEM%'")
                            .param("a", atendimento)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(contar("atd_mensagem where atendimento_id = :c and direcao = 'ENTRADA'", atendimento))
                    .isEqualTo(2);
            assertThat(jdbc.sql("select situacao from atd_atendimento where id = :a")
                            .param("a", atendimento)
                            .query(String.class)
                            .single())
                    .isEqualTo("EM_ATENDIMENTO");
        }
    }

    @Test
    void cadaCicloDoMesmoContatoTemSuaTriagemEOEncerradoFicaComoEstava() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());
        long comercial = setor(admin, "Comercial " + marca());
        configurar(admin, true, financeiro, financeiro, comercial);
        String numero = numeroUnico();

        long primeiro = novoAtendimento(numero);
        mensagemDoContato(numero, "2");
        assertThat(admin.post(url(primeiro, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(admin.post(
                                url(primeiro, "notas"),
                                Map.of(
                                        "texto",
                                        "Nota do primeiro ciclo",
                                        "chaveIdempotencia",
                                        UUID.randomUUID().toString()))
                        .getStatus())
                .isEqualTo(201);
        assertThat(admin.post(url(primeiro, "encerramento"), Map.of("versao", versao(primeiro)))
                        .getStatus())
                .isEqualTo(204);
        List<String> eventosDoPrimeiro = eventos(admin, primeiro, "TRIAGEM_CLASSIFICADA");

        mensagemDoContato(numero, "Voltei");
        long segundo = aberto(numero);
        assertThat(segundo).isNotEqualTo(primeiro);
        assertThat(textosDaTriagem(segundo)).as("saudação do novo ciclo").hasSize(1);
        mensagemDoContato(numero, "3");

        assertThat(setorDe(primeiro)).isEqualTo(financeiro);
        assertThat(setorDe(segundo)).isEqualTo(comercial);
        assertThat(eventos(admin, primeiro, "TRIAGEM_CLASSIFICADA")).isEqualTo(eventosDoPrimeiro);
        assertThat(contar("atd_nota where atendimento_id = :c", primeiro)).isEqualTo(1);
        assertThat(jdbc.sql("select situacao from atd_atendimento where id = :a")
                        .param("a", primeiro)
                        .query(String.class)
                        .single())
                .isEqualTo("ENCERRADO");

        // Histórico geral: o ciclo encerrado continua achado pelo setor que a triagem definiu.
        JsonNode historico = corpo(admin.get("/api/v1/atendimento/historico?setor=" + financeiro + "&busca=" + numero));
        assertThat(historico.get("itens")).hasSize(1);
        assertThat(historico.get("itens").get(0).get("id").asLong()).isEqualTo(primeiro);
    }

    @Test
    void configuracaoExigePermissaoVersaoEOpcoesValidas() throws Exception {
        Navegador admin = administrador();
        long financeiro = setor(admin, "Financeiro " + marca());

        // Desligada (padrão): ciclo novo sem saudação, como antes da triagem.
        long semTriagem = novoAtendimento(numeroUnico());
        assertThat(textosDaTriagem(semTriagem)).isEmpty();

        Navegador consulta = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR);
        MockHttpServletResponse lida = consulta.get("/api/v1/atendimento/triagem");
        assertThat(lida.getStatus()).isEqualTo(200);
        JsonNode configuracao = corpo(lida);
        assertThat(configuracao.get("ativa").asBoolean()).isFalse();
        assertThat(configuracao.get("opcoes")).hasSize(4);
        assertThat(configuracao.get("opcoes").get(3).get("configuravel").asBoolean())
                .isFalse();
        assertThat(configuracao.get("saudacao").asString()).contains(SAUDACAO);
        long versao = configuracao.get("versao").asLong();

        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("versao", versao);
        pedido.put("ativa", true);
        pedido.put("opcoes", List.of(Map.of("opcao", "FINANCEIRO", "setorId", financeiro)));
        assertThat(consulta.put("/api/v1/atendimento/triagem", pedido).getStatus())
                .as("sem triagem:configurar")
                .isEqualTo(403);
        assertThat(comPermissoes(Permissao.ATENDIMENTO_CONSULTAR, Permissao.TRIAGEM_CONFIGURAR)
                        .put("/api/v1/atendimento/triagem", pedido)
                        .getStatus())
                .isEqualTo(200);
        assertThat(admin.put("/api/v1/atendimento/triagem", pedido).getStatus())
                .as("versão antiga")
                .isEqualTo(409);
        assertThat(comPermissoes().get("/api/v1/atendimento/triagem").getStatus())
                .isEqualTo(403);

        long atual =
                corpo(admin.get("/api/v1/atendimento/triagem")).get("versao").asLong();
        pedido.put("versao", atual);
        pedido.put("opcoes", List.of(Map.of("opcao", "HUMANO", "setorId", financeiro)));
        assertThat(admin.put("/api/v1/atendimento/triagem", pedido).getStatus()).isEqualTo(400);
        pedido.put(
                "opcoes", List.of(Map.of("opcao", "FINANCEIRO", "setorId", financeiro), Map.of("opcao", "FINANCEIRO")));
        assertThat(admin.put("/api/v1/atendimento/triagem", pedido).getStatus()).isEqualTo(400);
        pedido.put("opcoes", List.of(Map.of("opcao", "COMERCIAL", "setorId", 999_999_999L)));
        assertThat(admin.put("/api/v1/atendimento/triagem", pedido).getStatus()).isEqualTo(400);
        assertThat(corpo(admin.get("/api/v1/atendimento/triagem")).get("versao").asLong())
                .as("pedidos recusados não alteram")
                .isEqualTo(atual);
    }
}
