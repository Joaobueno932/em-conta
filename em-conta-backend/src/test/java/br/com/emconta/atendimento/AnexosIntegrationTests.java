package br.com.emconta.atendimento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.emconta.acesso.Navegador;
import br.com.emconta.acesso.usuarios.Perfil;
import br.com.emconta.acesso.usuarios.Permissao;
import br.com.emconta.atendimento.GraphApiControlada.Download;
import br.com.emconta.atendimento.GraphApiControlada.Midia;
import br.com.emconta.atendimento.GraphApiControlada.Modo;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * Anexos (imagem, PDF, XLSX, vídeo e áudio) de ponta a ponta, com MySQL e MinIO reais e a Graph API
 * substituída pelo servidor CONTROLADO de teste. Arquivos pequenos e sintéticos, só com as assinaturas e
 * a estrutura mínima dos formatos.
 */
class AnexosIntegrationTests extends AtendimentoIntegracao {

    /** A fila de envio é global: esvazia o que outros testes deixaram antes de contar chamadas à Graph. */
    @org.junit.jupiter.api.BeforeEach
    void esvaziarFilas() {
        processar();
        GRAPH.limpar();
    }

    static final byte[] PNG =
            bytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "imagem de teste png");
    static final byte[] PDF = "%PDF-1.4\n% documento de teste\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] OGG =
            bytes("OggS".getBytes(StandardCharsets.US_ASCII), new String(new char[24]), "OpusHead", "audio de teste");

    // ------------------------------------------------------------------ recebimento

    @Test
    void recebeImagemPdfEAudioPorWebhookAssinadoEArmazenaNoMinio() throws Exception {
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        Navegador admin = administrador();
        String imagem = midiaUnica(), pdf = midiaUnica(), audio = midiaUnica();
        GRAPH.midia(imagem, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        GRAPH.midia(pdf, new Midia(PDF, "application/pdf", true, Download.NORMAL, null)); // hash em Base64
        GRAPH.midia(audio, new Midia(OGG, "audio/ogg", false, Download.NORMAL, null));

        receber(numero, "image", imagem, "image/png", null, "Foto do medidor");
        receber(numero, "document", pdf, "application/pdf", "..\\..\\etc/<script>fatura‮.pdf", null);
        receber(numero, "audio", audio, "audio/ogg; codecs=opus", null, null);
        // Antes do download a mensagem já aparece, com o anexo PENDENTE.
        JsonNode antes = corpo(admin.get(url(atendimento, "linha-do-tempo")));
        assertThat(antes.toString())
                .contains("\"tipoMensagem\":\"IMAGEM\"", "\"situacao\":\"PENDENTE\"", "Foto do medidor");

        midias.processarPendentes(10);
        List<Map<String, Object>> anexos = anexosDe(atendimento);
        assertThat(anexos).hasSize(3).allMatch(a -> "DISPONIVEL".equals(a.get("situacao")));
        Map<String, Object> documento = anexos.stream()
                .filter(a -> "DOCUMENTO".equals(a.get("categoria")))
                .findFirst()
                .orElseThrow();
        assertThat((String) documento.get("nome")).isEqualTo("_script_fatura_.pdf");
        assertThat((String) documento.get("chave_objeto"))
                .startsWith("atendimento/anexos/")
                .doesNotContain("fatura");
        assertThat(jdbc.sql("select count(*) from atd_anexo where chave_objeto like '%http%' or nome like '%http%'")
                        .query(Long.class)
                        .single())
                .as("nenhuma URL temporária gravada")
                .isZero();

        // Conteúdo servido pela rota autenticada é o mesmo arquivo, com cabeçalhos seguros.
        long idPdf = ((Number) documento.get("id")).longValue();
        MockHttpServletResponse conteudo = admin.get(url(atendimento, "anexos/" + idPdf + "/conteudo?download=true"));
        assertThat(conteudo.getStatus()).isEqualTo(200);
        assertThat(conteudo.getContentAsByteArray()).isEqualTo(PDF);
        assertThat(conteudo.getContentType()).isEqualTo("application/pdf");
        assertThat(conteudo.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(conteudo.getHeader("Content-Disposition"))
                .startsWith("attachment")
                .doesNotContain("..", "<");
        // Faixa (reprodução de áudio).
        long idAudio = idDe(anexos, "AUDIO");
        MockHttpServletResponse faixa =
                admin.get(url(atendimento, "anexos/" + idAudio + "/conteudo"), Map.of("Range", "bytes=0-3"));
        assertThat(faixa.getStatus()).isEqualTo(206);
        assertThat(faixa.getContentAsString()).isEqualTo("OggS");
        assertThat(faixa.getHeader("Content-Range")).isEqualTo("bytes 0-3/" + OGG.length);
        assertThat(faixa.getHeader("Content-Security-Policy")).contains("sandbox");
    }

    @Test
    void eventosRepetidosEProcessamentoConcorrenteNaoDuplicam() throws Exception {
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        String id = midiaUnica();
        GRAPH.midia(id, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        String wamid = wamid();
        String corpo = webhookMidia(numero, wamid, "image", id, "image/png", null, null);
        enviarWebhook(corpo, assinar(corpo));
        enviarWebhook(corpo, assinar(corpo));
        String reentrega = corpo.replace("\"Contato\"", "\"Contato Reentrega\"");
        enviarWebhook(reentrega, assinar(reentrega));
        recebimento.processarPendentes(50);
        assertThat(anexosDe(atendimento)).hasSize(1);

        List<Callable<Integer>> tarefas = List.of(
                () -> midias.processarPendentes(5),
                () -> midias.processarPendentes(5),
                () -> midias.processarPendentes(5));
        for (Future<Integer> f : emParalelo(tarefas)) {
            f.get();
        }
        assertThat(GRAPH.downloads(id)).as("um único download").isEqualTo(1);
        assertThat(anexosDe(atendimento).getFirst().get("situacao")).isEqualTo("DISPONIVEL");
    }

    @Test
    void urlExpiradaTimeoutArquivoAusenteENovaTentativa() throws Exception {
        String numero = numeroUnico();
        Navegador admin = administrador();
        long atendimento = assumido(admin, numero);
        String expira = midiaUnica(), lento = midiaUnica(), ausente = midiaUnica();
        GRAPH.midia(expira, new Midia(PNG, "image/png", false, Download.EXPIRAR_UMA_VEZ, null));
        GRAPH.midia(lento, new Midia(PNG, "image/png", false, Download.SEM_RESPOSTA, null));
        receber(numero, "image", expira, "image/png", null, null);
        receber(numero, "image", lento, "image/png", null, null);
        receber(numero, "image", ausente, "image/png", null, null); // a Meta não conhece este id

        midias.processarPendentes(10);
        assertThat(situacaoAnexo(expira)).isEqualTo("FALHA_TEMPORARIA");
        assertThat(situacaoAnexo(lento)).isEqualTo("FALHA_TEMPORARIA");
        assertThat(situacaoAnexo(ausente)).as("mídia inexistente é definitiva").isEqualTo("FALHA");

        // Nova tentativa: consulta outra URL e baixa.
        antecipar(expira);
        midias.processarPendentes(10);
        assertThat(situacaoAnexo(expira)).isEqualTo("DISPONIVEL");
        assertThat(GRAPH.downloads(expira)).isEqualTo(2);

        // Tentativas limitadas, com espera crescente, até a falha definitiva.
        for (int i = 0; i < 5; i++) {
            antecipar(lento);
            midias.processarPendentes(10);
        }
        assertThat(situacaoAnexo(lento)).isEqualTo("FALHA");
        assertThat(jdbc.sql("select tentativas from atd_anexo where id_midia_externo = :m")
                        .param("m", lento)
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);

        // Nova tentativa manual: só o responsável; não cria mensagem.
        long anexoAusente = idPorMidia(ausente);
        long mensagens = contar("atd_mensagem where atendimento_id = :c", atendimento);
        Navegador outro = administrador();
        assertThat(codigo(outro.post(url(atendimento, "anexos/" + anexoAusente + "/nova-tentativa"), null)))
                .isEqualTo("NAO_E_RESPONSAVEL");
        GRAPH.midia(ausente, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        assertThat(admin.post(url(atendimento, "anexos/" + anexoAusente + "/nova-tentativa"), null)
                        .getStatus())
                .isEqualTo(204);
        midias.processarPendentes(10);
        assertThat(situacaoAnexo(ausente)).isEqualTo("DISPONIVEL");
        assertThat(contar("atd_mensagem where atendimento_id = :c", atendimento))
                .isEqualTo(mensagens);
    }

    @Test
    void conteudoInvalidoMimeDivergenteHashLimiteETipoNaoSuportado() throws Exception {
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        String falso = midiaUnica(), grande = midiaUnica();
        GRAPH.midia(falso, new Midia(PDF, "image/png", false, Download.NORMAL, null)); // PDF dizendo ser PNG
        GRAPH.midia(grande, new Midia(bytes(PNG, "x".repeat(70_000)), "image/png", false, Download.NORMAL, null));
        receber(numero, "image", falso, "image/png", null, null);
        receber(numero, "image", grande, "image/png", null, null);
        // Hash do webhook divergente do arquivo servido.
        String alterado = midiaUnica();
        GRAPH.midia(alterado, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        String corpo = webhookMidia(numero, wamid(), "image", alterado, "image/png", "0".repeat(64), null);
        enviarWebhook(corpo, assinar(corpo));
        // Tipo fora da lista (DOCX) e tipo de mensagem não tratado (figurinha).
        receber(
                numero,
                "document",
                midiaUnica(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "a.docx",
                null);
        String figurinha = webhookMidia(numero, wamid(), "sticker", midiaUnica(), "image/webp", null, null);
        enviarWebhook(figurinha, assinar(figurinha));
        recebimento.processarPendentes(50);
        midias.processarPendentes(10);

        assertThat(situacaoAnexo(falso)).isEqualTo("FALHA");
        assertThat(erroAnexo(falso)).contains("não corresponde");
        assertThat(situacaoAnexo(grande)).isEqualTo("FALHA");
        assertThat(erroAnexo(grande)).contains("limite");
        assertThat(situacaoAnexo(alterado)).isEqualTo("FALHA");
        assertThat(erroAnexo(alterado)).contains("Hash");
        assertThat(contar("atd_anexo where atendimento_id = :c and situacao = 'NAO_SUPORTADO'", atendimento))
                .isEqualTo(1);
        assertThat(jdbc.sql("select erro from atd_anexo where atendimento_id = :c and situacao = 'NAO_SUPORTADO'")
                        .param("c", atendimento)
                        .query(String.class)
                        .single())
                .as("o motivo diz o que é aceito")
                .contains("PDF e planilha XLSX");
        assertThat(contar("atd_mensagem where atendimento_id = :c and tipo = 'NAO_SUPORTADO'", atendimento))
                .as("figurinha continua visível como não suportada")
                .isEqualTo(1);
        assertThat(contar("atd_anexo where atendimento_id = :c and chave_objeto is not null", atendimento))
                .as("nada inválido foi armazenado")
                .isZero();
    }

    @Test
    void destinoERedirecionamentoNaoAutorizadosNaoRecebemOToken() throws Exception {
        AtomicInteger acessos = new AtomicInteger();
        HttpServer externo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        externo.createContext("/", troca -> {
            acessos.incrementAndGet();
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        externo.start();
        try {
            String numero = numeroUnico();
            novoAtendimento(numero);
            String externa = midiaUnica(), redireciona = midiaUnica(), metadados = midiaUnica();
            String porta = String.valueOf(externo.getAddress().getPort());
            GRAPH.midia(
                    externa,
                    new Midia(PNG, "image/png", false, Download.URL_EXTERNA, "http://127.0.0.1:" + porta + "/x"));
            GRAPH.midia(
                    metadados,
                    new Midia(PNG, "image/png", false, Download.URL_EXTERNA, "https://169.254.169.254/latest"));
            GRAPH.midia(redireciona, new Midia(PNG, "image/png", false, Download.REDIRECIONAR, null));
            receber(numero, "image", externa, "image/png", null, null);
            receber(numero, "image", metadados, "image/png", null, null);
            receber(numero, "image", redireciona, "image/png", null, null);
            midias.processarPendentes(10);

            assertThat(situacaoAnexo(externa)).isEqualTo("FALHA");
            assertThat(erroAnexo(externa)).contains("não autorizado");
            assertThat(situacaoAnexo(metadados)).isEqualTo("FALHA");
            assertThat(situacaoAnexo(redireciona)).isEqualTo("FALHA");
            assertThat(erroAnexo(redireciona)).contains("Redirecionamento");
            assertThat(acessos.get())
                    .as("o destino não autorizado nunca é chamado")
                    .isZero();
        } finally {
            externo.stop(0);
        }
    }

    @Test
    void consultaEDownloadExigemPermissaoEARelacaoComOAtendimento() throws Exception {
        String numero = numeroUnico();
        Navegador admin = administrador();
        long primeiro = assumido(admin, numero);
        String id = midiaUnica();
        GRAPH.midia(id, new Midia(PNG, "image/png", false, Download.NORMAL, null));
        receber(numero, "image", id, "image/png", null, null);
        midias.processarPendentes(5);
        long anexo = idPorMidia(id);
        assertThat(admin.post(url(primeiro, "encerramento"), Map.of("versao", versao(primeiro)))
                        .getStatus())
                .isEqualTo(204);
        // Novo ciclo na mesma conversa.
        String retorno = mensagem(numero, "Retorno", wamid(), "voltei", agoraEpoch());
        enviarWebhook(retorno, assinar(retorno));
        recebimento.processarPendentes(50);
        long segundo = novoAtendimento(numero);
        long outraConversa = novoAtendimento(numeroUnico());

        String caminho = "anexos/" + anexo + "/conteudo";
        assertThat(admin.get(url(primeiro, caminho)).getStatus()).isEqualTo(200);
        assertThat(admin.get(url(primeiro, caminho + "?somenteEste=true")).getStatus())
                .isEqualTo(200);
        assertThat(admin.get(url(segundo, caminho)).getStatus())
                .as("conversa completa")
                .isEqualTo(200);
        assertThat(admin.get(url(segundo, caminho + "?somenteEste=true")).getStatus())
                .as("histórico só do outro ciclo")
                .isEqualTo(404);
        MockHttpServletResponse alheio = admin.get(url(outraConversa, caminho));
        assertThat(alheio.getStatus()).as("id conhecido, conversa errada").isEqualTo(404);
        assertThat(codigo(alheio)).isEqualTo("ANEXO_NAO_ENCONTRADO");

        Navegador semConsulta = comPermissoes(Permissao.ATENDIMENTO_ASSUMIR, Permissao.ATENDIMENTO_RESPONDER);
        for (MockHttpServletResponse negada : List.of(
                semConsulta.get(url(primeiro, caminho)),
                semConsulta.get(url(primeiro, "midia")),
                semConsulta.get(url(primeiro, "linha-do-tempo/situacoes?ids=1")))) {
            assertThat(negada.getStatus()).isEqualTo(403);
        }
        assertThat(new Navegador(mvc, json).get(url(primeiro, caminho)).getStatus())
                .isEqualTo(401);
        Navegador leitor = comPermissoes(Permissao.ATENDIMENTO_CONSULTAR);
        assertThat(leitor.get(url(primeiro, caminho)).getStatus()).isEqualTo(200);
        MockHttpServletResponse envio = leitor.multipart(
                url(segundo, "anexos"),
                arquivo("a.png", "image/png", PNG),
                Map.of("chaveIdempotencia", UUID.randomUUID().toString()));
        assertThat(envio.getStatus()).as("enviar exige atendimento:responder").isEqualTo(403);
    }

    // ------------------------------------------------------------------ envio

    @Test
    void responsavelEnviaImagemComLegendaEOutroUsuarioNao() throws Exception {
        String numero = numeroUnico();
        Navegador admin = administrador();
        long atendimento = assumido(admin, numero);
        Navegador outro = administrador();

        MockHttpServletResponse recusado = outro.multipart(
                url(atendimento, "anexos"),
                arquivo("foto.png", "image/png", PNG),
                campos("Segue a foto", UUID.randomUUID().toString()));
        assertThat(codigo(recusado)).isEqualTo("NAO_E_RESPONSAVEL");
        assertThat(contar("atd_anexo where atendimento_id = :c", atendimento)).isZero();

        long mensagem = enviarAnexo(
                admin,
                atendimento,
                arquivo("foto.png", "image/png", PNG),
                "Segue a foto",
                UUID.randomUUID().toString());
        assertThat(situacao(mensagem)).isEqualTo("PENDENTE");
        processar();
        assertThat(situacao(mensagem)).as("aceita não é entregue").isEqualTo("ACEITA");
        assertThat(GRAPH.uploads()).hasSize(1);
        assertThat(GRAPH.uploads().getFirst()).contains("name=\"type\"", "image/png", "name=\"messaging_product\"");
        String envio = GRAPH.requisicoes().stream()
                .filter(r -> r.contains("/messages"))
                .findFirst()
                .orElseThrow();
        assertThat(envio).contains("\"type\":\"image\"", "\"caption\":\"Segue a foto\"", "\"id\":\"midia-enviada-");
        // O arquivo enviado fica disponível na conversa.
        JsonNode linha = corpo(admin.get(url(atendimento, "linha-do-tempo")));
        assertThat(linha.toString()).contains("\"categoria\":\"IMAGEM\"", "\"situacao\":\"DISPONIVEL\"", "foto.png");
    }

    @Test
    void validacoesDoEnvioNoServidor() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        String chave = UUID.randomUUID().toString();
        record Caso(MockMultipartFile arquivo, String legenda, int status, String campo) {}
        for (Caso caso : List.of(
                new Caso(arquivo("foto.png", "image/png", PDF), null, 400, "arquivo"), // conteúdo de PDF
                new Caso(arquivo("foto.pdf", "image/png", PNG), null, 400, "arquivo"), // extensão errada
                new Caso(
                        arquivo(
                                "a.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                PDF),
                        null,
                        400,
                        "arquivo"),
                new Caso(
                        arquivo("script.exe", "application/octet-stream", "MZ".getBytes(StandardCharsets.US_ASCII)),
                        null,
                        400,
                        "arquivo"),
                new Caso(arquivo("vazio.png", "image/png", new byte[0]), null, 400, "arquivo"),
                new Caso(arquivo("grande.png", "image/png", bytes(PNG, "x".repeat(70_000))), null, 413, "arquivo"),
                new Caso(arquivo("voz.ogg", "audio/ogg", OGG), "legenda em áudio", 400, "legenda"),
                new Caso(arquivo("foto.png", "image/png", PNG), "x".repeat(1025), 400, "legenda"))) {
            MockHttpServletResponse resposta =
                    admin.multipart(url(atendimento, "anexos"), caso.arquivo(), campos(caso.legenda(), chave));
            assertThat(resposta.getStatus())
                    .as(caso.arquivo().getOriginalFilename())
                    .isEqualTo(caso.status());
            assertThat(corpo(resposta).path("campos").has(caso.campo()))
                    .as(caso.arquivo().getOriginalFilename())
                    .isTrue();
        }
        assertThat(contar("atd_anexo where atendimento_id = :c", atendimento)).isZero();
        assertThat(contar("atd_mensagem where chave_idempotencia = :c", chave)).isZero();

        // Nome malicioso é higienizado (só exibição), nunca usado como caminho.
        long mensagem = enviarAnexo(
                admin,
                atendimento,
                arquivo("../../\u0000relatorio<b>.pdf", "application/pdf", PDF),
                null,
                UUID.randomUUID().toString());
        Map<String, Object> anexo = jdbc.sql("select nome, chave_objeto from atd_anexo where mensagem_id = :m")
                .param("m", mensagem)
                .query()
                .singleRow();
        assertThat((String) anexo.get("nome")).isEqualTo("_relatorio_b_.pdf");
        assertThat((String) anexo.get("chave_objeto")).doesNotContain("relatorio");
    }

    // ------------------------------------------------------------------ vídeo e planilha

    static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final byte[] MP4 = mp4("avc1", "mp4a");
    static final byte[] XLSX = planilha(true, false);

    @Test
    void recebeVideoEPlanilhaComTipoRealReproducaoEDownload() throws Exception {
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        Navegador admin = administrador();
        String video = midiaUnica(), planilha = midiaUnica();
        GRAPH.midia(video, new Midia(MP4, "video/mp4", false, Download.NORMAL, null));
        GRAPH.midia(planilha, new Midia(XLSX, XLSX_MIME, true, Download.NORMAL, null));
        receber(numero, "video", video, "video/mp4", null, "Video do inversor");
        receber(numero, "document", planilha, XLSX_MIME, "leituras.xlsx", null);
        midias.processarPendentes(10);

        assertThat(situacaoAnexo(video)).isEqualTo("DISPONIVEL");
        assertThat(situacaoAnexo(planilha)).isEqualTo("DISPONIVEL");
        JsonNode linha = corpo(admin.get(url(atendimento, "linha-do-tempo")));
        assertThat(linha.toString())
                .contains(
                        "\"tipoMensagem\":\"VIDEO\"",
                        "\"categoria\":\"VIDEO\"",
                        "Video do inversor",
                        "\"mimeType\":\"" + XLSX_MIME + "\"",
                        "leituras.xlsx");

        // Vídeo: exibido no navegador, isolado e com faixa (reprodução e avanço).
        MockHttpServletResponse faixa =
                admin.get(url(atendimento, "anexos/" + idPorMidia(video) + "/conteudo"), Map.of("Range", "bytes=4-7"));
        assertThat(faixa.getStatus()).isEqualTo(206);
        assertThat(faixa.getContentAsString()).isEqualTo("ftyp");
        assertThat(faixa.getContentType()).isEqualTo("video/mp4");
        assertThat(faixa.getHeader("Content-Disposition")).startsWith("inline");
        assertThat(faixa.getHeader("Content-Security-Policy")).contains("sandbox", "media-src 'self'");
        // Planilha: sempre como download (o navegador não a exibe), mesmo sem pedir.
        MockHttpServletResponse xlsx = admin.get(url(atendimento, "anexos/" + idPorMidia(planilha) + "/conteudo"));
        assertThat(xlsx.getStatus()).isEqualTo(200);
        assertThat(xlsx.getContentAsByteArray()).isEqualTo(XLSX);
        assertThat(xlsx.getContentType()).isEqualTo(XLSX_MIME);
        assertThat(xlsx.getHeader("Content-Disposition"))
                .startsWith("attachment")
                .contains("leituras.xlsx");
        assertThat(xlsx.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        assertThat(corpo(admin.get(url(atendimento, "midia?categoria=VIDEO"))).get("itens"))
                .hasSize(1);
        assertThat(corpo(admin.get(url(atendimento, "midia?categoria=DOCUMENTO")))
                        .get("itens")
                        .get(0)
                        .get("mimeType")
                        .asString())
                .isEqualTo(XLSX_MIME);
    }

    @Test
    void planilhaRecebidaSemPastaDeTrabalhoOuComMacrosNaoEDisponibilizada() throws Exception {
        String numero = numeroUnico();
        long atendimento = novoAtendimento(numero);
        String semPasta = midiaUnica(), comMacros = midiaUnica();
        GRAPH.midia(semPasta, new Midia(planilha(false, false), XLSX_MIME, false, Download.NORMAL, null));
        GRAPH.midia(comMacros, new Midia(planilha(true, true), XLSX_MIME, false, Download.NORMAL, null));
        receber(numero, "document", semPasta, XLSX_MIME, "a.xlsx", null);
        receber(numero, "document", comMacros, XLSX_MIME, "b.xlsx", null);
        midias.processarPendentes(10);

        assertThat(situacaoAnexo(semPasta)).isEqualTo("FALHA");
        assertThat(erroAnexo(semPasta)).contains("planilha XLSX válida");
        assertThat(situacaoAnexo(comMacros)).isEqualTo("FALHA");
        assertThat(erroAnexo(comMacros)).contains("macros");
        assertThat(contar("atd_anexo where atendimento_id = :c and chave_objeto is not null", atendimento))
                .isZero();
    }

    @Test
    void responsavelEnviaVideoComLegendaEPlanilhaComNome() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        long video = enviarAnexo(
                admin,
                atendimento,
                arquivo("visita.mp4", "video/mp4", MP4),
                "Video da visita",
                UUID.randomUUID().toString());
        long planilha = enviarAnexo(
                admin,
                atendimento,
                arquivo("consumo.xlsx", XLSX_MIME, XLSX),
                "Consumo do mes",
                UUID.randomUUID().toString());
        processar();

        assertThat(situacao(video)).isEqualTo("ACEITA");
        assertThat(situacao(planilha)).isEqualTo("ACEITA");
        assertThat(GRAPH.uploads()).hasSize(2);
        assertThat(String.join("\n", GRAPH.uploads()))
                .contains("video/mp4", XLSX_MIME, "filename=\"anexo.mp4\"", "filename=\"anexo.xlsx\"");
        List<String> envios = GRAPH.requisicoes().stream()
                .filter(r -> r.contains("/messages"))
                .toList();
        assertThat(envios)
                .anySatisfy(e -> assertThat(e).contains("\"type\":\"video\"", "\"caption\":\"Video da visita\""))
                .anySatisfy(e -> assertThat(e)
                        .contains(
                                "\"type\":\"document\"",
                                "\"filename\":\"consumo.xlsx\"",
                                "\"caption\":\"Consumo do mes\""));
    }

    @Test
    void videoForaDosCodecsDaMetaEPlanilhaInvalidaSaoRecusadosNoEnvio() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        byte[] caixaQuebrada = bytes(
                new byte[] {0, 0, 0, 16}, "ftypisom", new byte[] {0, 0, 2, 0}, new byte[] {0x7F, 0, 0, 0}, "moov");
        record Caso(MockMultipartFile arquivo, String motivo) {}
        for (Caso caso : List.of(
                new Caso(arquivo("hevc.mp4", "video/mp4", mp4("hvc1", "mp4a")), "H.264"),
                new Caso(arquivo("dois-audios.mp4", "video/mp4", mp4("avc1", "mp4a", "mp4a")), "no máximo uma"),
                new Caso(arquivo("opus.mp4", "video/mp4", mp4("avc1", "Opus")), "AAC"),
                new Caso(arquivo("so-audio.mp4", "video/mp4", mp4(null, "mp4a")), "faixa de vídeo"),
                new Caso(arquivo("quebrado.mp4", "video/mp4", caixaQuebrada), "Estrutura do vídeo"),
                new Caso(arquivo("antigo.3gp", "video/3gpp", mp4("s263", "samr")), "H.263"),
                new Caso(arquivo("video.mp4", "video/mp4", PNG), "não corresponde"),
                new Caso(arquivo("video.mov", "video/mp4", MP4), "extensão"),
                new Caso(arquivo("sem-pasta.xlsx", XLSX_MIME, planilha(false, false)), "planilha XLSX válida"),
                new Caso(arquivo("macros.xlsx", XLSX_MIME, planilha(true, true)), "macros"),
                new Caso(arquivo("falsa.xlsx", XLSX_MIME, bytes(new byte[] {'P', 'K', 3, 4}, "nao e zip")), "XLSX"),
                new Caso(arquivo("antiga.xls", "application/vnd.ms-excel", XLSX), "não suportado"))) {
            MockHttpServletResponse resposta = admin.multipart(
                    url(atendimento, "anexos"),
                    caso.arquivo(),
                    campos(null, UUID.randomUUID().toString()));
            String nome = caso.arquivo().getOriginalFilename();
            assertThat(resposta.getStatus()).as(nome).isEqualTo(400);
            assertThat(corpo(resposta).path("campos").path("arquivo").asString())
                    .as(nome)
                    .contains(caso.motivo());
        }
        assertThat(contar("atd_anexo where atendimento_id = :c", atendimento)).isZero();
        assertThat(GRAPH.uploads()).as("nada recusado chega à Meta").isEmpty();
    }

    @Test
    void idempotenciaSequencialEConcorrenteSemDuplicarEnvio() throws Exception {
        String email = emailUnico("anexo");
        criarAtivo(email, Perfil.ADMINISTRADOR);
        Navegador admin = logado(email);
        long atendimento = assumido(admin, numeroUnico());
        String chave = UUID.randomUUID().toString();
        long primeira = enviarAnexo(admin, atendimento, arquivo("a.pdf", "application/pdf", PDF), "Contrato", chave);
        assertThat(enviarAnexo(
                        admin, atendimento, arquivo("outro-nome.pdf", "application/pdf", PDF), "  Contrato ", chave))
                .as("mesmo arquivo e legenda: mesma operação")
                .isEqualTo(primeira);
        for (MockHttpServletResponse conflito : List.of(
                admin.multipart(
                        url(atendimento, "anexos"),
                        arquivo("a.pdf", "application/pdf", PDF),
                        campos("Outra legenda", chave)),
                admin.multipart(
                        url(atendimento, "anexos"), arquivo("a.png", "image/png", PNG), campos("Contrato", chave)),
                admin.post(url(atendimento, "mensagens"), Map.of("texto", "Contrato", "chaveIdempotencia", chave)))) {
            assertThat(conflito.getStatus()).isEqualTo(409);
            assertThat(codigo(conflito)).isEqualTo("CHAVE_IDEMPOTENCIA_REUTILIZADA");
        }
        assertThat(contar("atd_anexo where atendimento_id = :c", atendimento)).isEqualTo(1);

        String concorrente = UUID.randomUUID().toString();
        List<Callable<MockHttpServletResponse>> tarefas = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Navegador aba = logado(email);
            tarefas.add(() -> aba.multipart(
                    url(atendimento, "anexos"), arquivo("b.png", "image/png", PNG), campos(null, concorrente)));
        }
        List<Long> ids = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : emParalelo(tarefas)) {
            assertThat(f.get().getStatus()).isEqualTo(202);
            ids.add(corpo(f.get()).get("mensagemId").asLong());
        }
        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(contar("atd_anexo where atendimento_id = :c", atendimento))
                .as("uploads perdedores descartados")
                .isEqualTo(2);
        processar();
        assertThat(GRAPH.uploads()).as("um upload por mensagem").hasSize(2);
        assertThat(GRAPH.requisicoes().stream().filter(r -> r.contains("/messages")))
                .hasSize(2);
    }

    @Test
    void janelaExpiradaAntesDoEnvioEAntesDaNovaTentativa() throws Exception {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        ultimaEntrada(atendimento, t0.minus(Duration.ofHours(24)).plusSeconds(60));
        relogio.fixar(t0);
        long antes = enviarAnexo(
                admin,
                atendimento,
                arquivo("a.png", "image/png", PNG),
                null,
                UUID.randomUUID().toString());
        long temporaria = enviarAnexo(
                admin,
                atendimento,
                arquivo("b.pdf", "application/pdf", PDF),
                null,
                UUID.randomUUID().toString());
        // A primeira expira antes de qualquer tentativa; a segunda falha no upload e expira antes da próxima.
        jdbc.sql("update atd_mensagem set proxima_tentativa_em = :depois where id = :id")
                .param("depois", Timestamp.from(t0.plusSeconds(120)))
                .param("id", antes)
                .update();
        GRAPH.modoUpload(Modo.ERRO_TEMPORARIO);
        envio.enviarPendentes(5);
        assertThat(situacao(temporaria)).isEqualTo("PENDENTE");
        assertThat(jdbc.sql("select etapa_falha from atd_mensagem where id = :id")
                        .param("id", temporaria)
                        .query(String.class)
                        .single())
                .isEqualTo("UPLOAD");

        GRAPH.modoUpload(Modo.ACEITAR);
        relogio.fixar(t0.plusSeconds(3600));
        envio.enviarPendentes(5);
        assertThat(situacao(antes)).isEqualTo("JANELA_EXPIRADA");
        assertThat(situacao(temporaria)).isEqualTo("JANELA_EXPIRADA");
        assertThat(GRAPH.requisicoes().stream().filter(r -> r.contains("/messages")))
                .as("nenhuma mensagem enviada")
                .isEmpty();
        // Registro fora da janela também é recusado.
        MockHttpServletResponse fora = admin.multipart(
                url(atendimento, "anexos"),
                arquivo("c.png", "image/png", PNG),
                campos(null, UUID.randomUUID().toString()));
        assertThat(codigo(fora)).isEqualTo("JANELA_ENCERRADA");
    }

    @Test
    void resultadoDesconhecidoNoEnvioNaoReenviaNemRepeteUpload() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        long mensagem = enviarAnexo(
                admin,
                atendimento,
                arquivo("a.ogg", "audio/ogg", OGG),
                null,
                UUID.randomUUID().toString());
        GRAPH.modo(Modo.SEM_RESPOSTA);
        envio.enviarPendentes(5);
        assertThat(situacao(mensagem)).isEqualTo("DESCONHECIDA");
        GRAPH.modo(Modo.ACEITAR);
        envio.enviarPendentes(5);
        assertThat(situacao(mensagem)).isEqualTo("DESCONHECIDA");
        assertThat(GRAPH.uploads()).hasSize(1);
        assertThat(GRAPH.requisicoes().stream().filter(r -> r.contains("/messages")))
                .hasSize(1);
        assertThat(GRAPH.requisicoes().stream()
                        .filter(r -> r.contains("/messages"))
                        .findFirst()
                        .orElseThrow())
                .contains("\"type\":\"audio\"")
                .doesNotContain("caption");
    }

    @Test
    void falhaDoArmazenamentoNoEnvioNaoRegistraNada() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        MockHttpServletResponse resposta;
        try {
            resposta = admin.multipart(
                    url(atendimento, "anexos"),
                    arquivo("a.png", "image/png", PNG),
                    campos(null, UUID.randomUUID().toString()));
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
        }
        assertThat(resposta.getStatus()).isEqualTo(503);
        assertThat(codigo(resposta)).isEqualTo("ARMAZENAMENTO_INDISPONIVEL");
        assertThat(contar("atd_mensagem where atendimento_id = :c and direcao = 'SAIDA'", atendimento))
                .isZero();
        assertThat(contar("atd_anexo where atendimento_id = :c and mensagem_id is not null", atendimento))
                .isZero();
    }

    @Test
    void uploadAbandonadoERemovidoSemTocarEmAnexosVinculados() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        long mensagem = enviarAnexo(
                admin,
                atendimento,
                arquivo("a.png", "image/png", PNG),
                null,
                UUID.randomUUID().toString());
        jdbc.sql(
                        "insert into atd_anexo (conversa_id, atendimento_id, direcao, categoria, situacao, chave_objeto, criado_em,"
                                + " atualizado_em) select conversa_id, id, 'SAIDA', 'IMAGEM', 'RECEBENDO', :chave, :antes, :antes"
                                + " from atd_atendimento where id = :a")
                .param("chave", "atendimento/anexos/" + UUID.randomUUID())
                .param("antes", Timestamp.from(Instant.now().minus(Duration.ofHours(2))))
                .param("a", atendimento)
                .update();
        jdbc.sql("update atd_anexo set criado_em = :antes where mensagem_id = :m")
                .param("antes", Timestamp.from(Instant.now().minus(Duration.ofHours(2))))
                .param("m", mensagem)
                .update();
        midias.processarPendentes(1);
        assertThat(contar("atd_anexo where atendimento_id = :c and situacao = 'RECEBENDO'", atendimento))
                .isZero();
        assertThat(contar("atd_anexo where mensagem_id = :c", mensagem)).isEqualTo(1);
        long anexo = jdbc.sql("select id from atd_anexo where mensagem_id = :m")
                .param("m", mensagem)
                .query(Long.class)
                .single();
        assertThat(admin.get(url(atendimento, "anexos/" + anexo + "/conteudo")).getStatus())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ Mídia, busca e situações

    @Test
    void midiaPaginadaPorCategoriaEIsoladaPorCiclo() throws Exception {
        String numero = numeroUnico();
        Navegador admin = administrador();
        long primeiro = assumido(admin, numero);
        for (int i = 0; i < 3; i++) {
            enviarAnexo(
                    admin,
                    primeiro,
                    arquivo("f" + i + ".png", "image/png", bytes(PNG, "i" + i)),
                    null,
                    UUID.randomUUID().toString());
        }
        enviarAnexo(
                admin,
                primeiro,
                arquivo("d.pdf", "application/pdf", PDF),
                null,
                UUID.randomUUID().toString());
        assertThat(admin.post(url(primeiro, "encerramento"), Map.of("versao", versao(primeiro)))
                        .getStatus())
                .isEqualTo(204);
        String retorno = mensagem(numero, "Retorno", wamid(), "voltei", agoraEpoch());
        enviarWebhook(retorno, assinar(retorno));
        recebimento.processarPendentes(50);
        long segundo = novoAtendimento(numero);
        String id = midiaUnica();
        GRAPH.midia(id, new Midia(OGG, "audio/ogg", false, Download.NORMAL, null));
        receber(numero, "audio", id, "audio/ogg", null, null);

        JsonNode pagina1 = corpo(admin.get(url(segundo, "midia?tamanho=2")));
        assertThat(pagina1.get("itens")).hasSize(2);
        // O cursor da mensagem abre o contexto dela: a página logo antes dele+1 termina na mensagem.
        JsonNode item = pagina1.get("itens").get(1);
        String[] partes = item.get("cursorMensagem").asString().split("\\.");
        String logoDepois = partes[0] + "." + partes[1] + "." + (Long.parseLong(partes[2]) + 1);
        JsonNode contexto = corpo(admin.get(url(segundo, "linha-do-tempo?tamanho=5&antes=" + logoDepois)));
        JsonNode ultimo = contexto.get("itens").get(contexto.get("itens").size() - 1);
        assertThat(ultimo.get("id").asLong()).isEqualTo(item.get("mensagemId").asLong());
        JsonNode pagina2 = corpo(admin.get(
                url(segundo, "midia?tamanho=2&antes=" + pagina1.get("anterior").asLong())));
        JsonNode pagina3 = corpo(admin.get(
                url(segundo, "midia?tamanho=2&antes=" + pagina2.get("anterior").asLong())));
        assertThat(pagina2.get("itens")).hasSize(2);
        assertThat(pagina3.get("itens")).hasSize(1);
        assertThat(pagina3.has("anterior") && !pagina3.get("anterior").isNull()).isFalse();
        assertThat(corpo(admin.get(url(segundo, "midia?categoria=IMAGEM"))).get("itens"))
                .hasSize(3);
        assertThat(corpo(admin.get(url(segundo, "midia?categoria=AUDIO")))
                        .get("itens")
                        .get(0)
                        .get("direcao")
                        .asString())
                .isEqualTo("ENTRADA");
        // Histórico de um ciclo: só os anexos dele.
        JsonNode soPrimeiro = corpo(admin.get(url(primeiro, "midia?somenteEste=true")));
        assertThat(soPrimeiro.get("itens"))
                .hasSize(4)
                .allMatch(i -> i.get("atendimentoId").asLong() == primeiro);
        assertThat(corpo(admin.get(url(segundo, "midia?somenteEste=true"))).get("itens"))
                .hasSize(1);
        assertThat(corpo(admin.get(url(segundo, "midia?categoria=VIDEO"))).get("itens"))
                .isEmpty();
        assertThat(admin.get(url(segundo, "midia?categoria=OUTRA")).getStatus()).isEqualTo(400);
        assertThat(admin.get(url(segundo, "midia?tamanho=51")).getStatus()).isEqualTo(400);
    }

    @Test
    void buscaDaFilaEDoHistoricoPeloNomeDoAssociadoPfEPj() throws Exception {
        Navegador admin = administrador();
        String marca = UUID.randomUUID().toString().substring(0, 8);
        long pj = criarAssociado(admin, "PJ", cnpjValido(), "Cooperativa BuscaPJ" + marca);
        long pf = criarAssociado(admin, "PF", cpfValido(), "Maria BuscaPF" + marca);
        long atendimentoPj = novoAtendimento(numeroUnico());
        long atendimentoPf = novoAtendimento(numeroUnico());
        vincular(admin, atendimentoPj, pj);
        vincular(admin, atendimentoPf, pf);

        JsonNode porPj = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=BuscaPJ" + marca));
        assertThat(porPj.get("totalItens").asLong()).isEqualTo(1);
        assertThat(porPj.get("itens").get(0).get("id").asLong()).isEqualTo(atendimentoPj);
        JsonNode porMarca = corpo(admin.get("/api/v1/atendimento/atendimentos?visao=AGUARDANDO&busca=" + marca));
        assertThat(porMarca.get("totalItens").asLong())
                .as("PF e PJ, sem duplicar")
                .isEqualTo(2);
        assertThat(porMarca.toString()).doesNotContain("documento\":\"");

        assertThat(admin.post(url(atendimentoPf, "assuncao"), null).getStatus()).isEqualTo(204);
        assertThat(admin.post(url(atendimentoPf, "encerramento"), Map.of("versao", versao(atendimentoPf)))
                        .getStatus())
                .isEqualTo(204);
        JsonNode historico = corpo(admin.get("/api/v1/atendimento/historico?busca=BuscaPF" + marca));
        assertThat(historico.get("totalItens").asLong()).isEqualTo(1);
        assertThat(historico.get("itens").get(0).get("id").asLong()).isEqualTo(atendimentoPf);
    }

    @Test
    void situacoesDeMensagensAntigasEmLote() throws Exception {
        Navegador admin = administrador();
        long atendimento = assumido(admin, numeroUnico());
        long texto = idDaResposta(admin.post(
                url(atendimento, "mensagens"),
                Map.of("texto", "antiga", "chaveIdempotencia", UUID.randomUUID().toString())));
        long anexo = enviarAnexo(
                admin,
                atendimento,
                arquivo("a.png", "image/png", PNG),
                null,
                UUID.randomUUID().toString());
        long alheia = idDaResposta(respostaEmOutraConversa());
        processar();
        String wamidTexto = jdbc.sql("select id_externo from atd_mensagem where id = :id")
                .param("id", texto)
                .query(String.class)
                .single();
        webhookSituacao(wamidTexto, "delivered");
        recebimento.processarPendentes(10);

        JsonNode situacoes = corpo(
                admin.get(url(atendimento, "linha-do-tempo/situacoes?ids=" + texto + "," + anexo + "," + alheia)));
        assertThat(situacoes).hasSize(2);
        assertThat(situacoes.toString()).contains("\"situacao\":\"ENTREGUE\"", "\"categoria\":\"IMAGEM\"");
        assertThat(situacoes.get(0).has("atualizadoEm")).isTrue();
        StringBuilder muitos = new StringBuilder("1");
        for (int i = 2; i <= 101; i++) {
            muitos.append(',').append(i);
        }
        assertThat(admin.get(url(atendimento, "linha-do-tempo/situacoes?ids=" + muitos))
                        .getStatus())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ apoio

    private static byte[] bytes(Object... partes) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        for (Object parte : partes) {
            byte[] b = parte instanceof byte[] bs ? bs : String.valueOf(parte).getBytes(StandardCharsets.ISO_8859_1);
            saida.writeBytes(b);
        }
        return saida.toByteArray();
    }

    /** Caixa ISO BMFF: tamanho (4 bytes), tipo e conteúdo. */
    private static byte[] caixa(String tipo, byte[]... conteudo) {
        byte[] corpo = bytes((Object[]) conteudo);
        return ByteBuffer.allocate(8 + corpo.length)
                .putInt(8 + corpo.length)
                .put(tipo.getBytes(StandardCharsets.ISO_8859_1))
                .put(corpo)
                .array();
    }

    private static byte[] faixa(String tipo, String codec) {
        byte[] hdlr = caixa("hdlr", new byte[8], tipo.getBytes(StandardCharsets.ISO_8859_1), new byte[13]);
        byte[] stsd = caixa("stsd", new byte[] {0, 0, 0, 0, 0, 0, 0, 1}, caixa(codec, new byte[8]));
        return caixa("trak", caixa("tkhd", new byte[12]), caixa("mdia", hdlr, caixa("minf", caixa("stbl", stsd))));
    }

    /**
     * MP4 sintético mínimo: ftyp, dados e moov no fim (como um arquivo sem "faststart"), com uma faixa de
     * vídeo no codec pedido (nenhuma se nulo) e uma faixa por áudio pedido.
     */
    static byte[] mp4(String video, String... audios) {
        List<byte[]> faixas = new ArrayList<>();
        if (video != null) {
            faixas.add(faixa("vide", video));
        }
        for (String audio : audios) {
            faixas.add(faixa("soun", audio));
        }
        return bytes(
                caixa(
                        "ftyp",
                        "isom".getBytes(StandardCharsets.ISO_8859_1),
                        new byte[] {0, 0, 2, 0},
                        "isomavc1".getBytes(StandardCharsets.ISO_8859_1)),
                caixa("mdat", "dados de video de teste".getBytes(StandardCharsets.ISO_8859_1)),
                caixa("moov", faixas.toArray(byte[][]::new)));
    }

    /** Pacote XLSX sintético: tipos de conteúdo e, se pedido, pasta de trabalho e projeto de macros. */
    static byte[] planilha(boolean comPasta, boolean comMacros) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            if (comPasta) {
                zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
                zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
            }
            if (comMacros) {
                zip.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
                zip.write(new byte[16]);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return saida.toByteArray();
    }

    private static String midiaUnica() {
        return "midia" + UUID.randomUUID().toString().replace("-", "");
    }

    private static MockMultipartFile arquivo(String nome, String mime, byte[] conteudo) {
        return new MockMultipartFile("arquivo", nome, mime, conteudo);
    }

    private static Map<String, String> campos(String legenda, String chave) {
        return legenda == null
                ? Map.of("chaveIdempotencia", chave)
                : Map.of("legenda", legenda, "chaveIdempotencia", chave);
    }

    private long enviarAnexo(
            Navegador navegador, long atendimento, MockMultipartFile arquivo, String legenda, String chave) {
        MockHttpServletResponse resposta =
                navegador.multipart(url(atendimento, "anexos"), arquivo, campos(legenda, chave));
        assertThat(resposta.getStatus())
                .as(resposta.getContentAsByteArray().length + " bytes")
                .isEqualTo(202);
        return corpo(resposta).get("mensagemId").asLong();
    }

    private long idDaResposta(MockHttpServletResponse resposta) {
        assertThat(resposta.getStatus()).isEqualTo(202);
        return corpo(resposta).get("mensagemId").asLong();
    }

    /** Resposta de texto em outro atendimento (de outra conversa), por outro administrador. */
    private MockHttpServletResponse respostaEmOutraConversa() throws Exception {
        Navegador outro = administrador();
        long atendimento = assumido(outro, numeroUnico());
        return outro.post(
                url(atendimento, "mensagens"),
                Map.of(
                        "texto",
                        "outra conversa",
                        "chaveIdempotencia",
                        UUID.randomUUID().toString()));
    }

    private void webhookSituacao(String idExterno, String situacao) throws Exception {
        String corpo = "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"" + WABA + "\",\"changes\":["
                + "{\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\"" + NUMERO
                + "\"},\"statuses\":[{"
                + "\"id\":\"" + idExterno + "\",\"status\":\"" + situacao + "\",\"timestamp\":\"" + agoraEpoch()
                + "\",\"recipient_id\":\"1\"}]},\"field\":\"messages\"}]}]}";
        assertThat(enviarWebhook(corpo, assinar(corpo)).getStatus()).isEqualTo(200);
    }

    private List<Map<String, Object>> anexosDe(long atendimento) {
        return jdbc.sql("select * from atd_anexo where atendimento_id = :a order by id")
                .param("a", atendimento)
                .query()
                .listOfRows();
    }

    private static long idDe(List<Map<String, Object>> anexos, String categoria) {
        return anexos.stream()
                .filter(a -> categoria.equals(a.get("categoria")))
                .map(a -> ((Number) a.get("id")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private long idPorMidia(String idMidia) {
        return jdbc.sql("select id from atd_anexo where id_midia_externo = :m")
                .param("m", idMidia)
                .query(Long.class)
                .single();
    }

    private String situacaoAnexo(String idMidia) {
        return jdbc.sql("select situacao from atd_anexo where id_midia_externo = :m")
                .param("m", idMidia)
                .query(String.class)
                .single();
    }

    private String erroAnexo(String idMidia) {
        return jdbc.sql("select erro from atd_anexo where id_midia_externo = :m")
                .param("m", idMidia)
                .query(String.class)
                .single();
    }

    private void antecipar(String idMidia) {
        jdbc.sql("update atd_anexo set proxima_tentativa_em = :agora where id_midia_externo = :m")
                .param("agora", Timestamp.from(Instant.now().minusSeconds(1)))
                .param("m", idMidia)
                .update();
    }

    private void ultimaEntrada(long atendimento, Instant instante) {
        jdbc.sql(
                        "update atd_conversa set ultima_entrada_em = :h where id = (select conversa_id from atd_atendimento where id = :id)")
                .param("h", Timestamp.from(instante))
                .param("id", atendimento)
                .update();
    }

    private long criarAssociado(Navegador admin, String tipo, String documento, String nome) {
        MockHttpServletResponse resposta =
                admin.post("/api/v1/associados", Map.of("tipo", tipo, "documento", documento, "nome", nome));
        assertThat(resposta.getStatus() / 100).isEqualTo(2);
        return corpo(resposta).get("id").asLong();
    }

    private static String cpfValido() {
        int[] d = new int[11];
        for (int i = 0; i < 9; i++) {
            d[i] = java.util.concurrent.ThreadLocalRandom.current().nextInt(10);
        }
        for (int j = 9; j < 11; j++) {
            int soma = 0;
            for (int i = 0; i < j; i++) {
                soma += d[i] * (j + 1 - i);
            }
            int resto = (soma * 10) % 11;
            d[j] = resto == 10 ? 0 : resto;
        }
        StringBuilder cpf = new StringBuilder();
        for (int x : d) {
            cpf.append(x);
        }
        return cpf.toString();
    }

    private void vincular(Navegador admin, long atendimento, long associado) {
        long versaoConversa = corpo(admin.get("/api/v1/atendimento/atendimentos/" + atendimento))
                .get("versaoConversa")
                .asLong();
        assertThat(admin.put(
                                url(atendimento, "associado"),
                                Map.of("versaoConversa", versaoConversa, "associadoId", associado))
                        .getStatus())
                .isEqualTo(204);
    }
}
