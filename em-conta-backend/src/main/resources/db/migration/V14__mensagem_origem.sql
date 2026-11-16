-- Origem da mensagem: CONTATO (recebida), ATENDENTE (resposta de uma pessoa) ou AVISO_RESPONSAVEL (aviso
-- automático ao cliente de quem passa a atendê-lo, ao assumir ou transferir). Separa os avisos do
-- cálculo da primeira resposta humana. As linhas existentes são recebidas ou respostas de atendentes.
ALTER TABLE atd_mensagem ADD COLUMN origem VARCHAR(20) NOT NULL DEFAULT 'ATENDENTE';

UPDATE atd_mensagem SET origem = 'CONTATO' WHERE direcao = 'ENTRADA';

ALTER TABLE atd_mensagem ADD CONSTRAINT atd_mensagem_origem_ck CHECK (origem IN ('CONTATO', 'ATENDENTE',
    'AVISO_RESPONSAVEL'));

CREATE INDEX atd_mensagem_atendimento_origem_ix ON atd_mensagem (atendimento_id, direcao, origem, criado_em);
