-- Autorização explícita para a sessão que fez a troca de senha continuar válida na nova versão de
-- segurança. Gravada na mesma transação que incrementa usuario.versao_seguranca, então não há
-- intervalo em que a sessão atual fique sem autorização; as demais sessões não têm registro e são
-- recusadas. chave_sessao é o SHA-256 da chave interna da sessão (nunca enviada ao cliente).
CREATE TABLE sessao_renovada (
    usuario_id BIGINT NOT NULL,
    chave_sessao CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    versao_seguranca INT NOT NULL,
    criado_em DATETIME(6) NOT NULL,
    CONSTRAINT sessao_renovada_pk PRIMARY KEY (usuario_id, chave_sessao),
    CONSTRAINT sessao_renovada_usuario_fk FOREIGN KEY (usuario_id) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
