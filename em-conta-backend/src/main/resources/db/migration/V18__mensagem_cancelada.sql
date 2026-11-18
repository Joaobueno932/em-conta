-- Situação final CANCELADA: mensagem automática da triagem (saudação ou repetição do menu) que ainda
-- aguardava envio quando a triagem terminou (alguém assumiu o atendimento ou o cliente já escolheu). Nada
-- foi enviado à Cloud API e não há nova tentativa; a linha fica na conversa, com a chave única, então
-- reprocessar não recria a mensagem. Só amplia a restrição; nenhum dado muda.
ALTER TABLE atd_mensagem DROP CHECK atd_mensagem_situacao_ck;

ALTER TABLE atd_mensagem ADD CONSTRAINT atd_mensagem_situacao_ck CHECK (situacao IN ('RECEBIDA', 'PENDENTE',
    'ENVIANDO', 'ACEITA', 'ENVIADA', 'ENTREGUE', 'LIDA', 'FALHA', 'DESCONHECIDA', 'JANELA_EXPIRADA', 'CANCELADA'));
