package br.com.emconta.acesso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.emconta.acesso.config.AcessoProperties;
import br.com.emconta.acesso.erros.FalhaAcesso;
import br.com.emconta.acesso.usuarios.Emails;
import br.com.emconta.acesso.usuarios.PoliticaSenha;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** Regras puras, sem Spring. */
class RegrasAcessoTests {

    @Test
    void politicaDeSenhaContaCaracteresEBytes() {
        assertThatThrownBy(() -> PoliticaSenha.validar("a".repeat(14))).isInstanceOf(FalhaAcesso.class);
        assertThatCode(() -> PoliticaSenha.validar("a".repeat(15))).doesNotThrowAnyException();
        assertThatCode(() -> PoliticaSenha.validar("a".repeat(128))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PoliticaSenha.validar("a".repeat(129))).isInstanceOf(FalhaAcesso.class);
        // 64 emojis: 64 caracteres e 256 bytes, aceito.
        assertThatCode(() -> PoliticaSenha.validar("🔒".repeat(64))).doesNotThrowAnyException();
        // 128 caracteres de 4 bytes = 512 bytes (limite), aceito; um a mais ultrapassa os caracteres.
        assertThatCode(() -> PoliticaSenha.validar("🔒".repeat(128))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PoliticaSenha.validar(" ".repeat(20))).isInstanceOf(FalhaAcesso.class);
        assertThatCode(() -> PoliticaSenha.validar("frase com espaços e sem símbolos"))
                .doesNotThrowAnyException();
    }

    @Test
    void emailENormalizadoDeFormaUnica() {
        assertThat(Emails.normalizar("  Maria.Silva@Exemplo.COM.br ")).isEqualTo("maria.silva@exemplo.com.br");
        // NFC: "é" composto e decomposto resultam no mesmo e-mail.
        assertThat(Emails.normalizar("josé@exemplo.com")).isEqualTo(Emails.normalizar("josé@exemplo.com"));
        assertThat(Emails.tentarNormalizar("sem-arroba")).isEmpty();
        assertThat(Emails.tentarNormalizar("a b@exemplo.com")).isEmpty();
    }

    @Test
    void urlPublicaExigeHttpsForaDoLocalhost() {
        assertThat(propriedades("http://localhost:5173/").urlPublica()).hasToString("http://localhost:5173");
        assertThat(propriedades("https://app.exemplo.com.br").link("/convite", "abc"))
                .isEqualTo("https://app.exemplo.com.br/convite#token=abc");
        assertThatThrownBy(() -> propriedades("http://app.exemplo.com.br"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propriedades("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propriedades("https://app.exemplo.com.br/?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AcessoProperties propriedades(String url) {
        return new AcessoProperties(URI.create(url), null, null, null, null);
    }
}
