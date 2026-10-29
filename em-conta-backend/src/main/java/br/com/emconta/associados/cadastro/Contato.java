package br.com.emconta.associados.cadastro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Contato de um associado: um nome e ao menos um meio (e-mail ou telefone). Sem unicidade de e-mail
 * ou telefone: a mesma pessoa pode ser contato de vários associados. A remoção é lógica, para o
 * histórico continuar apontando para o registro.
 */
@Entity
@Table(name = "associado_contato")
public class Contato {

    public static final int TAMANHO_MAXIMO_NOME = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "associado_id", nullable = false, updatable = false)
    private long associadoId;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(length = 254)
    private String email;

    @Column(length = 15)
    private String telefone;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    @Column(name = "removido_em")
    private Instant removidoEm;

    @Version
    private long versao;

    protected Contato() {}

    /** {@code campoBase} prefixa o nome do campo nas mensagens (ex.: {@code contatos[1].}). */
    static Contato novo(long associadoId, String campoBase, DadosContato dados, Instant agora) {
        Contato contato = new Contato();
        contato.associadoId = associadoId;
        contato.aplicar(campoBase, dados);
        contato.criadoEm = agora;
        contato.atualizadoEm = agora;
        return contato;
    }

    void alterar(DadosContato dados, Instant agora) {
        aplicar("", dados);
        atualizadoEm = agora;
    }

    void remover(Instant agora) {
        removidoEm = agora;
        atualizadoEm = agora;
    }

    private void aplicar(String campoBase, DadosContato dados) {
        nome = Textos.obrigatorio(campoBase + "nome", dados.nome(), TAMANHO_MAXIMO_NOME, "o nome do contato");
        email = Textos.email(campoBase + "email", dados.email());
        telefone = Textos.telefone(campoBase + "telefone", dados.telefone());
        if (email == null && telefone == null) {
            throw FalhaCadastro.campoInvalido(campoBase + "email", "Informe um e-mail ou um telefone.");
        }
    }

    public boolean removido() {
        return removidoEm != null;
    }

    public Long getId() {
        return id;
    }

    public long getAssociadoId() {
        return associadoId;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public String getTelefone() {
        return telefone;
    }

    public long getVersao() {
        return versao;
    }
}
