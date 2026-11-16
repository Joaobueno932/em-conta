-- Notas internas da equipe na conversa (aba "Nota interna"). Tabela própria, separada de atd_mensagem:
-- a fila de envio só lê mensagens, então uma nota nunca vai ao WhatsApp nem aparece para o cliente.
-- Entram na linha do tempo (conversa e histórico do ciclo) para quem tem acesso ao atendimento.
CREATE TABLE atd_nota (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversa_id BIGINT NOT NULL,
    atendimento_id BIGINT NOT NULL,
    autor_usuario_id BIGINT NOT NULL,
    texto TEXT NOT NULL,
    criado_em DATETIME(6) NOT NULL,
    chave_idempotencia VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT atd_nota_pk PRIMARY KEY (id),
    CONSTRAINT atd_nota_idempotencia_uk UNIQUE (chave_idempotencia),
    CONSTRAINT atd_nota_conversa_fk FOREIGN KEY (conversa_id) REFERENCES atd_conversa (id),
    CONSTRAINT atd_nota_atendimento_fk FOREIGN KEY (atendimento_id) REFERENCES atd_atendimento (id),
    CONSTRAINT atd_nota_autor_fk FOREIGN KEY (autor_usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_nota_conversa_ix ON atd_nota (conversa_id, criado_em, id);
CREATE INDEX atd_nota_atendimento_ix ON atd_nota (atendimento_id, criado_em, id);
