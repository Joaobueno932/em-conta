package br.com.emconta.acesso.usuarios;

import br.com.emconta.acesso.erros.FalhaAcesso;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Usuário interno do Em Conta. O hash da senha nunca sai deste módulo. */
@Entity
@Table(name = "usuario")
public class Usuario {

    public static final int TAMANHO_MAXIMO_NOME = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(nullable = false, length = Emails.TAMANHO_MAXIMO)
    private String email;

    @Column(name = "senha_hash")
    private String senhaHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private SituacaoConta situacao;

    @Column(name = "versao_seguranca", nullable = false)
    private int versaoSeguranca;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    @Column(name = "ativado_em")
    private Instant ativadoEm;

    @Column(name = "senha_alterada_em")
    private Instant senhaAlteradaEm;

    @Column(name = "desativado_em")
    private Instant desativadoEm;

    @Version
    private long versao;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "usuario_perfil", joinColumns = @JoinColumn(name = "usuario_id"))
    @Column(name = "perfil", length = 40, nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Set<Perfil> perfis = new HashSet<>();

    protected Usuario() {}

    private Usuario(String nome, String email, Set<Perfil> perfis, Instant agora) {
        this.nome = validarNome(nome);
        this.email = email;
        this.perfis = new HashSet<>(perfis);
        this.criadoEm = agora;
        this.atualizadoEm = agora;
    }

    public static Usuario convidar(String nome, String emailNormalizado, Set<Perfil> perfis, Instant agora) {
        Usuario usuario = new Usuario(nome, emailNormalizado, perfis, agora);
        usuario.situacao = SituacaoConta.CONVIDADO;
        return usuario;
    }

    public static Usuario ativo(
            String nome, String emailNormalizado, String senhaHash, Set<Perfil> perfis, Instant agora) {
        Usuario usuario = new Usuario(nome, emailNormalizado, perfis, agora);
        usuario.situacao = SituacaoConta.ATIVO;
        usuario.senhaHash = senhaHash;
        usuario.ativadoEm = agora;
        usuario.senhaAlteradaEm = agora;
        return usuario;
    }

    public static String validarNome(String nome) {
        String limpo = nome == null ? "" : nome.strip();
        if (limpo.isEmpty() || limpo.length() > TAMANHO_MAXIMO_NOME) {
            throw FalhaAcesso.requisicaoInvalida(
                    "NOME_INVALIDO", "Informe um nome com até " + TAMANHO_MAXIMO_NOME + " caracteres.");
        }
        return limpo;
    }

    /** Aceite do convite: define a primeira senha e libera o acesso. */
    public void definirSenhaInicial(String novoHash, Instant agora) {
        if (situacao != SituacaoConta.CONVIDADO) {
            throw FalhaAcesso.tokenInvalido();
        }
        situacao = SituacaoConta.ATIVO;
        senhaHash = novoHash;
        ativadoEm = agora;
        senhaAlteradaEm = agora;
        atualizadoEm = agora;
    }

    /** Troca ou redefinição de senha. Revoga as sessões existentes ao incrementar a versão de segurança. */
    public void trocarSenha(String novoHash, Instant agora) {
        if (situacao != SituacaoConta.ATIVO) {
            throw FalhaAcesso.tokenInvalido();
        }
        senhaHash = novoHash;
        senhaAlteradaEm = agora;
        atualizadoEm = agora;
        versaoSeguranca++;
    }

    public void renomear(String novoNome, Instant agora) {
        nome = validarNome(novoNome);
        atualizadoEm = agora;
    }

    public void definirPerfis(Set<Perfil> novosPerfis, Instant agora) {
        perfis.clear();
        perfis.addAll(novosPerfis);
        atualizadoEm = agora;
    }

    public void desativar(Instant agora) {
        situacao = SituacaoConta.DESATIVADO;
        desativadoEm = agora;
        atualizadoEm = agora;
        versaoSeguranca++;
    }

    /** Volta a ATIVO se já havia senha; caso contrário volta a CONVIDADO e precisa de novo convite. */
    public void reativar(Instant agora) {
        situacao = senhaHash != null ? SituacaoConta.ATIVO : SituacaoConta.CONVIDADO;
        desativadoEm = null;
        atualizadoEm = agora;
        versaoSeguranca++;
    }

    public boolean administradorAtivo() {
        return situacao == SituacaoConta.ATIVO && perfis.contains(Perfil.ADMINISTRADOR);
    }

    public Set<Permissao> permissoes() {
        return Permissao.de(perfis);
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public String getSenhaHash() {
        return senhaHash;
    }

    public SituacaoConta getSituacao() {
        return situacao;
    }

    public int getVersaoSeguranca() {
        return versaoSeguranca;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtivadoEm() {
        return ativadoEm;
    }

    public Instant getDesativadoEm() {
        return desativadoEm;
    }

    public Set<Perfil> getPerfis() {
        return perfis.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(perfis));
    }
}
