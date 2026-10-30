package br.com.emconta.atendimento.nucleo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limites dos anexos. Cada limite é reduzido ao do provedor ({@link FormatosAnexo.Categoria}) se for
 * configurado acima dele.
 *
 * @param limiteImagem tamanho máximo de imagem (Cloud API: 5 MB)
 * @param limiteDocumento tamanho máximo de PDF (Cloud API: 100 MB)
 * @param limiteAudio tamanho máximo de áudio (Cloud API: 16 MB)
 * @param maximoTentativas tentativas de download de um anexo recebido antes da falha definitiva
 * @param uploadAbandonado idade a partir da qual um upload sem mensagem é removido
 */
@ConfigurationProperties("emconta.atendimento.anexos")
public record AnexosProperties(
        @DefaultValue("5MB") DataSize limiteImagem,
        @DefaultValue("16MB") DataSize limiteDocumento,
        @DefaultValue("16MB") DataSize limiteAudio,
        @DefaultValue("5") int maximoTentativas,
        @DefaultValue("1h") Duration uploadAbandonado) {

    public long limite(FormatosAnexo.Categoria categoria) {
        long configurado = switch (categoria) {
            case IMAGEM -> limiteImagem.toBytes();
            case DOCUMENTO -> limiteDocumento.toBytes();
            case AUDIO -> limiteAudio.toBytes();
        };
        return Math.min(configurado, categoria.limiteProvedor);
    }
}
