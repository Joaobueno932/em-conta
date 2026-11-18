-- Setores e etiquetas do atendimento (protótipo: painel "Atendimento", Histórico geral e detalhe).
-- Cadastros compartilhados pela equipe; a classificação pertence a cada atendimento (ciclo): um setor
-- opcional e nenhuma ou várias etiquetas. Ciclos antigos ficam sem classificação (setor nulo, sem
-- etiquetas) e continuam consultáveis.
--
-- nome é único no cadastro, inclusive entre os inativos; a collation utf8mb4_0900_ai_ci compara sem
-- diferenciar maiúsculas nem acentos ("Financeiro" e "financeiro" são o mesmo nome). Desativar preserva o
-- cadastro e os vínculos já gravados; editar o nome vale para todos os atendimentos que o usam.
CREATE TABLE atd_setor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nome VARCHAR(60) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    versao BIGINT NOT NULL DEFAULT 0,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    CONSTRAINT atd_setor_pk PRIMARY KEY (id),
    CONSTRAINT atd_setor_nome_uk UNIQUE (nome),
    CONSTRAINT atd_setor_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT atd_setor_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE atd_etiqueta (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nome VARCHAR(40) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    versao BIGINT NOT NULL DEFAULT 0,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    CONSTRAINT atd_etiqueta_pk PRIMARY KEY (id),
    CONSTRAINT atd_etiqueta_nome_uk UNIQUE (nome),
    CONSTRAINT atd_etiqueta_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT atd_etiqueta_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Histórico (somente inclusão) dos dois cadastros: quem, quando, a ação e, ao renomear, o nome anterior.
CREATE TABLE atd_classificacao_historico (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tipo VARCHAR(10) NOT NULL,
    item_id BIGINT NOT NULL,
    acao VARCHAR(20) NOT NULL,
    nome_anterior VARCHAR(60) NULL,
    autor_usuario_id BIGINT NOT NULL,
    ocorrido_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_classificacao_historico_pk PRIMARY KEY (id),
    CONSTRAINT atd_classificacao_historico_tipo_ck CHECK (tipo IN ('SETOR', 'ETIQUETA')),
    CONSTRAINT atd_classificacao_historico_acao_ck CHECK (acao IN ('CRIADO', 'RENOMEADO', 'ATIVADO', 'DESATIVADO')),
    CONSTRAINT atd_classificacao_historico_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_classificacao_historico_ix ON atd_classificacao_historico (tipo, item_id, ocorrido_em, id);

-- Setor do ciclo. Nulo = sem setor (todos os atendimentos anteriores a esta versão).
ALTER TABLE atd_atendimento
    ADD COLUMN setor_id BIGINT NULL,
    ADD CONSTRAINT atd_atendimento_setor_fk FOREIGN KEY (setor_id) REFERENCES atd_setor (id);

-- Filtro do Histórico geral por setor, na ordem da lista (encerramento mais recente).
CREATE INDEX atd_atendimento_setor_ix ON atd_atendimento (setor_id, situacao, encerrado_em);

-- Etiquetas do ciclo.
CREATE TABLE atd_atendimento_etiqueta (
    atendimento_id BIGINT NOT NULL,
    etiqueta_id BIGINT NOT NULL,
    CONSTRAINT atd_atendimento_etiqueta_pk PRIMARY KEY (atendimento_id, etiqueta_id),
    CONSTRAINT atd_atendimento_etiqueta_atendimento_fk FOREIGN KEY (atendimento_id) REFERENCES atd_atendimento (id),
    CONSTRAINT atd_atendimento_etiqueta_etiqueta_fk FOREIGN KEY (etiqueta_id) REFERENCES atd_etiqueta (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Filtro do Histórico geral por etiqueta.
CREATE INDEX atd_atendimento_etiqueta_etiqueta_ix ON atd_atendimento_etiqueta (etiqueta_id, atendimento_id);

-- Retrato textual do que o evento registrou (por exemplo, setor e etiquetas definidos numa
-- classificação), com os nomes daquele momento: renomear ou desativar depois não muda o histórico.
ALTER TABLE atd_evento ADD COLUMN detalhe VARCHAR(500) NULL;
