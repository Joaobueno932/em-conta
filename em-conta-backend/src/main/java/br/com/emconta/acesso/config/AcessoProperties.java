package br.com.emconta.acesso.config;

import br.com.emconta.acesso.sessao.TipoLimite;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do módulo de acesso ({@code emconta.acesso.*}).
 *
 * @param urlPublica URL pública do frontend usada nos links enviados por e-mail. Vem somente da
 *     configuração, nunca de cabeçalhos da requisição.
 */
@ConfigurationProperties("emconta.acesso")
public record AcessoProperties(
        URI urlPublica,
        @DefaultValue Sessao sessao,
        @DefaultValue Tokens tokens,
        @DefaultValue Email email,
        @DefaultValue Limites limites) {

    public AcessoProperties {
        urlPublica = validarUrlPublica(urlPublica);
    }

    /** @param duracaoMaxima tempo máximo de uma sessão desde o login, mesmo com atividade. */
    public record Sessao(@DefaultValue("12h") Duration duracaoMaxima) {}

    public record Tokens(
            @DefaultValue("72h") Duration validadeConvite,
            @DefaultValue("30m") Duration validadeRedefinicao) {}

    public record Email(
            @DefaultValue("Em Conta <nao-responda@localhost>")
            String remetente) {}

    /** Limites de tentativas. Valores ausentes usam o padrão de {@link TipoLimite}. */
    public record Limites(
            Regra loginConta,
            Regra loginIp,
            Regra recuperacaoConta,
            Regra recuperacaoIp,
            Regra reenvioConvite,
            Regra usoTokenIp) {

        public Regra regra(TipoLimite tipo) {
            Regra configurada = switch (tipo) {
                case LOGIN_CONTA -> loginConta;
                case LOGIN_IP -> loginIp;
                case RECUPERACAO_CONTA -> recuperacaoConta;
                case RECUPERACAO_IP -> recuperacaoIp;
                case REENVIO_CONVITE -> reenvioConvite;
                case USO_TOKEN_IP -> usoTokenIp;
            };
            return configurada != null ? configurada : tipo.padrao();
        }
    }

    /**
     * @param maximo tentativas permitidas dentro da janela
     * @param janela período de contagem
     * @param bloqueio duração do bloqueio ao exceder o máximo
     */
    public record Regra(int maximo, Duration janela, Duration bloqueio) {}

    public String link(String caminho, String token) {
        return urlPublica + caminho + "#token=" + token;
    }

    private static URI validarUrlPublica(URI url) {
        if (url == null || url.getHost() == null || url.getScheme() == null) {
            throw new IllegalArgumentException(
                    "emconta.acesso.url-publica (EMCONTA_URL_PUBLICA) deve ser uma URL absoluta, ex.: https://app.exemplo.com.br");
        }
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        boolean local = url.getHost().equals("localhost") || url.getHost().equals("127.0.0.1");
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
            throw new IllegalArgumentException("emconta.acesso.url-publica deve usar https (http só para localhost)");
        }
        if (url.getQuery() != null || url.getFragment() != null || url.getUserInfo() != null) {
            throw new IllegalArgumentException("emconta.acesso.url-publica não pode ter usuário, query ou fragmento");
        }
        String texto = url.toString();
        return URI.create(texto.endsWith("/") ? texto.substring(0, texto.length() - 1) : texto);
    }
}
