-- Presença do atendente com modo. AUTOMATICO: o sistema marca Disponível quando a pessoa começa a
-- escrever uma resposta numa conversa que pode atender. MANUAL: a escolha da pessoa (Disponível ou
-- Ausente) vale até ela voltar ao automático; atualização automática não a altera. As linhas existentes
-- vieram de escolhas manuais. Continua separada do estado das conversas.
ALTER TABLE atd_presenca ADD COLUMN modo VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE atd_presenca ADD CONSTRAINT atd_presenca_modo_ck CHECK (modo IN ('AUTOMATICO', 'MANUAL'));
