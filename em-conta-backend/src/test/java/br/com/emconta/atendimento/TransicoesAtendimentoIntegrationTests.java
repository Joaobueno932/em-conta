package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.acesso.usuarios.Usuario;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Transferir, devolver à fila e encerrar pela API real, com sessões distintas: só o responsável atual,
 * com a permissão específica, em EM_ATENDIMENTO e na versão vista. Ação recusada não altera o
 * atendimento nem grava evento.
 */
class TransicoesAtendimentoIntegrationTests extends AtendimentoIntegracao {

    private record Pessoa(Usuario usuario, Navegador sessao, String email) {}

    private Pessoa pessoa(Perfil perfil) {
        String email = emailUnico("atendente");
        Usuario usuario = criarAtivo(email, perfil);
        return new Pessoa(usuario, logado(email), email);
    }

    @Test
    void outroUsuarioComAsMesmasPermissoesNaoTransfereDevolveNemEncerra() throws Exception {
        Pessoa responsavel = pessoa(Perfil.ADMINISTRADOR);
        Pessoa outro = pessoa(Perfil.ADMINISTRADOR);
        Pessoa destino = pessoa(Perfil.ADMINISTRADOR);
        long atendimento = assumido(responsavel.sessao(), numeroUnico());
        long versao = versao(atendimento);
        long eventos = eventos(atendimento);

        for (MockHttpServletResponse recusada : List.of(
                outro.sessao()
                        .post(
                                url(atendimento, "transferencia"),
                                Map.of(
                                        "versao",
                                        versao,
                                        "destinoId",
                                        destino.usuario().getId())),
                outro.sessao().post(url(atendimento, "transferencia"), Map.of("versao", versao)),
                outro.sessao().post(url(atendimento, "encerramento"), Map.of("versao", versao)))) {
            assertThat(recusada.getStatus()).isEqualTo(409);
            assertThat(codigo(recusada)).isEqualTo("NAO_E_RESPONSAVEL");
        }
        assertThat(situacaoAtendimento(atendimento))
                .containsEntry("situacao", "EM_ATENDIMENTO")
                .containsEntry("responsavel_id", responsavel.usuario().getId())
                .containsEntry("versao", versao);
        assertThat(eventos(atendimento)).as("nenhum evento de sucesso").isEqualTo(eventos);

        // O responsável consegue.
        assertThat(responsavel
                        .sessao()
                        .post(url(atendimento, "transferencia"), Map.of("versao", versao))
                        .getStatus())
                .isEqualTo(204);
        assertThat(situacaoAtendimento(atendimento)).containsEntry("situacao", "AGUARDANDO");
    }

    @Test
    void depoisDaTransferenciaSoONovoResponsavelAge() throws Exception {
        Pessoa antigo = pessoa(Perfil.ADMINISTRADOR);
        Pessoa novo = pessoa(Perfil.ADMINISTRADOR);
        long atendimento = assumido(antigo.sessao(), numeroUnico());
        assertThat(antigo.sessao()
                        .post(
                                url(atendimento, "transferencia"),
                                Map.of(
                                        "versao",
                                        versao(atendimento),
                                        "destinoId",
                                        novo.usuario().getId()))
                        .getStatus())
                .isEqualTo(204);

        long versao = versao(atendimento);
        for (MockHttpServletResponse recusada : List.of(
                antigo.sessao()
                        .post(
                                url(atendimento, "transferencia"),
                                Map.of(
                                        "versao",
                                        versao,
                                        "destinoId",
                                        antigo.usuario().getId())),
                antigo.sessao().post(url(atendimento, "transferencia"), Map.of("versao", versao)),
                antigo.sessao().post(url(atendimento, "encerramento"), Map.of("versao", versao)))) {
            assertThat(codigo(recusada)).isEqualTo("NAO_E_RESPONSAVEL");
        }
        assertThat(versao(atendimento)).isEqualTo(versao);

        // O novo transfere de volta, e depois o antigo (de novo responsável) encerra.
        assertThat(novo.sessao()
                        .post(
                                url(atendimento, "transferencia"),
                                Map.of(
                                        "versao",
                                        versao,
                                        "destinoId",
                                        antigo.usuario().getId()))
                        .getStatus())
                .isEqualTo(204);
        assertThat(codigo(novo.sessao().post(url(atendimento, "encerramento"), Map.of("versao", versao + 1))))
                .isEqualTo("NAO_E_RESPONSAVEL");
        assertThat(antigo.sessao()
                        .post(url(atendimento, "encerramento"), Map.of("versao", versao + 1))
                        .getStatus())
                .isEqualTo(204);
        assertThat(jdbc.sql("select encerrado_por from atd_atendimento where id = :id")
                        .param("id", atendimento)
                        .query(Long.class)
                        .single())
                .isEqualTo(antigo.usuario().getId());
    }

    @Test
    void responsavelSemAPermissaoEspecificaContinuaBloqueado() throws Exception {
        Navegador semTransferirNemEncerrar = comPermissoes(
                Permissao.ATENDIMENTO_CONSULTAR, Permissao.ATENDIMENTO_ASSUMIR, Permissao.ATENDIMENTO_RESPONDER);
        long atendimento = assumido(semTransferirNemEncerrar, numeroUnico());
        long versao = versao(atendimento);
        for (MockHttpServletResponse negada : List.of(
                semTransferirNemEncerrar.post(url(atendimento, "transferencia"), Map.of("versao", versao)),
                semTransferirNemEncerrar.post(url(atendimento, "encerramento"), Map.of("versao", versao)))) {
            assertThat(negada.getStatus()).isEqualTo(403);
            assertThat(codigo(negada)).isEqualTo("ACESSO_NEGADO");
        }
        assertThat(versao(atendimento)).isEqualTo(versao);
        assertThat(situacaoAtendimento(atendimento)).containsEntry("situacao", "EM_ATENDIMENTO");
    }

    @Test
    void aguardandoEEncerradoNaoAceitamAsAcoes() throws Exception {
        Navegador admin = administrador();
        long aguardando = novoAtendimento(numeroUnico());
        long versao = versao(aguardando);
        assertThat(codigo(admin.post(url(aguardando, "encerramento"), Map.of("versao", versao))))
                .as("aguardando não é encerrado diretamente")
                .isEqualTo("ATENDIMENTO_SEM_RESPONSAVEL");
        assertThat(codigo(admin.post(url(aguardando, "transferencia"), Map.of("versao", versao))))
                .isEqualTo("ATENDIMENTO_SEM_RESPONSAVEL");
        assertThat(situacaoAtendimento(aguardando))
                .containsEntry("situacao", "AGUARDANDO")
                .containsEntry("versao", versao);

        long encerrado = assumido(admin, numeroUnico());
        assertThat(admin.post(url(encerrado, "encerramento"), Map.of("versao", versao(encerrado)))
                        .getStatus())
                .isEqualTo(204);
        long eventos = eventos(encerrado);
        for (MockHttpServletResponse recusada : List.of(
                admin.post(url(encerrado, "encerramento"), Map.of("versao", versao(encerrado))),
                admin.post(url(encerrado, "transferencia"), Map.of("versao", versao(encerrado))))) {
            assertThat(recusada.getStatus()).isEqualTo(409);
            assertThat(codigo(recusada)).isEqualTo("ATENDIMENTO_ENCERRADO");
        }
        assertThat(eventos(encerrado)).isEqualTo(eventos);
    }

    @Test
    void acoesConcorrentesNaoSobrescrevemAVencedora() throws Exception {
        Pessoa responsavel = pessoa(Perfil.ADMINISTRADOR);
        Pessoa destino = pessoa(Perfil.ADMINISTRADOR);
        for (int rodada = 0; rodada < 5; rodada++) {
            long atendimento = assumido(responsavel.sessao(), numeroUnico());
            long versao = versao(atendimento);
            // Mesma pessoa em três sessões (abas), cada uma com seu token CSRF.
            Navegador a = logado(responsavel.email());
            Navegador b = logado(responsavel.email());
            Navegador c = logado(responsavel.email());
            List<Callable<MockHttpServletResponse>> tarefas = List.of(
                    () -> a.post(
                            url(atendimento, "transferencia"),
                            Map.of(
                                    "versao",
                                    versao,
                                    "destinoId",
                                    destino.usuario().getId())),
                    () -> b.post(url(atendimento, "encerramento"), Map.of("versao", versao)),
                    () -> c.post(url(atendimento, "transferencia"), Map.of("versao", versao)));
            List<Integer> status = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
                status.add(f.get().getStatus());
            }
            assertThat(status).as("uma única vencedora").containsOnlyOnce(204);
            assertThat(status.stream().filter(s -> s == 409)).hasSize(2);
            assertThat(versao(atendimento)).isEqualTo(versao + 1);
            assertThat(jdbc.sql("select count(*) from atd_evento where atendimento_id = :a"
                                    + " and tipo in ('TRANSFERIDO', 'DEVOLVIDO_A_FILA', 'ENCERRADO')")
                            .param("a", atendimento)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            // O estado final corresponde à vencedora.
            String tipo = jdbc.sql("select tipo from atd_evento where atendimento_id = :a"
                            + " and tipo in ('TRANSFERIDO', 'DEVOLVIDO_A_FILA', 'ENCERRADO')")
                    .param("a", atendimento)
                    .query(String.class)
                    .single();
            Map<String, Object> estado = situacaoAtendimento(atendimento);
            switch (tipo) {
                case "TRANSFERIDO" ->
                    assertThat(estado)
                            .containsEntry("situacao", "EM_ATENDIMENTO")
                            .containsEntry("responsavel_id", destino.usuario().getId());
                case "DEVOLVIDO_A_FILA" -> assertThat(estado).containsEntry("situacao", "AGUARDANDO");
                default -> assertThat(estado).containsEntry("situacao", "ENCERRADO");
            }
        }
    }

    private Map<String, Object> situacaoAtendimento(long atendimento) {
        return jdbc.sql("select situacao, responsavel_id, versao from atd_atendimento where id = :id")
                .param("id", atendimento)
                .query()
                .singleRow();
    }

    private long eventos(long atendimento) {
        return contar("atd_evento where atendimento_id = :c", atendimento);
    }
}
