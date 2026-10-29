package br.com.emconta.associados.cadastro;

import br.com.emconta.associados.cadastro.HistoricoCadastro.Diferencas;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastro de associados, contatos e UCs. Toda alteração confere a versão lida pelo cliente
 * (controle otimista) e é registrada no histórico na mesma transação. A unicidade de documento e de
 * UC é garantida por índice único no banco; a checagem prévia só serve para localizar o registro
 * existente.
 */
@Service
public class CadastroAssociados {

    /** Limite técnico contra cadastros abusivos; não é regra de negócio. */
    static final int MAXIMO_CONTATOS = 20;

    private static final String UK_DOCUMENTO = "associado_documento_uk";
    private static final String UK_UC = "uc_concessionaria_numero_uk";

    private final AssociadoRepository associados;
    private final ContatoRepository contatos;
    private final UnidadeConsumidoraRepository ucs;
    private final HistoricoCadastro historico;
    private final Clock clock;

    CadastroAssociados(
            AssociadoRepository associados,
            ContatoRepository contatos,
            UnidadeConsumidoraRepository ucs,
            HistoricoCadastro historico,
            Clock clock) {
        this.associados = associados;
        this.contatos = contatos;
        this.ucs = ucs;
        this.historico = historico;
        this.clock = clock;
    }

    public record NovoAssociado(
            TipoPessoa tipo, String documento, String nome, String nomeFantasia, List<DadosContato> contatos) {}

    public record Ficha(Associado associado, List<Contato> contatos, long quantidadeUcs) {}

    @Transactional
    public Ficha cadastrar(long autor, NovoAssociado novo) {
        if (novo.tipo() == null) {
            throw FalhaCadastro.campoInvalido("tipo", "Selecione pessoa física ou jurídica.");
        }
        String documento = Documentos.normalizar(novo.documento());
        boolean valido =
                novo.tipo() == TipoPessoa.PF ? Documentos.cpfValido(documento) : Documentos.cnpjValido(documento);
        if (!valido) {
            throw FalhaCadastro.documentoInvalido(novo.tipo());
        }
        List<DadosContato> dadosContatos = novo.contatos() == null ? List.of() : novo.contatos();
        if (dadosContatos.size() > MAXIMO_CONTATOS) {
            throw FalhaCadastro.campoInvalido("contatos", "Informe no máximo " + MAXIMO_CONTATOS + " contatos.");
        }
        Instant agora = clock.instant();
        Associado associado = Associado.novo(novo.tipo(), documento, novo.nome(), novo.nomeFantasia(), autor, agora);
        // Valida todos os contatos antes de gravar, para o erro apontar o contato certo.
        List<Contato> novosContatos = new ArrayList<>();
        for (int i = 0; i < dadosContatos.size(); i++) {
            novosContatos.add(Contato.novo(0, "contatos[" + i + "].", dadosContatos.get(i), agora));
        }
        associados.idPorDocumento(documento).ifPresent(existente -> {
            throw FalhaCadastro.documentoJaCadastrado(existente);
        });
        try {
            associados.saveAndFlush(associado);
        } catch (DataIntegrityViolationException e) {
            // Cadastro concorrente com o mesmo documento: o outro venceu.
            throw violou(e, UK_DOCUMENTO) ? FalhaCadastro.documentoJaCadastrado(null) : e;
        }
        historico.registrar(
                associado.getId(),
                null,
                null,
                null,
                AcaoHistorico.ASSOCIADO_CADASTRADO,
                autor,
                new Diferencas()
                        .comparar("tipo", null, associado.getTipo().name())
                        .comparar("documento", null, Documentos.mascarar(associado.getTipo(), documento))
                        .comparar("nome", null, associado.getNome())
                        .comparar("nomeFantasia", null, associado.getNomeFantasia()));
        List<Contato> gravados = new ArrayList<>();
        for (int i = 0; i < dadosContatos.size(); i++) {
            Contato contato = contatos.save(Contato.novo(associado.getId(), "", dadosContatos.get(i), agora));
            registrarInclusaoContato(associado.getId(), contato, autor);
            gravados.add(contato);
        }
        contatos.flush();
        return new Ficha(associado, gravados, 0);
    }

    @Transactional(readOnly = true)
    public Ficha obter(long id) {
        Associado associado = associados.findById(id).orElseThrow(CadastroAssociados::associadoNaoEncontrado);
        return new Ficha(associado, contatos.ativos(id), ucs.countByAssociadoId(id));
    }

    @Transactional
    public Ficha alterar(long autor, long id, long versao, String nome, String nomeFantasia) {
        Associado associado = associados.findById(id).orElseThrow(CadastroAssociados::associadoNaoEncontrado);
        conferirVersao(associado.getVersao(), versao);
        String nomeAntes = associado.getNome();
        String fantasiaAntes = associado.getNomeFantasia();
        associado.alterarDados(nome, nomeFantasia, autor, clock.instant());
        Diferencas diferencas = new Diferencas()
                .comparar("nome", nomeAntes, associado.getNome())
                .comparar("nomeFantasia", fantasiaAntes, associado.getNomeFantasia());
        associados.flush();
        if (!diferencas.vazia()) {
            historico.registrar(id, null, null, null, AcaoHistorico.ASSOCIADO_ALTERADO, autor, diferencas);
        }
        return new Ficha(associado, contatos.ativos(id), ucs.countByAssociadoId(id));
    }

    @Transactional
    public Contato adicionarContato(long autor, long associadoId, DadosContato dados) {
        associados.travar(associadoId).orElseThrow(CadastroAssociados::associadoNaoEncontrado);
        if (contatos.ativos(associadoId).size() >= MAXIMO_CONTATOS) {
            throw FalhaCadastro.campoInvalido("contatos", "Limite de " + MAXIMO_CONTATOS + " contatos atingido.");
        }
        Contato contato = contatos.saveAndFlush(Contato.novo(associadoId, "", dados, clock.instant()));
        registrarInclusaoContato(associadoId, contato, autor);
        return contato;
    }

    @Transactional
    public Contato alterarContato(long autor, long associadoId, long contatoId, long versao, DadosContato dados) {
        Contato contato = contatoAtivo(associadoId, contatoId);
        conferirVersao(contato.getVersao(), versao);
        Contato antes = copia(contato);
        contato.alterar(dados, clock.instant());
        Diferencas diferencas = diferencasContato(antes, contato);
        contatos.flush();
        if (!diferencas.vazia()) {
            historico.registrar(
                    associadoId, null, contatoId, contato.getNome(), AcaoHistorico.CONTATO_ALTERADO, autor, diferencas);
        }
        return contato;
    }

    @Transactional
    public void removerContato(long autor, long associadoId, long contatoId, long versao) {
        Contato contato = contatoAtivo(associadoId, contatoId);
        conferirVersao(contato.getVersao(), versao);
        contato.remover(clock.instant());
        contatos.flush();
        historico.registrar(
                associadoId,
                null,
                contatoId,
                contato.getNome(),
                AcaoHistorico.CONTATO_REMOVIDO,
                autor,
                new Diferencas().comparar("nome", contato.getNome(), null));
    }

    @Transactional(readOnly = true)
    public List<UnidadeConsumidora> ucs(long associadoId) {
        if (!associados.existsById(associadoId)) {
            throw associadoNaoEncontrado();
        }
        return ucs.findByAssociadoIdOrderByIdAsc(associadoId);
    }

    @Transactional(readOnly = true)
    public UnidadeConsumidora uc(long associadoId, long ucId) {
        return ucs.findById(ucId)
                .filter(uc -> uc.getAssociadoId() == associadoId)
                .orElseThrow(CadastroAssociados::ucNaoEncontrada);
    }

    @Transactional
    public UnidadeConsumidora cadastrarUc(
            long autor,
            long associadoId,
            Concessionaria concessionaria,
            String numero,
            String denominacao,
            String endereco) {
        associados.travar(associadoId).orElseThrow(CadastroAssociados::associadoNaoEncontrado);
        UnidadeConsumidora uc = UnidadeConsumidora.nova(
                associadoId, concessionaria, numero, denominacao, endereco, autor, clock.instant());
        try {
            ucs.saveAndFlush(uc);
        } catch (DataIntegrityViolationException e) {
            throw violou(e, UK_UC) ? FalhaCadastro.ucJaCadastrada() : e;
        }
        historico.registrar(
                associadoId,
                uc.getId(),
                null,
                referenciaUc(uc),
                AcaoHistorico.UC_CADASTRADA,
                autor,
                new Diferencas()
                        .comparar("concessionaria", null, uc.getConcessionaria().nome())
                        .comparar("numero", null, uc.getNumero())
                        .comparar("denominacao", null, uc.getDenominacao())
                        .compararSemValores("endereco", null, uc.getEndereco()));
        return uc;
    }

    @Transactional
    public UnidadeConsumidora alterarUc(
            long autor, long associadoId, long ucId, long versao, String denominacao, String endereco) {
        UnidadeConsumidora uc = ucs.findById(ucId)
                .filter(encontrada -> encontrada.getAssociadoId() == associadoId)
                .orElseThrow(CadastroAssociados::ucNaoEncontrada);
        conferirVersao(uc.getVersao(), versao);
        String denominacaoAntes = uc.getDenominacao();
        String enderecoAntes = uc.getEndereco();
        uc.alterar(denominacao, endereco, autor, clock.instant());
        Diferencas diferencas = new Diferencas()
                .comparar("denominacao", denominacaoAntes, uc.getDenominacao())
                .compararSemValores("endereco", enderecoAntes, uc.getEndereco());
        ucs.flush();
        if (!diferencas.vazia()) {
            historico.registrar(
                    associadoId, ucId, null, referenciaUc(uc), AcaoHistorico.UC_ALTERADA, autor, diferencas);
        }
        return uc;
    }

    private void registrarInclusaoContato(long associadoId, Contato contato, long autor) {
        historico.registrar(
                associadoId,
                null,
                contato.getId(),
                contato.getNome(),
                AcaoHistorico.CONTATO_ADICIONADO,
                autor,
                diferencasContato(null, contato));
    }

    private static Diferencas diferencasContato(Contato antes, Contato depois) {
        return new Diferencas()
                .comparar("nome", antes == null ? null : antes.getNome(), depois.getNome())
                .comparar(
                        "email",
                        antes == null ? null : Mascaras.email(antes.getEmail()),
                        Mascaras.email(depois.getEmail()))
                .comparar(
                        "telefone",
                        antes == null ? null : Mascaras.telefone(antes.getTelefone()),
                        Mascaras.telefone(depois.getTelefone()));
    }

    private static Contato copia(Contato contato) {
        return Contato.novo(
                contato.getAssociadoId(),
                "",
                new DadosContato(contato.getNome(), contato.getEmail(), contato.getTelefone()),
                Instant.EPOCH);
    }

    private Contato contatoAtivo(long associadoId, long contatoId) {
        return contatos.findById(contatoId)
                .filter(contato -> contato.getAssociadoId() == associadoId && !contato.removido())
                .orElseThrow(() -> FalhaCadastro.naoEncontrado("CONTATO_NAO_ENCONTRADO", "Contato não encontrado."));
    }

    private static String referenciaUc(UnidadeConsumidora uc) {
        return "UC " + uc.getNumero() + " · " + uc.getConcessionaria().nome();
    }

    /**
     * A versão enviada pelo cliente precisa ser a atual. Se outra transação gravar entre esta leitura e
     * o commit, o {@code @Version} da entidade recusa a gravação (também resulta em 409).
     */
    private static void conferirVersao(long atual, long informada) {
        if (atual != informada) {
            throw FalhaCadastro.conflitoConcorrente();
        }
    }

    private static boolean violou(DataIntegrityViolationException e, String constraint) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConstraintViolationException violacao
                    && violacao.getConstraintName() != null
                    && violacao.getConstraintName().contains(constraint)) {
                return true;
            }
            if (causa.getMessage() != null && causa.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    static FalhaCadastro associadoNaoEncontrado() {
        return FalhaCadastro.naoEncontrado("ASSOCIADO_NAO_ENCONTRADO", "Associado não encontrado.");
    }

    static FalhaCadastro ucNaoEncontrada() {
        return FalhaCadastro.naoEncontrado("UC_NAO_ENCONTRADA", "Unidade consumidora não encontrada.");
    }
}
