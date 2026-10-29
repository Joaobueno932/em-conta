package br.com.emconta.associados.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import br.com.emconta.associados.cadastro.AcaoHistorico;
import br.com.emconta.associados.cadastro.AcaoHistorico.CategoriaHistorico;
import br.com.emconta.associados.cadastro.Concessionaria;
import br.com.emconta.associados.cadastro.ConsultaAssociados;
import br.com.emconta.associados.cadastro.Contato;
import br.com.emconta.associados.cadastro.DadosContato;
import br.com.emconta.associados.cadastro.Documentos;
import br.com.emconta.associados.cadastro.HistoricoCadastro;
import br.com.emconta.associados.cadastro.TipoPessoa;
import br.com.emconta.associados.cadastro.UnidadeConsumidora;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * DTOs HTTP do cadastro de associados. Os limites de tamanho aqui só barram entradas abusivas; as
 * regras (documento, formatos, obrigatoriedade) são aplicadas no domínio, com mensagem por campo.
 */
final class AssociadosApi {

    private AssociadosApi() {}

    /** Limite generoso para texto bruto: a validação real de tamanho acontece no domínio. */
    private static final int BRUTO = 1000;

    @Schema(name = "ContatoRequest")
    record ContatoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            @Size(max = BRUTO) String email,
            @Size(max = BRUTO) String telefone) {

        DadosContato dados() {
            return new DadosContato(nome, email, telefone);
        }
    }

    @Schema(name = "NovoAssociadoRequest")
    record NovoAssociadoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull TipoPessoa tipo,

            @Schema(requiredMode = REQUIRED, description = "CPF ou CNPJ, com ou sem pontuação") @NotNull @Size(max = 40)
            String documento,

            @Schema(requiredMode = REQUIRED, description = "Nome completo (PF) ou razão social (PJ)")
            @NotNull
            @Size(max = BRUTO)
            String nome,

            @Schema(description = "Somente PJ") @Size(max = BRUTO)
            String nomeFantasia,

            @Size(max = 50) List<@Valid @NotNull ContatoRequest> contatos) {}

    @Schema(name = "EdicaoAssociadoRequest")
    record EdicaoAssociadoRequest(
            @Schema(requiredMode = REQUIRED, description = "Versão lida na consulta") @NotNull
            Long versao,

            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            @Size(max = BRUTO) String nomeFantasia) {}

    @Schema(name = "EdicaoContatoRequest")
    record EdicaoContatoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao,

            @Schema(requiredMode = REQUIRED) @NotNull @Size(max = BRUTO)
            String nome,

            @Size(max = BRUTO) String email,
            @Size(max = BRUTO) String telefone) {}

    @Schema(name = "VersaoRequest")
    record VersaoRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao) {}

    @Schema(name = "NovaUcRequest")
    record NovaUcRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Concessionaria concessionaria,

            @Schema(requiredMode = REQUIRED, description = "Número da UC na concessionária, como texto")
            @NotNull
            @Size(max = 100)
            String numero,

            @Size(max = BRUTO) String denominacao,
            @Size(max = BRUTO) String endereco) {}

    @Schema(name = "EdicaoUcRequest")
    record EdicaoUcRequest(
            @Schema(requiredMode = REQUIRED) @NotNull Long versao,
            @Size(max = BRUTO) String denominacao,
            @Size(max = BRUTO) String endereco) {}

    @Schema(name = "Autor", description = "Usuário interno que realizou a operação")
    record AutorResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome) {}

    @Schema(name = "Contato")
    record ContatoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) String nome,
            String email,
            @Schema(description = "Somente dígitos") String telefone,
            @Schema(requiredMode = REQUIRED) long versao) {

        static ContatoResponse de(Contato contato) {
            return new ContatoResponse(
                    contato.getId(), contato.getNome(), contato.getEmail(), contato.getTelefone(), contato.getVersao());
        }
    }

    @Schema(name = "Associado", description = "Ficha cadastral completa, para usuários com associados:consultar")
    record AssociadoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) TipoPessoa tipo,

            @Schema(requiredMode = REQUIRED, description = "Documento formatado")
            String documento,

            @Schema(requiredMode = REQUIRED) String nome,
            String nomeFantasia,
            @Schema(requiredMode = REQUIRED) List<ContatoResponse> contatos,
            @Schema(requiredMode = REQUIRED) long quantidadeUcs,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse criadoPor,
            @Schema(requiredMode = REQUIRED) Instant atualizadoEm,
            @Schema(requiredMode = REQUIRED) AutorResponse atualizadoPor,

            @Schema(requiredMode = REQUIRED, description = "Enviar de volta nas edições")
            long versao) {}

    @Schema(name = "AssociadoResumo", description = "Linha da listagem; documento parcialmente mascarado")
    record AssociadoResumoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) TipoPessoa tipo,

            @Schema(requiredMode = REQUIRED, example = "123.***.***-00")
            String documentoMascarado,

            @Schema(requiredMode = REQUIRED) String nome,
            String nomeFantasia,
            @Schema(requiredMode = REQUIRED) long quantidadeUcs,
            @Schema(requiredMode = REQUIRED) Instant criadoEm) {

        static AssociadoResumoResponse de(ConsultaAssociados.Resumo resumo) {
            return new AssociadoResumoResponse(
                    resumo.id(),
                    resumo.tipo(),
                    Documentos.mascarar(resumo.tipo(), resumo.documento()),
                    resumo.nome(),
                    resumo.nomeFantasia(),
                    resumo.quantidadeUcs(),
                    resumo.criadoEm());
        }
    }

    @Schema(name = "PaginaAssociados")
    record PaginaAssociadosResponse(
            @Schema(requiredMode = REQUIRED) List<AssociadoResumoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {

        static PaginaAssociadosResponse de(Page<ConsultaAssociados.Resumo> page) {
            return new PaginaAssociadosResponse(
                    page.getContent().stream().map(AssociadoResumoResponse::de).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    @Schema(name = "UnidadeConsumidora")
    record UcResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) long associadoId,
            @Schema(requiredMode = REQUIRED) Concessionaria concessionaria,

            @Schema(requiredMode = REQUIRED, example = "Energisa MS")
            String concessionariaNome,

            @Schema(requiredMode = REQUIRED, example = "0012345678")
            String numero,

            String denominacao,
            String endereco,
            @Schema(requiredMode = REQUIRED) Instant criadoEm,
            @Schema(requiredMode = REQUIRED) Instant atualizadoEm,
            @Schema(requiredMode = REQUIRED) long versao) {

        static UcResponse de(UnidadeConsumidora uc) {
            return new UcResponse(
                    uc.getId(),
                    uc.getAssociadoId(),
                    uc.getConcessionaria(),
                    uc.getConcessionaria().nome(),
                    uc.getNumero(),
                    uc.getDenominacao(),
                    uc.getEndereco(),
                    uc.getCriadoEm(),
                    uc.getAtualizadoEm(),
                    uc.getVersao());
        }
    }

    @Schema(name = "AlteracaoHistorico", description = "Valores nulos: vazio ou não exibido (dado pessoal)")
    record AlteracaoResponse(
            @Schema(requiredMode = REQUIRED) String campo, String antes, String depois) {

        static AlteracaoResponse de(HistoricoCadastro.Alteracao alteracao) {
            return new AlteracaoResponse(alteracao.campo(), alteracao.antes(), alteracao.depois());
        }
    }

    @Schema(name = "RegistroHistorico")
    record RegistroHistoricoResponse(
            @Schema(requiredMode = REQUIRED) long id,
            @Schema(requiredMode = REQUIRED) Instant ocorridoEm,
            @Schema(requiredMode = REQUIRED) AcaoHistorico acao,
            @Schema(requiredMode = REQUIRED) CategoriaHistorico categoria,
            Long ucId,
            Long contatoId,
            @Schema(description = "Contato ou UC afetados") String referencia,
            @Schema(requiredMode = REQUIRED) AutorResponse autor,
            @Schema(requiredMode = REQUIRED) List<AlteracaoResponse> alteracoes) {}

    @Schema(name = "PaginaHistorico")
    record PaginaHistoricoResponse(
            @Schema(requiredMode = REQUIRED) List<RegistroHistoricoResponse> itens,
            @Schema(requiredMode = REQUIRED) int pagina,
            @Schema(requiredMode = REQUIRED) int tamanho,
            @Schema(requiredMode = REQUIRED) long totalItens,
            @Schema(requiredMode = REQUIRED) int totalPaginas) {}
}
