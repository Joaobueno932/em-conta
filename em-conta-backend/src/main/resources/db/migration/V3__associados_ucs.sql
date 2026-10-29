-- Módulo associados: associados PF/PJ, contatos, unidades consumidoras e histórico de alterações.
-- Campos obrigatórios conforme o mínimo técnico provisório aprovado enquanto DP-01 está pendente.

CREATE TABLE associado (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tipo VARCHAR(2) NOT NULL,
    -- Forma canônica: CPF com 11 dígitos; CNPJ com 14 posições (12 alfanuméricas maiúsculas + 2 dígitos).
    -- Collation binária: a comparação não pode ignorar caixa nem acentos.
    documento VARCHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nome VARCHAR(200) NOT NULL,
    nome_fantasia VARCHAR(200) NULL,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT associado_pk PRIMARY KEY (id),
    CONSTRAINT associado_documento_uk UNIQUE (documento),
    CONSTRAINT associado_tipo_ck CHECK (tipo IN ('PF', 'PJ')),
    CONSTRAINT associado_documento_ck CHECK (
        (tipo = 'PF' AND documento REGEXP '^[0-9]{11}$')
        OR (tipo = 'PJ' AND documento REGEXP '^[0-9A-Z]{12}[0-9]{2}$')),
    CONSTRAINT associado_fantasia_ck CHECK (tipo = 'PJ' OR nome_fantasia IS NULL),
    CONSTRAINT associado_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT associado_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX associado_nome_ix ON associado (nome);
CREATE INDEX associado_criado_ix ON associado (criado_em);

-- Contatos: sem unicidade de e-mail ou telefone. Remoção lógica (removido_em).
CREATE TABLE associado_contato (
    id BIGINT NOT NULL AUTO_INCREMENT,
    associado_id BIGINT NOT NULL,
    nome VARCHAR(150) NOT NULL,
    email VARCHAR(254) NULL,
    -- Somente dígitos, com DDD e, se informado, DDI.
    telefone VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NULL,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    removido_em DATETIME(6) NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT associado_contato_pk PRIMARY KEY (id),
    CONSTRAINT associado_contato_associado_fk FOREIGN KEY (associado_id) REFERENCES associado (id),
    CONSTRAINT associado_contato_meio_ck CHECK (email IS NOT NULL OR telefone IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX associado_contato_associado_ix ON associado_contato (associado_id, removido_em);

-- Unidades consumidoras. Número guardado como texto (zeros à esquerda preservados), único por
-- concessionária (RN-CLI-03). Toda UC pertence a um associado.
CREATE TABLE unidade_consumidora (
    id BIGINT NOT NULL AUTO_INCREMENT,
    associado_id BIGINT NOT NULL,
    concessionaria VARCHAR(30) NOT NULL,
    numero VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    denominacao VARCHAR(120) NULL,
    endereco VARCHAR(300) NULL,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT unidade_consumidora_pk PRIMARY KEY (id),
    CONSTRAINT uc_concessionaria_numero_uk UNIQUE (concessionaria, numero),
    CONSTRAINT uc_concessionaria_ck CHECK (concessionaria IN ('ENERGISA_MS')),
    CONSTRAINT uc_associado_fk FOREIGN KEY (associado_id) REFERENCES associado (id),
    CONSTRAINT uc_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT uc_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX unidade_consumidora_associado_ix ON unidade_consumidora (associado_id);

-- Histórico de alterações (somente inclusão). alteracoes: lista JSON de {campo, antes, depois} com
-- dados pessoais já mascarados.
CREATE TABLE historico_cadastro (
    id BIGINT NOT NULL AUTO_INCREMENT,
    associado_id BIGINT NOT NULL,
    uc_id BIGINT NULL,
    contato_id BIGINT NULL,
    ocorrido_em DATETIME(6) NOT NULL,
    autor_usuario_id BIGINT NOT NULL,
    acao VARCHAR(40) NOT NULL,
    categoria VARCHAR(30) NOT NULL,
    referencia VARCHAR(200) NULL,
    alteracoes JSON NOT NULL,
    CONSTRAINT historico_cadastro_pk PRIMARY KEY (id),
    CONSTRAINT historico_associado_fk FOREIGN KEY (associado_id) REFERENCES associado (id),
    CONSTRAINT historico_uc_fk FOREIGN KEY (uc_id) REFERENCES unidade_consumidora (id),
    CONSTRAINT historico_contato_fk FOREIGN KEY (contato_id) REFERENCES associado_contato (id),
    CONSTRAINT historico_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX historico_cadastro_associado_ix ON historico_cadastro (associado_id, ocorrido_em, id);
CREATE INDEX historico_cadastro_categoria_ix ON historico_cadastro (associado_id, categoria, ocorrido_em);
