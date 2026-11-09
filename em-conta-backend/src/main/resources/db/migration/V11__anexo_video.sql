-- Vídeo (MP4 e 3GPP, formatos aceitos pela Cloud API) como categoria de anexo, nos dois sentidos.
-- Planilhas XLSX entram como DOCUMENTO (só muda a lista de formatos da aplicação). Só amplia a
-- restrição; nenhum dado muda.
ALTER TABLE atd_anexo DROP CHECK atd_anexo_categoria_ck;

ALTER TABLE atd_anexo ADD CONSTRAINT atd_anexo_categoria_ck CHECK (categoria IN ('IMAGEM', 'DOCUMENTO', 'AUDIO',
    'VIDEO'));
