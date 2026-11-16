package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Permissao;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Presença do atendente: automática (Disponível ao começar a responder) e manual, que prevalece até a
 * pessoa voltar ao automático, inclusive contra uma atualização automática atrasada.
 */
class PresencaIntegrationTests extends AtendimentoIntegracao {

    private static final String PRESENCA = "/api/v1/atendimento/presenca";

    @Test
    void automaticaPassaADisponivelAoComecarAResponderSemMudarOAtendimento() throws Exception {
        Navegador atendente = administrador();
        long atendimento = assumido(atendente, numeroUnico());
        assertThat(presenca(atendente)).isEqualTo(Map.of("disponivel", false, "modo", "AUTOMATICO"));
        long versao = versao(atendimento);

        assertThat(atividade(atendente, atendimento)).isEqualTo(Map.of("disponivel", true, "modo", "AUTOMATICO"));
        assertThat(presenca(atendente)).isEqualTo(Map.of("disponivel", true, "modo", "AUTOMATICO"));
        assertThat(versao(atendimento)).as("presença não mexe no atendimento").isEqualTo(versao);
    }

    @Test
    void escolhaManualPrevaleceSobreAtividadeAtrasadaAteVoltarAoAutomatico() throws Exception {
        Navegador atendente = administrador();
        long atendimento = assumido(atendente, numeroUnico());

        assertThat(definir(atendente, Map.of("modo", "MANUAL", "disponivel", false)))
                .isEqualTo(Map.of("disponivel", false, "modo", "MANUAL"));
        // Pedido automático que chega depois da escolha manual (por exemplo, de outra aba): nada muda.
        assertThat(atividade(atendente, atendimento)).isEqualTo(Map.of("disponivel", false, "modo", "MANUAL"));
        assertThat(presenca(atendente)).isEqualTo(Map.of("disponivel", false, "modo", "MANUAL"));

        // Volta ao automático: mantém a situação até a próxima atividade.
        assertThat(definir(atendente, Map.of("modo", "AUTOMATICO")))
                .isEqualTo(Map.of("disponivel", false, "modo", "AUTOMATICO"));
        assertThat(atividade(atendente, atendimento)).isEqualTo(Map.of("disponivel", true, "modo", "AUTOMATICO"));

        // Disponível manual também vale até voltar ao automático; o formato antigo (só disponivel) é manual.
        assertThat(definir(atendente, Map.of("disponivel", true)))
                .isEqualTo(Map.of("disponivel", true, "modo", "MANUAL"));
    }

    @Test
    void atividadeSoNumaConversaQueAPessoaPodeAtenderEComPermissaoDeResponder() throws Exception {
        Navegador responsavel = administrador();
        long atendimento = assumido(responsavel, numeroUnico());
        long aguardando = novoAtendimento(numeroUnico());
        Navegador outro = administrador();

        assertThat(codigo(outro.post(PRESENCA + "/atividade", Map.of("atendimentoId", atendimento))))
                .isEqualTo("NAO_E_RESPONSAVEL");
        assertThat(codigo(outro.post(PRESENCA + "/atividade", Map.of("atendimentoId", aguardando))))
                .isEqualTo("NAO_E_RESPONSAVEL");
        assertThat(presenca(outro).get("disponivel")).isEqualTo(false);
        assertThat(comPermissoes(Permissao.ATENDIMENTO_CONSULTAR)
                        .post(PRESENCA + "/atividade", Map.of("atendimentoId", atendimento))
                        .getStatus())
                .isEqualTo(403);
        assertThat(responsavel.put(PRESENCA, Map.of("modo", "MANUAL")).getStatus())
                .as("manual exige a escolha")
                .isEqualTo(400);
        assertThat(responsavel
                        .put(PRESENCA, Map.of("modo", "OUTRO", "disponivel", true))
                        .getStatus())
                .isEqualTo(400);
    }

    private Map<String, Object> presenca(Navegador navegador) {
        return mapa(corpo(navegador.get(PRESENCA)));
    }

    private Map<String, Object> definir(Navegador navegador, Map<String, Object> corpo) {
        var resposta = navegador.put(PRESENCA, corpo);
        assertThat(resposta.getStatus()).isEqualTo(200);
        return mapa(corpo(resposta));
    }

    private Map<String, Object> atividade(Navegador navegador, long atendimento) {
        var resposta = navegador.post(PRESENCA + "/atividade", Map.of("atendimentoId", atendimento));
        assertThat(resposta.getStatus()).isEqualTo(200);
        return mapa(corpo(resposta));
    }

    // A resposta inteira (com atualizadoEm) é conferida pelo contrato; aqui, só a presença.
    private static Map<String, Object> mapa(JsonNode no) {
        return Map.of(
                "disponivel",
                no.get("disponivel").asBoolean(),
                "modo",
                no.get("modo").asString());
    }
}
