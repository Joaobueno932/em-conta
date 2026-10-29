package br.com.emconta.associados.cadastro;

/** Ações registradas no histórico do associado, agrupadas pela categoria usada no filtro da tela. */
public enum AcaoHistorico {
    ASSOCIADO_CADASTRADO(CategoriaHistorico.CADASTRO),
    ASSOCIADO_ALTERADO(CategoriaHistorico.CADASTRO),
    CONTATO_ADICIONADO(CategoriaHistorico.CONTATOS),
    CONTATO_ALTERADO(CategoriaHistorico.CONTATOS),
    CONTATO_REMOVIDO(CategoriaHistorico.CONTATOS),
    UC_CADASTRADA(CategoriaHistorico.UNIDADES_CONSUMIDORAS),
    UC_ALTERADA(CategoriaHistorico.UNIDADES_CONSUMIDORAS);

    private final CategoriaHistorico categoria;

    AcaoHistorico(CategoriaHistorico categoria) {
        this.categoria = categoria;
    }

    public CategoriaHistorico categoria() {
        return categoria;
    }

    public enum CategoriaHistorico {
        CADASTRO,
        CONTATOS,
        UNIDADES_CONSUMIDORAS
    }
}
