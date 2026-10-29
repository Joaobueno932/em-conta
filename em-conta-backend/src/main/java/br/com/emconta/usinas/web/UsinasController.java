package br.com.emconta.usinas.web;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.associados.UnidadesConsumidoras;
import br.com.emconta.associados.UnidadesConsumidoras.ResumoUc;
import br.com.emconta.usinas.cadastro.CadastroUsinas;
import br.com.emconta.usinas.cadastro.ConsultaUsinas;
import br.com.emconta.usinas.cadastro.ConsultaUsinas.Ordenacao;
import br.com.emconta.usinas.cadastro.ConsultaUsinas.SituacaoVinculo;
import br.com.emconta.usinas.cadastro.Fazenda;
import br.com.emconta.usinas.cadastro.HistoricoUsinas;
import br.com.emconta.usinas.cadastro.Usina;
import br.com.emconta.usinas.cadastro.VinculoUc;
import br.com.emconta.usinas.cadastro.VinculosUsina;
import br.com.emconta.usinas.web.UsinasApi.AlteracaoResponse;
import br.com.emconta.usinas.web.UsinasApi.AutorResponse;
import br.com.emconta.usinas.web.UsinasApi.EdicaoFazendaRequest;
import br.com.emconta.usinas.web.UsinasApi.EdicaoUsinaRequest;
import br.com.emconta.usinas.web.UsinasApi.EncerramentoVinculoRequest;
import br.com.emconta.usinas.web.UsinasApi.FazendaRequest;
import br.com.emconta.usinas.web.UsinasApi.FazendaResponse;
import br.com.emconta.usinas.web.UsinasApi.FazendaResumoResponse;
import br.com.emconta.usinas.web.UsinasApi.NovoVinculoRequest;
import br.com.emconta.usinas.web.UsinasApi.PaginaFazendasResponse;
import br.com.emconta.usinas.web.UsinasApi.PaginaHistoricoResponse;
import br.com.emconta.usinas.web.UsinasApi.PaginaUsinasResponse;
import br.com.emconta.usinas.web.UsinasApi.PaginaVinculosResponse;
import br.com.emconta.usinas.web.UsinasApi.ReferenciaResponse;
import br.com.emconta.usinas.web.UsinasApi.RegistroHistoricoResponse;
import br.com.emconta.usinas.web.UsinasApi.UcCandidataResponse;
import br.com.emconta.usinas.web.UsinasApi.UcResponse;
import br.com.emconta.usinas.web.UsinasApi.UsinaRequest;
import br.com.emconta.usinas.web.UsinasApi.UsinaResponse;
import br.com.emconta.usinas.web.UsinasApi.UsinaResumoResponse;
import br.com.emconta.usinas.web.UsinasApi.VinculoResponse;
import br.com.emconta.usinas.web.UsinasApi.VinculoVigenteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * Fazendas, usinas e vínculos cadastrais UC → usina. As permissões de cada rota são exigidas na
 * SecurityConfiguration do módulo de acesso. Vínculo cadastral não representa rateio, ativação nem
 * aptidão de faturamento.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Usinas", description = "Fazendas, usinas e vínculos cadastrais com UCs")
class UsinasController {

    private final CadastroUsinas cadastro;
    private final ConsultaUsinas consulta;
    private final VinculosUsina vinculos;
    private final HistoricoUsinas historico;
    private final UnidadesConsumidoras ucs;
    private final Autoria autoria;

    UsinasController(
            CadastroUsinas cadastro,
            ConsultaUsinas consulta,
            VinculosUsina vinculos,
            HistoricoUsinas historico,
            UnidadesConsumidoras ucs,
            Autoria autoria) {
        this.cadastro = cadastro;
        this.consulta = consulta;
        this.vinculos = vinculos;
        this.historico = historico;
        this.ucs = ucs;
        this.autoria = autoria;
    }

    // ------------------------------------------------------------------ fazendas

    @GetMapping("/fazendas")
    @Operation(
            operationId = "listarFazendas",
            summary = "Lista paginada; busca por nome ou código FZD (usinas:consultar)")
    PaginaFazendasResponse fazendas(
            @RequestParam(required = false) @Size(max = 100) String busca,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConsultaUsinas.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        Page<ConsultaUsinas.ResumoFazenda> page = consulta.fazendas(busca, pagina, tamanho);
        return new PaginaFazendasResponse(
                page.getContent().stream()
                        .map(f -> new FazendaResumoResponse(
                                f.id(), Fazenda.codigo(f.id()), f.nome(), f.cidade(), f.uf(), f.usinas()))
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @PostMapping(path = "/fazendas", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cadastrarFazenda", summary = "Cadastra fazenda (usinas:cadastrar)")
    @ApiResponse(responseCode = "201", description = "Fazenda cadastrada")
    @ApiResponse(responseCode = "409", description = "NOME_POSSIVEL_DUPLICIDADE (com existentes); reenviar confirmando")
    ResponseEntity<FazendaResponse> cadastrarFazenda(@Valid @RequestBody FazendaRequest corpo) {
        Fazenda fazenda = cadastro.cadastrarFazenda(
                autoria.usuarioAtual(), corpo.dados(), Boolean.TRUE.equals(corpo.confirmarNomeRepetido()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(fazenda));
    }

    @GetMapping("/fazendas/{id}")
    @Operation(operationId = "obterFazenda", summary = "Dados da fazenda (usinas:consultar)")
    FazendaResponse fazenda(@PathVariable long id) {
        return resposta(cadastro.fazenda(id));
    }

    @PutMapping(path = "/fazendas/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editarFazenda", summary = "Edita a fazenda (usinas:editar)")
    @ApiResponse(responseCode = "200", description = "Fazenda atualizada, com a nova versão")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE ou NOME_POSSIVEL_DUPLICIDADE")
    FazendaResponse editarFazenda(@PathVariable long id, @Valid @RequestBody EdicaoFazendaRequest corpo) {
        return resposta(cadastro.alterarFazenda(
                autoria.usuarioAtual(),
                id,
                corpo.versao(),
                corpo.dados(),
                Boolean.TRUE.equals(corpo.confirmarNomeRepetido())));
    }

    @GetMapping("/fazendas/{id}/historico")
    @Operation(operationId = "historicoFazenda", summary = "Histórico da fazenda (usinas:consultar-historico)")
    PaginaHistoricoResponse historicoFazenda(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int tamanho) {
        cadastro.fazenda(id);
        return historico(historico.daFazenda(id, pagina, tamanho));
    }

    // ------------------------------------------------------------------ usinas

    @GetMapping("/usinas")
    @Operation(
            operationId = "listarUsinas",
            summary = "Lista paginada; busca por nome, fazenda ou código USN; filtros fazenda e UF (usinas:consultar)")
    PaginaUsinasResponse usinas(
            @RequestParam(required = false) @Size(max = 100) String busca,
            @RequestParam(required = false) Long fazendaId,
            @RequestParam(required = false) @Size(max = 2) String uf,
            @RequestParam(defaultValue = "NOME") Ordenacao ordenacao,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConsultaUsinas.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        Page<ConsultaUsinas.ResumoUsina> page = consulta.usinas(new ConsultaUsinas.FiltroUsinas(
                busca, fazendaId, uf == null || uf.isBlank() ? null : uf, ordenacao, pagina, tamanho));
        return new PaginaUsinasResponse(
                page.getContent().stream()
                        .map(u -> new UsinaResumoResponse(
                                u.id(),
                                Usina.codigo(u.id()),
                                u.nome(),
                                u.fazendaNome(),
                                u.cidade(),
                                u.uf(),
                                u.ucsVinculadas(),
                                u.associadosDistintos(),
                                u.criadoEm()))
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @PostMapping(path = "/usinas", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cadastrarUsina", summary = "Cadastra usina (usinas:cadastrar)")
    @ApiResponse(responseCode = "201", description = "Usina cadastrada")
    @ApiResponse(responseCode = "409", description = "NOME_POSSIVEL_DUPLICIDADE (com existentes); reenviar confirmando")
    ResponseEntity<UsinaResponse> cadastrarUsina(@Valid @RequestBody UsinaRequest corpo) {
        Usina usina = cadastro.cadastrarUsina(
                autoria.usuarioAtual(), corpo.dados(), Boolean.TRUE.equals(corpo.confirmarNomeRepetido()));
        return ResponseEntity.status(HttpStatus.CREATED).body(resposta(usina));
    }

    @GetMapping("/usinas/{id}")
    @Operation(operationId = "obterUsina", summary = "Dados e contagens de vínculos da usina (usinas:consultar)")
    UsinaResponse usina(@PathVariable long id) {
        return resposta(cadastro.usina(id));
    }

    @PutMapping(path = "/usinas/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editarUsina", summary = "Edita a usina (usinas:editar)")
    @ApiResponse(responseCode = "200", description = "Usina atualizada, com a nova versão")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE ou NOME_POSSIVEL_DUPLICIDADE")
    UsinaResponse editarUsina(@PathVariable long id, @Valid @RequestBody EdicaoUsinaRequest corpo) {
        return resposta(cadastro.alterarUsina(
                autoria.usuarioAtual(),
                id,
                corpo.versao(),
                corpo.dados(),
                Boolean.TRUE.equals(corpo.confirmarNomeRepetido())));
    }

    @GetMapping("/usinas/{id}/historico")
    @Operation(
            operationId = "historicoUsina",
            summary = "Histórico da usina e dos seus vínculos (usinas:consultar-historico)")
    PaginaHistoricoResponse historicoUsina(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int tamanho) {
        cadastro.usina(id);
        return historico(historico.daUsina(id, pagina, tamanho));
    }

    // ------------------------------------------------------------------ vínculos

    @GetMapping("/usinas/{id}/vinculos")
    @Operation(
            operationId = "listarVinculosUsina",
            summary = "Vínculos da usina, vigentes primeiro (vinculos-usina:consultar)")
    PaginaVinculosResponse vinculosDaUsina(
            @PathVariable long id,
            @RequestParam(defaultValue = "VIGENTES") SituacaoVinculo situacao,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConsultaUsinas.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        Usina usina = cadastro.usina(id);
        Page<ConsultaUsinas.LinhaVinculo> page = consulta.vinculos(id, situacao, pagina, tamanho);
        Map<Long, ResumoUc> porUc = indexar(ucs.porIds(page.getContent().stream()
                .map(ConsultaUsinas.LinhaVinculo::ucId)
                .toList()));
        Set<Long> autores = new HashSet<>();
        page.forEach(v -> {
            autores.add(v.criadoPor());
            if (v.encerradoPor() != null) {
                autores.add(v.encerradoPor());
            }
        });
        Map<Long, String> nomes = autoria.nomes(autores);
        ReferenciaResponse referencia = new ReferenciaResponse(id, usina.codigo(), usina.getNome());
        return new PaginaVinculosResponse(
                page.getContent().stream()
                        .map(v -> new VinculoResponse(
                                v.id(),
                                referencia,
                                UcResponse.de(porUc.get(v.ucId())),
                                v.inicio(),
                                v.fim(),
                                v.fim() == null,
                                v.motivoEncerramento(),
                                v.criadoEm(),
                                autor(v.criadoPor(), nomes),
                                v.encerradoEm(),
                                v.encerradoPor() == null ? null : autor(v.encerradoPor(), nomes),
                                v.versao()))
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @GetMapping("/vinculos-usina/ucs-candidatas")
    @Operation(
            operationId = "buscarUcsParaVinculo",
            summary = "Busca UCs existentes por número, titular ou CPF/CNPJ, com o vínculo vigente de cada uma"
                    + " (vinculos-usina:criar)")
    List<UcCandidataResponse> ucsCandidatas(
            @RequestParam @Size(min = 2, max = 100) String busca,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limite) {
        var candidatas = vinculos.candidatas(busca, limite);
        Map<Long, ConsultaUsinas.ReferenciaUsina> usinas = consulta.referencias(candidatas.values().stream()
                .flatMap(java.util.Optional::stream)
                .map(VinculoUc::getUsinaId)
                .collect(Collectors.toSet()));
        return candidatas.entrySet().stream()
                .map(e -> new UcCandidataResponse(
                        UcResponse.de(e.getKey()),
                        e.getValue()
                                .map(v -> new VinculoVigenteResponse(
                                        v.getId(), referencia(usinas.get(v.getUsinaId())), v.getInicio()))
                                .orElse(null)))
                .toList();
    }

    @PostMapping(path = "/usinas/{id}/vinculos", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "vincularUc", summary = "Vincula uma UC existente à usina (vinculos-usina:criar)")
    @ApiResponse(responseCode = "201", description = "Vínculo cadastral criado")
    @ApiResponse(
            responseCode = "409",
            description = "UC_VINCULADA_A_OUTRA_USINA, VINCULO_JA_EXISTE ou UC_JA_VINCULADA, com o vínculo atual")
    ResponseEntity<VinculoResponse> vincular(@PathVariable long id, @Valid @RequestBody NovoVinculoRequest corpo) {
        VinculoUc vinculo = vinculos.criar(autoria.usuarioAtual(), id, corpo.ucId(), corpo.inicio());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(respostas(List.of(vinculo)).getFirst());
    }

    @PostMapping(path = "/usinas/{id}/vinculos/{vinculoId}/encerramento", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "encerrarVinculo",
            summary = "Encerra o vínculo com data e motivo; o registro é mantido (vinculos-usina:encerrar)")
    @ApiResponse(responseCode = "200", description = "Vínculo encerrado")
    @ApiResponse(responseCode = "409", description = "CONFLITO_CONCORRENTE ou VINCULO_JA_ENCERRADO")
    VinculoResponse encerrar(
            @PathVariable long id, @PathVariable long vinculoId, @Valid @RequestBody EncerramentoVinculoRequest corpo) {
        VinculoUc vinculo =
                vinculos.encerrar(autoria.usuarioAtual(), id, vinculoId, corpo.versao(), corpo.fim(), corpo.motivo());
        return respostas(List.of(vinculo)).getFirst();
    }

    @GetMapping("/associados/{associadoId}/vinculos-usina")
    @Operation(
            operationId = "vinculosUsinaDoAssociado",
            summary = "Vínculos (vigentes e encerrados) das UCs do associado (vinculos-usina:consultar)")
    List<VinculoResponse> vinculosDoAssociado(@PathVariable long associadoId) {
        return respostas(vinculos.doAssociado(associadoId));
    }

    // ------------------------------------------------------------------ apoio

    private List<VinculoResponse> respostas(List<VinculoUc> lista) {
        Map<Long, ResumoUc> porUc =
                indexar(ucs.porIds(lista.stream().map(VinculoUc::getUcId).toList()));
        Map<Long, ConsultaUsinas.ReferenciaUsina> usinas =
                consulta.referencias(lista.stream().map(VinculoUc::getUsinaId).collect(Collectors.toSet()));
        Set<Long> autores = new HashSet<>();
        lista.forEach(v -> {
            autores.add(v.getCriadoPor());
            if (v.getEncerradoPor() != null) {
                autores.add(v.getEncerradoPor());
            }
        });
        Map<Long, String> nomes = autoria.nomes(autores);
        return lista.stream()
                .map(v -> new VinculoResponse(
                        v.getId(),
                        referencia(usinas.get(v.getUsinaId())),
                        UcResponse.de(porUc.get(v.getUcId())),
                        v.getInicio(),
                        v.getFim(),
                        v.vigente(),
                        v.getMotivoEncerramento(),
                        v.getCriadoEm(),
                        autor(v.getCriadoPor(), nomes),
                        v.getEncerradoEm(),
                        v.getEncerradoPor() == null ? null : autor(v.getEncerradoPor(), nomes),
                        v.getVersao()))
                .toList();
    }

    private static Map<Long, ResumoUc> indexar(Collection<ResumoUc> lista) {
        return lista.stream().collect(Collectors.toMap(ResumoUc::id, Function.identity()));
    }

    private static ReferenciaResponse referencia(ConsultaUsinas.ReferenciaUsina usina) {
        return new ReferenciaResponse(usina.id(), Usina.codigo(usina.id()), usina.nome());
    }

    private FazendaResponse resposta(Fazenda f) {
        Map<Long, String> nomes = autoria.nomes(List.of(f.getCriadoPor(), f.getAtualizadoPor()));
        return new FazendaResponse(
                f.getId(),
                f.codigo(),
                f.getNome(),
                f.getCidade(),
                f.getUf(),
                f.getObservacoes(),
                f.getCriadoEm(),
                autor(f.getCriadoPor(), nomes),
                f.getAtualizadoEm(),
                autor(f.getAtualizadoPor(), nomes),
                f.getVersao());
    }

    private UsinaResponse resposta(Usina u) {
        Map<Long, String> nomes = autoria.nomes(List.of(u.getCriadoPor(), u.getAtualizadoPor()));
        ReferenciaResponse fazenda = null;
        if (u.getFazendaId() != null) {
            Fazenda f = cadastro.fazenda(u.getFazendaId());
            fazenda = new ReferenciaResponse(f.getId(), f.codigo(), f.getNome());
        }
        ConsultaUsinas.Contagens contagens = consulta.contagens(u.getId());
        return new UsinaResponse(
                u.getId(),
                u.codigo(),
                u.getNome(),
                fazenda,
                u.getCep() == null
                        ? null
                        : u.getCep().substring(0, 5) + "-" + u.getCep().substring(5),
                u.getEndereco(),
                u.getCidade(),
                u.getUf(),
                u.getObservacoes(),
                contagens.ucsVinculadas(),
                contagens.associadosDistintos(),
                contagens.vinculosEncerrados(),
                u.getCriadoEm(),
                autor(u.getCriadoPor(), nomes),
                u.getAtualizadoEm(),
                autor(u.getAtualizadoPor(), nomes),
                u.getVersao());
    }

    private PaginaHistoricoResponse historico(Page<HistoricoUsinas.Registro> page) {
        Set<Long> autores = new HashSet<>();
        page.forEach(r -> autores.add(r.autorId()));
        Map<Long, String> nomes = autoria.nomes(autores);
        return new PaginaHistoricoResponse(
                page.getContent().stream()
                        .map(r -> new RegistroHistoricoResponse(
                                r.id(),
                                r.ocorridoEm(),
                                r.acao(),
                                r.vinculoId(),
                                r.referencia(),
                                r.motivo(),
                                autor(r.autorId(), nomes),
                                r.alteracoes().stream()
                                        .map(AlteracaoResponse::de)
                                        .toList()))
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private static AutorResponse autor(long id, Map<Long, String> nomes) {
        return new AutorResponse(id, nomes.getOrDefault(id, "Usuário #" + id));
    }
}
