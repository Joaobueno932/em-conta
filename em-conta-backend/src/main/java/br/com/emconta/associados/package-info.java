/**
 * Módulo Associados.
 *
 * <p>Associados (pessoas físicas e jurídicas), seus contatos e unidades consumidoras (UCs), com
 * histórico de alterações. CPF/CNPJ é único no sistema; o número da UC é único por concessionária
 * (RN-CLI-03). Campos obrigatórios seguem o mínimo técnico provisório enquanto DP-01 está pendente.
 *
 * <p>Situações do cliente, da UC e do contrato (DP-08/DP-18), transferência de titularidade, vínculo
 * com usina, rateio e faturamento não fazem parte deste módulo nesta entrega.
 *
 * <p>Depende do módulo de acesso apenas pela API pública {@code br.com.emconta.acesso.Autoria}
 * (usuário autenticado e nomes de autores).
 */
@ApplicationModule(displayName = "Associados")
package br.com.emconta.associados;

import org.springframework.modulith.ApplicationModule;
