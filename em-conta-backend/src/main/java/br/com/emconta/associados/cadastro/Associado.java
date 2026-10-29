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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Associado PF ou PJ. Tipo e documento identificam o cadastro e não são editáveis. Não há exclusão:
 * o registro é necessário à autoria e ao histórico (RN-CLI-08).
 */
@Entity
@Table(name = "associado")
public class Associado {

    public static final int TAMANHO_MAXIMO_NOME = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 2, updatable = false)
    private TipoPessoa tipo;

    @Column(nullable = false, length = 14, updatable = false)
    private String documento;

    /** Nome completo (PF) ou razão social (PJ). */
    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    /** Somente PJ. */
    @Column(name = "nome_fantasia", length = TAMANHO_MAXIMO_NOME)
    private String nomeFantasia;

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

    protected Associado() {}

    static Associado novo(
            TipoPessoa tipo, String documentoCanonico, String nome, String nomeFantasia, long autor, Instant agora) {
        Associado associado = new Associado();
        associado.tipo = tipo;
        associado.documento = documentoCanonico;
        associado.aplicarDados(nome, nomeFantasia);
        associado.criadoEm = agora;
        associado.criadoPor = autor;
        associado.atualizadoEm = agora;
        associado.atualizadoPor = autor;
        return associado;
    }

    void alterarDados(String nome, String nomeFantasia, long autor, Instant agora) {
        aplicarDados(nome, nomeFantasia);
        atualizadoEm = agora;
        atualizadoPor = autor;
    }

    private void aplicarDados(String nome, String nomeFantasia) {
        this.nome = Textos.obrigatorio(
                "nome", nome, TAMANHO_MAXIMO_NOME, tipo == TipoPessoa.PF ? "o nome completo" : "a razão social");
        if (tipo == TipoPessoa.PF && Textos.limpar(nomeFantasia) != null) {
            throw FalhaCadastro.campoInvalido("nomeFantasia", "Nome fantasia se aplica somente a pessoa jurídica.");
        }
        this.nomeFantasia = Textos.opcional("nomeFantasia", nomeFantasia, TAMANHO_MAXIMO_NOME);
    }

    public Long getId() {
        return id;
    }

    public TipoPessoa getTipo() {
        return tipo;
    }

    public String getDocumento() {
        return documento;
    }

    public String getNome() {
        return nome;
    }

    public String getNomeFantasia() {
        return nomeFantasia;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public long getCriadoPor() {
        return criadoPor;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public long getAtualizadoPor() {
        return atualizadoPor;
    }

    public long getVersao() {
        return versao;
    }
}
