-- Anexos do atendimento (imagem, PDF e áudio), recebidos e enviados. O arquivo fica no armazenamento
-- privado de objetos (MinIO) sob chave_objeto, opaca e gerada pelo sistema; nome é só metadado de
-- exibição, já higienizado. URLs temporárias da Meta nunca são gravadas.
--
-- Situações:
--   RECEBENDO       upload do atendente em andamento (ainda sem mensagem; removido se abandonado)
--   PENDENTE        recebido pelo webhook, aguardando download
--   PROCESSANDO     download reservado por uma instância (reservado_em)
--   FALHA_TEMPORARIA nova tentativa agendada (proxima_tentativa_em)
--   DISPONIVEL      validado e armazenado
--   FALHA           falha definitiva (erro)
--   NAO_SUPORTADO   tipo/MIME fora da lista aceita; nada é baixado
CREATE TABLE atd_anexo (
    id BIGINT NOT NULL AUTO_INCREMENT,
    mensagem_id BIGINT NULL,
    conversa_id BIGINT NOT NULL,
    atendimento_id BIGINT NOT NULL,
    direcao VARCHAR(10) NOT NULL,
    autor_usuario_id BIGINT NULL,
    categoria VARCHAR(20) NOT NULL,
    mime_type VARCHAR(100) NULL,
    nome VARCHAR(255) NULL,
    tamanho BIGINT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sha256_provedor VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    id_midia_externo VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    chave_objeto VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    situacao VARCHAR(20) NOT NULL,
    erro VARCHAR(300) NULL,
    tentativas INT NOT NULL DEFAULT 0,
    proxima_tentativa_em DATETIME(6) NULL,
    reservado_em DATETIME(6) NULL,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_anexo_pk PRIMARY KEY (id),
    CONSTRAINT atd_anexo_mensagem_uk UNIQUE (mensagem_id),
    CONSTRAINT atd_anexo_chave_uk UNIQUE (chave_objeto),
    CONSTRAINT atd_anexo_direcao_ck CHECK (direcao IN ('ENTRADA', 'SAIDA')),
    CONSTRAINT atd_anexo_categoria_ck CHECK (categoria IN ('IMAGEM', 'DOCUMENTO', 'AUDIO')),
    CONSTRAINT atd_anexo_situacao_ck CHECK (situacao IN ('RECEBENDO', 'PENDENTE', 'PROCESSANDO',
        'FALHA_TEMPORARIA', 'DISPONIVEL', 'FALHA', 'NAO_SUPORTADO')),
    CONSTRAINT atd_anexo_mensagem_fk FOREIGN KEY (mensagem_id) REFERENCES atd_mensagem (id),
    CONSTRAINT atd_anexo_conversa_fk FOREIGN KEY (conversa_id) REFERENCES atd_conversa (id),
    CONSTRAINT atd_anexo_atendimento_fk FOREIGN KEY (atendimento_id) REFERENCES atd_atendimento (id),
    CONSTRAINT atd_anexo_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Área Mídia (conversa ou um ciclo, por categoria, do mais recente) e fila de download.
CREATE INDEX atd_anexo_conversa_ix ON atd_anexo (conversa_id, categoria, id);
CREATE INDEX atd_anexo_atendimento_ix ON atd_anexo (atendimento_id, categoria, id);
CREATE INDEX atd_anexo_processamento_ix ON atd_anexo (situacao, proxima_tentativa_em);

-- Em qual etapa uma mensagem de saída falhou: UPLOAD do arquivo à Meta ou ENVIO da mensagem.
ALTER TABLE atd_mensagem ADD COLUMN etapa_falha VARCHAR(20) NULL;
ALTER TABLE atd_mensagem ADD CONSTRAINT atd_mensagem_etapa_falha_ck CHECK (etapa_falha IN ('UPLOAD', 'ENVIO'));
