package br.com.emconta.acesso.usuarios;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Perfis atribuíveis. Nesta entrega existe apenas o perfil técnico de administrador. Os perfis de
 * negócio (comercial, atendimento, operação, financeiro, gestão) aguardam a matriz de permissões
 * aprovada.
 *
 * <p>As permissões de cada perfil são listadas explicitamente: uma permissão nova não é concedida a
 * nenhum perfil sem estar aqui. Perfis e permissões vivem no código, não no banco, então esta lista é
 * o equivalente controlado (versionado e revisado) de uma migration de concessão.
 */
public enum Perfil {
    ADMINISTRADOR(
            "Administrador",
            EnumSet.of(
                    Permissao.USUARIOS_CONSULTAR,
                    Permissao.USUARIOS_CONVIDAR,
                    Permissao.USUARIOS_EDITAR,
                    Permissao.USUARIOS_ATRIBUIR_PERFIS,
                    Permissao.USUARIOS_ALTERAR_SITUACAO,
                    Permissao.ASSOCIADOS_CONSULTAR,
                    Permissao.ASSOCIADOS_CADASTRAR,
                    Permissao.ASSOCIADOS_EDITAR,
                    Permissao.ASSOCIADOS_CONSULTAR_HISTORICO,
                    Permissao.UCS_CONSULTAR,
                    Permissao.UCS_CADASTRAR,
                    Permissao.UCS_EDITAR,
                    Permissao.USINAS_CONSULTAR,
                    Permissao.USINAS_CADASTRAR,
                    Permissao.USINAS_EDITAR,
                    Permissao.USINAS_CONSULTAR_HISTORICO,
                    Permissao.VINCULOS_USINA_CONSULTAR,
                    Permissao.VINCULOS_USINA_CRIAR,
                    Permissao.VINCULOS_USINA_ENCERRAR,
                    Permissao.ATENDIMENTO_CONSULTAR,
                    Permissao.ATENDIMENTO_ASSUMIR,
                    Permissao.ATENDIMENTO_RESPONDER,
                    Permissao.ATENDIMENTO_TRANSFERIR,
                    Permissao.ATENDIMENTO_ENCERRAR,
                    Permissao.ATENDIMENTO_VINCULAR_ASSOCIADO,
                    Permissao.ATENDIMENTO_REPROCESSAR,
                    Permissao.RESPOSTAS_RAPIDAS_CONSULTAR,
                    Permissao.RESPOSTAS_RAPIDAS_CADASTRAR,
                    Permissao.RESPOSTAS_RAPIDAS_EDITAR,
                    Permissao.RESPOSTAS_RAPIDAS_ALTERAR_SITUACAO));

    private final String nome;
    private final Set<Permissao> permissoes;

    Perfil(String nome, Set<Permissao> permissoes) {
        this.nome = nome;
        this.permissoes = Collections.unmodifiableSet(permissoes);
    }

    public String nome() {
        return nome;
    }

    public Set<Permissao> permissoes() {
        return permissoes;
    }
}
