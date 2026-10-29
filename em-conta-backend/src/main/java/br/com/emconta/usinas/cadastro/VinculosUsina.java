package br.com.emconta.usinas.cadastro;

import br.com.emconta.associados.UnidadesConsumidoras;
import br.com.emconta.associados.UnidadesConsumidoras.ResumoUc;
import br.com.emconta.usinas.cadastro.HistoricoUsinas.Acao;
import br.com.emconta.usinas.cadastro.HistoricoUsinas.Diferencas;
import br.com.emconta.usinas.cadastro.VinculoUcRepository.LinhaVinculo;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vínculos cadastrais UC → usina (US-CLI-04). Regras aprovadas em 29/10/2026:
 *
 * <ul>
 *   <li>no máximo uma usina vigente por UC (índice único {@code vinculo_uc_vigente_uk});
 *   <li>o vínculo do associado com a usina é derivado das UCs vinculadas;
 *   <li>início informado, não futuro, retroativo permitido; vigências da mesma UC não se sobrepõem;
 *   <li>encerramento com data (≥ início, não futura) e motivo obrigatório; trocar de usina ou corrigir
 *       um lançamento é encerrar e, se for o caso, criar outro. Nada é apagado.
 * </ul>
 *
 * <p>Concorrência: a linha da UC é travada primeiro (API pública do módulo de associados), o que
 * serializa criações e encerramentos da mesma UC; em seguida os vínculos da UC são lidos com trava,
 * para as checagens usarem o estado atual. O índice único continua garantindo a cardinalidade.
 */
@Service
public class VinculosUsina {

    static final int MINIMO_MOTIVO = 5;

    private final VinculoUcRepository vinculos;
    private final UsinaRepository usinas;
    private final UnidadesConsumidoras ucs;
    private final HistoricoUsinas historico;
    private final CalendarioNegocio calendario;
    private final Clock clock;

    VinculosUsina(
            VinculoUcRepository vinculos,
            UsinaRepository usinas,
            UnidadesConsumidoras ucs,
            HistoricoUsinas historico,
            CalendarioNegocio calendario,
            Clock clock) {
        this.vinculos = vinculos;
        this.usinas = usinas;
        this.ucs = ucs;
        this.historico = historico;
        this.calendario = calendario;
        this.clock = clock;
    }

    @Transactional
    public VinculoUc criar(long autor, long usinaId, long ucId, LocalDate inicio) {
        // Trava a UC primeiro: serializa as operações de vínculo da mesma UC.
        ResumoUc uc = ucs.travar(ucId)
                .orElseThrow(
                        () -> FalhaUsinas.naoEncontrado("UC_NAO_ENCONTRADA", "Unidade consumidora não encontrada."));
        Usina usina = usinas.findById(usinaId).orElseThrow(CadastroUsinas::usinaNaoEncontrada);
        if (inicio == null) {
            throw FalhaUsinas.vigenciaInvalida("inicio", "Informe a data de início do vínculo.");
        }
        if (inicio.isAfter(calendario.hoje())) {
            throw FalhaUsinas.vigenciaInvalida("inicio", "A data de início não pode ser futura.");
        }
        List<LinhaVinculo> atuais = vinculos.travarDaUc(ucId);
        Optional<LinhaVinculo> vigente =
                atuais.stream().filter(v -> v.getFim() == null).findFirst();
        if (vigente.isPresent()) {
            throw ucJaVinculada(vigente.get(), usinaId);
        }
        Optional<LocalDate> ultimoFim =
                atuais.stream().map(LinhaVinculo::getFim).max(Comparator.naturalOrder());
        if (ultimoFim.isPresent() && inicio.isBefore(ultimoFim.get())) {
            throw FalhaUsinas.vigenciaInvalida(
                    "inicio", "O início sobrepõe um vínculo anterior desta UC, encerrado em " + ultimoFim.get() + ".");
        }
        VinculoUc vinculo = VinculoUc.novo(usinaId, ucId, uc.associadoId(), inicio, autor, clock.instant());
        try {
            vinculos.saveAndFlush(vinculo);
        } catch (DataIntegrityViolationException e) {
            // Outra criação para a mesma UC venceu entre a checagem e a gravação.
            throw FalhaUsinas.conflito(
                    "UC_JA_VINCULADA", "ucId", "Esta UC acabou de ser vinculada a uma usina. Recarregue.", null);
        }
        historico.registrar(
                usina.getFazendaId(),
                usinaId,
                vinculo.getId(),
                Acao.VINCULO_CRIADO,
                referencia(uc),
                null,
                autor,
                new Diferencas().comparar("inicio", null, inicio));
        return vinculo;
    }

    @Transactional
    public VinculoUc encerrar(long autor, long usinaId, long vinculoId, long versao, LocalDate fim, String motivo) {
        VinculoUc vinculo = vinculos.findById(vinculoId)
                .filter(v -> v.getUsinaId() == usinaId)
                .orElseThrow(() -> FalhaUsinas.naoEncontrado("VINCULO_NAO_ENCONTRADO", "Vínculo não encontrado."));
        ucs.travar(vinculo.getUcId());
        boolean vigenteAgora = vinculos.travarDaUc(vinculo.getUcId()).stream()
                .anyMatch(v -> v.getId() == vinculoId && v.getFim() == null);
        CadastroUsinas.conferirVersao(vinculo.getVersao(), versao);
        if (!vigenteAgora || !vinculo.vigente()) {
            throw FalhaUsinas.conflito("VINCULO_JA_ENCERRADO", null, "Este vínculo já foi encerrado.", null);
        }
        if (fim == null) {
            throw FalhaUsinas.vigenciaInvalida("fim", "Informe a data de encerramento.");
        }
        if (fim.isBefore(vinculo.getInicio())) {
            throw FalhaUsinas.vigenciaInvalida("fim", "O encerramento não pode ser anterior ao início do vínculo.");
        }
        if (fim.isAfter(calendario.hoje())) {
            throw FalhaUsinas.vigenciaInvalida("fim", "A data de encerramento não pode ser futura.");
        }
        String motivoLimpo = Campos.limpar(motivo);
        if (motivoLimpo == null || motivoLimpo.length() < MINIMO_MOTIVO) {
            throw FalhaUsinas.campoInvalido(
                    "motivo", "Informe o motivo do encerramento (mínimo " + MINIMO_MOTIVO + " caracteres).");
        }
        if (motivoLimpo.length() > VinculoUc.TAMANHO_MAXIMO_MOTIVO) {
            throw FalhaUsinas.campoInvalido(
                    "motivo", "Use no máximo " + VinculoUc.TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        vinculo.encerrar(fim, motivoLimpo, autor, clock.instant());
        vinculos.flush();
        Usina usina = usinas.findById(usinaId).orElseThrow(CadastroUsinas::usinaNaoEncontrada);
        historico.registrar(
                usina.getFazendaId(),
                usinaId,
                vinculoId,
                Acao.VINCULO_ENCERRADO,
                ucs.obter(vinculo.getUcId()).map(VinculosUsina::referencia).orElse(null),
                motivoLimpo,
                autor,
                new Diferencas().comparar("fim", null, fim));
        return vinculo;
    }

    /** Vínculos do associado (vigentes primeiro), derivados das suas UCs. */
    @Transactional(readOnly = true)
    public List<VinculoUc> doAssociado(long associadoId) {
        if (!ucs.associadoExiste(associadoId)) {
            throw FalhaUsinas.naoEncontrado("ASSOCIADO_NAO_ENCONTRADO", "Associado não encontrado.");
        }
        return vinculos.doAssociado(associadoId);
    }

    /** UCs encontradas pela busca, com o vínculo vigente de cada uma (se houver). */
    @Transactional(readOnly = true)
    public Map<ResumoUc, Optional<VinculoUc>> candidatas(String busca, int limite) {
        List<ResumoUc> encontradas = ucs.buscar(busca, limite);
        Map<Long, VinculoUc> vigentes = new LinkedHashMap<>();
        if (!encontradas.isEmpty()) {
            vinculos.vigentesDasUcs(encontradas.stream().map(ResumoUc::id).toList())
                    .forEach(v -> vigentes.put(v.getUcId(), v));
        }
        Map<ResumoUc, Optional<VinculoUc>> resultado = new LinkedHashMap<>();
        encontradas.forEach(uc -> resultado.put(uc, Optional.ofNullable(vigentes.get(uc.id()))));
        return resultado;
    }

    private FalhaUsinas ucJaVinculada(LinhaVinculo vigente, long usinaPretendida) {
        Usina atual = usinas.findById(vigente.getUsinaId()).orElseThrow();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("vinculoAtualId", vigente.getId());
        extras.put("usinaAtualId", atual.getId());
        extras.put("usinaAtualCodigo", atual.codigo());
        extras.put("usinaAtualNome", atual.getNome());
        extras.put("vigenteDesde", vigente.getInicio().toString());
        if (vigente.getUsinaId() == usinaPretendida) {
            return FalhaUsinas.conflito("VINCULO_JA_EXISTE", "ucId", "Esta UC já está vinculada a esta usina.", extras);
        }
        return FalhaUsinas.conflito(
                "UC_VINCULADA_A_OUTRA_USINA",
                "ucId",
                "Esta UC já tem vínculo vigente com outra usina. Encerre-o antes de criar um novo; nada foi alterado.",
                extras);
    }

    private static String referencia(ResumoUc uc) {
        return "UC " + uc.numero() + " · " + uc.concessionariaNome() + " · " + uc.associadoNome();
    }
}
