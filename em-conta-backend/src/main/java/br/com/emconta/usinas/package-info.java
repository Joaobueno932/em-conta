/**
 * Módulo Usinas.
 *
 * <p>Cadastro básico de fazendas e usinas (US-CLI-03) e vínculos cadastrais UC → usina (US-CLI-04),
 * com histórico. Este módulo é o dono do vínculo: uma usina vigente por UC; o vínculo do associado
 * com a usina é derivado das suas UCs.
 *
 * <p>Depende de {@code associados} (API pública {@code UnidadesConsumidoras}) e de {@code acesso}
 * ({@code Autoria}). {@code associados} não depende deste módulo.
 *
 * <p>Vínculo cadastral não comprova rateio, disponibilidade de energia, ativação nem aptidão de
 * faturamento (RN-CLI-06). Rateio, geração, créditos e monitoramento ficam para releases posteriores.
 */
@ApplicationModule(displayName = "Usinas")
package br.com.emconta.usinas;

import org.springframework.modulith.ApplicationModule;
