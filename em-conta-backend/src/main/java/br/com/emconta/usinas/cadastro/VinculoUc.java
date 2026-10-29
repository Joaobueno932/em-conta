package br.com.emconta.usinas.cadastro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Vínculo cadastral de uma UC com uma usina (RN-CLI-05/06). Não representa rateio, disponibilidade
 * de energia, ativação nem aptidão de faturamento.
 *
 * <p>Vigência em datas de negócio, intervalo semiaberto {@code [inicio, fim)}: o vínculo vale a partir
 * de {@code inicio} e deixa de valer em {@code fim}. Sem {@code fim}, está vigente. Uma UC tem no
 * máximo um vínculo vigente (índice único no banco). Trocar de usina é encerrar e criar outro;
 * corrigir um lançamento é encerrar com motivo. Nada é apagado.
 *
 * <p>{@code associadoId} é o titular da UC no momento do vínculo. A titularidade da UC é imutável
 * (não há transferência), então a cópia não diverge; ela permite consultar vínculos por associado
 * sem ler tabelas do módulo de associados.
 */
@Entity
@Table(name = "vinculo_uc_usina")
public class VinculoUc {

    public static final int TAMANHO_MAXIMO_MOTIVO = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usina_id", nullable = false, updatable = false)
    private long usinaId;

    @Column(name = "uc_id", nullable = false, updatable = false)
    private long ucId;

    @Column(name = "associado_id", nullable = false, updatable = false)
    private long associadoId;

    @Column(nullable = false, updatable = false)
    private LocalDate inicio;

    private LocalDate fim;

    @Column(name = "motivo_encerramento", length = TAMANHO_MAXIMO_MOTIVO)
    private String motivoEncerramento;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "criado_por", nullable = false, updatable = false)
    private long criadoPor;

    @Column(name = "encerrado_em")
    private Instant encerradoEm;

    @Column(name = "encerrado_por")
    private Long encerradoPor;

    @Version
    private long versao;

    protected VinculoUc() {}

    static VinculoUc novo(long usinaId, long ucId, long associadoId, LocalDate inicio, long autor, Instant agora) {
        VinculoUc vinculo = new VinculoUc();
        vinculo.usinaId = usinaId;
        vinculo.ucId = ucId;
        vinculo.associadoId = associadoId;
        vinculo.inicio = inicio;
        vinculo.criadoEm = agora;
        vinculo.criadoPor = autor;
        return vinculo;
    }

    void encerrar(LocalDate fim, String motivo, long autor, Instant agora) {
        this.fim = fim;
        this.motivoEncerramento = motivo;
        this.encerradoEm = agora;
        this.encerradoPor = autor;
    }

    public boolean vigente() {
        return fim == null;
    }

    public Long getId() {
        return id;
    }

    public long getUsinaId() {
        return usinaId;
    }

    public long getUcId() {
        return ucId;
    }

    public long getAssociadoId() {
        return associadoId;
    }

    public LocalDate getInicio() {
        return inicio;
    }

    public LocalDate getFim() {
        return fim;
    }

    public String getMotivoEncerramento() {
        return motivoEncerramento;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public long getCriadoPor() {
        return criadoPor;
    }

    public Instant getEncerradoEm() {
        return encerradoEm;
    }

    public Long getEncerradoPor() {
        return encerradoPor;
    }

    public long getVersao() {
        return versao;
    }
}
