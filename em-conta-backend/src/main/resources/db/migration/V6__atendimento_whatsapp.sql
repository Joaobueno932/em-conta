-- Módulo atendimento: conversas do WhatsApp (Cloud API), ciclos de atendimento, mensagens, eventos,
-- caixa de entrada do webhook e presença manual do atendente.
-- Decisões do responsável em 29/10/2026: integração direta com a Cloud API; mensagem após o
-- encerramento abre novo atendimento na fila; transferência direta com motivo opcional; presença
-- manual simples (Disponível/Ausente), sem efeito em distribuição.

-- Contato externo (quem escreve pelo WhatsApp). Não é associado: pode ser atendido sem cadastro.
CREATE TABLE atd_contato (
    id BIGINT NOT NULL AUTO_INCREMENT,
    wa_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    nome_perfil VARCHAR(200) NULL,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_contato_pk PRIMARY KEY (id),
    CONSTRAINT atd_contato_wa_uk UNIQUE (wa_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Uma conversa por contato (a linha do tempo inteira). O vínculo com associado é opcional, explícito e
-- feito por uma pessoa; telefone só sugere candidatos.
CREATE TABLE atd_conversa (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contato_id BIGINT NOT NULL,
    associado_id BIGINT NULL,
    ultima_entrada_em DATETIME(6) NULL,
    ultima_mensagem_em DATETIME(6) NULL,
    criado_em DATETIME(6) NOT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT atd_conversa_pk PRIMARY KEY (id),
    CONSTRAINT atd_conversa_contato_uk UNIQUE (contato_id),
    CONSTRAINT atd_conversa_contato_fk FOREIGN KEY (contato_id) REFERENCES atd_contato (id),
    CONSTRAINT atd_conversa_associado_fk FOREIGN KEY (associado_id) REFERENCES associado (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Ciclo de atendimento: AGUARDANDO (sem responsável) -> EM_ATENDIMENTO -> ENCERRADO (somente leitura).
-- conversa_aberta só é preenchida enquanto não encerrado: no máximo um ciclo aberto por conversa.
CREATE TABLE atd_atendimento (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversa_id BIGINT NOT NULL,
    situacao VARCHAR(20) NOT NULL,
    responsavel_id BIGINT NULL,
    iniciado_em DATETIME(6) NOT NULL,
    assumido_em DATETIME(6) NULL,
    encerrado_em DATETIME(6) NULL,
    encerrado_por BIGINT NULL,
    versao BIGINT NOT NULL DEFAULT 0,
    conversa_aberta BIGINT GENERATED ALWAYS AS (CASE WHEN situacao <> 'ENCERRADO' THEN conversa_id END) STORED,
    CONSTRAINT atd_atendimento_pk PRIMARY KEY (id),
    CONSTRAINT atd_atendimento_aberto_uk UNIQUE (conversa_aberta),
    CONSTRAINT atd_atendimento_situacao_ck CHECK (situacao IN ('AGUARDANDO', 'EM_ATENDIMENTO', 'ENCERRADO')),
    CONSTRAINT atd_atendimento_responsavel_ck CHECK (
        (situacao = 'AGUARDANDO' AND responsavel_id IS NULL)
        OR (situacao = 'EM_ATENDIMENTO' AND responsavel_id IS NOT NULL)
        OR situacao = 'ENCERRADO'),
    CONSTRAINT atd_atendimento_conversa_fk FOREIGN KEY (conversa_id) REFERENCES atd_conversa (id),
    CONSTRAINT atd_atendimento_responsavel_fk FOREIGN KEY (responsavel_id) REFERENCES usuario (id),
    CONSTRAINT atd_atendimento_encerrado_por_fk FOREIGN KEY (encerrado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_atendimento_fila_ix ON atd_atendimento (situacao, iniciado_em);
CREATE INDEX atd_atendimento_responsavel_ix ON atd_atendimento (responsavel_id, situacao);
CREATE INDEX atd_atendimento_conversa_ix ON atd_atendimento (conversa_id, iniciado_em);

-- Mensagens. id_externo (wamid) é único: o mesmo evento nunca gera duas mensagens. correlacao vai no
-- envio (biz_opaque_callback_data) e volta nos eventos de situação. chave_idempotencia evita duplicar
-- um envio repetido pelo cliente (clique duplo, nova tentativa da requisição).
CREATE TABLE atd_mensagem (
    id BIGINT NOT NULL AUTO_INCREMENT,
    correlacao CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversa_id BIGINT NOT NULL,
    atendimento_id BIGINT NOT NULL,
    direcao VARCHAR(10) NOT NULL,
    autor_usuario_id BIGINT NULL,
    tipo VARCHAR(20) NOT NULL,
    tipo_externo VARCHAR(40) NULL,
    texto TEXT NULL,
    id_externo VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    situacao VARCHAR(20) NOT NULL,
    erro_codigo INT NULL,
    erro_titulo VARCHAR(300) NULL,
    horario_externo DATETIME(6) NULL,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    tentativas INT NOT NULL DEFAULT 0,
    proxima_tentativa_em DATETIME(6) NULL,
    chave_idempotencia VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT atd_mensagem_pk PRIMARY KEY (id),
    CONSTRAINT atd_mensagem_correlacao_uk UNIQUE (correlacao),
    CONSTRAINT atd_mensagem_externo_uk UNIQUE (id_externo),
    CONSTRAINT atd_mensagem_idempotencia_uk UNIQUE (chave_idempotencia),
    CONSTRAINT atd_mensagem_direcao_ck CHECK (direcao IN ('ENTRADA', 'SAIDA')),
    CONSTRAINT atd_mensagem_situacao_ck CHECK (situacao IN ('RECEBIDA', 'PENDENTE', 'ENVIANDO', 'ACEITA',
        'ENVIADA', 'ENTREGUE', 'LIDA', 'FALHA', 'DESCONHECIDA')),
    CONSTRAINT atd_mensagem_conversa_fk FOREIGN KEY (conversa_id) REFERENCES atd_conversa (id),
    CONSTRAINT atd_mensagem_atendimento_fk FOREIGN KEY (atendimento_id) REFERENCES atd_atendimento (id),
    CONSTRAINT atd_mensagem_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_mensagem_conversa_ix ON atd_mensagem (conversa_id, criado_em, id);
CREATE INDEX atd_mensagem_envio_ix ON atd_mensagem (situacao, proxima_tentativa_em);

-- Histórico de situações informadas pelo canal (inclusive falhas), uma linha por situação.
CREATE TABLE atd_mensagem_situacao (
    id BIGINT NOT NULL AUTO_INCREMENT,
    mensagem_id BIGINT NOT NULL,
    situacao VARCHAR(20) NOT NULL,
    horario_externo DATETIME(6) NULL,
    erro_codigo INT NULL,
    erro_titulo VARCHAR(300) NULL,
    registrado_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_mensagem_situacao_pk PRIMARY KEY (id),
    CONSTRAINT atd_mensagem_situacao_uk UNIQUE (mensagem_id, situacao),
    CONSTRAINT atd_mensagem_situacao_fk FOREIGN KEY (mensagem_id) REFERENCES atd_mensagem (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Eventos do atendimento (assumir, transferir, devolver, encerrar, vincular associado...). Autoria e
-- instante; nunca conteúdo de mensagem.
CREATE TABLE atd_evento (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversa_id BIGINT NOT NULL,
    atendimento_id BIGINT NULL,
    tipo VARCHAR(40) NOT NULL,
    autor_usuario_id BIGINT NULL,
    usuario_alvo_id BIGINT NULL,
    associado_id BIGINT NULL,
    motivo VARCHAR(500) NULL,
    ocorrido_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_evento_pk PRIMARY KEY (id),
    CONSTRAINT atd_evento_conversa_fk FOREIGN KEY (conversa_id) REFERENCES atd_conversa (id),
    CONSTRAINT atd_evento_atendimento_fk FOREIGN KEY (atendimento_id) REFERENCES atd_atendimento (id),
    CONSTRAINT atd_evento_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id),
    CONSTRAINT atd_evento_alvo_fk FOREIGN KEY (usuario_alvo_id) REFERENCES usuario (id),
    CONSTRAINT atd_evento_associado_fk FOREIGN KEY (associado_id) REFERENCES associado (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_evento_conversa_ix ON atd_evento (conversa_id, ocorrido_em, id);

-- Caixa de entrada do webhook: o corpo bruto autenticado é gravado antes do 200; o processamento é
-- assíncrono (poller no MySQL). hash_corpo deduplica reentregas idênticas da Meta.
CREATE TABLE atd_webhook_evento (
    id BIGINT NOT NULL AUTO_INCREMENT,
    hash_corpo CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    corpo MEDIUMTEXT NOT NULL,
    recebido_em DATETIME(6) NOT NULL,
    situacao VARCHAR(20) NOT NULL,
    tentativas INT NOT NULL DEFAULT 0,
    proxima_tentativa_em DATETIME(6) NOT NULL,
    processado_em DATETIME(6) NULL,
    erro VARCHAR(300) NULL,
    ignorados INT NOT NULL DEFAULT 0,
    CONSTRAINT atd_webhook_evento_pk PRIMARY KEY (id),
    CONSTRAINT atd_webhook_evento_hash_uk UNIQUE (hash_corpo),
    CONSTRAINT atd_webhook_evento_situacao_ck CHECK (situacao IN ('PENDENTE', 'PROCESSADO', 'FALHA'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_webhook_evento_pendente_ix ON atd_webhook_evento (situacao, proxima_tentativa_em);

-- Presença manual do atendente. Não é a disponibilidade técnica do canal.
CREATE TABLE atd_presenca (
    usuario_id BIGINT NOT NULL,
    situacao VARCHAR(20) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_presenca_pk PRIMARY KEY (usuario_id),
    CONSTRAINT atd_presenca_situacao_ck CHECK (situacao IN ('DISPONIVEL', 'AUSENTE')),
    CONSTRAINT atd_presenca_usuario_fk FOREIGN KEY (usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
