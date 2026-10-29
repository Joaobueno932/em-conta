package br.com.emconta.acesso.erros;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** Falha esperada do módulo de acesso, convertida em Problem Details com um {@code codigo} estável. */
public class FalhaAcesso extends RuntimeException {

    private final HttpStatus status;
    private final String codigo;
    private final Duration tentarNovamenteEm;

    public FalhaAcesso(HttpStatus status, String codigo, String mensagem) {
        this(status, codigo, mensagem, null);
    }

    private FalhaAcesso(HttpStatus status, String codigo, String mensagem, Duration tentarNovamenteEm) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
        this.tentarNovamenteEm = tentarNovamenteEm;
    }

    public static FalhaAcesso credenciaisInvalidas() {
        return new FalhaAcesso(HttpStatus.UNAUTHORIZED, "CREDENCIAIS_INVALIDAS", "E-mail ou senha inválidos.");
    }

    public static FalhaAcesso muitasTentativas(Duration tentarNovamenteEm) {
        return new FalhaAcesso(
                HttpStatus.TOO_MANY_REQUESTS,
                "MUITAS_TENTATIVAS",
                "Muitas tentativas. Aguarde alguns minutos e tente novamente.",
                tentarNovamenteEm);
    }

    public static FalhaAcesso tokenInvalido() {
        return new FalhaAcesso(
                HttpStatus.BAD_REQUEST,
                "TOKEN_INVALIDO",
                "Este link é inválido, expirou ou já foi utilizado. Solicite um novo link.");
    }

    /** A senha ou a versão de segurança mudou entre a validação e a gravação; nada foi alterado. */
    public static FalhaAcesso credencialAlterada() {
        return new FalhaAcesso(
                HttpStatus.CONFLICT,
                "CREDENCIAL_ALTERADA",
                "Sua senha ou sua sessão foi alterada por outra operação. Nada foi alterado; entre novamente.");
    }

    public static FalhaAcesso requisicaoInvalida(String codigo, String mensagem) {
        return new FalhaAcesso(HttpStatus.BAD_REQUEST, codigo, mensagem);
    }

    public static FalhaAcesso conflito(String codigo, String mensagem) {
        return new FalhaAcesso(HttpStatus.CONFLICT, codigo, mensagem);
    }

    public static FalhaAcesso proibido(String codigo, String mensagem) {
        return new FalhaAcesso(HttpStatus.FORBIDDEN, codigo, mensagem);
    }

    public static FalhaAcesso usuarioNaoEncontrado() {
        return new FalhaAcesso(HttpStatus.NOT_FOUND, "USUARIO_NAO_ENCONTRADO", "Usuário não encontrado.");
    }

    public HttpStatus status() {
        return status;
    }

    public String codigo() {
        return codigo;
    }

    public Duration tentarNovamenteEm() {
        return tentarNovamenteEm;
    }
}
