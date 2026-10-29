package br.com.emconta.usinas.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import br.com.emconta.associados.UnidadesConsumidoras.ResumoUc;
import br.com.emconta.usinas.cadastro.DadosFazenda;
import br.com.emconta.usinas.cadastro.DadosUsina;
import br.com.emconta.usinas.cadastro.HistoricoUsinas;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs HTTP de fazendas, usinas e vínculos. Os limites aqui só barram entradas abusivas; as regras
 * ficam no domínio, com mensagem por campo. Nenhum dado energético é exposto.
 */
final class UsinasApi {

    private UsinasApi() {}

    private static final int BRUTO = 1000;

    @Schema(name = "FazendaRequest")
    record FazendaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            @Size(max = BRUTO) String cidade,
            @Size(max = 10) String uf,
            @Size(max = BRUTO) String observacoes,

            @Schema(description = "true para salvar mesmo com aviso NOME_POSSIVEL_DUPLICIDADE")
            Boolean confirmarNomeRepetido) {

        DadosFazenda dados() {
            return new DadosFazenda(nome, cidade, uf, observacoes);
        }
    }

    @Schema(name = "EdicaoFazendaRequest")
    record EdicaoFazendaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao,

            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            @Size(max = BRUTO) String cidade,
            @Size(max = 10) String uf,
            @Size(max = BRUTO) String observacoes,
            Boolean confirmarNomeRepetido) {

        DadosFazenda dados() {
            return new DadosFazenda(nome, cidade, uf, observacoes);
        }
    }

    @Schema(name = "UsinaRequest")
    record UsinaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            Long fazendaId,
            @Size(max = 20) String cep,
            @Size(max = BRUTO) String endereco,
            @Size(max = BRUTO) String cidade,
            @Size(max = 10) String uf,
            @Size(max = BRUTO) String observacoes,

            @Schema(description = "true para salvar mesmo com aviso NOME_POSSIVEL_DUPLICIDADE")
            Boolean confirmarNomeRepetido) {

        DadosUsina dados() {
            return new DadosUsina(nome, fazendaId, cep, endereco, cidade, uf, observacoes);
        }
    }

    @Schema(name = "EdicaoUsinaRequest")
    record EdicaoUsinaRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao,

            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            Long fazendaId,
            @Size(max = 20) String cep,
            @Size(max = BRUTO) String endereco,
            @Size(max = BRUTO) String cidade,
            @Size(max = 10) String uf,
            @Size(max = BRUTO) String observacoes,
            Boolean confirmarNomeRepetido) {

        DadosUsina dados() {
            return new DadosUsina(nome, fazendaId, cep, endereco, cidade, uf, observacoes);
        }
    }

    @Schema(name = "NovoVinculoRequest")
    record NovoVinculoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long ucId,

            @Schema(requiredMode = REQUIRED, description = "Início da vigência; não pode ser futuro") @NotNull
            LocalDate inicio) {}

    @Schema(name = "EncerramentoVinculoRequest")
    record EncerramentoVinculoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao,

            @Schema(requiredMode = REQUIRED, description = "Fim da vigência (≥ início, não futuro)") @NotNull
            LocalDate fim,

            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String motivo) {}

    @Schema(name = "AutorUsinas", description = "Usuário interno que realizou a operação")
    record AutorResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome) {}

    @Schema(name = "ReferenciaCadastro", description = "Fazenda ou usina referenciada")
    record ReferenciaResponse(
            @Schema(requiredMode = REQUIRED) long id,

            @Schema(requiredMode = REQUIRED, example = "USN-0001")
            String codigo,

            @Schema(requiredMode = REQUIRED) String nome) {}

    @Schema(name = "Fazenda")
    record FazendaResponse(
            @Schema(requiredMode = REQUIRED) long id,

            @Schema(requiredMode = REQUIRED, example = "FZD-0001")
            String codigo,

            @Schema(requiredMode = REQUIRED) String nome,
            String cidade,
            String uf,
            String observacoes,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse criadoPor,
            @Schema(requiredMode = REQUIRED) Instant atualizadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse atualizadoPor,
            @Schema(requiredMode = REQUIRED) long versao) {}

    @Schema(name = "FazendaResumo")
    record FazendaResumoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String codigo,
            @Schema(requiredMode = REQUIRED) String nome,
            String cidade,
            String uf,
            @Schema(requiredMode = REQUIRED) long usinas) {}

    @Schema(name = "PaginaFazendas")
    record PaginaFazendasResponse(
            @Schema(requiredMode = REQUIRED) List<FazendaResumoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {}

    @Schema(name = "Usina")
    record UsinaResponse(
            @Schema(requiredMode = REQUIRED) long id,

            @Schema(requiredMode = REQUIRED, example = "USN-0001")
            String codigo,

            @Schema(requiredMode = REQUIRED) String nome,
            ReferenciaResponse fazenda,
            @Schema(example = "79000-000") String cep,
            String endereco,
            String cidade,
            String uf,
            String observacoes,

            @Schema(requiredMode = REQUIRED, description = "UCs com vínculo cadastral vigente")
            long ucsVinculadas,

            @Schema(requiredMode = REQUIRED, description = "Titulares distintos das UCs vigentes")
            long associadosDistintos,

            @Schema(requiredMode = REQUIRED) long vinculosEncerrados,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse criadoPor,
            @Schema(requiredMode = REQUIRED) Instant atualizadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse atualizadoPor,
            @Schema(requiredMode = REQUIRED) long versao) {}

    @Schema(name = "UsinaResumo")
    record UsinaResumoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String codigo,
            @Schema(requiredMode = REQUIRED) String nome,
            String fazendaNome,
            String cidade,
            String uf,
            @Schema(requiredMode = REQUIRED) long ucsVinculadas,
            @Schema(requiredMode = REQUIRED) long associadosDistintos,
            @Schema(requiredMode = REQUIRED) Instant criadoEm) {}

    @Schema(name = "PaginaUsinas")
    record PaginaUsinasResponse(
            @Schema(requiredMode = REQUIRED) List<UsinaResumoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {}

    @Schema(name = "UcDoVinculo", description = "UC e titular, com documento parcialmente mascarado")
    record UcResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String numero,
            @Schema(requiredMode = REQUIRED) String concessionariaNome,
            String denominacao,
            @Schema(requiredMode = REQUIRED) long associadoId,
            @Schema(requiredMode = REQUIRED) String associadoNome,
            @Schema(requiredMode = REQUIRED) String associadoTipo,
            @Schema(requiredMode = REQUIRED) String associadoDocumentoMascarado) {

        static UcResponse de(ResumoUc uc) {
            return new UcResponse(
                    uc.id(),
                    uc.numero(),
                    uc.concessionariaNome(),
                    uc.denominacao(),
                    uc.associadoId(),
                    uc.associadoNome(),
                    uc.associadoTipo(),
                    uc.associadoDocumentoMascarado());
        }
    }

    @Schema(name = "VinculoUsina", description = "Vínculo cadastral; não representa rateio nem ativação")
    record VinculoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) ReferenciaResponse usina,
            @Schema(requiredMode = REQUIRED) UcResponse uc,
            @Schema(requiredMode = REQUIRED) LocalDate inicio,
            LocalDate fim,
            @Schema(requiredMode = REQUIRED) boolean vigente,
            String motivoEncerramento,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse criadoPor,
            Instant encerradoEm,
            AutorResponse encerradoPor,
            @Schema(requiredMode = REQUIRED) long versao) {}

    @Schema(name = "PaginaVinculosUsina")
    record PaginaVinculosResponse(
            @Schema(requiredMode = REQUIRED) List<VinculoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {}

    @Schema(name = "VinculoVigenteResumo")
    record VinculoVigenteResponse(
            @Schema(requiredMode = REQUIRED) long vinculoId,
            @Schema(requiredMode = REQUIRED) ReferenciaResponse usina,
            @Schema(requiredMode = REQUIRED) LocalDate inicio) {}

    @Schema(name = "UcCandidata", description = "UC encontrada para vínculo, com o vínculo vigente se houver")
    record UcCandidataResponse(
            @Schema(requiredMode = REQUIRED) UcResponse uc, VinculoVigenteResponse vinculoVigente) {}

    @Schema(name = "AlteracaoHistoricoUsinas")
    record AlteracaoResponse(
            @Schema(requiredMode = REQUIRED) String campo, String antes, String depois) {

        static AlteracaoResponse de(HistoricoUsinas.Alteracao alteracao) {
            return new AlteracaoResponse(alteracao.campo(), alteracao.antes(), alteracao.depois());
        }
    }

    @Schema(name = "RegistroHistoricoUsinas")
    record RegistroHistoricoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) Instant ocorridoEm,
            @Schema(requiredMode = REQUIRED) HistoricoUsinas.Acao acao,
            Long vinculoId,
            String referencia,
            String motivo,
            @Schema(requiredMode = REQUIRED) AutorResponse autor,
            @Schema(requiredMode = REQUIRED) List<AlteracaoResponse> alteracoes) {}

    @Schema(name = "PaginaHistoricoUsinas")
    record PaginaHistoricoResponse(
            @Schema(requiredMode = REQUIRED) List<RegistroHistoricoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {}
}
