package br.com.emconta.atendimento.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.atendimento.nucleo.Classificacoes;
import br.com.emconta.atendimento.nucleo.Classificacoes.Cadastro;
import br.com.emconta.atendimento.nucleo.Classificacoes.Item;
import br.com.emconta.atendimento.nucleo.Classificacoes.Situacao;
import br.com.emconta.atendimento.nucleo.FalhaAtendimento;
import br.com.emconta.atendimento.web.AtendimentoApi.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Cadastros de setores e de etiquetas do atendimento, com as mesmas rotas para os dois. A lista acompanha
 * {@code atendimento:consultar} (painel, filtros do Histórico geral e gerenciamento); cadastrar, editar e
 * alterar a situação têm permissões próprias. Permissões de cada rota na SecurityConfiguration.
 */
@RestController
@RequestMapping(path = "/api/v1/atendimento", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Atendimento", description = "Conversas do WhatsApp, fila e histórico")
class ClassificacoesController {

    private final Classificacoes classificacoes;
    private final Autoria autoria;

    ClassificacoesController(Classificacoes classificacoes, Autoria autoria) {
        this.classificacoes = classificacoes;
        this.autoria = autoria;
    }

    @Schema(name = "ItemCadastroClassificacao", description = "Setor ou etiqueta do cadastro")
    record ItemResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome,
            @Schema(requiredMode = REQUIRED) boolean ativo,
            @Schema(requiredMode = REQUIRED) long versao,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) UsuarioResponse criadoPor,
            @Schema(requiredMode = REQUIRED) Instant atualizadoEm,
            @Schema(requiredMode = REQUIRED) UsuarioResponse atualizadoPor) {}

    @Schema(name = "ListaCadastroClassificacao")
    record ListaResponse(@Schema(requiredMode = REQUIRED) List<ItemResponse> itens) {}

    @Schema(name = "CadastroClassificacaoRequest")
    record DadosRequest(
            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "De 2 a 60 caracteres (setor) ou 2 a 40 (etiqueta); espaços repetidos" + " viram um só")
            @Size(max = 200)
            String nome,

            @Schema(description = "Versão vista (obrigatória na edição)")
            Long versao) {}

    @Schema(name = "SituacaoCadastroClassificacaoRequest")
    record VersaoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao) {}

    // ------------------------------------------------------------------ setores

    @GetMapping("/setores")
    @Operation(
            operationId = "listarSetores",
            summary = "Setores do atendimento, em ordem de nome (atendimento:consultar)")
    ListaResponse listarSetores(@RequestParam(defaultValue = "TODOS") Situacao situacao) {
        return lista(classificacoes.listar(Cadastro.SETOR, situacao));
    }

    @PostMapping(path = "/setores", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "criarSetor", summary = "Cadastra setor (setores:cadastrar)")
    @ApiResponse(responseCode = "201", description = "Cadastrado (ativo)")
    @ApiResponse(responseCode = "409", description = "NOME_EM_USO")
    ResponseEntity<ItemResponse> criarSetor(@Valid @RequestBody DadosRequest corpo) {
        return criado(classificacoes.criar(Cadastro.SETOR, autoria.usuarioAtual(), corpo.nome()));
    }

    @PutMapping(path = "/setores/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "editarSetor",
            summary = "Renomeia setor; vale para os atendimentos que já o usam (setores:editar)")
    @ApiResponse(responseCode = "409", description = "NOME_EM_USO ou CONFLITO_CONCORRENTE")
    ItemResponse editarSetor(@PathVariable long id, @Valid @RequestBody DadosRequest corpo) {
        return renomear(Cadastro.SETOR, id, corpo);
    }

    @PostMapping(path = "/setores/{id}/ativacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "ativarSetor", summary = "Ativa setor (setores:alterar-situacao)")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    ItemResponse ativarSetor(@PathVariable long id, @Valid @RequestBody VersaoRequest corpo) {
        return situacao(Cadastro.SETOR, id, corpo, true);
    }

    @PostMapping(path = "/setores/{id}/desativacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "desativarSetor",
            summary = "Desativa setor, preservando o cadastro e os atendimentos que o usam (setores:alterar-situacao)")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    ItemResponse desativarSetor(@PathVariable long id, @Valid @RequestBody VersaoRequest corpo) {
        return situacao(Cadastro.SETOR, id, corpo, false);
    }

    // ------------------------------------------------------------------ etiquetas

    @GetMapping("/etiquetas")
    @Operation(
            operationId = "listarEtiquetas",
            summary = "Etiquetas do atendimento, em ordem de nome (atendimento:consultar)")
    ListaResponse listarEtiquetas(@RequestParam(defaultValue = "TODOS") Situacao situacao) {
        return lista(classificacoes.listar(Cadastro.ETIQUETA, situacao));
    }

    @PostMapping(path = "/etiquetas", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "criarEtiqueta", summary = "Cadastra etiqueta (etiquetas:cadastrar)")
    @ApiResponse(responseCode = "201", description = "Cadastrada (ativa)")
    @ApiResponse(responseCode = "409", description = "NOME_EM_USO")
    ResponseEntity<ItemResponse> criarEtiqueta(@Valid @RequestBody DadosRequest corpo) {
        return criado(classificacoes.criar(Cadastro.ETIQUETA, autoria.usuarioAtual(), corpo.nome()));
    }

    @PutMapping(path = "/etiquetas/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "editarEtiqueta",
            summary = "Renomeia etiqueta; vale para os atendimentos que já a usam (etiquetas:editar)")
    @ApiResponse(responseCode = "409", description = "NOME_EM_USO ou CONFLITO_CONCORRENTE")
    ItemResponse editarEtiqueta(@PathVariable long id, @Valid @RequestBody DadosRequest corpo) {
        return renomear(Cadastro.ETIQUETA, id, corpo);
    }

    @PostMapping(path = "/etiquetas/{id}/ativacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "ativarEtiqueta", summary = "Ativa etiqueta (etiquetas:alterar-situacao)")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    ItemResponse ativarEtiqueta(@PathVariable long id, @Valid @RequestBody VersaoRequest corpo) {
        return situacao(Cadastro.ETIQUETA, id, corpo, true);
    }

    @PostMapping(path = "/etiquetas/{id}/desativacao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "desativarEtiqueta",
            summary =
                    "Desativa etiqueta, preservando o cadastro e os atendimentos que a usam (etiquetas:alterar-situacao)")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE")
    ItemResponse desativarEtiqueta(@PathVariable long id, @Valid @RequestBody VersaoRequest corpo) {
        return situacao(Cadastro.ETIQUETA, id, corpo, false);
    }

    // ------------------------------------------------------------------ apoio

    private ItemResponse renomear(Cadastro cadastro, long id, DadosRequest corpo) {
        if (corpo.versao() == null) {
            throw FalhaAtendimento.versaoObrigatoria();
        }
        return resposta(classificacoes.renomear(cadastro, autoria.usuarioAtual(), id, corpo.versao(), corpo.nome()));
    }

    private ItemResponse situacao(Cadastro cadastro, long id, VersaoRequest corpo, boolean ativo) {
        return resposta(classificacoes.alterarSituacao(cadastro, autoria.usuarioAtual(), id, corpo.versao(), ativo));
    }

    private ResponseEntity<ItemResponse> criado(Item item) {
        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(item));
    }

    private ListaResponse lista(List<Item> itens) {
        Map<Long, String> nomes = nomes(itens);
        return new ListaResponse(itens.stream().map(i -> resposta(i, nomes)).toList());
    }

    private ItemResponse resposta(Item item) {
        return resposta(item, nomes(List.of(item)));
    }

    private Map<Long, String> nomes(List<Item> itens) {
        Set<Long> ids = new HashSet<>();
        itens.forEach(i -> {
            ids.add(i.criadoPor());
            ids.add(i.atualizadoPor());
        });
        return autoria.nomes(ids);
    }

    private static ItemResponse resposta(Item i, Map<Long, String> nomes) {
        return new ItemResponse(
                i.id(),
                i.nome(),
                i.ativo(),
                i.versao(),
                i.criadoEm(),
                new UsuarioResponse(i.criadoPor(), nomes.getOrDefault(i.criadoPor(), "—")),
                i.atualizadoEm(),
                new UsuarioResponse(i.atualizadoPor(), nomes.getOrDefault(i.atualizadoPor(), "—")));
    }
}
