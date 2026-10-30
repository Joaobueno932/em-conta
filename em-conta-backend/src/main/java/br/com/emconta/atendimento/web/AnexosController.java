package br.com.emconta.atendimento.web;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.atendimento.nucleo.Anexos;
import br.com.emconta.atendimento.nucleo.Anexos.AnexoLeitura;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento;
import br.com.emconta.atendimento.nucleo.FalhaAtendimento;
import br.com.emconta.atendimento.web.AtendimentoApi.AnexoResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.ItemMidiaResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.PaginaMidiaResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.RespostaResponse;
import br.com.emconta.atendimento.web.AtendimentoApi.SituacaoResponse;
import br.com.emconta.integracoes.ArmazenamentoObjetos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Anexos do atendimento: envio, conteúdo, área Mídia e situações de mensagens já carregadas. As
 * permissões de cada rota são exigidas na SecurityConfiguration; a relação anexo ↔ atendimento e as
 * regras de responsável e janela, no serviço.
 */
@RestController
@RequestMapping(path = "/api/v1/atendimento/atendimentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Atendimento", description = "Conversas do WhatsApp, fila e histórico")
class AnexosController {

    private static final java.util.regex.Pattern FAIXA =
            java.util.regex.Pattern.compile("bytes=(\\d{1,15})-(\\d{0,15})");

    private final Anexos anexos;
    private final ConsultaAtendimento consulta;
    private final Autoria autoria;

    AnexosController(Anexos anexos, ConsultaAtendimento consulta, Autoria autoria) {
        this.anexos = anexos;
        this.consulta = consulta;
        this.autoria = autoria;
    }

    @PostMapping(path = "/anexos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "enviarAnexoAtendimento",
            summary = "Registra um anexo (imagem, PDF ou áudio) para envio assíncrono (atendimento:responder)")
    @ApiResponse(responseCode = "202", description = "Registrado como PENDENTE; upload à Meta e envio vêm depois")
    @ApiResponse(responseCode = "400", description = "DADOS_INVALIDOS (tipo, extensão, conteúdo, legenda ou chave)")
    @ApiResponse(
            responseCode = "409",
            description = "NAO_E_RESPONSAVEL, JANELA_ENCERRADA ou CHAVE_IDEMPOTENCIA_REUTILIZADA")
    @ApiResponse(responseCode = "413", description = "ARQUIVO_MUITO_GRANDE")
    @ApiResponse(
            responseCode = "503",
            description = "CANAL_NAO_CONFIGURADO, ARMAZENAMENTO_NAO_CONFIGURADO ou ARMAZENAMENTO_INDISPONIVEL")
    ResponseEntity<RespostaResponse> enviar(
            @PathVariable long id,
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestPart(name = "legenda", required = false) String legenda,
            @RequestPart("chaveIdempotencia") String chaveIdempotencia)
            throws IOException {
        long mensagem = anexos.enviar(
                autoria.usuarioAtual(),
                id,
                new Anexos.ArquivoEnviado(
                        arquivo.getOriginalFilename(), arquivo.getContentType(), arquivo.getInputStream()),
                legenda,
                chaveIdempotencia);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RespostaResponse(mensagem));
    }

    /**
     * Conteúdo do anexo, em streaming do armazenamento privado. Aceita uma faixa ({@code Range:
     * bytes=inicio-fim}) para reprodução de áudio. Cabeçalhos impedem interpretação do conteúdo como outra
     * coisa: tipo validado, {@code nosniff}, CSP restritiva e nome higienizado.
     */
    @GetMapping(path = "/anexos/{anexo}/conteudo", produces = MediaType.ALL_VALUE)
    @Operation(
            operationId = "conteudoAnexoAtendimento",
            summary = "Conteúdo de um anexo da conversa (ou só deste atendimento) (atendimento:consultar)")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Faixa pedida em Range")
    @ApiResponse(responseCode = "404", description = "ANEXO_NAO_ENCONTRADO")
    void conteudo(
            @PathVariable long id,
            @PathVariable long anexo,
            @RequestParam(defaultValue = "false") boolean somenteEste,
            @RequestParam(defaultValue = "false") boolean download,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String faixa,
            HttpServletResponse resposta)
            throws IOException {
        AnexoLeitura leitura = anexos.paraLeitura(id, anexo, somenteEste).orElseThrow(AnexosController::naoEncontrado);
        String faixaValida = faixa == null || !FAIXA.matcher(faixa.strip()).matches() ? null : faixa.strip();
        Optional<ArmazenamentoObjetos.Objeto> objeto;
        try {
            objeto = anexos.abrir(leitura, faixaValida);
        } catch (ArmazenamentoObjetos.FaixaInvalida e) {
            resposta.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            resposta.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + leitura.tamanho());
            return;
        }
        try (ArmazenamentoObjetos.Objeto o = objeto.orElseThrow(AnexosController::naoEncontrado)) {
            resposta.setStatus(o.parcial() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
            resposta.setContentType(leitura.mime());
            if (o.tamanho() >= 0) {
                resposta.setContentLengthLong(o.tamanho());
            }
            if (o.faixa() != null) {
                resposta.setHeader(HttpHeaders.CONTENT_RANGE, o.faixa());
            }
            resposta.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            resposta.setHeader("X-Content-Type-Options", "nosniff");
            resposta.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            // PDF precisa do visualizador do navegador; os demais tipos ficam isolados (sandbox).
            resposta.setHeader(
                    "Content-Security-Policy",
                    "application/pdf".equals(leitura.mime())
                            ? "default-src 'none'; object-src 'self'; frame-ancestors 'self'"
                            : "default-src 'none'; img-src 'self'; media-src 'self'; sandbox; frame-ancestors 'self'");
            String nome = leitura.nome() != null ? leitura.nome() : "anexo-" + leitura.id();
            resposta.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                            .filename(nome, StandardCharsets.UTF_8)
                            .build()
                            .toString());
            try (InputStream entrada = o.conteudo();
                    OutputStream saida = resposta.getOutputStream()) {
                entrada.transferTo(saida);
            }
        }
    }

    @GetMapping("/midia")
    @Operation(
            operationId = "midiaAtendimento",
            summary =
                    "Anexos da conversa (ou só deste atendimento), do mais recente, por cursor (atendimento:consultar)")
    PaginaMidiaResponse midia(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean somenteEste,
            @Parameter(schema = @Schema(allowableValues = {"IMAGEM", "DOCUMENTO", "AUDIO"}))
                    @RequestParam(required = false)
                    @Pattern(regexp = "IMAGEM|DOCUMENTO|AUDIO")
                    String categoria,
            @RequestParam(required = false) @Min(1) Long antes,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int tamanho) {
        var detalhe = consulta.detalhe(id).orElseThrow(NaoEncontrado::new);
        var pagina = consulta.midia(detalhe.resumo().conversaId(), somenteEste ? id : null, categoria, antes, tamanho);
        return new PaginaMidiaResponse(
                pagina.itens().stream()
                        .map(i -> new ItemMidiaResponse(
                                i.id(),
                                i.mensagemId(),
                                i.atendimentoId(),
                                i.direcao(),
                                i.categoria(),
                                i.mime(),
                                i.nome(),
                                i.tamanho(),
                                i.situacao(),
                                i.registradoEm(),
                                i.texto(),
                                i.cursorMensagem()))
                        .toList(),
                pagina.anterior());
    }

    @GetMapping("/linha-do-tempo/situacoes")
    @Operation(
            operationId = "situacoesMensagens",
            summary = "Situação atual de mensagens já carregadas, até 100 por consulta (atendimento:consultar)")
    List<SituacaoResponse> situacoes(
            @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean somenteEste,
            @RequestParam @Size(min = 1, max = ConsultaAtendimento.MAXIMO_SITUACOES) List<Long> ids) {
        var detalhe = consulta.detalhe(id).orElseThrow(NaoEncontrado::new);
        return consulta.situacoes(detalhe.resumo().conversaId(), somenteEste ? id : null, ids).stream()
                .map(s -> new SituacaoResponse(
                        s.id(),
                        s.situacao(),
                        s.erroCodigo(),
                        s.erroTitulo(),
                        s.etapaFalha(),
                        s.atualizadoEm(),
                        AnexoResponse.de(s.anexo())))
                .toList();
    }

    @PostMapping("/anexos/{anexo}/nova-tentativa")
    @Operation(
            operationId = "novaTentativaAnexo",
            summary = "Baixa de novo um anexo recebido que falhou; só o responsável (atendimento:responder)")
    @ApiResponse(responseCode = "204", description = "Voltou para a fila de download")
    @ApiResponse(responseCode = "409", description = "NAO_E_RESPONSAVEL ou NOVA_TENTATIVA_INDISPONIVEL")
    ResponseEntity<Void> novaTentativa(@PathVariable long id, @PathVariable long anexo) {
        anexos.novaTentativaDownload(autoria.usuarioAtual(), id, anexo);
        return ResponseEntity.noContent().build();
    }

    private static FalhaAtendimento naoEncontrado() {
        return FalhaAtendimento.anexoNaoEncontrado();
    }
}
