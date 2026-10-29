package br.com.emconta.usinas.cadastro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Fazenda: agrupa uma ou mais usinas (1:N, decisão do responsável em 29/10/2026). Identificada pelo
 * código gerado ({@code FZD-0001}); nome repetido gera aviso, não bloqueio. Sem exclusão.
 */
@Entity
@Table(name = "fazenda")
public class Fazenda {

    public static final int TAMANHO_MAXIMO_NOME = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(name = "chave_nome", nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String chaveNome;

    @Column(length = 100)
    private String cidade;

    @Column(length = 2)
    private String uf;

    @Column(length = 500)
    private String observacoes;

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

    protected Fazenda() {}

    static Fazenda nova(DadosFazenda dados, long autor, Instant agora) {
        Fazenda fazenda = new Fazenda();
        fazenda.aplicar(dados);
        fazenda.criadoEm = agora;
        fazenda.criadoPor = autor;
        fazenda.atualizadoEm = agora;
        fazenda.atualizadoPor = autor;
        return fazenda;
    }

    void alterar(DadosFazenda dados, long autor, Instant agora) {
        aplicar(dados);
        atualizadoEm = agora;
        atualizadoPor = autor;
    }

    private void aplicar(DadosFazenda dados) {
        nome = Campos.obrigatorio("nome", dados.nome(), TAMANHO_MAXIMO_NOME, "o nome da fazenda");
        chaveNome = Campos.chaveNome(nome);
        cidade = Campos.opcional("cidade", dados.cidade(), 100);
        uf = Campos.uf(dados.uf());
        observacoes = Campos.opcional("observacoes", dados.observacoes(), 500);
    }

    public static String codigo(long id) {
        return "FZD-%04d".formatted(id);
    }

    public String codigo() {
        return codigo(id);
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getChaveNome() {
        return chaveNome;
    }

    public String getCidade() {
        return cidade;
    }

    public String getUf() {
        return uf;
    }

    public String getObservacoes() {
        return observacoes;
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
