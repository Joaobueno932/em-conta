-- Módulo usinas: fazendas, usinas e vínculos cadastrais UC → usina, com histórico.
-- Decisões do responsável em 29/10/2026: fazenda 1:N usinas; identificador gerado (FZD-/USN-);
-- nome não é único (nome repetido gera aviso); uma usina vigente por UC; vínculo do associado
-- derivado das UCs; vigência por datas, sem início futuro e sem sobreposição; encerramento com motivo.

CREATE TABLE fazenda (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nome VARCHAR(150) NOT NULL,
    -- Nome sem acentos e em minúsculas, só para avisar de possível duplicidade (não é único).
    chave_nome VARCHAR(150) NOT NULL,
    cidade VARCHAR(100) NULL,
    uf CHAR(2) NULL,
    observacoes VARCHAR(500) NULL,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fazenda_pk PRIMARY KEY (id),
    CONSTRAINT fazenda_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT fazenda_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX fazenda_chave_nome_ix ON fazenda (chave_nome);
CREATE INDEX fazenda_nome_ix ON fazenda (nome);

CREATE TABLE usina (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nome VARCHAR(150) NOT NULL,
    chave_nome VARCHAR(150) NOT NULL,
    fazenda_id BIGINT NULL,
    cep CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    endereco VARCHAR(300) NULL,
    cidade VARCHAR(100) NULL,
    uf CHAR(2) NULL,
    observacoes VARCHAR(500) NULL,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    atualizado_por BIGINT NOT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT usina_pk PRIMARY KEY (id),
    CONSTRAINT usina_fazenda_fk FOREIGN KEY (fazenda_id) REFERENCES fazenda (id),
    CONSTRAINT usina_cep_ck CHECK (cep IS NULL OR cep REGEXP '^[0-9]{8}$'),
    CONSTRAINT usina_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT usina_atualizado_por_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX usina_chave_nome_ix ON usina (chave_nome);
CREATE INDEX usina_nome_ix ON usina (nome);
CREATE INDEX usina_uf_ix ON usina (uf);

-- Vigência [inicio, fim): sem fim = vigente. uc_vigente só é preenchida no vínculo vigente, então o
-- índice único garante no banco, também sob concorrência, no máximo uma usina vigente por UC.
CREATE TABLE vinculo_uc_usina (
    id BIGINT NOT NULL AUTO_INCREMENT,
    usina_id BIGINT NOT NULL,
    uc_id BIGINT NOT NULL,
    -- Titular da UC no vínculo (titularidade da UC é imutável).
    associado_id BIGINT NOT NULL,
    inicio DATE NOT NULL,
    fim DATE NULL,
    motivo_encerramento VARCHAR(500) NULL,
    criado_em DATETIME(6) NOT NULL,
    criado_por BIGINT NOT NULL,
    encerrado_em DATETIME(6) NULL,
    encerrado_por BIGINT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    uc_vigente BIGINT GENERATED ALWAYS AS (CASE WHEN fim IS NULL THEN uc_id END) STORED,
    CONSTRAINT vinculo_uc_usina_pk PRIMARY KEY (id),
    CONSTRAINT vinculo_uc_vigente_uk UNIQUE (uc_vigente),
    CONSTRAINT vinculo_fim_ck CHECK (fim IS NULL OR fim >= inicio),
    CONSTRAINT vinculo_encerramento_ck CHECK (
        (fim IS NULL AND motivo_encerramento IS NULL AND encerrado_em IS NULL AND encerrado_por IS NULL)
        OR (fim IS NOT NULL AND motivo_encerramento IS NOT NULL AND encerrado_em IS NOT NULL
            AND encerrado_por IS NOT NULL)),
    CONSTRAINT vinculo_usina_fk FOREIGN KEY (usina_id) REFERENCES usina (id),
    CONSTRAINT vinculo_uc_fk FOREIGN KEY (uc_id) REFERENCES unidade_consumidora (id),
    CONSTRAINT vinculo_associado_fk FOREIGN KEY (associado_id) REFERENCES associado (id),
    CONSTRAINT vinculo_criado_por_fk FOREIGN KEY (criado_por) REFERENCES usuario (id),
    CONSTRAINT vinculo_encerrado_por_fk FOREIGN KEY (encerrado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX vinculo_uc_ix ON vinculo_uc_usina (uc_id, inicio);
CREATE INDEX vinculo_usina_ix ON vinculo_uc_usina (usina_id, fim);
CREATE INDEX vinculo_associado_ix ON vinculo_uc_usina (associado_id, fim);

-- Histórico (somente inclusão) de fazendas, usinas e vínculos. alteracoes: lista JSON de
-- {campo, antes, depois}; CEP e endereço registram só que mudaram.
CREATE TABLE historico_usinas (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fazenda_id BIGINT NULL,
    usina_id BIGINT NULL,
    vinculo_id BIGINT NULL,
    ocorrido_em DATETIME(6) NOT NULL,
    autor_usuario_id BIGINT NOT NULL,
    acao VARCHAR(40) NOT NULL,
    referencia VARCHAR(200) NULL,
    motivo VARCHAR(500) NULL,
    alteracoes JSON NOT NULL,
    CONSTRAINT historico_usinas_pk PRIMARY KEY (id),
    CONSTRAINT historico_usinas_fazenda_fk FOREIGN KEY (fazenda_id) REFERENCES fazenda (id),
    CONSTRAINT historico_usinas_usina_fk FOREIGN KEY (usina_id) REFERENCES usina (id),
    CONSTRAINT historico_usinas_vinculo_fk FOREIGN KEY (vinculo_id) REFERENCES vinculo_uc_usina (id),
    CONSTRAINT historico_usinas_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX historico_usinas_usina_ix ON historico_usinas (usina_id, ocorrido_em, id);
CREATE INDEX historico_usinas_fazenda_ix ON historico_usinas (fazenda_id, ocorrido_em, id);
