-- Módulo acesso: usuários internos, perfis, tokens de uso único, limites de tentativas e auditoria.

CREATE TABLE usuario (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nome VARCHAR(150) NOT NULL,
    -- E-mail normalizado pela aplicação (NFC, sem espaços nas pontas, minúsculas). A collation binária
    -- evita que a comparação acento/caixa-insensível do padrão do banco una endereços diferentes.
    email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    senha_hash VARCHAR(255) NULL,
    situacao VARCHAR(20) NOT NULL,
    -- Incrementada quando acessos devem ser revogados (senha, desativação); sessões com versão antiga
    -- são recusadas.
    versao_seguranca INT NOT NULL DEFAULT 0,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    ativado_em DATETIME(6) NULL,
    senha_alterada_em DATETIME(6) NULL,
    desativado_em DATETIME(6) NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT usuario_pk PRIMARY KEY (id),
    CONSTRAINT usuario_email_uk UNIQUE (email),
    CONSTRAINT usuario_situacao_ck CHECK (situacao IN ('CONVIDADO', 'ATIVO', 'DESATIVADO')),
    CONSTRAINT usuario_senha_ck CHECK (situacao <> 'ATIVO' OR senha_hash IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE usuario_perfil (
    usuario_id BIGINT NOT NULL,
    perfil VARCHAR(40) NOT NULL,
    CONSTRAINT usuario_perfil_pk PRIMARY KEY (usuario_id, perfil),
    CONSTRAINT usuario_perfil_usuario_fk FOREIGN KEY (usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX usuario_perfil_perfil_ix ON usuario_perfil (perfil);

-- Tokens de convite e de redefinição de senha. Somente o SHA-256 do token é armazenado.
CREATE TABLE token_acesso (
    id BIGINT NOT NULL AUTO_INCREMENT,
    usuario_id BIGINT NOT NULL,
    finalidade VARCHAR(30) NOT NULL,
    hash_token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    criado_em DATETIME(6) NOT NULL,
    expira_em DATETIME(6) NOT NULL,
    consumido_em DATETIME(6) NULL,
    invalidado_em DATETIME(6) NULL,
    CONSTRAINT token_acesso_pk PRIMARY KEY (id),
    CONSTRAINT token_acesso_hash_uk UNIQUE (hash_token),
    CONSTRAINT token_acesso_usuario_fk FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT token_acesso_finalidade_ck CHECK (finalidade IN ('CONVITE', 'REDEFINICAO_SENHA'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX token_acesso_usuario_ix ON token_acesso (usuario_id, finalidade);
CREATE INDEX token_acesso_expira_ix ON token_acesso (expira_em);

-- Contadores de tentativas (login, recuperação, reenvio) por chave. A chave é um hash: não guarda
-- e-mails digitados nem IPs em claro. Linhas vencidas são removidas periodicamente.
CREATE TABLE limite_tentativa (
    tipo VARCHAR(30) NOT NULL,
    chave CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tentativas INT NOT NULL,
    janela_inicio DATETIME(6) NOT NULL,
    bloqueado_ate DATETIME(6) NULL,
    atualizado_em DATETIME(6) NOT NULL,
    CONSTRAINT limite_tentativa_pk PRIMARY KEY (tipo, chave)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX limite_tentativa_atualizado_ix ON limite_tentativa (atualizado_em);

-- Auditoria de segurança. Nunca contém senhas, tokens, hashes ou cookies.
CREATE TABLE auditoria_seguranca (
    id BIGINT NOT NULL AUTO_INCREMENT,
    ocorrido_em DATETIME(6) NOT NULL,
    tipo VARCHAR(40) NOT NULL,
    ator_usuario_id BIGINT NULL,
    alvo_usuario_id BIGINT NULL,
    ip VARCHAR(45) NULL,
    detalhes VARCHAR(500) NULL,
    CONSTRAINT auditoria_seguranca_pk PRIMARY KEY (id),
    CONSTRAINT auditoria_ator_fk FOREIGN KEY (ator_usuario_id) REFERENCES usuario (id),
    CONSTRAINT auditoria_alvo_fk FOREIGN KEY (alvo_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX auditoria_ocorrido_ix ON auditoria_seguranca (ocorrido_em);
CREATE INDEX auditoria_alvo_ix ON auditoria_seguranca (alvo_usuario_id, ocorrido_em);
