package br.com.emconta.atendimento.web;

import br.com.emconta.atendimento.nucleo.AvisosAtendimento.ConversaAlterada;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Avisos de mudança para as conversas abertas nesta instância (Server-Sent Events).
 *
 * <p>Cada tela aberta assina a conversa. A cada {@code intervalo} (e na hora, quando a mudança foi feita
 * nesta instância: {@link ConversaAlterada}), lê só os contadores de {@code atd_sinal} das conversas
 * assinadas, pela chave primária, e avisa quem assina uma conversa cujo contador mudou. O aviso não leva
 * conteúdo: a tela relê pelas rotas de sempre, com as mesmas permissões. Assim funciona com várias
 * réplicas e com o processamento no worker.
 *
 * <p>Cada conexão dura no máximo {@code duracao}; o navegador reconecta sozinho e, a cada (re)conexão,
 * recebe {@code pronto} e relê o que pode ter mudado no intervalo. Um comentário a cada {@code
 * manutencao} mantém proxies sem cortar a conexão e descobre conexões fechadas.
 */
@Component
class NotificadorConversas {

    private static final Logger log = LoggerFactory.getLogger(NotificadorConversas.class);

    private final JdbcClient jdbc;
    private final Duration intervalo;
    private final Duration duracao;
    private final Duration manutencao;
    private final Map<Long, Set<SseEmitter>> assinantes = new ConcurrentHashMap<>();
    private final Map<Long, Long> vistos = new ConcurrentHashMap<>();
    private final ScheduledExecutorService agenda = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("atendimento-eventos").daemon().factory());
    private final AtomicBoolean agendado = new AtomicBoolean();

    NotificadorConversas(
            JdbcClient jdbc,
            @Value("${emconta.atendimento.eventos.intervalo:PT0.3S}") Duration intervalo,
            @Value("${emconta.atendimento.eventos.duracao:PT5M}") Duration duracao,
            @Value("${emconta.atendimento.eventos.manutencao:PT20S}") Duration manutencao) {
        this.jdbc = jdbc;
        this.intervalo = intervalo;
        this.duracao = duracao;
        this.manutencao = manutencao;
    }

    @PostConstruct
    void iniciar() {
        agenda.scheduleWithFixedDelay(
                this::verificar, intervalo.toMillis(), intervalo.toMillis(), TimeUnit.MILLISECONDS);
        agenda.scheduleWithFixedDelay(
                this::manter, manutencao.toMillis(), manutencao.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void encerrar() {
        agenda.shutdownNow();
        assinantes.values().forEach(emissores -> emissores.forEach(SseEmitter::complete));
    }

    /** Nova assinatura (permissão e existência do atendimento já conferidas por quem chama). */
    SseEmitter assinar(long conversa) {
        SseEmitter emissor = new SseEmitter(duracao.toMillis());
        Runnable sair = () -> remover(conversa, emissor);
        emissor.onCompletion(sair);
        emissor.onTimeout(() -> {
            sair.run();
            emissor.complete();
        });
        emissor.onError(erro -> sair.run());
        long contador = contador(conversa);
        vistos.putIfAbsent(conversa, contador);
        assinantes.computeIfAbsent(conversa, c -> ConcurrentHashMap.newKeySet()).add(emissor);
        enviar(conversa, emissor, SseEmitter.event().name("pronto").data(contador));
        return emissor;
    }

    @EventListener
    void aoAlterar(ConversaAlterada alterada) {
        if (assinantes.containsKey(alterada.conversaId()) && agendado.compareAndSet(false, true)) {
            try {
                agenda.execute(this::verificar);
            } catch (RejectedExecutionException e) {
                agendado.set(false);
            }
        }
    }

    int assinaturas() {
        return assinantes.values().stream().mapToInt(Set::size).sum();
    }

    private void verificar() {
        agendado.set(false);
        try {
            List<Long> conversas = List.copyOf(assinantes.keySet());
            if (conversas.isEmpty()) {
                return;
            }
            jdbc.sql("select conversa_id, contador from atd_sinal where conversa_id in (:ids)")
                    .param("ids", conversas)
                    .query((rs, n) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                    .list()
                    .forEach(linha -> {
                        Long anterior = vistos.put(linha.getKey(), linha.getValue());
                        if (!linha.getValue().equals(anterior)) {
                            avisar(linha.getKey(), linha.getValue());
                        }
                    });
        } catch (RuntimeException e) {
            // Banco indisponível: a próxima verificação tenta de novo; a tela tem a consulta de segurança.
            log.warn(
                    "Atendimento: falha ao verificar mudanças ({})",
                    e.getClass().getSimpleName());
        }
    }

    private void avisar(long conversa, long contador) {
        for (SseEmitter emissor : assinantes.getOrDefault(conversa, Set.of())) {
            enviar(conversa, emissor, SseEmitter.event().name("mudanca").data(contador));
        }
    }

    private void manter() {
        try {
            assinantes.forEach((conversa, emissores) -> emissores.forEach(
                    emissor -> enviar(conversa, emissor, SseEmitter.event().comment("manter"))));
        } catch (RuntimeException e) {
            // Uma exceção aqui cancelaria a tarefa periódica: registra e segue.
            log.warn(
                    "Atendimento: falha na manutenção das conexões ({})",
                    e.getClass().getSimpleName());
        }
    }

    private void enviar(long conversa, SseEmitter emissor, SseEmitter.SseEventBuilder evento) {
        try {
            emissor.send(evento);
        } catch (IOException | IllegalStateException e) {
            // Conexão fechada pelo navegador (ou já concluída): sai da lista.
            remover(conversa, emissor);
        }
    }

    private void remover(long conversa, SseEmitter emissor) {
        assinantes.computeIfPresent(conversa, (c, emissores) -> {
            emissores.remove(emissor);
            if (emissores.isEmpty()) {
                vistos.remove(c);
                return null;
            }
            return emissores;
        });
    }

    private long contador(long conversa) {
        return jdbc.sql("select contador from atd_sinal where conversa_id = :c")
                .param("c", conversa)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }
}
