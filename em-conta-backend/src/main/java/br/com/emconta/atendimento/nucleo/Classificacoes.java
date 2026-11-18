package br.com.emconta.atendimento.nucleo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cadastros de setores e de etiquetas do atendimento e a leitura da classificação de cada ciclo. Os dois
 * cadastros têm as mesmas regras: nome obrigatório (espaços repetidos viram um só), único sem diferenciar
 * maiúsculas nem acentos, inclusive entre os inativos (restrição única no banco); edição e mudança de
 * situação exigem a versão vista. Desativar tira o item das escolhas novas, mas preserva o cadastro e os
 * atendimentos que já o usam, que continuam aparecendo e sendo filtrados no Histórico geral. Renomear vale
 * para todos esses atendimentos; o evento de cada classificação guarda os nomes daquele momento. O
 * histórico do cadastro registra ação, autor, instante e o nome anterior ao renomear.
 *
 * <p>A classificação em si (setor e etiquetas de um atendimento) é gravada por
 * {@link Atendimentos#classificar}, com as regras de responsável e versão do atendimento.
 */
@Service
public class Classificacoes {

    public static final int TAMANHO_MAXIMO_NOME_SETOR = 60;
    public static final int TAMANHO_MAXIMO_NOME_ETIQUETA = 40;
    public static final int TAMANHO_MINIMO_NOME = 2;
    /** Etiquetas num mesmo atendimento. */
    public static final int MAXIMO_ETIQUETAS = 10;

    private final JdbcClient jdbc;
    private final Clock clock;

    Classificacoes(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Os dois cadastros. Tabela e mensagens são constantes, nunca entrada do usuário. */
    public enum Cadastro {
        SETOR(
                "atd_setor",
                TAMANHO_MAXIMO_NOME_SETOR,
                "SETOR_NAO_ENCONTRADO",
                "Setor não encontrado.",
                "Já existe um setor com este nome (ativo ou inativo).",
                "Este setor foi alterado por outra pessoa. Recarregue para ver a versão atual antes de salvar."),
        ETIQUETA(
                "atd_etiqueta",
                TAMANHO_MAXIMO_NOME_ETIQUETA,
                "ETIQUETA_NAO_ENCONTRADA",
                "Etiqueta não encontrada.",
                "Já existe uma etiqueta com este nome (ativa ou inativa).",
                "Esta etiqueta foi alterada por outra pessoa. Recarregue para ver a versão atual antes de salvar.");

        private final String tabela;
        private final int tamanhoMaximo;
        private final String codigoNaoEncontrado;
        private final String naoEncontrado;
        private final String nomeEmUso;
        private final String conflito;

        Cadastro(
                String tabela,
                int tamanhoMaximo,
                String codigoNaoEncontrado,
                String naoEncontrado,
                String nomeEmUso,
                String conflito) {
            this.tabela = tabela;
            this.tamanhoMaximo = tamanhoMaximo;
            this.codigoNaoEncontrado = codigoNaoEncontrado;
            this.naoEncontrado = naoEncontrado;
            this.nomeEmUso = nomeEmUso;
            this.conflito = conflito;
        }

        public int tamanhoMaximo() {
            return tamanhoMaximo;
        }

        FalhaAtendimento naoEncontrado() {
            return FalhaAtendimento.naoEncontrado(codigoNaoEncontrado, naoEncontrado);
        }
    }

    public enum Situacao {
        ATIVOS,
        INATIVOS,
        TODOS
    }

    public record Item(
            long id,
            String nome,
            boolean ativo,
            long versao,
            Instant criadoEm,
            long criadoPor,
            Instant atualizadoEm,
            long atualizadoPor) {}

    /** Forma persistida do nome: sem espaços nas pontas e com um único espaço entre as palavras. */
    public static String normalizarNome(String nome) {
        return nome == null ? "" : nome.strip().replaceAll("\\s+", " ");
    }

    private static String validar(Cadastro cadastro, String nome) {
        String limpo = normalizarNome(nome);
        if (limpo.length() < TAMANHO_MINIMO_NOME || limpo.length() > cadastro.tamanhoMaximo) {
            throw FalhaAtendimento.campoInvalido(
                    "nome",
                    "Informe um nome de " + TAMANHO_MINIMO_NOME + " a " + cadastro.tamanhoMaximo + " caracteres.");
        }
        return limpo;
    }

    @Transactional
    public Item criar(Cadastro cadastro, long ator, String nome) {
        String limpo = validar(cadastro, nome);
        Instant agora = clock.instant();
        try {
            jdbc.sql("insert into " + cadastro.tabela + " (nome, ativo, versao, criado_em, criado_por, atualizado_em,"
                            + " atualizado_por) values (:nome, true, 0, :agora, :ator, :agora, :ator)")
                    .param("nome", limpo)
                    .param("agora", Timestamp.from(agora))
                    .param("ator", ator)
                    .update();
        } catch (DuplicateKeyException e) {
            throw nomeEmUso(cadastro);
        }
        long id = jdbc.sql("select id from " + cadastro.tabela + " where nome = :nome")
                .param("nome", limpo)
                .query(Long.class)
                .single();
        historico(cadastro, id, "CRIADO", null, ator, agora);
        return obter(cadastro, id).orElseThrow();
    }

    /** Renomeia. O mesmo nome (depois de normalizado) não altera nada nem gera histórico. */
    @Transactional
    public Item renomear(Cadastro cadastro, long ator, long id, long versao, String nome) {
        Item atual = travar(cadastro, id);
        if (atual.versao() != versao) {
            throw conflitoConcorrente(cadastro);
        }
        String limpo = validar(cadastro, nome);
        if (atual.nome().equals(limpo)) {
            return atual;
        }
        Instant agora = clock.instant();
        try {
            jdbc.sql("update " + cadastro.tabela + " set nome = :nome, versao = versao + 1, atualizado_em = :agora,"
                            + " atualizado_por = :ator where id = :id")
                    .param("nome", limpo)
                    .param("agora", Timestamp.from(agora))
                    .param("ator", ator)
                    .param("id", id)
                    .update();
        } catch (DuplicateKeyException e) {
            throw nomeEmUso(cadastro);
        }
        historico(cadastro, id, "RENOMEADO", atual.nome(), ator, agora);
        return obter(cadastro, id).orElseThrow();
    }

    /** Ativa ou desativa (preserva o cadastro e os vínculos). Pedir a situação que já vale não altera nada. */
    @Transactional
    public Item alterarSituacao(Cadastro cadastro, long ator, long id, long versao, boolean ativo) {
        Item atual = travar(cadastro, id);
        if (atual.versao() != versao) {
            throw conflitoConcorrente(cadastro);
        }
        if (atual.ativo() == ativo) {
            return atual;
        }
        Instant agora = clock.instant();
        jdbc.sql("update " + cadastro.tabela + " set ativo = :ativo, versao = versao + 1, atualizado_em = :agora,"
                        + " atualizado_por = :ator where id = :id")
                .param("ativo", ativo)
                .param("agora", Timestamp.from(agora))
                .param("ator", ator)
                .param("id", id)
                .update();
        historico(cadastro, id, ativo ? "ATIVADO" : "DESATIVADO", null, ator, agora);
        return obter(cadastro, id).orElseThrow();
    }

    private Item travar(Cadastro cadastro, long id) {
        return jdbc.sql(select(cadastro) + " where id = :id for update")
                .param("id", id)
                .query(Classificacoes::item)
                .optional()
                .orElseThrow(cadastro::naoEncontrado);
    }

    private void historico(Cadastro cadastro, long id, String acao, String nomeAnterior, long ator, Instant agora) {
        jdbc.sql("insert into atd_classificacao_historico (tipo, item_id, acao, nome_anterior, autor_usuario_id,"
                        + " ocorrido_em) values (:tipo, :id, :acao, :anterior, :ator, :agora)")
                .param("tipo", cadastro.name())
                .param("id", id)
                .param("acao", acao)
                .param("anterior", nomeAnterior)
                .param("ator", ator)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    private static FalhaAtendimento nomeEmUso(Cadastro cadastro) {
        return FalhaAtendimento.conflitoNoCampo("NOME_EM_USO", "nome", cadastro.nomeEmUso);
    }

    private static FalhaAtendimento conflitoConcorrente(Cadastro cadastro) {
        return FalhaAtendimento.conflito("CONFLITO_CONCORRENTE", cadastro.conflito);
    }

    // ------------------------------------------------------------------ consultas

    private static String select(Cadastro cadastro) {
        return "select id, nome, ativo, versao, criado_em, criado_por, atualizado_em, atualizado_por from "
                + cadastro.tabela;
    }

    @Transactional(readOnly = true)
    public Optional<Item> obter(Cadastro cadastro, long id) {
        return jdbc.sql(select(cadastro) + " where id = :id")
                .param("id", id)
                .query(Classificacoes::item)
                .optional();
    }

    /** Cadastro inteiro em ordem de nome (é pequeno: escolhas do painel, filtros e gerenciamento). */
    @Transactional(readOnly = true)
    public List<Item> listar(Cadastro cadastro, Situacao situacao) {
        String where = switch (situacao) {
            case ATIVOS -> " where ativo = true";
            case INATIVOS -> " where ativo = false";
            case TODOS -> "";
        };
        return jdbc.sql(select(cadastro) + where + " order by nome, id")
                .query(Classificacoes::item)
                .list();
    }

    /** Itens pelos ids, ativos ou não (exibição da classificação gravada). */
    @Transactional(readOnly = true)
    public Map<Long, Item> porIds(Cadastro cadastro, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql(select(cadastro) + " where id in (:ids)")
                .param("ids", ids)
                .query(Classificacoes::item)
                .list()
                .stream()
                .collect(Collectors.toMap(Item::id, Function.identity()));
    }

    /** Etiquetas de cada atendimento, em ordem de nome; atendimento sem etiqueta fica fora do mapa. */
    @Transactional(readOnly = true)
    public Map<Long, List<Item>> etiquetasDe(Collection<Long> atendimentos) {
        if (atendimentos.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Item>> mapa = new LinkedHashMap<>();
        jdbc.sql("select ae.atendimento_id, e.id, e.nome, e.ativo, e.versao, e.criado_em, e.criado_por,"
                        + " e.atualizado_em, e.atualizado_por from atd_atendimento_etiqueta ae"
                        + " join atd_etiqueta e on e.id = ae.etiqueta_id where ae.atendimento_id in (:ids)"
                        + " order by e.nome, e.id")
                .param("ids", atendimentos)
                .query((rs, n) -> {
                    mapa.computeIfAbsent(rs.getLong("atendimento_id"), k -> new ArrayList<>())
                            .add(item(rs, n));
                    return null;
                })
                .list();
        return mapa;
    }

    /**
     * Confere as escolhas de uma classificação nova: o setor e cada etiqueta precisam existir e estar
     * ativos, exceto os que o atendimento já tem (manter um item que foi desativado depois não é escolha
     * nova). Devolve os itens na ordem de nome, para o retrato do evento.
     */
    List<Item> exigirEscolhas(Cadastro cadastro, Collection<Long> ids, Collection<Long> jaVinculados, String campo) {
        Map<Long, Item> encontrados = porIds(cadastro, ids);
        for (Long id : ids) {
            Item item = encontrados.get(id);
            if (item == null) {
                throw FalhaAtendimento.campoInvalido(campo, cadastro.naoEncontrado);
            }
            if (!item.ativo() && !jaVinculados.contains(id)) {
                throw FalhaAtendimento.campoInvalido(
                        campo,
                        "“" + item.nome() + "” está inativ" + (cadastro == Cadastro.SETOR ? "o" : "a")
                                + " e não pode ser escolhid" + (cadastro == Cadastro.SETOR ? "o" : "a") + ".");
            }
        }
        return encontrados.values().stream()
                .sorted(Comparator.comparing(Item::nome).thenComparing(Item::id))
                .toList();
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        return new Item(
                rs.getLong("id"),
                rs.getString("nome"),
                rs.getBoolean("ativo"),
                rs.getLong("versao"),
                Objects.requireNonNull(rs.getTimestamp("criado_em")).toInstant(),
                rs.getLong("criado_por"),
                Objects.requireNonNull(rs.getTimestamp("atualizado_em")).toInstant(),
                rs.getLong("atualizado_por"));
    }
}
