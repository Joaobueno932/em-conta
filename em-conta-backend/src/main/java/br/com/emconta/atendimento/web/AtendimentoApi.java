package br.com.emconta.atendimento.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** DTOs HTTP do atendimento. Nenhum dado do cadastro do associado além do mínimo de identificação. */
final class AtendimentoApi {

    private AtendimentoApi() {}

    @Schema(name = "CanalAtendimento", description = "Estado técnico do canal (não é a presença do atendente)")
    record CanalResponse(
            @Schema(requiredMode = REQUIRED) String canal,

            @Schema(requiredMode = REQUIRED, description = "false = Canal não configurado; nada é enviado")
            boolean configurado) {}

    @Schema(name = "PresencaAtendente", description = "Presença informada manualmente pelo atendente")
    record PresencaResponse(@Schema(requiredMode = REQUIRED) boolean disponivel) {}

    @Schema(name = "PresencaRequest")
    record PresencaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Boolean disponivel) {}

    @Schema(name = "UsuarioAtendimento")
    record UsuarioResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome) {}

    @Schema(name = "AssociadoAtendimento", description = "Identificação mínima do associado vinculado")
    record AssociadoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String tipo,
            @Schema(requiredMode = REQUIRED) String nome,
            @Schema(requiredMode = REQUIRED) String documentoMascarado) {}

    @Schema(name = "AtendimentoResumo")
    record ResumoResponse(
            @Schema(requiredMode = REQUIRED) long id,

            @Schema(requiredMode = REQUIRED, example = "ATD-000123")
            String codigo,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"AGUARDANDO", "EM_ATENDIMENTO", "ENCERRADO"})
            String situacao,

            @Schema(requiredMode = REQUIRED) String contatoNumero,
            String contatoNome,
            AssociadoResponse associado,
            UsuarioResponse responsavel,
            @Schema(requiredMode = REQUIRED) Instant iniciadoEm,
            Instant ultimaMensagemEm,
            Instant encerradoEm,
            UsuarioResponse encerradoPor) {}

    @Schema(name = "ContagensFila")
    record ContagensResponse(
            @Schema(requiredMode = REQUIRED) long aguardando,
            @Schema(requiredMode = REQUIRED) long emAndamento,
            @Schema(requiredMode = REQUIRED) long meus) {}

    @Schema(name = "PaginaAtendimentos")
    record PaginaResponse(
            @Schema(requiredMode = REQUIRED) List<ResumoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas,
            @Schema(requiredMode = REQUIRED) ContagensResponse contagens) {}

    @Schema(name = "AtendimentoDetalhe")
    record DetalheResponse(
            @Schema(requiredMode = REQUIRED) ResumoResponse resumo,
            @Schema(requiredMode = REQUIRED) long versao,
            @Schema(requiredMode = REQUIRED) long versaoConversa,

            @Schema(description = "Até quando texto livre é permitido (24 h após a última mensagem do cliente)")
            Instant janelaAbertaAte,

            @Schema(requiredMode = REQUIRED) boolean canalConfigurado) {}

    @Schema(name = "ItemLinhaDoTempo", description = "Mensagem ou evento do atendimento")
    record ItemResponse(
            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"MENSAGEM", "EVENTO"})
            String tipo,

            @Schema(requiredMode = REQUIRED) long id,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Posição do item na linha do tempo; use como \"antes\" para a página anterior")
            String cursor,

            @Schema(requiredMode = REQUIRED) Instant ocorridoEm,
            Long atendimentoId,
            @Schema(allowableValues = {"ENTRADA", "SAIDA"}) String direcao,

            @Schema(allowableValues = {"TEXTO", "IMAGEM", "DOCUMENTO", "AUDIO", "NAO_SUPORTADO"})
            String tipoMensagem,

            @Schema(description = "Tipo informado pelo canal (ex.: image, audio)")
            String tipoExterno,

            String texto,

            @Schema(
                    allowableValues = {
                        "RECEBIDA",
                        "PENDENTE",
                        "ENVIANDO",
                        "ACEITA",
                        "ENVIADA",
                        "ENTREGUE",
                        "LIDA",
                        "FALHA",
                        "DESCONHECIDA",
                        "JANELA_EXPIRADA"
                    })
            String situacao,

            Integer erroCodigo,
            String erroTitulo,
            UsuarioResponse autor,
            String evento,
            UsuarioResponse usuarioAlvo,
            AssociadoResponse associado,
            String motivo,

            @Schema(
                    allowableValues = {"UPLOAD", "ENVIO"},
                    description = "Etapa da falha de uma mensagem de saída: upload do arquivo ou envio")
            String etapaFalha,

            @Schema(description = "Última mudança da mensagem (situação ou anexo)")
            Instant atualizadoEm,

            AnexoResponse anexo) {}

    @Schema(name = "AnexoAtendimento", description = "Anexo de uma mensagem; o conteúdo só por rota autenticada")
    record AnexoResponse(
            @Schema(requiredMode = REQUIRED) long id,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"IMAGEM", "DOCUMENTO", "AUDIO"})
            String categoria,

            String mimeType,

            @Schema(description = "Nome original higienizado (só exibição)")
            String nome,

            Long tamanho,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {
                        "RECEBENDO",
                        "PENDENTE",
                        "PROCESSANDO",
                        "FALHA_TEMPORARIA",
                        "DISPONIVEL",
                        "FALHA",
                        "NAO_SUPORTADO"
                    })
            String situacao,

            String erro) {

        static AnexoResponse de(br.com.emconta.atendimento.nucleo.ConsultaAtendimento.AnexoResumo anexo) {
            return anexo == null
                    ? null
                    : new AnexoResponse(
                            anexo.id(),
                            anexo.categoria(),
                            anexo.mime(),
                            anexo.nome(),
                            anexo.tamanho(),
                            anexo.situacao(),
                            anexo.erro());
        }
    }

    @Schema(name = "SituacaoMensagem", description = "Situação atual de uma mensagem já carregada")
    record SituacaoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String situacao,
            Integer erroCodigo,
            String erroTitulo,
            String etapaFalha,
            Instant atualizadoEm,
            AnexoResponse anexo) {}

    @Schema(name = "ItemMidia", description = "Anexo listado na área Mídia")
    record ItemMidiaResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) long mensagemId,
            @Schema(requiredMode = REQUIRED) long atendimentoId,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"ENTRADA", "SAIDA"})
            String direcao,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"IMAGEM", "DOCUMENTO", "AUDIO"})
            String categoria,

            String mimeType,
            String nome,
            Long tamanho,
            @Schema(requiredMode = REQUIRED) String situacao,
            @Schema(requiredMode = REQUIRED) Instant registradoEm,
            @Schema(description = "Legenda") String legenda) {}

    @Schema(name = "PaginaMidia", description = "Página da área Mídia, do mais recente para o mais antigo")
    record PaginaMidiaResponse(
            @Schema(requiredMode = REQUIRED) List<ItemMidiaResponse> itens,

            @Schema(description = "Cursor (id) para a próxima página; ausente no fim")
            Long anterior) {}

    @Schema(name = "PaginaLinhaDoTempo", description = "Página da linha do tempo, em ordem cronológica")
    record PaginaLinhaDoTempoResponse(
            @Schema(requiredMode = REQUIRED) List<ItemResponse> itens,

            @Schema(description = "Cursor para a página anterior; ausente no início do histórico")
            String anterior) {}

    @Schema(name = "TransferenciaRequest")
    record TransferenciaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao,

            @Schema(description = "Novo responsável; vazio devolve o atendimento à fila")
            Long destinoId,

            @Size(max = 1000) String motivo) {}

    @Schema(name = "EncerramentoRequest")
    record EncerramentoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao) {}

    @Schema(name = "RespostaRequest")
    record RespostaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = 5000)
            String texto,

            @Schema(requiredMode = REQUIRED, description = "Gerada pelo cliente; repetir a requisição não duplica")
            @NotNull
            @Size(max = 64)
            String chaveIdempotencia) {}

    @Schema(name = "RespostaRegistrada")
    record RespostaResponse(@Schema(requiredMode = REQUIRED) long mensagemId) {}

    @Schema(name = "VinculoAssociadoRequest")
    record VinculoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versaoConversa,
            @Schema(description = "Vazio remove o vínculo") Long associadoId,

            @Schema(description = "Obrigatório ao alterar ou remover um vínculo existente") @Size(max = 1000)
            String motivo) {}

    @Schema(name = "FalhaWebhook")
    record FalhaWebhookResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) Instant recebidoEm,
            @Schema(requiredMode = REQUIRED) int tentativas,
            String erro) {}
}
