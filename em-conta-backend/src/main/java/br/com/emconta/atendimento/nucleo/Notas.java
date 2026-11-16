package br.com.emconta.atendimento.nucleo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notas internas da equipe numa conversa: texto, autor e horário, na linha do tempo e no histórico para
 * quem tem acesso ao atendimento. Ficam em {@code atd_nota}, fora de {@code atd_mensagem}: a fila de envio
 * ({@link Envio}) nunca as lê, então nada vai ao WhatsApp nem aparece para o cliente.
 *
 * <p>Mesma regra das ações na conversa: só o responsável atual, com o atendimento em andamento, registra
 * notas (409 {@code NAO_E_RESPONSAVEL}); outros atendentes leem. A janela de 24 h não se aplica, porque
 * nada vai ao cliente. Atendimento encerrado é somente leitura (409 {@code ATENDIMENTO_ENCERRADO}). A
 * chave de idempotência funciona como nas respostas: repetir a mesma nota devolve a mesma.
 */
@Service
public class Notas {

    static final int TAMANHO_MAXIMO = 4096;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AvisosAtendimento avisos;

    Notas(JdbcClient jdbc, Clock clock, AvisosAtendimento avisos) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.avisos = avisos;
    }

    private record Operacao(long autor, long atendimento, String texto) {}

    /** @return id da nota (ou da já registrada, na repetição exata) */
    @Transactional
    public long registrar(long ator, long atendimento, String texto, String chave) {
        if (chave == null || !Atendimentos.CHAVE.matcher(chave).matches()) {
            throw FalhaAtendimento.campoInvalido("chaveIdempotencia", "Chave de idempotência inválida.");
        }
        String conteudo = texto == null ? "" : texto.strip();
        if (conteudo.isEmpty() || conteudo.length() > TAMANHO_MAXIMO) {
            throw FalhaAtendimento.campoInvalido("texto", "Escreva uma nota de 1 a " + TAMANHO_MAXIMO + " caracteres.");
        }
        Operacao pedida = new Operacao(ator, atendimento, conteudo);
        Optional<Long> repetida = repeticao(chave, pedida, false);
        if (repetida.isPresent()) {
            return repetida.get();
        }
        Map<String, Object> estado = jdbc
                .sql("select conversa_id, situacao, responsavel_id from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        FalhaAtendimento.naoEncontrado("ATENDIMENTO_NAO_ENCONTRADO", "Atendimento não encontrado."));
        if ("ENCERRADO".equals(estado.get("situacao"))) {
            throw FalhaAtendimento.conflito(
                    "ATENDIMENTO_ENCERRADO", "Este atendimento foi encerrado: o histórico é somente leitura.");
        }
        Object responsavel = estado.get("responsavel_id");
        if (!"EM_ATENDIMENTO".equals(estado.get("situacao"))
                || responsavel == null
                || ((Number) responsavel).longValue() != ator) {
            throw FalhaAtendimento.conflito(
                    "NAO_E_RESPONSAVEL", "Só o responsável atual registra notas neste atendimento.");
        }
        long conversa = ((Number) estado.get("conversa_id")).longValue();
        try {
            jdbc.sql("insert into atd_nota (conversa_id, atendimento_id, autor_usuario_id, texto, criado_em,"
                            + " chave_idempotencia) values (:c, :a, :autor, :texto, :agora, :chave)")
                    .param("c", conversa)
                    .param("a", atendimento)
                    .param("autor", ator)
                    .param("texto", conteudo)
                    .param("agora", Timestamp.from(Instant.now(clock)))
                    .param("chave", chave)
                    .update();
        } catch (DuplicateKeyException e) {
            return repeticao(chave, pedida, true)
                    .orElseThrow(
                            () -> FalhaAtendimento.conflito("REQUISICAO_REPETIDA", "Esta nota já foi registrada."));
        }
        avisos.conversa(conversa);
        return jdbc.sql("select id from atd_nota where chave_idempotencia = :chave")
                .param("chave", chave)
                .query(Long.class)
                .single();
    }

    private Optional<Long> repeticao(String chave, Operacao pedida, boolean atual) {
        Optional<Map<String, Object>> linha = jdbc
                .sql(
                        "select id, autor_usuario_id, atendimento_id, texto from atd_nota where chave_idempotencia = :chave"
                                + (atual ? " for share" : ""))
                .param("chave", chave)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
        if (linha.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> registrada = linha.get();
        boolean mesma = ((Number) registrada.get("autor_usuario_id")).longValue() == pedida.autor()
                && ((Number) registrada.get("atendimento_id")).longValue() == pedida.atendimento()
                && Objects.equals(pedida.texto(), registrada.get("texto"));
        if (!mesma) {
            throw FalhaAtendimento.conflito(
                    "CHAVE_IDEMPOTENCIA_REUTILIZADA",
                    "Esta chave de idempotência já foi usada em outra nota. Nada foi registrado.");
        }
        return Optional.of(((Number) registrada.get("id")).longValue());
    }
}
