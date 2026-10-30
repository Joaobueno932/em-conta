package br.com.emconta.integracoes.armazenamento;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Armazenamento de objetos compatível com S3 (MinIO no ambiente local). Os objetos são privados: a
 * aplicação lê e grava com a credencial abaixo e entrega o conteúdo só por rotas autenticadas; nenhuma
 * URL do armazenamento chega ao navegador. Sem endpoint, bucket e credenciais o armazenamento fica "não
 * configurado" e anexos não são recebidos nem enviados.
 *
 * @param endpoint URL do serviço (ex.: http://minio:9000)
 * @param regiao região usada na assinatura (MinIO aceita a padrão)
 * @param bucket bucket privado dos anexos
 * @param chaveAcesso usuário de acesso (nunca versionado)
 * @param chaveSecreta segredo de acesso (nunca versionado)
 * @param criarBucket cria o bucket na inicialização se não existir (ambientes locais e de teste)
 */
@ConfigurationProperties("emconta.armazenamento")
public record ArmazenamentoProperties(
        URI endpoint,
        @DefaultValue("us-east-1") String regiao,
        String bucket,
        String chaveAcesso,
        String chaveSecreta,
        @DefaultValue("false") boolean criarBucket,
        @DefaultValue("5s") Duration timeoutConexao,
        @DefaultValue("60s") Duration timeoutResposta) {

    public boolean configurado() {
        return endpoint != null && preenchido(bucket) && preenchido(chaveAcesso) && preenchido(chaveSecreta);
    }

    private static boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }
}
