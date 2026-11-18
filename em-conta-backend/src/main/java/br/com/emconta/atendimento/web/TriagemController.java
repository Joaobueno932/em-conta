package br.com.emconta.atendimento.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.atendimento.nucleo.Classificacoes;
import br.com.emconta.atendimento.nucleo.Classificacoes.Cadastro;
import br.com.emconta.atendimento.nucleo.Triagem;
import br.com.emconta.atendimento.nucleo.Triagem.Configuracao;
import br.com.emconta.atendimento.nucleo.Triagem.Opcao;
import br.com.emconta.atendimento.web.AtendimentoApi.ItemClassificacaoResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuração da triagem inicial pelo WhatsApp: ligada ou não e o setor de destino de cada opção do menu.
 * A consulta acompanha {@code atendimento:consultar}; alterar exige {@code triagem:configurar}
 * (SecurityConfiguration). O comportamento da triagem está em {@link Triagem}.
 */
@RestController
@RequestMapping(path = "/api/v1/atendimento/triagem", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Atendimento", description = "Conversas do WhatsApp, fila e histórico")
class TriagemController {

    private final Triagem triagem;
    private final Classificacoes classificacoes;
    private final Autoria autoria;

    TriagemController(Triagem triagem, Classificacoes classificacoes, Autoria autoria) {
        this.triagem = triagem;
        this.classificacoes = classificacoes;
        this.autoria = autoria;
    }

    @Schema(name = "OpcaoTriagem", description = "Opção do menu da triagem, na ordem exibida ao cliente")
    record OpcaoResponse(
            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"FINANCEIRO", "COBRANCA", "COMERCIAL", "HUMANO"})
            String opcao,

            @Schema(requiredMode = REQUIRED, description = "Número que o cliente responde")
            int numero,

            @Schema(requiredMode = REQUIRED) String rotulo,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Falso para \"Outro assunto ou falar com um atendente\", que vai sempre para"
                            + " a fila sem setor")
            boolean configuravel,

            @Schema(description = "Setor de destino; vazio = fila sem setor")
            ItemClassificacaoResponse setor) {}

    @Schema(name = "ConfiguracaoTriagem")
    record ConfiguracaoResponse(
            @Schema(requiredMode = REQUIRED, description = "Ligada: cada ciclo novo recebe a saudação com o menu")
            boolean ativa,

            @Schema(requiredMode = REQUIRED) long versao,

            @Schema(requiredMode = REQUIRED, description = "Texto da saudação, com o menu")
            String saudacao,

            @Schema(requiredMode = REQUIRED) List<OpcaoResponse> opcoes,
            Instant atualizadoEm,
            UsuarioResponse atualizadoPor) {}

    @Schema(name = "DestinoTriagemRequest")
    record DestinoRequest(
            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"FINANCEIRO", "COBRANCA", "COMERCIAL"})
            @NotNull
            Opcao opcao,

            @Schema(description = "Setor ativo; vazio = fila sem setor")
            Long setorId) {}

    @Schema(name = "ConfiguracaoTriagemRequest")
    record ConfiguracaoRequest(
            @Schema(requiredMode = REQUIRED, description = "Versão vista") @NotNull
            Long versao,

            @Schema(requiredMode = REQUIRED) @NotNull Boolean ativa,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Destino de cada opção configurável; opção ausente fica sem destino")
            @NotNull
            @Size(max = 10)
            List<@Valid @NotNull DestinoRequest> opcoes) {}

    @GetMapping
    @Operation(operationId = "obterTriagem", summary = "Configuração da triagem inicial (atendimento:consultar)")
    ConfiguracaoResponse obter() {
        return resposta(triagem.configuracao());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "configurarTriagem",
            summary = "Liga ou desliga a triagem e define o setor de cada opção (triagem:configurar)")
    @ApiResponse(responseCode = "400", description = "Opção repetida ou não configurável, setor inexistente ou inativo")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    ConfiguracaoResponse configurar(@Valid @RequestBody ConfiguracaoRequest corpo) {
        List<Triagem.Destino> destinos = corpo.opcoes().stream()
                .map(d -> new Triagem.Destino(d.opcao(), d.setorId()))
                .toList();
        return resposta(triagem.configurar(autoria.usuarioAtual(), corpo.versao(), corpo.ativa(), destinos));
    }

    private ConfiguracaoResponse resposta(Configuracao configuracao) {
        Map<Long, Classificacoes.Item> setores = classificacoes.porIds(
                Cadastro.SETOR,
                configuracao.destinos().values().stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
        List<OpcaoResponse> opcoes = Arrays.stream(Opcao.values())
                .map(o -> {
                    Long setor = configuracao.destinos().get(o);
                    Classificacoes.Item item = setor == null ? null : setores.get(setor);
                    return new OpcaoResponse(
                            o.name(),
                            o.numero(),
                            o.rotulo(),
                            o.configuravel(),
                            item == null ? null : new ItemClassificacaoResponse(item.id(), item.nome(), item.ativo()));
                })
                .toList();
        Long autor = configuracao.atualizadoPor();
        return new ConfiguracaoResponse(
                configuracao.ativa(),
                configuracao.versao(),
                Triagem.SAUDACAO,
                opcoes,
                configuracao.atualizadoEm(),
                autor == null
                        ? null
                        : new UsuarioResponse(
                                autor, autoria.nomes(List.of(autor)).getOrDefault(autor, "—")));
    }
}
