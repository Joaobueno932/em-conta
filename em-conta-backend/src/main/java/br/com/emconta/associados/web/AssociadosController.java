package br.com.emconta.associados.web;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.associados.cadastro.AcaoHistorico.CategoriaHistorico;
import br.com.emconta.associados.cadastro.CadastroAssociados;
import br.com.emconta.associados.cadastro.CadastroAssociados.Ficha;
import br.com.emconta.associados.cadastro.ConsultaAssociados;
import br.com.emconta.associados.cadastro.ConsultaAssociados.Ordenacao;
import br.com.emconta.associados.cadastro.DadosContato;
import br.com.emconta.associados.cadastro.Documentos;
import br.com.emconta.associados.cadastro.HistoricoCadastro;
import br.com.emconta.associados.cadastro.TipoPessoa;
import br.com.emconta.associados.web.AssociadosApi.AlteracaoResponse;
import br.com.emconta.associados.web.AssociadosApi.AssociadoResponse;
import br.com.emconta.associados.web.AssociadosApi.AutorResponse;
import br.com.emconta.associados.web.AssociadosApi.ContatoRequest;
import br.com.emconta.associados.web.AssociadosApi.ContatoResponse;
import br.com.emconta.associados.web.AssociadosApi.EdicaoAssociadoRequest;
import br.com.emconta.associados.web.AssociadosApi.EdicaoContatoRequest;
import br.com.emconta.associados.web.AssociadosApi.EdicaoUcRequest;
import br.com.emconta.associados.web.AssociadosApi.NovaUcRequest;
import br.com.emconta.associados.web.AssociadosApi.NovoAssociadoRequest;
import br.com.emconta.associados.web.AssociadosApi.PaginaAssociadosResponse;
import br.com.emconta.associados.web.AssociadosApi.PaginaHistoricoResponse;
import br.com.emconta.associados.web.AssociadosApi.RegistroHistoricoResponse;
import br.com.emconta.associados.web.AssociadosApi.UcResponse;
import br.com.emconta.associados.web.AssociadosApi.VersaoRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
 * Associados, contatos, UCs e histórico. As permissões de cada rota são exigidas na
 * SecurityConfiguration do módulo de acesso.
 */
@RestController
@RequestMapping(path = "/api/v1/associados", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Associados", description = "Associados PF/PJ, contatos e unidades consumidoras")
class AssociadosController {

    private final CadastroAssociados cadastro;
    private final ConsultaAssociados consulta;
    private final HistoricoCadastro historico;
    private final Autoria autoria;

    AssociadosController(
            CadastroAssociados cadastro, ConsultaAssociados consulta, HistoricoCadastro historico, Autoria autoria) {
        this.cadastro = cadastro;
        this.consulta = consulta;
        this.historico = historico;
        this.autoria = autoria;
    }

    @GetMapping
    @Operation(
            operationId = "listarAssociados",
            summary = "Lista paginada; busca por nome, razão social, nome fantasia ou CPF/CNPJ (associados:consultar)")
    PaginaAssociadosResponse listar(
            @RequestParam(required = false) @Size(max = 100) String busca,
            @RequestParam(required = false) TipoPessoa tipo,
            @RequestParam(defaultValue = "NOME") Ordenacao ordenacao,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConsultaAssociados.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        return PaginaAssociadosResponse.de(
                consulta.listar(new ConsultaAssociados.Filtro(busca, tipo, ordenacao, pagina, tamanho)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "cadastrarAssociado",
            summary = "Cadastra associado PF ou PJ e contatos (associados:cadastrar)")
    @ApiResponse(responseCode = "201", description = "Associado cadastrado")
    @ApiResponse(
            responseCode = "409",
            description = "DOCUMENTO_JA_CADASTRADO, com associadoExistenteId quando localizado")
    ResponseEntity<AssociadoResponse> cadastrar(@Valid @RequestBody NovoAssociadoRequest corpo) {
        List<ContatoRequest> contatos = corpo.contatos() == null ? List.of() : corpo.contatos();
        Ficha ficha = cadastro.cadastrar(
                autoria.usuarioAtual(),
                new CadastroAssociados.NovoAssociado(
                        corpo.tipo(),
                        corpo.documento(),
                        corpo.nome(),
                        corpo.nomeFantasia(),
                        contatos.stream().map(ContatoRequest::dados).toList()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(ficha));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "obterAssociado", summary = "Ficha cadastral com contatos (associados:consultar)")
    AssociadoResponse obter(@PathVariable long id) {
        return resposta(cadastro.obter(id));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "editarAssociado",
            summary = "Edita nome/razão social e nome fantasia; tipo e documento não mudam (associados:editar)")
    @ApiResponse(responseCode = "200", description = "Dados atualizados, com a nova versão")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE: versão desatualizada")
    AssociadoResponse editar(@PathVariable long id, @Valid @RequestBody EdicaoAssociadoRequest corpo) {
        return resposta(
                cadastro.alterar(autoria.usuarioAtual(), id, corpo.versao(), corpo.nome(), corpo.nomeFantasia()));
    }

    @PostMapping(path = "/{id}/contatos", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "adicionarContato", summary = "Adiciona contato (associados:editar)")
    @ApiResponse(responseCode = "201", description = "Contato adicionado")
    ResponseEntity<ContatoResponse> adicionarContato(@PathVariable long id, @Valid @RequestBody ContatoRequest corpo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ContatoResponse.de(cadastro.adicionarContato(autoria.usuarioAtual(), id, corpo.dados())));
    }

    @PutMapping(path = "/{id}/contatos/{contatoId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editarContato", summary = "Edita contato (associados:editar)")
    @ApiResponse(responseCode = "200", description = "Dados atualizados, com a nova versão")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE: versão desatualizada")
    ContatoResponse editarContato(
            @PathVariable long id, @PathVariable long contatoId, @Valid @RequestBody EdicaoContatoRequest corpo) {
        return ContatoResponse.de(cadastro.alterarContato(
                autoria.usuarioAtual(),
                id,
                contatoId,
                corpo.versao(),
                new DadosContato(corpo.nome(), corpo.email(), corpo.telefone())));
    }

    @PostMapping(path = "/{id}/contatos/{contatoId}/remocao", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "removerContato",
            summary = "Remove o contato da ficha; o registro é mantido para o histórico (associados:editar)")
    @ApiResponse(responseCode = "204", description = "Contato removido")
    ResponseEntity<Void> removerContato(
            @PathVariable long id, @PathVariable long contatoId, @Valid @RequestBody VersaoRequest corpo) {
        cadastro.removerContato(autoria.usuarioAtual(), id, contatoId, corpo.versao());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/ucs")
    @Operation(operationId = "listarUcs", summary = "UCs do associado (ucs:consultar)")
    List<UcResponse> ucs(@PathVariable long id) {
        return cadastro.ucs(id).stream().map(UcResponse::de).toList();
    }

    @GetMapping("/{id}/ucs/{ucId}")
    @Operation(operationId = "obterUc", summary = "Dados de uma UC do associado (ucs:consultar)")
    UcResponse uc(@PathVariable long id, @PathVariable long ucId) {
        return UcResponse.de(cadastro.uc(id, ucId));
    }

    @PostMapping(path = "/{id}/ucs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cadastrarUc", summary = "Cadastra UC vinculada ao associado (ucs:cadastrar)")
    @ApiResponse(responseCode = "201", description = "UC cadastrada")
    @ApiResponse(responseCode = "409", description = "UC_JA_CADASTRADA na mesma concessionária")
    ResponseEntity<UcResponse> cadastrarUc(@PathVariable long id, @Valid @RequestBody NovaUcRequest corpo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UcResponse.de(cadastro.cadastrarUc(
                        autoria.usuarioAtual(),
                        id,
                        corpo.concessionaria(),
                        corpo.numero(),
                        corpo.denominacao(),
                        corpo.endereco())));
    }

    @PutMapping(path = "/{id}/ucs/{ucId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "editarUc",
            summary = "Edita denominação e endereço; concessionária, número e titular não mudam (ucs:editar)")
    @ApiResponse(responseCode = "200", description = "Dados atualizados, com a nova versão")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE: versão desatualizada")
    UcResponse editarUc(@PathVariable long id, @PathVariable long ucId, @Valid @RequestBody EdicaoUcRequest corpo) {
        return UcResponse.de(cadastro.alterarUc(
                autoria.usuarioAtual(), id, ucId, corpo.versao(), corpo.denominacao(), corpo.endereco()));
    }

    @GetMapping("/{id}/historico")
    @Operation(
            operationId = "consultarHistorico",
            summary = "Histórico do associado, contatos e UCs, mais recente primeiro (associados:consultar-historico)")
    PaginaHistoricoResponse historico(
            @PathVariable long id,
            @RequestParam(required = false) CategoriaHistorico categoria,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int tamanho) {
        cadastro.obter(id); // 404 para associado inexistente
        Page<HistoricoCadastro.Registro> registros = historico.consultar(id, categoria, pagina, tamanho);
        Set<Long> autores = new HashSet<>();
        registros.forEach(registro -> autores.add(registro.autorId()));
        Map<Long, String> nomes = autoria.nomes(autores);
        return new PaginaHistoricoResponse(
                registros.getContent().stream()
                        .map(registro -> new RegistroHistoricoResponse(
                                registro.id(),
                                registro.ocorridoEm(),
                                registro.acao(),
                                registro.acao().categoria(),
                                registro.ucId(),
                                registro.contatoId(),
                                registro.referencia(),
                                autor(registro.autorId(), nomes),
                                registro.alteracoes().stream()
                                        .map(AlteracaoResponse::de)
                                        .toList()))
                        .toList(),
                registros.getNumber(),
                registros.getSize(),
                registros.getTotalElements(),
                registros.getTotalPages());
    }

    private AssociadoResponse resposta(Ficha ficha) {
        var associado = ficha.associado();
        Map<Long, String> nomes = autoria.nomes(List.of(associado.getCriadoPor(), associado.getAtualizadoPor()));
        return new AssociadoResponse(
                associado.getId(),
                associado.getTipo(),
                Documentos.formatar(associado.getTipo(), associado.getDocumento()),
                associado.getNome(),
                associado.getNomeFantasia(),
                ficha.contatos().stream().map(ContatoResponse::de).toList(),
                ficha.quantidadeUcs(),
                associado.getCriadoEm(),
                autor(associado.getCriadoPor(), nomes),
                associado.getAtualizadoEm(),
                autor(associado.getAtualizadoPor(), nomes),
                associado.getVersao());
    }

    private static AutorResponse autor(long id, Map<Long, String> nomes) {
        return new AutorResponse(id, nomes.getOrDefault(id, "Usuário #" + id));
    }
}
