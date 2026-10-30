package br.com.emconta.acesso.usuarios;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Permissões por ação e recurso ({@code recurso:acao}). Somente as operações implementadas têm
 * permissão; permissões de módulos de negócio serão criadas junto com eles.
 */
public enum Permissao {
    USUARIOS_CONSULTAR("usuarios:consultar"),
    USUARIOS_CONVIDAR("usuarios:convidar"),
    USUARIOS_EDITAR("usuarios:editar"),
    USUARIOS_ATRIBUIR_PERFIS("usuarios:atribuir-perfis"),
    USUARIOS_ALTERAR_SITUACAO("usuarios:alterar-situacao"),
    ASSOCIADOS_CONSULTAR("associados:consultar"),
    ASSOCIADOS_CADASTRAR("associados:cadastrar"),
    /** Dados cadastrais e contatos. */
    ASSOCIADOS_EDITAR("associados:editar"),
    ASSOCIADOS_CONSULTAR_HISTORICO("associados:consultar-historico"),
    UCS_CONSULTAR("ucs:consultar"),
    UCS_CADASTRAR("ucs:cadastrar"),
    UCS_EDITAR("ucs:editar"),
    /** Usinas e fazendas. */
    USINAS_CONSULTAR("usinas:consultar"),
    USINAS_CADASTRAR("usinas:cadastrar"),
    USINAS_EDITAR("usinas:editar"),
    /** Histórico de usinas e fazendas, inclusive dos vínculos. */
    USINAS_CONSULTAR_HISTORICO("usinas:consultar-historico"),
    /** Vínculos cadastrais UC → usina (pela usina e pela ficha do associado). */
    VINCULOS_USINA_CONSULTAR("vinculos-usina:consultar"),
    VINCULOS_USINA_CRIAR("vinculos-usina:criar"),
    VINCULOS_USINA_ENCERRAR("vinculos-usina:encerrar"),
    /** Fila, conversa, linha do tempo, histórico, estado do canal e a própria presença. */
    ATENDIMENTO_CONSULTAR("atendimento:consultar"),
    ATENDIMENTO_ASSUMIR("atendimento:assumir"),
    /** Responder (só o responsável) e ser destinatário de transferências. */
    ATENDIMENTO_RESPONDER("atendimento:responder"),
    ATENDIMENTO_TRANSFERIR("atendimento:transferir"),
    ATENDIMENTO_ENCERRAR("atendimento:encerrar"),
    ATENDIMENTO_VINCULAR_ASSOCIADO("atendimento:vincular-associado"),
    /** Consultar e reprocessar eventos do webhook em falha. */
    ATENDIMENTO_REPROCESSAR("atendimento:reprocessar"),
    /** Catálogo de respostas rápidas: consultar (inclui o seletor da conversa). */
    RESPOSTAS_RAPIDAS_CONSULTAR("respostas-rapidas:consultar"),
    RESPOSTAS_RAPIDAS_CADASTRAR("respostas-rapidas:cadastrar"),
    RESPOSTAS_RAPIDAS_EDITAR("respostas-rapidas:editar"),
    /** Ativar e desativar respostas rápidas. */
    RESPOSTAS_RAPIDAS_ALTERAR_SITUACAO("respostas-rapidas:alterar-situacao");

    private final String autoridade;

    Permissao(String autoridade) {
        this.autoridade = autoridade;
    }

    /** Valor usado como {@code GrantedAuthority} no Spring Security. */
    public String autoridade() {
        return autoridade;
    }

    public static Set<Permissao> de(Collection<Perfil> perfis) {
        Set<Permissao> permissoes = EnumSet.noneOf(Permissao.class);
        perfis.forEach(perfil -> permissoes.addAll(perfil.permissoes()));
        return permissoes;
    }
}
