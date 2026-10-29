/**
 * Módulo Acesso.
 *
 * <p>Autenticação, sessão, usuários, convites e autorização por ação/recurso.
 *
 * <p>Nesta etapa contém apenas a configuração base do Spring Security (negação por padrão,
 * CSRF ativo, respostas 401/403 em Problem Details). Login, logout, recuperação de senha,
 * convite e permissões serão entregues na próxima tarefa.
 */
@ApplicationModule(displayName = "Acesso")
package br.com.emconta.acesso;

import org.springframework.modulith.ApplicationModule;
