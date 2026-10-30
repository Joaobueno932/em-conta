-- Respostas rápidas: catálogo interno de textos reutilizáveis, compartilhado pela equipe. Não são
-- templates aprovados pela Meta; só preenchem o campo de resposta para revisão e envio manual.
--
-- atalho é a forma normalizada (minúsculas, dígitos e hífen, 2 a 30 caracteres, sem a barra exibida na
-- interface) e é único no catálogo, inclusive entre as inativas. Desativar preserva o cadastro.
CREATE TABLE atd_resposta_rapida (
    id BIGINT NOT NULL AUTO_INCREMENT,
    titulo VARCHAR(80) NOT NULL,
    atalho VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conteudo TEXT NOT NULL,
    ativa BOOLEAN NOT NULL DEFAULT TRUE,
    versao BIGINT NOT NULL DEFAULT 0,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    CONSTRAINT atd_resposta_rapida_pk PRIMARY KEY (id),
    CONSTRAINT atd_resposta_rapida_atalho_uk UNIQUE (atalho),
    CONSTRAINT atd_resposta_rapida_atalho_ck CHECK (atalho REGEXP '^[a-z0-9][a-z0-9-]{1,29}$'),
    CONSTRAINT atd_resposta_rapida_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT atd_resposta_rapida_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Listagem por situação em ordem de título (tela e seletor).
CREATE INDEX atd_resposta_rapida_lista_ix ON atd_resposta_rapida (ativa, titulo, id);

-- Histórico (somente inclusão) das alterações do catálogo: quem, quando, a ação e quais campos mudaram.
-- Não guarda o conteúdo das respostas.
CREATE TABLE atd_resposta_rapida_historico (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resposta_id BIGINT NOT NULL,
    acao VARCHAR(20) NOT NULL,
    campos VARCHAR(100) NULL,
    autor_usuario_id BIGINT NOT NULL,
    ocorrido_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_resposta_rapida_historico_pk PRIMARY KEY (id),
    CONSTRAINT atd_resposta_rapida_historico_acao_ck CHECK (acao IN ('CRIADA', 'ALTERADA', 'ATIVADA', 'DESATIVADA')),
    CONSTRAINT atd_resposta_rapida_historico_resposta_fk FOREIGN KEY (resposta_id) REFERENCES atd_resposta_rapida (id),
    CONSTRAINT atd_resposta_rapida_historico_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_resposta_rapida_historico_ix ON atd_resposta_rapida_historico (resposta_id, ocorrido_em, id);
