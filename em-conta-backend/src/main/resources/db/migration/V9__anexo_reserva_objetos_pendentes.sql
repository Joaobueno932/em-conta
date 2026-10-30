-- Reserva de download identificada: cada aquisição de um anexo recebe um token próprio. Conclusão, falha
-- e renovação só valem com o token da reserva vigente; a recuperação de reservas expiradas limpa o token
-- e invalida a execução anterior.
ALTER TABLE atd_anexo ADD COLUMN reserva CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- Diário durável de objetos do armazenamento que podem ter ficado sem dono no recebimento de mídia.
--   GRAVANDO  intenção registrada antes de gravar o objeto (chave + token da reserva). Se a execução
--             cair antes de concluir, a linha continua aqui e a limpeza confere: objeto não vinculado a
--             nenhum anexo e reserva não mais vigente → remove.
--   REMOVER   objeto sabidamente excedente (reserva perdida, falha depois da gravação) a remover.
-- A remoção é idempotente; falha fica registrada (erro, tentativas) e é tentada de novo com espera.
CREATE TABLE atd_objeto_pendente (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chave_objeto VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    anexo_id BIGINT NOT NULL,
    reserva CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    situacao VARCHAR(20) NOT NULL,
    tentativas INT NOT NULL DEFAULT 0,
    proxima_tentativa_em DATETIME(6) NOT NULL,
    erro VARCHAR(300) NULL,
    criado_em DATETIME(6) NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_objeto_pendente_pk PRIMARY KEY (id),
    CONSTRAINT atd_objeto_pendente_chave_uk UNIQUE (chave_objeto),
    CONSTRAINT atd_objeto_pendente_situacao_ck CHECK (situacao IN ('GRAVANDO', 'REMOVER')),
    CONSTRAINT atd_objeto_pendente_anexo_fk FOREIGN KEY (anexo_id) REFERENCES atd_anexo (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX atd_objeto_pendente_fila_ix ON atd_objeto_pendente (proxima_tentativa_em);
