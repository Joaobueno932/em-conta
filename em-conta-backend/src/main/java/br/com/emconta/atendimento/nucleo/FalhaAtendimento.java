package br.com.emconta.atendimento.nucleo;

import org.springframework.http.HttpStatus;

/** Falha esperada do atendimento, convertida em Problem Details com {@code codigo} estável. */
public class FalhaAtendimento extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final String campo;

    FalhaAtendimento(HttpStatus status, String codigo, String campo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
        this.campo = campo;
    }

    static FalhaAtendimento naoEncontrado(String codigo, String mensagem) {
        return new FalhaAtendimento(HttpStatus.NOT_FOUND, codigo, null, mensagem);
    }

    static FalhaAtendimento conflito(String codigo, String mensagem) {
        return new FalhaAtendimento(HttpStatus.CONFLICT, codigo, null, mensagem);
    }

    static FalhaAtendimento campoInvalido(String campo, String mensagem) {
        return new FalhaAtendimento(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", campo, mensagem);
    }

    static FalhaAtendimento proibido(String codigo, String mensagem) {
        return new FalhaAtendimento(HttpStatus.FORBIDDEN, codigo, null, mensagem);
    }

    public static FalhaAtendimento anexoNaoEncontrado() {
        return naoEncontrado("ANEXO_NAO_ENCONTRADO", "Anexo não encontrado.");
    }

    static FalhaAtendimento indisponivel(String codigo, String mensagem) {
        return new FalhaAtendimento(HttpStatus.SERVICE_UNAVAILABLE, codigo, null, mensagem);
    }

    static FalhaAtendimento arquivoMuitoGrande(String mensagem) {
        return new FalhaAtendimento(HttpStatus.CONTENT_TOO_LARGE, "ARQUIVO_MUITO_GRANDE", "arquivo", mensagem);
    }

    static FalhaAtendimento canalNaoConfigurado() {
        return new FalhaAtendimento(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CANAL_NAO_CONFIGURADO",
                null,
                "O canal WhatsApp ainda não está configurado. Nenhuma mensagem pode ser enviada.");
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
}
