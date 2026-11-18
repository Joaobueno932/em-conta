package br.com.emconta.atendimento.web;

import br.com.emconta.acesso.Autoria;
import br.com.emconta.associados.CatalogoAssociados;
import br.com.emconta.associados.CatalogoAssociados.ResumoAssociado;
import br.com.emconta.atendimento.nucleo.Classificacoes;
import br.com.emconta.atendimento.nucleo.Classificacoes.Cadastro;
import br.com.emconta.atendimento.nucleo.Classificacoes.Item;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.AnexoResumo;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.Cursor;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.Detalhe;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.ItemLinhaDoTempo;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.PaginaLinhaDoTempo;
import br.com.emconta.atendimento.nucleo.ConsultaAtendimento.Resumo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Conversa de um atendimento encerrado em texto (UTF-8), para guardar ou anexar a um processo interno: cabeçalho
 * com protocolo, contato, associado, responsável, encerramento, setor e etiquetas, e depois a linha do tempo só deste
 * ciclo, em ordem cronológica, com o que a tela do detalhe mostra a quem tem {@code atendimento:consultar}:
 * mensagens com a situação de envio, avisos automáticos, eventos (assumir, transferir, encerrar), notas
 * internas e anexos. Dos anexos vão nome, tipo e tamanho; o conteúdo continua só na rota autenticada.
 * Horários no fuso do negócio.
 */
@Component
class ExportacaoConversa {

    /** Teto de itens da linha do tempo num arquivo; acima disso vai o trecho mais recente, avisado. */
    static final int LIMITE_ITENS = 20_000;

    private static final int PAGINA = ConsultaAtendimento.TAMANHO_MAXIMO_PAGINA;

    private static final Map<String, String> SITUACAO = Map.ofEntries(
            Map.entry("PENDENTE", "aguardando envio"),
            Map.entry("ENVIANDO", "enviando"),
            Map.entry("ACEITA", "aceita pela Meta"),
            Map.entry("ENVIADA", "enviada"),
            Map.entry("ENTREGUE", "entregue"),
            Map.entry("LIDA", "lida"),
            Map.entry("FALHA", "falha no envio"),
            Map.entry("DESCONHECIDA", "envio não confirmado"),
            Map.entry("JANELA_EXPIRADA", "não enviada: janela de 24 horas expirada"),
            Map.entry("CANCELADA", "não enviada: a triagem terminou antes do envio"));

    private static final Map<String, String> EVENTO = Map.ofEntries(
            Map.entry("ATENDIMENTO_INICIADO", "Novo atendimento na fila"),
            Map.entry("ASSUMIDO", "assumiu o atendimento"),
            Map.entry("TRANSFERIDO", "transferiu o atendimento"),
            Map.entry("DEVOLVIDO_A_FILA", "devolveu o atendimento à fila"),
            Map.entry("ENCERRADO", "encerrou o atendimento"),
            Map.entry("ASSOCIADO_VINCULADO", "vinculou o associado"),
            Map.entry("ASSOCIADO_ALTERADO", "alterou o associado vinculado"),
            Map.entry("ASSOCIADO_DESVINCULADO", "removeu o vínculo com o associado"),
            Map.entry("CLASSIFICADO", "classificou o atendimento"),
            // Triagem automática: sem autor; o detalhe diz a opção e o destino.
            Map.entry("TRIAGEM_CLASSIFICADA", "Triagem automática classificou o atendimento"),
            Map.entry("TRIAGEM_ENCAMINHADA", "Triagem automática encaminhou para atendimento humano"),
            Map.entry("TRIAGEM_INTERROMPIDA", "Triagem automática encerrada"));

    private static final Map<String, String> CATEGORIA =
            Map.of("IMAGEM", "Imagem", "DOCUMENTO", "Documento", "AUDIO", "Áudio", "VIDEO", "Vídeo");

    private final ConsultaAtendimento consulta;
    private final Autoria autoria;
    private final CatalogoAssociados associados;
    private final Classificacoes classificacoes;
    private final Clock clock;

    ExportacaoConversa(
            ConsultaAtendimento consulta,
            Autoria autoria,
            CatalogoAssociados associados,
            Classificacoes classificacoes,
            Clock clock) {
        this.consulta = consulta;
        this.autoria = autoria;
        this.associados = associados;
        this.classificacoes = classificacoes;
        this.clock = clock;
    }

    record Arquivo(String nome, String conteudo, int itens) {}

    Optional<Arquivo> exportar(long atendimento, long ator) {
        // Só ciclo encerrado: o aberto ainda muda e é consultado na própria tela de Atendimento.
        return consulta.detalhe(atendimento)
                .filter(detalhe -> "ENCERRADO".equals(detalhe.resumo().situacao()))
                .map(detalhe -> montar(detalhe, ator));
    }

    private Arquivo montar(Detalhe detalhe, long ator) {
        Resumo r = detalhe.resumo();
        List<ItemLinhaDoTempo> itens = new ArrayList<>();
        Cursor cursor = null;
        boolean parcial = false;
        // Do mais recente para trás, até o início do ciclo; cada página já vem em ordem cronológica.
        while (true) {
            PaginaLinhaDoTempo pagina = consulta.linhaDoTempo(r.conversaId(), r.id(), cursor, PAGINA);
            List<ItemLinhaDoTempo> trecho = new ArrayList<>(pagina.itens());
            Collections.reverse(trecho);
            itens.addAll(trecho);
            if (pagina.anterior() == null) {
                break;
            }
            if (itens.size() >= LIMITE_ITENS) {
                parcial = true;
                break;
            }
            cursor = Cursor.ler(pagina.anterior()).orElseThrow();
        }
        Collections.reverse(itens);

        Set<Long> usuarios = new HashSet<>(List.of(ator));
        Set<Long> ids = new HashSet<>();
        if (r.responsavelId() != null) usuarios.add(r.responsavelId());
        if (r.encerradoPor() != null) usuarios.add(r.encerradoPor());
        if (r.associadoId() != null) ids.add(r.associadoId());
        itens.forEach(i -> {
            if (i.autorId() != null) usuarios.add(i.autorId());
            if (i.usuarioAlvoId() != null) usuarios.add(i.usuarioAlvoId());
            if (i.associadoId() != null) ids.add(i.associadoId());
        });
        Map<Long, String> nomes = autoria.nomes(usuarios);
        Map<Long, ResumoAssociado> porId = new HashMap<>();
        associados.porIds(ids).forEach(a -> porId.put(a.id(), a));

        String protocolo = "ATD-%06d".formatted(r.id());
        DateTimeFormatter dataHora =
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(consulta.fuso());
        DateTimeFormatter hora = DateTimeFormatter.ofPattern("HH:mm").withZone(consulta.fuso());
        DateTimeFormatter dia = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String contato = r.nomePerfil() == null || r.nomePerfil().isBlank() ? "+" + r.waId() : r.nomePerfil();

        StringBuilder s = new StringBuilder();
        s.append("Em Conta · Conversa do atendimento ").append(protocolo).append('\n');
        s.append("Documento interno: inclui notas da equipe que o cliente não vê.\n\n");
        linha(s, "Protocolo", protocolo);
        linha(s, "Situação", "Encerrado");
        linha(s, "Contato", contato.equals("+" + r.waId()) ? contato : contato + " (+" + r.waId() + ")");
        ResumoAssociado associado = r.associadoId() == null ? null : porId.get(r.associadoId());
        linha(s, "Associado", associado == null ? "—" : associado.nome() + " (" + associado.documentoMascarado() + ")");
        linha(s, "Atendente", nome(r.responsavelId(), nomes));
        linha(s, "Encerrado por", nome(r.encerradoPor(), nomes));
        // Nomes atuais do cadastro; o evento de cada classificação, abaixo, guarda os daquele momento.
        linha(
                s,
                "Setor",
                r.setorId() == null
                        ? "—"
                        : classificacoes
                                .obter(Cadastro.SETOR, r.setorId())
                                .map(ExportacaoConversa::nomeItem)
                                .orElse("—"));
        List<Item> etiquetas = classificacoes.etiquetasDe(List.of(r.id())).getOrDefault(r.id(), List.of());
        linha(
                s,
                "Etiquetas",
                etiquetas.isEmpty()
                        ? "—"
                        : etiquetas.stream().map(ExportacaoConversa::nomeItem).collect(Collectors.joining(", ")));
        linha(s, "Início", dataHora.format(r.iniciadoEm()));
        linha(
                s,
                "Primeira resposta",
                detalhe.primeiraRespostaEm() == null ? "—" : dataHora.format(detalhe.primeiraRespostaEm()));
        linha(s, "Encerramento", r.encerradoEm() == null ? "Em aberto" : dataHora.format(r.encerradoEm()));
        Instant agora = clock.instant();
        linha(s, "Exportado em", dataHora.format(agora) + " por " + nome(ator, nomes));
        linha(s, "Fuso dos horários", consulta.fuso().getId());
        if (parcial) {
            s.append("\nExportação parcial: só os ")
                    .append(itens.size())
                    .append(" registros mais recentes deste ciclo.\n");
        }

        LocalDate diaAtual = null;
        for (ItemLinhaDoTempo i : itens) {
            LocalDate d = LocalDate.ofInstant(i.ocorridoEm(), consulta.fuso());
            if (!d.equals(diaAtual)) {
                s.append("\n— ").append(dia.format(d)).append(" —\n");
                diaAtual = d;
            }
            s.append('[').append(hora.format(i.ocorridoEm())).append("] ");
            switch (i.tipo()) {
                case "EVENTO" -> s.append(evento(i, nomes, porId));
                case "NOTA" ->
                    s.append("Nota interna de ")
                            .append(nome(i.autorId(), nomes))
                            .append(": ")
                            .append(texto(i.texto()));
                default -> s.append(mensagem(i, contato, nomes));
            }
            s.append('\n');
        }
        if (itens.isEmpty()) {
            s.append("\nNenhuma mensagem neste atendimento.\n");
        }
        return new Arquivo("conversa-" + protocolo + ".txt", s.toString(), itens.size());
    }

    private static String mensagem(ItemLinhaDoTempo i, String contato, Map<Long, String> nomes) {
        StringBuilder s = new StringBuilder();
        boolean entrada = "ENTRADA".equals(i.direcao());
        if (entrada) {
            s.append(contato);
        } else if ("AVISO_RESPONSAVEL".equals(i.origem())) {
            s.append("Aviso automático");
        } else if ("TRIAGEM".equals(i.origem())) {
            s.append("Triagem automática");
        } else {
            s.append(nome(i.autorId(), nomes));
        }
        s.append(": ");
        if ("NAO_SUPORTADO".equals(i.tipoMensagem())) {
            s.append("[mensagem do tipo \"").append(i.tipoExterno()).append("\" não exibida pelo Em Conta]");
        }
        AnexoResumo anexo = i.anexo();
        if (anexo != null) {
            s.append('[')
                    .append(CATEGORIA.getOrDefault(anexo.categoria(), "Anexo"))
                    .append(": ");
            s.append(anexo.nome() != null ? anexo.nome() : "sem nome");
            if (anexo.tamanho() != null) s.append(", ").append(tamanho(anexo.tamanho()));
            if (!"DISPONIVEL".equals(anexo.situacao())) s.append(", arquivo indisponível");
            s.append(']');
            if (i.texto() != null && !i.texto().isBlank()) s.append(' ');
        }
        if (i.texto() != null) {
            s.append(texto(i.texto()));
        }
        if (!entrada && i.situacao() != null) {
            s.append(" (")
                    .append(SITUACAO.getOrDefault(i.situacao(), i.situacao().toLowerCase()));
            if (i.erroCodigo() != null) s.append(", erro ").append(i.erroCodigo());
            s.append(')');
        }
        return s.toString();
    }

    private static String evento(ItemLinhaDoTempo i, Map<Long, String> nomes, Map<Long, ResumoAssociado> associados) {
        String rotulo = EVENTO.getOrDefault(i.evento(), i.evento());
        if ("ATENDIMENTO_INICIADO".equals(i.evento())) {
            return rotulo;
        }
        if (i.evento() != null && i.evento().startsWith("TRIAGEM_")) {
            return i.texto() == null ? rotulo : rotulo + ": " + texto(i.texto());
        }
        StringBuilder s =
                new StringBuilder(nome(i.autorId(), nomes)).append(' ').append(rotulo);
        if (i.usuarioAlvoId() != null) s.append(" para ").append(nome(i.usuarioAlvoId(), nomes));
        if (i.associadoId() != null && associados.containsKey(i.associadoId())) {
            s.append(' ').append(associados.get(i.associadoId()).nome());
        }
        if (i.motivo() != null && !i.motivo().isBlank())
            s.append(" (motivo: ").append(texto(i.motivo())).append(')');
        if (i.texto() != null && !i.texto().isBlank()) s.append(": ").append(texto(i.texto()));
        return s.toString();
    }

    private static String nomeItem(Item item) {
        return item.ativo() ? item.nome() : item.nome() + " (inativo)";
    }

    private static void linha(StringBuilder s, String rotulo, String valor) {
        s.append(rotulo).append(": ").append(valor).append('\n');
    }

    private static String nome(Long id, Map<Long, String> nomes) {
        return id == null ? "—" : nomes.getOrDefault(id, "Usuário #" + id);
    }

    /** Quebras de linha dentro de um texto ficam recuadas, para cada registro começar numa linha nova. */
    private static String texto(String valor) {
        return valor.strip().replace("\r\n", "\n").replace("\n", "\n    ");
    }

    private static String tamanho(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)).replace('.', ',');
    }
}
