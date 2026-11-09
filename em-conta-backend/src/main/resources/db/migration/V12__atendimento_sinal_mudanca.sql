-- Sinal de mudança por conversa, para a entrega de eventos à tela (SSE). Cada alteração visível na
-- linha do tempo (mensagem nova, situação, anexo, evento do atendimento) incrementa o contador depois
-- da confirmação da transação; as instâncias da API leem só os contadores das conversas abertas e
-- avisam a tela, que relê pelas rotas de sempre. Não guarda conteúdo. Funciona com o processamento na
-- API ou no worker e com várias réplicas.
CREATE TABLE atd_sinal (
    conversa_id BIGINT NOT NULL,
    contador BIGINT NOT NULL,
    atualizado_em DATETIME(6) NOT NULL,
    CONSTRAINT atd_sinal_pk PRIMARY KEY (conversa_id),
    CONSTRAINT atd_sinal_conversa_fk FOREIGN KEY (conversa_id) REFERENCES atd_conversa (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
