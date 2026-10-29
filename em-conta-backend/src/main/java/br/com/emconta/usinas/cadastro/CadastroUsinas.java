package br.com.emconta.usinas.cadastro;

import br.com.emconta.usinas.cadastro.HistoricoUsinas.Acao;
import br.com.emconta.usinas.cadastro.HistoricoUsinas.Diferencas;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de fazendas e usinas. Edições conferem a versão lida pelo cliente. Nome repetido (sem
 * acentos e caixa) gera 409 {@code NOME_POSSIVEL_DUPLICIDADE} com os cadastros parecidos; o usuário
 * pode confirmar e salvar mesmo assim (RN-CLI-04, decisão do responsável).
 */
@Service
public class CadastroUsinas {

    private final FazendaRepository fazendas;
    private final UsinaRepository usinas;
    private final HistoricoUsinas historico;
    private final Clock clock;

    CadastroUsinas(FazendaRepository fazendas, UsinaRepository usinas, HistoricoUsinas historico, Clock clock) {
        this.fazendas = fazendas;
        this.usinas = usinas;
        this.historico = historico;
        this.clock = clock;
    }

    public record Semelhante(long id, String codigo, String nome) {}

    // ------------------------------------------------------------------ fazendas

    @Transactional
    public Fazenda cadastrarFazenda(long autor, DadosFazenda dados, boolean confirmarNomeRepetido) {
        Instant agora = clock.instant();
        Fazenda fazenda = Fazenda.nova(dados, autor, agora);
        if (!confirmarNomeRepetido) {
            avisarDuplicidade(fazendas.comMesmoNome(fazenda.getChaveNome(), null).stream()
                    .map(f -> new Semelhante(f.getId(), f.codigo(), f.getNome()))
                    .toList());
        }
        fazendas.saveAndFlush(fazenda);
        historico.registrar(
                fazenda.getId(),
                null,
                null,
                Acao.FAZENDA_CADASTRADA,
                fazenda.codigo() + " · " + fazenda.getNome(),
                null,
                autor,
                diferencas(null, fazenda));
        return fazenda;
    }

    @Transactional(readOnly = true)
    public Fazenda fazenda(long id) {
        return fazendas.findById(id).orElseThrow(CadastroUsinas::fazendaNaoEncontrada);
    }

    @Transactional
    public Fazenda alterarFazenda(long autor, long id, long versao, DadosFazenda dados, boolean confirmarNomeRepetido) {
        Fazenda fazenda = fazendas.findById(id).orElseThrow(CadastroUsinas::fazendaNaoEncontrada);
        conferirVersao(fazenda.getVersao(), versao);
        Fazenda antes = Fazenda.nova(
                new DadosFazenda(fazenda.getNome(), fazenda.getCidade(), fazenda.getUf(), fazenda.getObservacoes()),
                autor,
                Instant.EPOCH);
        String chaveAntes = fazenda.getChaveNome();
        fazenda.alterar(dados, autor, clock.instant());
        if (!confirmarNomeRepetido && !fazenda.getChaveNome().equals(chaveAntes)) {
            avisarDuplicidade(fazendas.comMesmoNome(fazenda.getChaveNome(), id).stream()
                    .map(f -> new Semelhante(f.getId(), f.codigo(), f.getNome()))
                    .toList());
        }
        Diferencas diferencas = diferencas(antes, fazenda);
        fazendas.flush();
        if (!diferencas.vazia()) {
            historico.registrar(
                    id,
                    null,
                    null,
                    Acao.FAZENDA_ALTERADA,
                    fazenda.codigo() + " · " + fazenda.getNome(),
                    null,
                    autor,
                    diferencas);
        }
        return fazenda;
    }

    private static Diferencas diferencas(Fazenda antes, Fazenda depois) {
        return new Diferencas()
                .comparar("nome", antes == null ? null : antes.getNome(), depois.getNome())
                .comparar("cidade", antes == null ? null : antes.getCidade(), depois.getCidade())
                .comparar("uf", antes == null ? null : antes.getUf(), depois.getUf())
                .comparar("observacoes", antes == null ? null : antes.getObservacoes(), depois.getObservacoes());
    }

    // ------------------------------------------------------------------ usinas

    @Transactional
    public Usina cadastrarUsina(long autor, DadosUsina dados, boolean confirmarNomeRepetido) {
        conferirFazenda(dados.fazendaId());
        Usina usina = Usina.nova(dados, autor, clock.instant());
        if (!confirmarNomeRepetido) {
            avisarDuplicidade(semelhantes(usina.getChaveNome(), null));
        }
        usinas.saveAndFlush(usina);
        historico.registrar(
                usina.getFazendaId(),
                usina.getId(),
                null,
                Acao.USINA_CADASTRADA,
                usina.codigo() + " · " + usina.getNome(),
                null,
                autor,
                diferencas(null, usina));
        return usina;
    }

    @Transactional(readOnly = true)
    public Usina usina(long id) {
        return usinas.findById(id).orElseThrow(CadastroUsinas::usinaNaoEncontrada);
    }

    @Transactional
    public Usina alterarUsina(long autor, long id, long versao, DadosUsina dados, boolean confirmarNomeRepetido) {
        Usina usina = usinas.findById(id).orElseThrow(CadastroUsinas::usinaNaoEncontrada);
        conferirVersao(usina.getVersao(), versao);
        conferirFazenda(dados.fazendaId());
        Usina antes = copia(usina, autor);
        usina.alterar(dados, autor, clock.instant());
        if (!confirmarNomeRepetido && !usina.getChaveNome().equals(antes.getChaveNome())) {
            avisarDuplicidade(semelhantes(usina.getChaveNome(), id));
        }
        Diferencas diferencas = diferencas(antes, usina);
        usinas.flush();
        if (!diferencas.vazia()) {
            historico.registrar(
                    usina.getFazendaId(),
                    id,
                    null,
                    Acao.USINA_ALTERADA,
                    usina.codigo() + " · " + usina.getNome(),
                    null,
                    autor,
                    diferencas);
        }
        return usina;
    }

    private List<Semelhante> semelhantes(String chave, Long ignorar) {
        return usinas.comMesmoNome(chave, ignorar).stream()
                .map(u -> new Semelhante(u.getId(), u.codigo(), u.getNome()))
                .toList();
    }

    private void conferirFazenda(Long fazendaId) {
        if (fazendaId != null && !fazendas.existsById(fazendaId)) {
            throw FalhaUsinas.campoInvalido("fazendaId", "Fazenda não encontrada.");
        }
    }

    private static Usina copia(Usina usina, long autor) {
        return Usina.nova(
                new DadosUsina(
                        usina.getNome(),
                        usina.getFazendaId(),
                        usina.getCep(),
                        usina.getEndereco(),
                        usina.getCidade(),
                        usina.getUf(),
                        usina.getObservacoes()),
                autor,
                Instant.EPOCH);
    }

    private static Diferencas diferencas(Usina antes, Usina depois) {
        return new Diferencas()
                .comparar("nome", antes == null ? null : antes.getNome(), depois.getNome())
                .comparar(
                        "fazenda",
                        antes == null || antes.getFazendaId() == null ? null : Fazenda.codigo(antes.getFazendaId()),
                        depois.getFazendaId() == null ? null : Fazenda.codigo(depois.getFazendaId()))
                .compararSemValores("cep", antes == null ? null : antes.getCep(), depois.getCep())
                .compararSemValores("endereco", antes == null ? null : antes.getEndereco(), depois.getEndereco())
                .comparar("cidade", antes == null ? null : antes.getCidade(), depois.getCidade())
                .comparar("uf", antes == null ? null : antes.getUf(), depois.getUf())
                .comparar("observacoes", antes == null ? null : antes.getObservacoes(), depois.getObservacoes());
    }

    // ------------------------------------------------------------------ apoio

    private static void avisarDuplicidade(List<Semelhante> existentes) {
        if (!existentes.isEmpty()) {
            throw FalhaUsinas.conflito(
                    "NOME_POSSIVEL_DUPLICIDADE",
                    "nome",
                    "Já existe cadastro com nome igual ou muito parecido. Confira antes de salvar.",
                    Map.of("existentes", existentes));
        }
    }

    static void conferirVersao(long atual, long informada) {
        if (atual != informada) {
            throw FalhaUsinas.conflitoConcorrente();
        }
    }

    static FalhaUsinas fazendaNaoEncontrada() {
        return FalhaUsinas.naoEncontrado("FAZENDA_NAO_ENCONTRADA", "Fazenda não encontrada.");
    }

    static FalhaUsinas usinaNaoEncontrada() {
        return FalhaUsinas.naoEncontrado("USINA_NAO_ENCONTRADA", "Usina não encontrada.");
    }
}
