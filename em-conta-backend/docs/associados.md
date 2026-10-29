# Associados, contatos e unidades consumidoras

Primeira entrega do domínio 04 (Clientes, UCs e usinas), histórias US-CLI-01, 02, 05, 07 e 08.
Fontes: especificação funcional v1.3 (07/10/2026, RN-CLI-01 a 11), decisões registradas com o
responsável em 28/10/2026 e o protótipo no Figma (*Em Conta — Protótipo*, página Telas).

## Regras adotadas

| Regra | Fonte |
|---|---|
| Associado é PF ou PJ (tipo explícito, não editável) e tem vários contatos e UCs | RN-CLI-01 |
| CPF/CNPJ único no sistema após normalização; duplicidade bloqueada, sem sobrescrita, com o cadastro existente indicado | RN-CLI-02, decisão do responsável |
| Número da UC único **por concessionária** (`UNIQUE (concessionaria, numero)`) | RN-CLI-03, decisão do responsável em 28/10/2026 |
| Sem exclusão física de associado e UC; contato removido é marcado (`removido_em`) | RN-CLI-08 |
| Uma organização (Em Conta), sem multitenancy | Decisão do responsável |
| Cadastro concluído não significa contrato assinado nem cliente ativo | Decisão do responsável |

### Campos (mínimo técnico provisório, DP-01 pendente)

Aprovado pelo responsável em 28/10/2026 como **provisório** enquanto DP-01 não é definido:

- **PF**: nome completo e CPF (obrigatórios).
- **PJ**: razão social e CNPJ (obrigatórios); nome fantasia (opcional).
- **Contato**: nome e ao menos um meio (e-mail ou telefone). Sem contato principal (não previsto) e
  sem unicidade de e-mail ou telefone (a mesma pessoa pode atender vários associados).
- **UC**: concessionária e número (obrigatórios); denominação e endereço da UC (opcionais).

Fora desta entrega (aparecem no Figma, mas dependem de DP-01, DP-08 ou de outros módulos): endereço
do associado, dados pessoais (nascimento, gênero, estado civil...), número do cliente na
concessionária, origem do contato, tags, equipe responsável, usina, situação e datas do cliente,
comunicação e acesso ao portal, campos personalizados, importação e exportação.

### CPF e CNPJ

- **Validação estrutural** pelo módulo 11. CNPJ segue o formato alfanumérico da Receita Federal
  (IN RFB 2.229/2024, vigente desde julho de 2026): 12 posições `0-9`/`A-Z` e 2 dígitos
  verificadores numéricos, convertendo cada caractere pelo código ASCII menos 48 e pesos 2 a 9.
  Conferido com o exemplo oficial do manual de cálculo do DV (Receita Federal/Serpro):
  `12.ABC.345/01DE-35`. CNPJs numéricos continuam válidos. Sem consulta externa obrigatória.
- **Forma canônica** (gravada e usada na unicidade): sem pontuação, letras maiúsculas.
- **Máscara de apresentação**: `000.000.000-00` e `00.000.000/0000-00` (também alfanumérico), só
  na exibição. A digitação aceita colar com ou sem pontuação.
- **Unicidade**: índice único `associado_documento_uk` com collation binária.
- **Listagem e histórico**: documento parcialmente mascarado (`529.***.***-25`,
  `11.222.***/0001-81`).

### Número da UC

Texto, sem tamanho fixo nem algoritmo de validação (não documentados pela concessionária). A
normalização só remove espaços das pontas e põe letras em maiúsculas; zeros à esquerda são
preservados. Aceita letras, dígitos, ponto, hífen e barra (até 30). Concessionária e número não
são editáveis e não há transferência de titularidade.

## Concorrência

- Toda edição envia a `versao` lida. Versão diferente da atual ou gravação concorrente
  (`@Version`) resultam em 409 `CONFLITO_CONCORRENTE`, sem sobrescrever nada.
- Cadastros simultâneos do mesmo documento ou da mesma UC: o índice único aceita um; os demais
  recebem 409 `DOCUMENTO_JA_CADASTRADO` / `UC_JA_CADASTRADA`, sem mensagem do banco.
- Inclusão de contato e de UC trava o associado (`SELECT ... FOR UPDATE`) sem mudar sua versão.

## Histórico

`historico_cadastro` (somente inclusão) registra cadastro e edição do associado, inclusão, edição
e remoção de contatos, cadastro e edição de UCs: autor (id do usuário, nome resolvido pela API
pública do módulo de acesso, inclusive de usuários desativados), instante UTC, ação, categoria,
referência (nome do contato ou número da UC) e os campos alterados. Documento, e-mail e telefone são
gravados mascarados; endereço registra só que mudou. Nenhum corpo de requisição é copiado. Não há
motivo obrigatório: nenhuma regra o exige para as operações desta entrega.

## Erros (Problem Details, campo `codigo`)

| Código | HTTP | Quando |
|---|---|---|
| `DADOS_INVALIDOS` | 400 | Campo ausente ou inválido; `campos` traz campo → mensagem (ex.: `contatos[1].email`) |
| `DOCUMENTO_INVALIDO` | 400 | CPF/CNPJ fora das regras oficiais |
| `DOCUMENTO_JA_CADASTRADO` | 409 | Documento já existe; `associadoExistenteId` quando localizado |
| `UC_JA_CADASTRADA` | 409 | Mesmo número na mesma concessionária |
| `CONFLITO_CONCORRENTE` | 409 | Versão desatualizada |
| `ASSOCIADO_NAO_ENCONTRADO`, `UC_NAO_ENCONTRADA`, `CONTATO_NAO_ENCONTRADO` | 404 | Recurso inexistente ou de outro associado |
| `NAO_AUTENTICADO` / `SESSAO_EXPIRADA` / `SESSAO_ENCERRADA` | 401 | Sem sessão válida |
| `ACESSO_NEGADO` | 403 | Sem a permissão da rota |

Permissões por rota: [seguranca.md](seguranca.md#autorização).

## Pendências e decisões necessárias

- **DP-01**: campos obrigatórios definitivos de PF/PJ, contatos, UC e documentação.
- **DP-08 / DP-18**: situação cadastral do cliente, etapa comercial, situação contratual e situação
  operacional por UC. Nenhum gatilho automático ("Ativo após 1ª fatura" etc.) foi implementado.
- **DP-16**: matriz de permissões por departamento.
- Formato oficial do número da UC por concessionária e lista de concessionárias além da Energisa MS.
- Transferência de titularidade da UC. (O vínculo com usina foi entregue: ver
  [usinas.md](usinas.md).)
- Figma OV-D05 ("Vincular unidade consumidora") seleciona UCs existentes "sem associado"; esta
  entrega exige que toda UC pertença a um associado, então a UC é cadastrada a partir da ficha.
- Pendências cadastrais (US-CLI-06), importação e exportação.
