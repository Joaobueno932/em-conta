package br.com.emconta.acesso.sessao;

import br.com.emconta.acesso.config.AcessoProperties.Regra;
import java.time.Duration;

/** Tipos de limite de tentativas e seus valores padrão (sobrescrevíveis em emconta.acesso.limites). */
public enum TipoLimite {
    LOGIN_CONTA(new Regra(5, Duration.ofMinutes(15), Duration.ofMinutes(15))),
    LOGIN_IP(new Regra(50, Duration.ofMinutes(15), Duration.ofMinutes(15))),
    RECUPERACAO_CONTA(new Regra(3, Duration.ofHours(1), Duration.ofHours(1))),
    RECUPERACAO_IP(new Regra(20, Duration.ofHours(1), Duration.ofHours(1))),
    REENVIO_CONVITE(new Regra(5, Duration.ofHours(1), Duration.ofHours(1))),
    /**
     * Uso de links de convite e redefinição (validação, aceite e redefinição) por IP. Só por IP: um
     * limite por conta poderia ser acionado por terceiros e impedir a recuperação legítima.
     */
    USO_TOKEN_IP(new Regra(30, Duration.ofMinutes(15), Duration.ofMinutes(15)));

    private final Regra padrao;

    TipoLimite(Regra padrao) {
        this.padrao = padrao;
    }

    public Regra padrao() {
        return padrao;
    }
}
