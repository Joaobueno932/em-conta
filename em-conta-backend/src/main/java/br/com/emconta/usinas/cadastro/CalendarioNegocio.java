package br.com.emconta.usinas.cadastro;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * "Hoje" para datas de negócio (vigência de vínculos). O fuso de referência do negócio ainda não foi
 * decidido; o padrão provisório é o de Mato Grosso do Sul, configurável em
 * {@code emconta.negocio.fuso}.
 */
@Component
class CalendarioNegocio {

    private final Clock clock;
    private final ZoneId fuso;

    CalendarioNegocio(Clock clock, @Value("${emconta.negocio.fuso:America/Campo_Grande}") String fuso) {
        this.clock = clock;
        this.fuso = ZoneId.of(fuso);
    }

    LocalDate hoje() {
        return LocalDate.ofInstant(clock.instant(), fuso);
    }
}
