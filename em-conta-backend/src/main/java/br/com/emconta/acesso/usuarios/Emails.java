package br.com.emconta.acesso.usuarios;

import br.com.emconta.acesso.erros.FalhaAcesso;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalização única de e-mails: remove espaços nas pontas, aplica Unicode NFC e minúsculas. A
 * mesma função é usada para gravar, buscar e montar chaves de limite de tentativas.
 */
public final class Emails {

    public static final int TAMANHO_MAXIMO = 254;
    private static final Pattern FORMATO = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {}

    public static Optional<String> tentarNormalizar(String informado) {
        if (informado == null) {
            return Optional.empty();
        }
        String email =
                Normalizer.normalize(informado.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (email.length() > TAMANHO_MAXIMO || !FORMATO.matcher(email).matches()) {
            return Optional.empty();
        }
        return Optional.of(email);
    }

    public static String normalizar(String informado) {
        return tentarNormalizar(informado)
                .orElseThrow(() -> FalhaAcesso.requisicaoInvalida("EMAIL_INVALIDO", "Informe um e-mail válido."));
    }
}
