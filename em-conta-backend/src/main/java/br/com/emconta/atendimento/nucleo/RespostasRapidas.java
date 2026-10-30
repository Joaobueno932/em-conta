package br.com.emconta.atendimento.nucleo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de respostas rápidas: textos internos, compartilhados pela equipe, que o atendente insere no
 * campo de resposta para revisar e enviar pelo fluxo normal. Não são templates aprovados pela Meta, não
 * liberam envio fora da janela de 24 h e nunca enviam nada sozinhos.
 *
 * <p>Regras: título (até {@value #TAMANHO_MAXIMO_TITULO}), atalho e conteúdo obrigatórios; conteúdo até o
 * limite do envio ({@link Atendimentos#TAMANHO_MAXIMO_TEXTO}), com quebras de linha e Unicode preservados
 * (só os espaços das pontas são removidos, como no envio). Atalho normalizado ({@link #normalizarAtalho})
 * e único no catálogo, inclusive entre inativas (restrição única no banco). Edição e mudança de situação
 * exigem a versão vista; desativar preserva o cadastro. O histórico registra ação, autor, instante e quais
 * campos mudaram, nunca o conteúdo.
 */
@Service
public class RespostasRapidas {

    public static final int TAMANHO_MAXIMO_TITULO = 80;
    public static final int TAMANHO_MINIMO_ATALHO = 2;
    public static final int TAMANHO_MAXIMO_ATALHO = 30;
    public static final int TAMANHO_MAXIMO_PAGINA = 100;
    private static final Pattern ATALHO = Pattern.compile("[a-z0-9][a-z0-9-]{1,29}");

    private final JdbcClient jdbc;
    private final Clock clock;

    RespostasRapidas(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Situacao {
        ATIVAS,
        INATIVAS,
        TODAS
    }

    public record Dados(String titulo, String atalho, String conteudo) {}

    public record Resposta(
            long id,
            String titulo,
            String atalho,
            String conteudo,
            boolean ativa,
            long versao,
            Instant criadoEm,
            long criadoPor,
            Instant atualizadoEm,
            long atualizadoPor) {}

    /**
     * Forma persistida do atalho: sem espaços nas pontas, sem a barra inicial que a interface mostra
     * ("/segunda-via" → "segunda-via") e em minúsculas. O resultado precisa ter de 2 a 30 caracteres, só
     * letras minúsculas sem acento, dígitos e hífen, começando por letra ou dígito.
     */
    public static String normalizarAtalho(String atalho) {
        String limpo = atalho == null ? "" : atalho.strip();
        while (limpo.startsWith("/")) {
            limpo = limpo.substring(1);
        }
        return limpo.toLowerCase(Locale.ROOT);
    }

    private record Validos(String titulo, String atalho, String conteudo) {}

    private static Validos validar(Dados dados) {
        String titulo = dados.titulo() == null ? "" : dados.titulo().strip();
        if (titulo.isEmpty() || titulo.length() > TAMANHO_MAXIMO_TITULO) {
            throw FalhaAtendimento.campoInvalido(
                    "titulo", "Informe um título de até " + TAMANHO_MAXIMO_TITULO + " caracteres.");
        }
        String atalho = normalizarAtalho(dados.atalho());
        if (!ATALHO.matcher(atalho).matches()) {
            throw FalhaAtendimento.campoInvalido(
                    "atalho",
                    "Use de " + TAMANHO_MINIMO_ATALHO + " a " + TAMANHO_MAXIMO_ATALHO
                            + " caracteres: letras minúsculas sem acento, números e hífen (ex.: segunda-via).");
        }
        String conteudo = dados.conteudo() == null ? "" : dados.conteudo().strip();
        if (conteudo.isEmpty() || conteudo.length() > Atendimentos.TAMANHO_MAXIMO_TEXTO) {
            throw FalhaAtendimento.campoInvalido(
                    "conteudo", "Escreva um texto de 1 a " + Atendimentos.TAMANHO_MAXIMO_TEXTO + " caracteres.");
        }
        return new Validos(titulo, atalho, conteudo);
    }

    private static FalhaAtendimento atalhoEmUso() {
        return FalhaAtendimento.conflitoNoCampo(
                "ATALHO_EM_USO", "atalho", "Este atalho já é usado por outra resposta (ativa ou inativa).");
    }

    private static FalhaAtendimento naoEncontrada() {
        return FalhaAtendimento.respostaRapidaNaoEncontrada();
    }

    private static FalhaAtendimento conflitoConcorrente() {
        return FalhaAtendimento.conflito(
                "CONFLITO_CONCORRENTE",
                "Esta resposta foi alterada por outra pessoa. Recarregue para ver a versão atual antes de salvar.");
    }

    @Transactional
    public Resposta criar(long ator, Dados dados) {
        Validos v = validar(dados);
        Instant agora = clock.instant();
        try {
            jdbc.sql("insert into atd_resposta_rapida (titulo, atalho, conteudo, ativa, versao, criado_em, criado_por,"
                            + " atualizado_em, atualizado_por) values (:titulo, :atalho, :conteudo, true, 0, :agora, :ator,"
                            + " :agora, :ator)")
                    .param("titulo", v.titulo())
                    .param("atalho", v.atalho())
                    .param("conteudo", v.conteudo())
                    .param("agora", Timestamp.from(agora))
                    .param("ator", ator)
                    .update();
        } catch (DuplicateKeyException e) {
            throw atalhoEmUso();
        }
        long id = jdbc.sql("select id from atd_resposta_rapida where atalho = :atalho")
                .param("atalho", v.atalho())
                .query(Long.class)
                .single();
        historico(id, "CRIADA", null, ator, agora);
        return obter(id).orElseThrow();
    }

    @Transactional
    public Resposta editar(long ator, long id, long versao, Dados dados) {
        Resposta atual = travar(id);
        if (atual.versao() != versao) {
            throw conflitoConcorrente();
        }
        Validos v = validar(dados);
        List<String> campos = new ArrayList<>();
        if (!atual.titulo().equals(v.titulo())) campos.add("titulo");
        if (!atual.atalho().equals(v.atalho())) campos.add("atalho");
        if (!atual.conteudo().equals(v.conteudo())) campos.add("conteudo");
        if (campos.isEmpty()) {
            return atual;
        }
        Instant agora = clock.instant();
        try {
            jdbc.sql("update atd_resposta_rapida set titulo = :titulo, atalho = :atalho, conteudo = :conteudo,"
                            + " versao = versao + 1, atualizado_em = :agora, atualizado_por = :ator where id = :id")
                    .param("titulo", v.titulo())
                    .param("atalho", v.atalho())
                    .param("conteudo", v.conteudo())
                    .param("agora", Timestamp.from(agora))
                    .param("ator", ator)
                    .param("id", id)
                    .update();
        } catch (DuplicateKeyException e) {
            throw atalhoEmUso();
        }
        historico(id, "ALTERADA", String.join(",", campos), ator, agora);
        return obter(id).orElseThrow();
    }

    /** Ativa ou desativa (preserva o cadastro). Pedir a situação que já vale não altera nada. */
    @Transactional
    public Resposta alterarSituacao(long ator, long id, long versao, boolean ativa) {
        Resposta atual = travar(id);
        if (atual.versao() != versao) {
            throw conflitoConcorrente();
        }
        if (atual.ativa() == ativa) {
            return atual;
        }
        Instant agora = clock.instant();
        jdbc.sql("update atd_resposta_rapida set ativa = :ativa, versao = versao + 1, atualizado_em = :agora,"
                        + " atualizado_por = :ator where id = :id")
                .param("ativa", ativa)
                .param("agora", Timestamp.from(agora))
                .param("ator", ator)
                .param("id", id)
                .update();
        historico(id, ativa ? "ATIVADA" : "DESATIVADA", null, ator, agora);
        return obter(id).orElseThrow();
    }

    private Resposta travar(long id) {
        return jdbc.sql(SELECT + " where id = :id for update")
                .param("id", id)
                .query(RespostasRapidas::resposta)
                .optional()
                .orElseThrow(RespostasRapidas::naoEncontrada);
    }

    private void historico(long resposta, String acao, String campos, long ator, Instant agora) {
        jdbc.sql("insert into atd_resposta_rapida_historico (resposta_id, acao, campos, autor_usuario_id, ocorrido_em)"
                        + " values (:r, :acao, :campos, :ator, :agora)")
                .param("r", resposta)
                .param("acao", acao)
                .param("campos", campos)
                .param("ator", ator)
                .param("agora", Timestamp.from(agora))
                .update();
    }

    // ------------------------------------------------------------------ consultas

    private static final String SELECT = "select id, titulo, atalho, conteudo, ativa, versao, criado_em, criado_por,"
            + " atualizado_em, atualizado_por from atd_resposta_rapida";

    @Transactional(readOnly = true)
    public Optional<Resposta> obter(long id) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(RespostasRapidas::resposta)
                .optional();
    }

    /**
     * Página do catálogo em ordem de título. {@code busca} procura no título e no atalho e, com
     * {@code noConteudo}, também no conteúdo (a tela de gerenciamento); o seletor da conversa busca só
     * título e atalho, nas ativas.
     */
    @Transactional(readOnly = true)
    public Page<Resposta> listar(String busca, boolean noConteudo, Situacao situacao, int pagina, int tamanho) {
        List<String> condicoes = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        switch (situacao) {
            case ATIVAS -> condicoes.add("ativa = true");
            case INATIVAS -> condicoes.add("ativa = false");
            case TODAS -> {}
        }
        String termo = busca == null ? "" : busca.strip();
        if (!termo.isEmpty()) {
            String trecho = "%" + termo.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            String atalho = "%"
                    + normalizarAtalho(termo)
                            .replace("!", "!!")
                            .replace("%", "!%")
                            .replace("_", "!_") + "%";
            condicoes.add("(titulo like :trecho escape '!' or atalho like :atalho escape '!'"
                    + (noConteudo ? " or conteudo like :trecho escape '!'" : "") + ")");
            params.put("trecho", trecho);
            params.put("atalho", atalho);
        }
        String where = condicoes.isEmpty() ? "" : " where " + String.join(" and ", condicoes);
        var contagem = jdbc.sql("select count(*) from atd_resposta_rapida" + where);
        var consulta = jdbc.sql(SELECT + where + " order by titulo, id limit :limite offset :deslocamento");
        for (var p : params.entrySet()) {
            contagem = contagem.param(p.getKey(), p.getValue());
            consulta = consulta.param(p.getKey(), p.getValue());
        }
        long total = contagem.query(Long.class).single();
        List<Resposta> itens = consulta.param("limite", tamanho)
                .param("deslocamento", (long) pagina * tamanho)
                .query(RespostasRapidas::resposta)
                .list();
        return new PageImpl<>(itens, PageRequest.of(pagina, tamanho), total);
    }

    private static Resposta resposta(ResultSet rs, int n) throws SQLException {
        return new Resposta(
                rs.getLong("id"),
                rs.getString("titulo"),
                rs.getString("atalho"),
                rs.getString("conteudo"),
                rs.getBoolean("ativa"),
                rs.getLong("versao"),
                Objects.requireNonNull(rs.getTimestamp("criado_em")).toInstant(),
                rs.getLong("criado_por"),
                Objects.requireNonNull(rs.getTimestamp("atualizado_em")).toInstant(),
                rs.getLong("atualizado_por"));
    }
}
