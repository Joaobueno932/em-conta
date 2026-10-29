package br.com.emconta.associados;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.associados.cadastro.Documentos;
import br.com.emconta.associados.cadastro.TipoPessoa;
import br.com.emconta.associados.cadastro.UnidadeConsumidora;
import org.junit.jupiter.api.Test;

/** Regras oficiais de CPF e CNPJ (inclusive o CNPJ alfanumérico da IN RFB 2.229/2024). */
class DocumentosTests {

    @Test
    void cpf() {
        assertThat(Documentos.cpfValido(Documentos.normalizar("529.982.247-25")))
                .isTrue();
        assertThat(Documentos.cpfValido("52998224725")).isTrue();
        assertThat(Documentos.cpfValido("52998224724")).as("dígito errado").isFalse();
        assertThat(Documentos.cpfValido("11111111111")).as("dígitos repetidos").isFalse();
        assertThat(Documentos.cpfValido("5299822472")).as("10 dígitos").isFalse();
        assertThat(Documentos.cpfValido("5299822472A")).as("letra").isFalse();
    }

    @Test
    void cnpjNumerico() {
        assertThat(Documentos.cnpjValido(Documentos.normalizar("11.222.333/0001-81")))
                .isTrue();
        assertThat(Documentos.cnpjValido("11222333000182")).isFalse();
        assertThat(Documentos.cnpjValido("00000000000000")).isFalse();
    }

    @Test
    void cnpjAlfanumericoConformeExemploOficial() {
        // Exemplo do manual de cálculo do DV da Receita Federal/Serpro: 12.ABC.345/01DE-35.
        assertThat(Documentos.normalizar("12.abc.345/01de-35")).isEqualTo("12ABC34501DE35");
        assertThat(Documentos.cnpjValido("12ABC34501DE35")).isTrue();
        assertThat(Documentos.cnpjValido("12ABC34501DE36")).isFalse();
        assertThat(Documentos.cnpjValido("12ABC34501DE3A"))
                .as("DV não numérico")
                .isFalse();
        assertThat(Documentos.cnpjValido("12ABC34501D!35")).isFalse();
    }

    @Test
    void mascaras() {
        assertThat(Documentos.formatar(TipoPessoa.PF, "52998224725")).isEqualTo("529.982.247-25");
        assertThat(Documentos.formatar(TipoPessoa.PJ, "12ABC34501DE35")).isEqualTo("12.ABC.345/01DE-35");
        assertThat(Documentos.mascarar(TipoPessoa.PF, "52998224725")).isEqualTo("529.***.***-25");
        assertThat(Documentos.mascarar(TipoPessoa.PJ, "11222333000181")).isEqualTo("11.222.***/0001-81");
    }

    @Test
    void numeroDaUcPreservaZerosAEsquerda() {
        assertThat(UnidadeConsumidora.normalizarNumero("  0001234567 ")).isEqualTo("0001234567");
        assertThat(UnidadeConsumidora.normalizarNumero("12-ab/3")).isEqualTo("12-AB/3");
    }
}
