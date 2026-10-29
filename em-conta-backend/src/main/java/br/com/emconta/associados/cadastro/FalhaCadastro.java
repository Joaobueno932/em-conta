package br.com.emconta.associados.cadastro;

import org.springframework.http.HttpStatus;

/**
 * Falha esperada do cadastro, convertida em Problem Details com um {@code codigo} estável. Quando a
 * falha se refere a um campo, {@link #campo()} indica qual, para a interface exibir a mensagem junto
 * dele. A mensagem nunca repete o valor informado.
 */
public class FalhaCadastro extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final String campo;
    private final Long recursoExistenteId;

    private FalhaCadastro(HttpStatus status, String codigo, String campo, String mensagem, Long recursoExistenteId) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
        this.campo = campo;
        this.recursoExistenteId = recursoExistenteId;
    }

    public static FalhaCadastro campoInvalido(String campo, String mensagem) {
        return new FalhaCadastro(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", campo, mensagem, null);
    }

    public static FalhaCadastro documentoInvalido(TipoPessoa tipo) {
        return new FalhaCadastro(
                HttpStatus.BAD_REQUEST,
                "DOCUMENTO_INVALIDO",
                "documento",
                tipo == TipoPessoa.PF ? "CPF inválido." : "CNPJ inválido.",
                null);
    }

    /** {@code existenteId} só é informado quando foi possível localizar o cadastro existente. */
    public static FalhaCadastro documentoJaCadastrado(Long existenteId) {
        return new FalhaCadastro(
                HttpStatus.CONFLICT,
                "DOCUMENTO_JA_CADASTRADO",
                "documento",
                "Já existe um associado com este documento.",
                existenteId);
    }

    public static FalhaCadastro ucJaCadastrada() {
        return new FalhaCadastro(
                HttpStatus.CONFLICT,
                "UC_JA_CADASTRADA",
                "numero",
                "Esta unidade consumidora já está cadastrada nesta concessionária.",
                null);
    }

    public static FalhaCadastro conflitoConcorrente() {
        return new FalhaCadastro(
                HttpStatus.CONFLICT,
                "CONFLITO_CONCORRENTE",
                null,
                "Os dados foram alterados por outra operação. Recarregue e tente novamente.",
                null);
    }

    public static FalhaCadastro naoEncontrado(String codigo, String mensagem) {
        return new FalhaCadastro(HttpStatus.NOT_FOUND, codigo, null, mensagem, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String codigo() {
        return codigo;
    }

    public String campo() {
        return campo;
    }

    public Long recursoExistenteId() {
        return recursoExistenteId;
    }
}
