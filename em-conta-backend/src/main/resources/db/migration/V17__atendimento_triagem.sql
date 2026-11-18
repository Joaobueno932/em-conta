-- Triagem inicial pelo WhatsApp: ao abrir um ciclo, uma saudação com menu numerado; a opção escolhida
-- define o setor do ciclo antes de alguém assumir. Começa DESLIGADA: os fluxos atuais não mudam até um
-- administrador ligar a triagem e escolher os destinos.

-- Configuração única (id = 1): ligada ou não, versão e quem alterou por último.
CREATE TABLE atd_triagem_configuracao (
    id TINYINT NOT NULL,
    ativa BOOLEAN NOT NULL DEFAULT FALSE,
    versao BIGINT NOT NULL DEFAULT 0,
    atualizado_em DATETIME(6) NULL,
    atualizado_por BIGINT NULL,
    CONSTRAINT atd_triagem_configuracao_pk PRIMARY KEY (id),
    CONSTRAINT atd_triagem_configuracao_unica_ck CHECK (id = 1),
    CONSTRAINT atd_triagem_configuracao_autor_fk FOREIGN KEY (atualizado_por) REFERENCES usuario (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO atd_triagem_configuracao (id, ativa, versao) VALUES (1, FALSE, 0);

-- Setor de destino de cada opção com destino configurável (a opção "Outro assunto ou falar com um
-- atendente" vai sempre para a fila sem setor). Nulo = sem destino: o ciclo vai para a fila sem setor.
-- Duas opções podem apontar para o mesmo setor.
CREATE TABLE atd_triagem_opcao (
    opcao VARCHAR(20) NOT NULL,
    setor_id BIGINT NULL,
    CONSTRAINT atd_triagem_opcao_pk PRIMARY KEY (opcao),
    CONSTRAINT atd_triagem_opcao_ck CHECK (opcao IN ('FINANCEIRO', 'COBRANCA', 'COMERCIAL')),
    CONSTRAINT atd_triagem_opcao_setor_fk FOREIGN KEY (setor_id) REFERENCES atd_setor (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO atd_triagem_opcao (opcao, setor_id) VALUES ('FINANCEIRO', NULL), ('COBRANCA', NULL), ('COMERCIAL', NULL);

-- Triagem de cada ciclo (só dos ciclos abertos com a triagem ligada). AGUARDANDO_RESPOSTA enquanto o
-- cliente não escolheu; depois CLASSIFICADA (setor aplicado), ENCAMINHADA (fila sem setor: atendente
-- pedido, destino indisponível, resposta inválida repetida ou falha no envio) ou INTERROMPIDA (alguém
-- assumiu antes da resposta). O desfecho nunca volta a AGUARDANDO_RESPOSTA.
CREATE TABLE atd_triagem (
    atendimento_id BIGINT NOT NULL,
    situacao VARCHAR(20) NOT NULL,
    respostas_invalidas INT NOT NULL DEFAULT 0,
    opcao VARCHAR(20) NULL,
    motivo VARCHAR(30) NULL,
    iniciada_em DATETIME(6) NOT NULL,
    concluida_em DATETIME(6) NULL,
    CONSTRAINT atd_triagem_pk PRIMARY KEY (atendimento_id),
    CONSTRAINT atd_triagem_situacao_ck CHECK (situacao IN ('AGUARDANDO_RESPOSTA', 'CLASSIFICADA', 'ENCAMINHADA',
        'INTERROMPIDA')),
    CONSTRAINT atd_triagem_opcao_escolhida_ck CHECK (opcao IN ('FINANCEIRO', 'COBRANCA', 'COMERCIAL', 'HUMANO')),
    CONSTRAINT atd_triagem_atendimento_fk FOREIGN KEY (atendimento_id) REFERENCES atd_atendimento (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Mensagens automáticas da triagem (saudação, nova tentativa e encaminhamento): como o aviso de
-- responsável, ficam fora da primeira resposta humana.
ALTER TABLE atd_mensagem DROP CHECK atd_mensagem_origem_ck;
ALTER TABLE atd_mensagem ADD CONSTRAINT atd_mensagem_origem_ck CHECK (origem IN ('CONTATO', 'ATENDENTE',
    'AVISO_RESPONSAVEL', 'TRIAGEM'));
