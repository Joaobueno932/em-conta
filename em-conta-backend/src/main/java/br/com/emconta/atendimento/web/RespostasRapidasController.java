package br.com.emconta.atendimento.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.atendimento.nucleo.FalhaAtendimento;
import br.com.emconta.atendimento.nucleo.RespostasRapidas;
import br.com.emconta.atendimento.nucleo.RespostasRapidas.Dados;
import br.com.emconta.atendimento.nucleo.RespostasRapidas.Resposta;
import br.com.emconta.atendimento.nucleo.RespostasRapidas.Situacao;
import br.com.emconta.atendimento.web.AtendimentoApi.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de respostas rápidas (textos internos; não são templates da Meta). Permissões de cada rota na
 * SecurityConfiguration; regras e unicidade do atalho no serviço e no banco.
 */
@RestController
@RequestMapping(path = "/api/v1/atendimento/respostas-rapidas", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Atendimento", description = "Conversas do WhatsApp, fila e histórico")
class RespostasRapidasController {

    private final RespostasRapidas respostas;
    private final Autoria autoria;

    RespostasRapidasController(RespostasRapidas respostas, Autoria autoria) {
        this.respostas = respostas;
        this.autoria = autoria;
    }

    @Schema(name = "RespostaRapida", description = "Texto reutilizável do catálogo interno")
    record RespostaResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String titulo,

            @Schema(requiredMode = REQUIRED, description = "Forma normalizada, sem a barra exibida na interface")
            String atalho,

            @Schema(requiredMode = REQUIRED) String conteudo,
            @Schema(requiredMode = REQUIRED) boolean ativa,
            @Schema(requiredMode = REQUIRED) long versao,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) UsuarioResponse criadoPor,
            @Schema(requiredMode = REQUIRED) Instant atualizadoEm,
            @Schema(requiredMode = REQUIRED) UsuarioResponse atualizadoPor) {}

    @Schema(name = "PaginaRespostasRapidas")
    record PaginaResponse(
            @Schema(requiredMode = REQUIRED) List<RespostaResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {}

    @Schema(name = "RespostaRapidaRequest")
    record DadosRequest(
            @Schema(requiredMode = REQUIRED, maxLength = RespostasRapidas.TAMANHO_MAXIMO_TITULO) @Size(max = 200)
            String titulo,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "Letras minúsculas sem acento, números e hífen (2 a 30); a barra inicial é" + " ignorada")
            @Size(max = 60)
            String atalho,

            @Schema(requiredMode = REQUIRED, maxLength = 4096) @Size(max = 8192)
            String conteudo,

            @Schema(description = "Versão vista (obrigatória na edição)")
            Long versao) {}

    @Schema(name = "SituacaoRespostaRapidaRequest")
    record VersaoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao) {}

    @GetMapping
    @Operation(
            operationId = "listarRespostasRapidas",
            summary = "Catálogo de respostas rápidas, com busca e situação (respostas-rapidas:consultar)")
    PaginaResponse listar(
            @RequestParam(required = false) @Size(max = 100) String busca,
            @Schema(description = "Também procurar no conteúdo (tela de gerenciamento)")
                    @RequestParam(defaultValue = "true")
                    boolean noConteudo,
            @RequestParam(defaultValue = "TODAS") Situacao situacao,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(RespostasRapidas.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        Page<Resposta> page = respostas.listar(busca, noConteudo, situacao, pagina, tamanho);
        Map<Long, String> nomes = nomes(page.getContent());
        return new PaginaResponse(
                page.getContent().stream().map(r -> resposta(r, nomes)).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "obterRespostaRapida", summary = "Uma resposta rápida (respostas-rapidas:consultar)")
    RespostaResponse obter(@PathVariable long id) {
        Resposta r = respostas.obter(id).orElseThrow(RespostasRapidasController::naoEncontrada);
        return resposta(r, nomes(List.of(r)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "criarRespostaRapida", summary = "Cadastra resposta rápida (respostas-rapidas:cadastrar)")
    @ApiResponse(responseCode = "201", description = "Cadastrada (ativa)")
    @ApiResponse(responseCode = "409", description = "ATALHO_EM_USO")
    ResponseEntity<RespostaResponse> criar(@Valid @RequestBody DadosRequest corpo) {
        Resposta r = respostas.criar(autoria.usuarioAtual(), dados(corpo));
        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(r, nomes(List.of(r))));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editarRespostaRapida", summary = "Edita resposta rápida (respostas-rapidas:editar)")
    @ApiResponse(responseCode = "409", description = "ATALHO_EM_USO ou CONFLITO_CONCORRENTE")
    RespostaResponse editar(@PathVariable long id, @Valid @RequestBody DadosRequest corpo) {
        if (corpo.versao() == null) {
            throw FalhaAtendimento.versaoObrigatoria();
        }
        Resposta r = respostas.editar(autoria.usuarioAtual(), id, corpo.versao(), dados(corpo));
        return resposta(r, nomes(List.of(r)));
    }

    @PostMapping(path = "/{id}/ativacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "ativarRespostaRapida",
            summary = "Ativa resposta rápida (respostas-rapidas:alterar-situacao)")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    RespostaResponse ativar(@PathVariable long id, @Valid @RequestBody VersaoRequest corpo) {
        Resposta r = respostas.alterarSituacao(autoria.usuarioAtual(), id, corpo.versao(), true);
        return resposta(r, nomes(List.of(r)));
    }

    @PostMapping(path = "/{id}/desativacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "desativarRespostaRapida",
            summary = "Desativa resposta rápida, preservando o cadastro (respostas-rapidas:alterar-situacao)")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    RespostaResponse desativar(@PathVariable long id, @Valid @RequestBody VersaoRequest corpo) {
        Resposta r = respostas.alterarSituacao(autoria.usuarioAtual(), id, corpo.versao(), false);
        return resposta(r, nomes(List.of(r)));
    }

    private static Dados dados(DadosRequest corpo) {
        return new Dados(corpo.titulo(), corpo.atalho(), corpo.conteudo());
    }

    private Map<Long, String> nomes(List<Resposta> lista) {
        Set<Long> ids = new HashSet<>();
        lista.forEach(r -> {
            ids.add(r.criadoPor());
            ids.add(r.atualizadoPor());
        });
        return autoria.nomes(ids);
    }

    private static RespostaResponse resposta(Resposta r, Map<Long, String> nomes) {
        return new RespostaResponse(
                r.id(),
                r.titulo(),
                r.atalho(),
                r.conteudo(),
                r.ativa(),
                r.versao(),
                r.criadoEm(),
                new UsuarioResponse(r.criadoPor(), nomes.getOrDefault(r.criadoPor(), "—")),
                r.atualizadoEm(),
                new UsuarioResponse(r.atualizadoPor(), nomes.getOrDefault(r.atualizadoPor(), "—")));
    }

    private static FalhaAtendimento naoEncontrada() {
        return FalhaAtendimento.respostaRapidaNaoEncontrada();
    }
}
