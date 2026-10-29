package br.com.emconta.associados.cadastro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Unidade consumidora de um associado. Concessionária, número e titular não mudam depois do cadastro:
 * transferência de titularidade e encerramento de vínculo ainda não têm regra definida. A existência
 * da UC não indica situação operacional nem aptidão de faturamento.
 */
@Entity
@Table(name = "unidade_consumidora")
public class UnidadeConsumidora {

    public static final int TAMANHO_MAXIMO_NUMERO = 30;
    public static final int TAMANHO_MAXIMO_DENOMINACAO = 120;
    public static final int TAMANHO_MAXIMO_ENDERECO = 300;

    /**
     * O formato oficial por concessionária não foi documentado. Aceita letras, dígitos, ponto, hífen e
     * barra, sem espaços; zeros à esquerda são preservados.
     */
    private static final Pattern NUMERO = Pattern.compile("[0-9A-Z./\\-]+");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "associado_id", nullable = false, updatable = false)
    private long associadoId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30, updatable = false)
    private Concessionaria concessionaria;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NUMERO, updatable = false)
    private String numero;

    @Column(length = TAMANHO_MAXIMO_DENOMINACAO)
    private String denominacao;

    @Column(length = TAMANHO_MAXIMO_ENDERECO)
    private String endereco;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "criado_por", nullable = false)
    private long criadoPor;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    @Column(name = "atualizado_por", nullable = false)
    private long atualizadoPor;

    @Version
    private long versao;

    protected UnidadeConsumidora() {}

    static UnidadeConsumidora nova(
            long associadoId,
            Concessionaria concessionaria,
            String numero,
            String denominacao,
            String endereco,
            long autor,
            Instant agora) {
        if (concessionaria == null) {
            throw FalhaCadastro.campoInvalido("concessionaria", "Selecione a concessionária.");
        }
        UnidadeConsumidora uc = new UnidadeConsumidora();
        uc.associadoId = associadoId;
        uc.concessionaria = concessionaria;
        uc.numero = normalizarNumero(numero);
        uc.aplicar(denominacao, endereco);
        uc.criadoEm = agora;
        uc.criadoPor = autor;
        uc.atualizadoEm = agora;
        uc.atualizadoPor = autor;
        return uc;
    }

    /** Remove espaços das pontas e põe letras em maiúsculas; nada mais é alterado. */
    public static String normalizarNumero(String informado) {
        String limpo = informado == null ? "" : informado.strip().toUpperCase(Locale.ROOT);
        if (limpo.isEmpty()) {
            throw FalhaCadastro.campoInvalido("numero", "Informe o número da UC.");
        }
        if (limpo.length() > TAMANHO_MAXIMO_NUMERO || !NUMERO.matcher(limpo).matches()) {
            throw FalhaCadastro.campoInvalido(
                    "numero",
                    "Use até " + TAMANHO_MAXIMO_NUMERO + " letras, dígitos, ponto, hífen ou barra, sem espaços.");
        }
        return limpo;
    }

    void alterar(String denominacao, String endereco, long autor, Instant agora) {
        aplicar(denominacao, endereco);
        atualizadoEm = agora;
        atualizadoPor = autor;
    }

    private void aplicar(String denominacao, String endereco) {
        this.denominacao = Textos.opcional("denominacao", denominacao, TAMANHO_MAXIMO_DENOMINACAO);
        this.endereco = Textos.opcional("endereco", endereco, TAMANHO_MAXIMO_ENDERECO);
    }

    public Long getId() {
        return id;
    }

    public long getAssociadoId() {
        return associadoId;
    }

    public Concessionaria getConcessionaria() {
        return concessionaria;
    }

    public String getNumero() {
        return numero;
    }

    public String getDenominacao() {
        return denominacao;
    }

    public String getEndereco() {
        return endereco;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public long getVersao() {
        return versao;
    }
}
