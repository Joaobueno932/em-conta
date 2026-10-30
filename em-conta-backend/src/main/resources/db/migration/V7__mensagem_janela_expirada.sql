-- Situação final JANELA_EXPIRADA: a resposta foi registrada dentro da janela de 24 h, mas a janela já
-- tinha expirado quando o envio seria feito. Nada foi enviado à Cloud API (diferente de FALHA, que é
-- recusa do provedor) e não há nova tentativa automática. Só amplia a restrição; nenhum dado muda.
ALTER TABLE atd_mensagem DROP CHECK atd_mensagem_situacao_ck;

ALTER TABLE atd_mensagem ADD CONSTRAINT atd_mensagem_situacao_ck CHECK (situacao IN ('RECEBIDA', 'PENDENTE',
    'ENVIANDO', 'ACEITA', 'ENVIADA', 'ENTREGUE', 'LIDA', 'FALHA', 'DESCONHECIDA', 'JANELA_EXPIRADA'));
