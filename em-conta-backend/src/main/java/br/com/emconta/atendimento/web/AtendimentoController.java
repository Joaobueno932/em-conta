package br.com.emconta.atendimento.web;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.associados.CatalogoAssociados;
import br.com.emconta.associados.CatalogoAssociados.ResumoAssociado;
import br.com.emconta.atendimento.nucleo.Atendimentos;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.Cursor;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.Resumo;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.Visao;
import br.com.emconta.atendimento.nucleo.Recebimento;
import br.com.emconta.atendimento.web.AtendimentoApi.AnexoResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.AssociadoResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.CanalResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.ContagensResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.DetalheResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.EncerramentoRequest;
import br.com.emconta.atendimento.web.AtendimentoApi.FalhaWebhookResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.ItemResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.PaginaLinhaDoTempoResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.PaginaResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.PresencaRequest;
import br.com.emconta.atendimento.web.AtendimentoApi.PresencaResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.RespostaRequest;
import br.com.emconta.atendimento.web.AtendimentoApi.RespostaResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.ResumoResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.TransferenciaRequest;
import br.com.emconta.atendimento.web.AtendimentoApi.UsuarioResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.VinculoRequest;
import br.com.emconta.integracoes.WhatsAppCloud;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
 * Atendimento pelo WhatsApp: fila, conversa, ações e histórico. As permissões de cada rota são exigidas
 * na SecurityConfiguration do módulo de acesso; regras de responsável e janela, no serviço.
 */
@RestController
@RequestMapping(path = "/api/v1/atendimento", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Atendimento", description = "Conversas do WhatsApp, fila e histórico")
class AtendimentoController {

    private final Atendimentos atendimentos;
    private final ConsultaAtendimento consulta;
    private final Recebimento recebimento;
    private final CatalogoAssociados associados;
    private final WhatsAppCloud canal;
    private final Autoria autoria;

    AtendimentoController(
            Atendimentos atendimentos,
            ConsultaAtendimento consulta,
            Recebimento recebimento,
            CatalogoAssociados associados,
            WhatsAppCloud canal,
            Autoria autoria) {
        this.atendimentos = atendimentos;
        this.consulta = consulta;
        this.recebimento = recebimento;
        this.associados = associados;
        this.canal = canal;
        this.autoria = autoria;
    }

    @GetMapping("/canal")
    @Operation(operationId = "estadoCanal", summary = "Se o canal WhatsApp está configurado (atendimento:consultar)")
    CanalResponse canal() {
        return new CanalResponse("WHATSAPP", canal.configurado());
    }

    @GetMapping("/presenca")
    @Operation(operationId = "obterPresenca", summary = "Presença manual do próprio atendente (atendimento:consultar)")
    PresencaResponse presenca() {
        return new PresencaResponse(consulta.disponivel(autoria.usuarioAtual()));
    }

    @PutMapping(path = "/presenca", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "definirPresenca",
            summary = "Marca Disponível/Ausente; sem efeito em distribuição (atendimento:consultar)")
    PresencaResponse definirPresenca(@Valid @RequestBody PresencaRequest corpo) {
        atendimentos.definirPresenca(autoria.usuarioAtual(), corpo.disponivel());
        return new PresencaResponse(corpo.disponivel());
    }

    @GetMapping("/atendimentos")
    @Operation(
            operationId = "listarAtendimentos",
            summary = "Fila paginada por visão, com busca (atendimento:consultar)")
    PaginaResponse fila(
            @RequestParam(defaultValue = "AGUARDANDO") Visao visao,
            @RequestParam(required = false) @Size(max = 100) String busca,
            @RequestParam(defaultValue = "true") boolean maisAntigos,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConsultaAtendimento.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        long ator = autoria.usuarioAtual();
        Page<Resumo> page = consulta.fila(visao, busca, ator, maisAntigos, pagina, tamanho);
        Map<Visao, Long> contagens = consulta.contagens(ator);
        return new PaginaResponse(
                resumos(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                new ContagensResponse(
                        contagens.get(Visao.AGUARDANDO), contagens.get(Visao.EM_ANDAMENTO), contagens.get(Visao.MEUS)));
    }

    @GetMapping("/historico")
    @Operation(
            operationId = "historicoAtendimentos",
            summary = "Atendimentos encerrados, com busca e período (atendimento:consultar)")
    PaginaResponse historico(
            @RequestParam(required = false) @Size(max = 100) String busca,
            @RequestParam(required = false) LocalDate de,
            @RequestParam(required = false) LocalDate ate,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ConsultaAtendimento.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        Page<Resumo> page = consulta.historico(busca, de, ate, pagina, tamanho);
        Map<Visao, Long> contagens = consulta.contagens(autoria.usuarioAtual());
        return new PaginaResponse(
                resumos(page.getContent()),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                new ContagensResponse(
                        contagens.get(Visao.AGUARDANDO), contagens.get(Visao.EM_ANDAMENTO), contagens.get(Visao.MEUS)));
    }

    @GetMapping("/atendimentos/{id}")
    @Operation(operationId = "obterAtendimento", summary = "Detalhe do atendimento (atendimento:consultar)")
    DetalheResponse detalhe(@PathVariable long id) {
        var detalhe = consulta.detalhe(id).orElseThrow(AtendimentoController::naoEncontrado);
        return new DetalheResponse(
                resumos(List.of(detalhe.resumo())).getFirst(),
                detalhe.versao(),
                detalhe.conversaVersao(),
                detalhe.ultimaEntradaEm() == null
                        ? null
                        : detalhe.ultimaEntradaEm().plusSeconds(24 * 3600),
                canal.configurado());
    }

    @GetMapping("/atendimentos/{id}/linha-do-tempo")
    @Operation(
            operationId = "linhaDoTempo",
            summary = "Página de mensagens e eventos da conversa (ou só deste atendimento), da mais recente para"
                    + " trás por cursor (atendimento:consultar)")
    @ApiResponse(responseCode = "400", description = "DADOS_INVALIDOS (cursor ou tamanho)")
    PaginaLinhaDoTempoResponse linhaDoTempo(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean somenteEste,
            @Parameter(description = "Cursor de um item: devolve os itens anteriores a ele")
                    @RequestParam(required = false)
                    @Size(max = 40)
                    String antes,
            @RequestParam(defaultValue = "50") @Min(1) @Max(ConsultaAtendimento.TAMANHO_MAXIMO_PAGINA) int tamanho) {
        Cursor cursor = Cursor.ler(antes).orElse(null);
        var detalhe = consulta.detalhe(id).orElseThrow(AtendimentoController::naoEncontrado);
        var pagina = consulta.linhaDoTempo(detalhe.resumo().conversaId(), somenteEste ? id : null, cursor, tamanho);
        var itens = pagina.itens();
        Set<Long> usuarios = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        itens.forEach(i -> {
            if (i.autorId() != null) usuarios.add(i.autorId());
            if (i.usuarioAlvoId() != null) usuarios.add(i.usuarioAlvoId());
            if (i.associadoId() != null) ids.add(i.associadoId());
        });
        Map<Long, String> nomes = autoria.nomes(usuarios);
        Map<Long, ResumoAssociado> porId = indexar(associados.porIds(ids));
        return new PaginaLinhaDoTempoResponse(
                itens.stream()
                        .map(i -> new ItemResponse(
                                i.tipo(),
                                i.id(),
                                i.cursor(),
                                i.ocorridoEm(),
                                i.atendimentoId(),
                                i.direcao(),
                                i.tipoMensagem(),
                                i.tipoExterno(),
                                i.texto(),
                                i.situacao(),
                                i.erroCodigo(),
                                i.erroTitulo(),
                                usuario(i.autorId(), nomes),
                                i.evento(),
                                usuario(i.usuarioAlvoId(), nomes),
                                associado(porId.get(i.associadoId())),
                                i.motivo(),
                                i.etapaFalha(),
                                i.atualizadoEm(),
                                AnexoResponse.de(i.anexo())))
                        .toList(),
                pagina.anterior());
    }

    @PostMapping("/atendimentos/{id}/assuncao")
    @Operation(operationId = "assumirAtendimento", summary = "Assume um atendimento aguardando (atendimento:assumir)")
    @ApiResponse(responseCode = "204", description = "Assumido")
    @ApiResponse(responseCode = "409", description = "ATENDIMENTO_JA_ASSUMIDO ou ATENDIMENTO_ENCERRADO")
    ResponseEntity<Void> assumir(@PathVariable long id) {
        atendimentos.assumir(autoria.usuarioAtual(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/atendimentos/{id}/transferencia", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "transferirAtendimento",
            summary = "Transfere para outro responsável ou devolve à fila (atendimento:transferir)")
    @ApiResponse(responseCode = "204", description = "Transferido")
    @ApiResponse(
            responseCode = "409",
            description =
                    "NAO_E_RESPONSAVEL, ATENDIMENTO_SEM_RESPONSAVEL, ATENDIMENTO_ENCERRADO ou CONFLITO_CONCORRENTE")
    ResponseEntity<Void> transferir(@PathVariable long id, @Valid @RequestBody TransferenciaRequest corpo) {
        atendimentos.transferir(autoria.usuarioAtual(), id, corpo.versao(), corpo.destinoId(), corpo.motivo());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/atendimentos/{id}/encerramento", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "encerrarAtendimento", summary = "Encerra o atendimento (atendimento:encerrar)")
    @ApiResponse(responseCode = "204", description = "Encerrado")
    @ApiResponse(
            responseCode = "409",
            description =
                    "NAO_E_RESPONSAVEL, ATENDIMENTO_SEM_RESPONSAVEL, ATENDIMENTO_ENCERRADO ou CONFLITO_CONCORRENTE")
    ResponseEntity<Void> encerrar(@PathVariable long id, @Valid @RequestBody EncerramentoRequest corpo) {
        atendimentos.encerrar(autoria.usuarioAtual(), id, corpo.versao());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/atendimentos/{id}/mensagens", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "responderAtendimento",
            summary = "Registra resposta de texto para envio assíncrono (atendimento:responder)")
    @ApiResponse(responseCode = "202", description = "Registrada como PENDENTE; envio e entrega vêm depois")
    @ApiResponse(
            responseCode = "409",
            description = "NAO_E_RESPONSAVEL, JANELA_ENCERRADA ou CHAVE_IDEMPOTENCIA_REUTILIZADA")
    @ApiResponse(responseCode = "503", description = "CANAL_NAO_CONFIGURADO")
    ResponseEntity<RespostaResponse> responder(@PathVariable long id, @Valid @RequestBody RespostaRequest corpo) {
        long mensagem = atendimentos.responder(autoria.usuarioAtual(), id, corpo.texto(), corpo.chaveIdempotencia());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RespostaResponse(mensagem));
    }

    @PutMapping(path = "/atendimentos/{id}/associado", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "vincularAssociadoAtendimento",
            summary = "Vincula, troca ou remove o associado da conversa (atendimento:vincular-associado)")
    @ApiResponse(responseCode = "204", description = "Vínculo atualizado")
    ResponseEntity<Void> vincular(@PathVariable long id, @Valid @RequestBody VinculoRequest corpo) {
        atendimentos.vincularAssociado(
                autoria.usuarioAtual(), id, corpo.versaoConversa(), corpo.associadoId(), corpo.motivo());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/atendimentos/{id}/associados-sugeridos")
    @Operation(
            operationId = "associadosSugeridos",
            summary = "Associados com contato no mesmo telefone; só sugestão (atendimento:vincular-associado)")
    List<AssociadoResponse> sugeridos(@PathVariable long id) {
        var detalhe = consulta.detalhe(id).orElseThrow(AtendimentoController::naoEncontrado);
        return associados.sugeridosPorTelefone(detalhe.resumo().waId(), 10).stream()
                .map(AtendimentoController::associado)
                .toList();
    }

    @GetMapping("/associados")
    @Operation(
            operationId = "buscarAssociadosAtendimento",
            summary = "Busca associados para vínculo (atendimento:vincular-associado)")
    List<AssociadoResponse> buscarAssociados(@RequestParam @Size(min = 2, max = 100) String busca) {
        return associados.buscar(busca, 20).stream()
                .map(AtendimentoController::associado)
                .toList();
    }

    @GetMapping("/responsaveis")
    @Operation(
            operationId = "responsaveisAtendimento",
            summary = "Usuários ativos que podem receber atendimentos (atendimento:transferir)")
    List<UsuarioResponse> responsaveis() {
        return autoria.ativosComPermissao(Atendimentos.PERMISSAO_RESPONDER).stream()
                .map(u -> new UsuarioResponse(u.id(), u.nome()))
                .toList();
    }

    @GetMapping("/webhook-falhas")
    @Operation(
            operationId = "falhasWebhook",
            summary = "Eventos do webhook em falha definitiva (atendimento:reprocessar)")
    List<FalhaWebhookResponse> falhas() {
        return recebimento.falhas(50).stream()
                .map(f -> new FalhaWebhookResponse(f.id(), f.recebidoEm(), f.tentativas(), f.erro()))
                .toList();
    }

    @PostMapping("/webhook-eventos/{id}/reprocessamento")
    @Operation(
            operationId = "reprocessarWebhook",
            summary = "Devolve um evento em falha à fila (atendimento:reprocessar)")
    @ApiResponse(responseCode = "204", description = "Evento voltou para a fila")
    ResponseEntity<Void> reprocessar(@PathVariable long id) {
        if (recebimento.reprocessar(id) == 0) {
            throw naoEncontrado();
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ apoio

    private List<ResumoResponse> resumos(List<Resumo> lista) {
        Set<Long> usuarios = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        lista.forEach(r -> {
            if (r.responsavelId() != null) usuarios.add(r.responsavelId());
            if (r.encerradoPor() != null) usuarios.add(r.encerradoPor());
            if (r.associadoId() != null) ids.add(r.associadoId());
        });
        Map<Long, String> nomes = autoria.nomes(usuarios);
        Map<Long, ResumoAssociado> porId = indexar(associados.porIds(ids));
        return lista.stream()
                .map(r -> new ResumoResponse(
                        r.id(),
                        "ATD-%06d".formatted(r.id()),
                        r.situacao(),
                        r.waId(),
                        r.nomePerfil(),
                        associado(porId.get(r.associadoId())),
                        usuario(r.responsavelId(), nomes),
                        r.iniciadoEm(),
                        r.ultimaMensagemEm(),
                        r.encerradoEm(),
                        usuario(r.encerradoPor(), nomes)))
                .toList();
    }

    private static Map<Long, ResumoAssociado> indexar(Collection<ResumoAssociado> lista) {
        return lista.stream().collect(Collectors.toMap(ResumoAssociado::id, Function.identity()));
    }

    private static UsuarioResponse usuario(Long id, Map<Long, String> nomes) {
        return id == null ? null : new UsuarioResponse(id, nomes.getOrDefault(id, "Usuário #" + id));
    }

    private static AssociadoResponse associado(ResumoAssociado a) {
        return a == null ? null : new AssociadoResponse(a.id(), a.tipo(), a.nome(), a.documentoMascarado());
    }

    private static RuntimeException naoEncontrado() {
        return new NaoEncontrado();
    }
}
