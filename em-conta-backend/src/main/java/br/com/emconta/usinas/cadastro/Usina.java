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
 * Usina (cadastro básico, US-CLI-03). Identificada pelo código gerado ({@code USN-0001}); o nome não é
 * único (nome repetido gera aviso). Sem CNPJ, dados técnicos, potência, disponibilidade ou situação
 * operacional nesta entrega. Sem exclusão: o registro sustenta vínculos e histórico.
 */
@Entity
@Table(name = "usina")
public class Usina {

    public static final int TAMANHO_MAXIMO_NOME = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(name = "chave_nome", nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String chaveNome;

    @Column(name = "fazenda_id")
    private Long fazendaId;

    @Column(length = 8)
    private String cep;

    @Column(length = 300)
    private String endereco;

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

    protected Usina() {}

    static Usina nova(DadosUsina dados, long autor, Instant agora) {
        Usina usina = new Usina();
        usina.aplicar(dados);
        usina.criadoEm = agora;
        usina.criadoPor = autor;
        usina.atualizadoEm = agora;
        usina.atualizadoPor = autor;
        return usina;
    }

    void alterar(DadosUsina dados, long autor, Instant agora) {
        aplicar(dados);
        atualizadoEm = agora;
        atualizadoPor = autor;
    }

    private void aplicar(DadosUsina dados) {
        nome = Campos.obrigatorio("nome", dados.nome(), TAMANHO_MAXIMO_NOME, "o nome da usina");
        chaveNome = Campos.chaveNome(nome);
        fazendaId = dados.fazendaId();
        cep = Campos.cep(dados.cep());
        endereco = Campos.opcional("endereco", dados.endereco(), 300);
        cidade = Campos.opcional("cidade", dados.cidade(), 100);
        uf = Campos.uf(dados.uf());
        observacoes = Campos.opcional("observacoes", dados.observacoes(), 500);
    }

    public static String codigo(long id) {
        return "USN-%04d".formatted(id);
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

    public Long getFazendaId() {
        return fazendaId;
    }

    public String getCep() {
        return cep;
    }

    public String getEndereco() {
        return endereco;
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
