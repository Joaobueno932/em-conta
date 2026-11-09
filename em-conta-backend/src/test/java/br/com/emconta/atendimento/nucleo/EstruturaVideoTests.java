package br.com.emconta.atendimento.nucleo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Regressão da homologação de 09/11/2026 com os dois arquivos sintéticos usados nela (canvas e tom de
 * teste gravados pelo MediaRecorder do Chrome; o convencional é o mesmo conteúdo sem fragmentos). Com os
 * mesmos codecs (H.264 e AAC), a Meta marcou o fragmentado como falho (#131053) e entregou o convencional.
 */
class EstruturaVideoTests {

    @Test
    void mp4FragmentadoQueAMetaRecusouERejeitadoComOMotivo() {
        assertThatThrownBy(() -> EstruturaVideo.exigirCodecsAceitos(recurso("homologacao-mp4-fragmentado.mp4")))
                .isInstanceOf(ArquivoValidado.Invalido.class)
                .hasMessage("Este vídeo está em um formato incompatível com o envio pelo WhatsApp."
                        + " Exporte como MP4 padrão e tente novamente.");
    }

    @Test
    void mp4ConvencionalQueAMetaEntregouContinuaAceito() {
        assertThatCode(() -> EstruturaVideo.exigirCodecsAceitos(recurso("homologacao-mp4-convencional.mp4")))
                .doesNotThrowAnyException();
    }

    private static Path recurso(String nome) throws URISyntaxException {
        return Path.of(
                EstruturaVideoTests.class.getResource("/atendimento/" + nome).toURI());
    }
}
