package br.com.emconta.usinas.cadastro;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Falha esperada de fazendas, usinas e vínculos, convertida em Problem Details com {@code codigo}
 * estável. {@link #campo()} indica o campo, quando houver; {@link #extras()} leva dados para a
 * interface decidir (ex.: o vínculo vigente que impediu um novo). Mensagens nunca repetem o valor
 * informado.
 */
public class FalhaUsinas extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final String campo;
    private final Map<String, Object> extras;

    FalhaUsinas(HttpStatus status, String codigo, String campo, String mensagem, Map<String, Object> extras) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
        this.campo = campo;
        this.extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    static FalhaUsinas campoInvalido(String campo, String mensagem) {
        return new FalhaUsinas(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", campo, mensagem, null);
    }

    static FalhaUsinas vigenciaInvalida(String campo, String mensagem) {
        return new FalhaUsinas(HttpStatus.BAD_REQUEST, "VIGENCIA_INVALIDA", campo, mensagem, null);
    }

    static FalhaUsinas conflitoConcorrente() {
        return new FalhaUsinas(
                HttpStatus.CONFLICT,
                "CONFLITO_CONCORRENTE",
                null,
                "Os dados foram alterados por outra operação. Recarregue e tente novamente.",
                null);
    }

    static FalhaUsinas naoEncontrado(String codigo, String mensagem) {
        return new FalhaUsinas(HttpStatus.NOT_FOUND, codigo, null, mensagem, null);
    }

    static FalhaUsinas conflito(String codigo, String campo, String mensagem, Map<String, Object> extras) {
        return new FalhaUsinas(HttpStatus.CONFLICT, codigo, campo, mensagem, extras);
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

    public Map<String, Object> extras() {
        return extras;
    }
}
