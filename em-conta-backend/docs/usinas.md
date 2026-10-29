# Fazendas, usinas e vínculos cadastrais

Entrega de US-CLI-03 (cadastrar e manter usinas e fazendas), US-CLI-04 (vincular usina às UCs do
associado) e extensão de US-CLI-05 (histórico de vínculos). Fontes: especificação funcional v1.3
(RN-CLI-04, 05, 06, 08, 10; RN-CON-11), decisões do responsável em 29/10/2026 e o protótipo no Figma
(*Em Conta — Protótipo*, seções "Usinas — Listagem", "Usinas — Detalhe da usina" e "Usinas —
Overlays e painéis").

**Vínculo cadastral não representa rateio, ativação energética, disponibilidade de energia nem
aptidão de faturamento (RN-CLI-06).**

## Modelo (decisões de 29/10/2026)

| Tema | Regra | Fonte |
|---|---|---|
| Fazenda × usina | Fazenda é cadastro próprio; uma fazenda tem várias usinas; a usina aponta opcionalmente para uma fazenda | Decisão do responsável (US-CLI-03 + Figma OV-U06) |
| Identificador | Gerado pelo sistema: `FZD-0001`, `USN-0001` (derivado do id) | Figma OV-U06/UI-01 |
| Campos | Usina: nome (obrigatório); fazenda, CEP, endereço, cidade, UF, observações (opcionais). Fazenda: nome (obrigatório); cidade, UF, observações | Decisão do responsável (DP-01 pendente) |
| Nome repetido | Não é único. Nome igual sem acentos/caixa gera 409 `NOME_POSSIVEL_DUPLICIDADE` com os parecidos; o usuário confirma para salvar | RN-CLI-04, Figma OV-U08, decisão do responsável |
| Cardinalidade | Uma UC tem no máximo **uma** usina vigente; uma usina tem várias UCs | Decisão do responsável, Figma OV-U11 |
| Associado × usina | Derivado das UCs vinculadas (sem tabela própria) | Decisão do responsável |
| Vigência | Datas de negócio `[inicio, fim)`. Início informado, retroativo permitido, nunca futuro. Vigências da mesma UC não se sobrepõem (novo início ≥ último fim) | Decisão do responsável, RN-CLI-10 |
| Encerramento | Data (≥ início, não futura) e motivo obrigatório (5 a 500 caracteres). Trocar de usina = encerrar e vincular de novo; corrigir lançamento = encerrar com motivo. Nada é apagado | Decisão do responsável |
| Exclusão | Não há exclusão de fazenda, usina ou vínculo | RN-CLI-08 |

Fora desta entrega (aparecem no Figma, mas não têm regra ou não são cadastrais): responsável
interno, tags, coordenadas, instituição/investidor, dados técnicos (potência, inversores),
"pode receber novos clientes", estimativas de geração, exportação e desativação de usina.

## Propriedade e fronteiras

- O módulo `usinas` é o dono de fazendas, usinas e vínculos.
- `usinas` → `associados` apenas pela API pública `UnidadesConsumidoras` (consulta de UCs e
  titulares, com documento mascarado, e trava da linha da UC). `usinas` → `acesso` pela `Autoria`.
  `associados` não depende de `usinas`: a ficha do associado consulta os vínculos pela rota
  `GET /api/v1/associados/{id}/vinculos-usina`, implementada no módulo de usinas.
- O vínculo guarda `associado_id` (titular da UC no vínculo). A titularidade da UC é imutável (não
  há transferência), então a cópia não diverge; ela permite contar associados distintos e listar
  vínculos por associado sem ler tabelas do módulo de associados.

## Concorrência

- Edição de fazenda e usina com versão otimista: versão antiga → 409 `CONFLITO_CONCORRENTE`.
- Vínculo: a linha da UC é travada primeiro (`SELECT ... FOR UPDATE OF u`, pela API pública), o que
  serializa criações e encerramentos da mesma UC; os vínculos da UC são então lidos com trava (estado
  atual, não o instantâneo da transação) para as checagens de vigente e sobreposição.
- No banco, `uc_vigente` (coluna gerada: `uc_id` só quando `fim` é nulo) tem índice único: no
  máximo um vínculo vigente por UC mesmo que alguma checagem falhe.
- Uma primeira versão travava só o intervalo do índice dos vínculos; com inserções simultâneas isso
  gerou deadlock no MySQL (coberto por teste). A trava da linha da UC eliminou o problema.

## Histórico

`historico_usinas` (somente inclusão) registra cadastro e alteração de fazenda e usina, criação e
encerramento de vínculo: autor, instante UTC, ação, referência (código e nome; para vínculos, UC,
concessionária e titular), motivo do encerramento e campos alterados. CEP e endereço registram só que
mudaram. A ficha do associado mostra a lista de vínculos (vigentes e encerrados) com datas, motivo e
autores.

## Erros (Problem Details, campo `codigo`)

| Código | HTTP | Quando |
|---|---|---|
| `DADOS_INVALIDOS` | 400 | Campo ausente ou inválido (`campos`: campo → mensagem) |
| `VIGENCIA_INVALIDA` | 400 | Início futuro, fim antes do início ou futuro, sobreposição de vigência |
| `NOME_POSSIVEL_DUPLICIDADE` | 409 | Nome parecido; `existentes` lista os cadastros; reenviar com `confirmarNomeRepetido` |
| `UC_VINCULADA_A_OUTRA_USINA` | 409 | UC já tem vínculo vigente com outra usina (com `usinaAtual*` e `vigenteDesde`) |
| `VINCULO_JA_EXISTE` | 409 | UC já vinculada a esta usina |
| `UC_JA_VINCULADA` | 409 | Criação simultânea perdeu a corrida |
| `VINCULO_JA_ENCERRADO`, `CONFLITO_CONCORRENTE` | 409 | Encerramento repetido ou versão antiga |
| `USINA_NAO_ENCONTRADA`, `FAZENDA_NAO_ENCONTRADA`, `UC_NAO_ENCONTRADA`, `VINCULO_NAO_ENCONTRADO`, `ASSOCIADO_NAO_ENCONTRADO` | 404 | Recurso inexistente |
| `ACESSO_NEGADO` | 403 | Sem a permissão da rota |

Permissões por rota: [seguranca.md](seguranca.md#autorização).

## Pendências

- DP-01: campos obrigatórios definitivos de usina e fazenda; DP-05: distinção entre vínculo
  cadastral, previsão de alocação e rateio efetivo (nada disso foi implementado além do cadastral).
- Fuso de referência do negócio: "hoje" usa provisoriamente `America/Campo_Grande`
  (`emconta.negocio.fuso`).
- Desativação de usina/fazenda e seu efeito sobre vínculos vigentes: sem regra; não implementado.
- Situação operacional da UC (DP-08/DP-18), transferência de titularidade e múltiplos vínculos
  simultâneos: sem regra; bloqueados.
