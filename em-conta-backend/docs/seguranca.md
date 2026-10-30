# Segurança e acesso de usuários internos

Autenticação própria (Spring Security), sessão no servidor (Spring Session JDBC/MySQL), frontend e
API na mesma origem. Sem JWT, sem Keycloak, sem tokens no navegador. Este documento cobre os
**usuários internos**; o portal do cliente terá entrega própria.

Itens marcados **(provisório)** dependem de decisão do negócio.

## Primeiro administrador (bootstrap)

Não há endpoint, senha padrão nem criação automática. O modo `bootstrap-admin` roda as migrations,
cria o administrador e encerra. Ele recusa a execução (código de saída 1, nada alterado) se já
existir administrador convidado ou ativo, ou se o e-mail já estiver em uso.

A senha é lida do terminal sem eco (digitada duas vezes) ou, sem terminal, da primeira linha da
entrada padrão. Nunca é aceita como argumento ou variável de ambiente e nunca vai para o log.

```powershell
# Container (recomendado; -it dá terminal para digitar a senha sem eco)
docker run --rm -it --network em-conta_default `
  -e SPRING_PROFILES_ACTIVE=local,bootstrap-admin -e DB_HOST=mysql -e DB_PORT=3306 `
  em-conta-backend --emconta.bootstrap.nome="Nome da Pessoa" --emconta.bootstrap.email=pessoa@exemplo.com.br

# Sem terminal (ex.: automação): senha na 1ª linha de um arquivo protegido, apagado depois
Get-Content .\senha.txt | docker run --rm -i --network em-conta_default `
  -e SPRING_PROFILES_ACTIVE=local,bootstrap-admin -e DB_HOST=mysql -e DB_PORT=3306 `
  em-conta-backend --emconta.bootstrap.nome="Nome" --emconta.bootstrap.email=pessoa@exemplo.com.br

# Com JDK local (o terminal da IDE pode não oferecer leitura sem eco; prefira o container)
java -jar target\em-conta-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local,bootstrap-admin `
  --emconta.bootstrap.nome="Nome" --emconta.bootstrap.email=pessoa@exemplo.com.br
```

Em produção, use o mesmo comando com as variáveis de banco do ambiente, sem o profile `local`.

Depois de criar o administrador, o comando fecha o contexto e encerra com código 0. (Até a entrega
de associados, o caminho de sucesso ficava ativo indefinidamente por causa de uma thread de
agendamento não daemon; corrigido em `BootstrapAdministradorRunner`.)

## Modelo

| Tabela (migration `V2`) | Conteúdo |
|---|---|
| `usuario` | nome, e-mail normalizado (único, collation binária), hash da senha, `situacao`, `versao_seguranca`, datas técnicas, `versao` (concorrência otimista) |
| `usuario_perfil` | perfis atribuídos |
| `token_acesso` | SHA-256 dos tokens de convite/redefinição, finalidade, validade, consumo e invalidação |
| `limite_tentativa` | contadores de tentativas por chave (hash), janela e bloqueio |
| `auditoria_seguranca` | eventos de segurança, sem segredos |

**Situação da conta**: `CONVIDADO` (convite pendente, sem senha), `ATIVO` (único apto a entrar) e
`DESATIVADO` (acesso revogado; o registro permanece para preservar autoria e histórico). Há uma
`CHECK` impedindo conta ativa sem senha.

**E-mail**: normalizado (espaços nas pontas removidos, Unicode NFC, minúsculas) antes de gravar,
buscar ou limitar. O índice único resolve criações simultâneas (a segunda recebe 409). A alteração
de e-mail não existe nesta etapa (exige confirmação própria). **(provisório)**: a parte local
também é convertida para minúsculas.

## Senhas (política provisória)

- De **15 a 128 caracteres** (pontos de código Unicode) e no máximo 512 bytes em UTF-8.
- Espaços e colagem são permitidos. Não há regra de composição nem expiração periódica.
- A senha é usada exatamente como digitada: nada é aparado, truncado ou normalizado. O frontend
  não usa `maxlength` e só avisa.
- Hash **Argon2id** (`Argon2PasswordEncoder` do Spring Security com BouncyCastle 1.86):
  m = 19 MiB, t = 2, p = 1, sal de 16 bytes, hash de 32 bytes (recomendação OWASP). O Argon2 não
  tem limite de entrada como o BCrypt; o teto de 512 bytes evita custo arbitrário. O prefixo
  `{argon2}` do `DelegatingPasswordEncoder` permite trocar os parâmetros sem invalidar hashes
  antigos.
- Não há verificação contra senhas vazadas nesta etapa.

## Sessão

- Cookie `EMCONTA_SESSION`: `HttpOnly`, `SameSite=Lax`, `Secure` fora do profile `local` (ou com
  `EMCONTA_COOKIE_SECURE`). O `CookieSerializer` é declarado explicitamente, então os atributos
  são os mesmos com servidor embarcado ou em testes.
- Expiração: **30 min de inatividade** (`spring.session.timeout`) e **12 h de duração máxima** desde
  o login (`emconta.acesso.sessao.duracao-maxima`). Não há "lembrar de mim".
- Login: novo identificador de sessão (`ChangeSessionIdAuthenticationStrategy`, contra fixação) e
  novo token CSRF na mesma resposta.
- Logout: invalida a sessão no banco e expira os cookies de sessão e CSRF.
- **Revogação**: a sessão guarda só `usuarioId`, `versao_seguranca` e o horário do login. A cada
  requisição, o `SessaoUsuarioFilter` lê situação, versão e perfis no banco (uma consulta):
  - desativação, troca e redefinição de senha incrementam a versão: as sessões antigas são
    recusadas mesmo que a linha do Spring Session ainda exista, e as linhas também são apagadas;
  - permissões nunca vêm da sessão. Mudança de perfil vale na requisição seguinte, inclusive para
    sessões já abertas.
- **Troca autenticada de senha**: mantém a sessão atual (com novo identificador) e encerra as
  demais. **Redefinição pelo link**: encerra todas.
- Respostas 401 distinguem `NAO_AUTENTICADO` (sem sessão), `SESSAO_EXPIRADA` (cookie de sessão que
  não existe mais) e `SESSAO_ENCERRADA` (revogada pelo servidor). Permissão insuficiente é 403
  `ACESSO_NEGADO`; token CSRF ausente ou velho é 403 `CSRF_INVALIDO`.

## CSRF

`csrf.spa()` com o `CookieCsrfTokenRepository`: o cookie legível `XSRF-TOKEN` volta no cabeçalho
`X-XSRF-TOKEN` em métodos mutáveis, inclusive no login (contra login CSRF). `GET /api/v1/auth/csrf`
emite o cookie quando ele não existe. O token é renovado no login e removido no logout.
Única exceção: o webhook do WhatsApp (`/api/v1/integracoes/whatsapp/webhook`), chamado pela Meta
sem sessão nem cookie; cada evento é autenticado pela assinatura `X-Hub-Signature-256`.

## Limite de tentativas

Persistido no MySQL (`limite_tentativa`), compartilhado entre réplicas, sem Redis. Cada tentativa é
contada **antes** da operação, em transação própria: o upsert trava a linha da chave, então
tentativas paralelas não ultrapassam o limite. As chaves são SHA-256 (nenhum e-mail ou IP em claro).
Uma limpeza horária remove contadores inativos há mais de 1 dia, e tokens vencidos há mais de
30 dias.

| Limite | Padrão | Ao exceder |
|---|---|---|
| Login por conta (e-mail digitado) | 5 em 15 min, bloqueio de 15 min | 429 `MUITAS_TENTATIVAS` com `Retry-After`, mesmo com a senha certa. Vale também para e-mails inexistentes, sem revelar nada |
| Login por IP | 50 em 15 min, bloqueio de 15 min | 429. Logins bem-sucedidos devolvem a tentativa |
| Troca de senha (senha atual errada) | conta no limite de login da conta | 429 |
| Recuperação por IP | 20 por hora | 429 |
| Recuperação por conta | 3 por hora | ignorada em silêncio (a resposta continua genérica) |
| Reenvio de convite por usuário | 5 por hora | 429 |
| Uso de links por IP (validação, aceite de convite e redefinição, somados) | 30 em 15 min, bloqueio de 15 min | 429 `MUITAS_TENTATIVAS` com `Retry-After` |

Uso de links é limitado **só por IP**: um limite por conta poderia ser acionado por terceiros com
informação pública (o e-mail) e impedir a recuperação legítima. Os tokens têm 256 bits, então o
limite protege o servidor do custo, não contra adivinhação. Ambientes de teste automatizado em que
todas as requisições chegam pelo mesmo IP podem elevar o máximo
(`EMCONTA_ACESSO_LIMITES_USOTOKENIP_MAXIMO`, `_JANELA`, `_BLOQUEIO`).

Sucesso no login zera o contador da conta. Os valores podem ser ajustados em
`emconta.acesso.limites.<login-conta|login-ip|recuperacao-conta|recuperacao-ip|reenvio-convite|uso-token-ip>`
(`maximo`, `janela`, `bloqueio`). **(provisório)**

**IP do cliente**: `server.forward-headers-strategy=native`. O Tomcat (RemoteIpValve) só aceita
`X-Forwarded-For`/`-Proto` de proxies em `server.tomcat.remoteip.internal-proxies` (padrão: redes
privadas e loopback). Cabeçalhos enviados diretamente por clientes externos são ignorados. Ajuste
essa lista ao proxy real de produção.

**Credenciais inválidas**: e-mail inexistente, senha errada e conta convidada ou desativada recebem
a mesma resposta (401 `CREDENCIAIS_INVALIDAS`, "E-mail ou senha inválidos.") e o mesmo custo de
hash (hash fictício para e-mails desconhecidos). Falhas de banco ou servidor resultam em 5xx, nunca
em "senha incorreta".

## Tokens de convite e redefinição

- 256 bits do `SecureRandom`, entregues em Base64URL; no banco, apenas o SHA-256.
- Vinculados à conta e à finalidade (`CONVITE` 72 h, `REDEFINICAO_SENHA` 30 min, configuráveis em
  `emconta.acesso.tokens`).
- Uso único com consumo atômico (UPDATE condicional na mesma transação que grava a senha;
  requisições simultâneas têm um único sucesso). Uma falha posterior, como senha fora da política,
  desfaz o consumo.
- Emitir um novo token (reenvio, nova solicitação) invalida os pendentes. A desativação invalida
  todos.
- O link é `https://<url-publica>/convite#token=...` ou `/redefinir-senha#token=...`. O token vai no
  **fragmento**: não é enviado ao servidor web, não aparece em logs de acesso nem no Referer. O
  frontend o remove da barra de endereço ao abrir a página, e as páginas usam
  `Referrer-Policy: no-referrer`.
- A URL pública vem só da configuração (`EMCONTA_URL_PUBLICA`, https obrigatório fora do
  localhost), nunca de cabeçalhos da requisição.
- Link inválido, expirado ou usado: 400 `TOKEN_INVALIDO`, com mensagem e caminho para pedir outro.

**Ordem nos fluxos públicos (aceite e redefinição)**: limite por IP → validação do token (consulta
barata: formato, existência, finalidade, validade, uso e situação da conta) → política da nova senha
→ Argon2 (fora de transação) → transação que consome o token de forma atômica (UPDATE condicional),
trava a conta e grava. Token malformado, inexistente, expirado, consumido ou invalidado nunca chega ao
Argon2. A validação prévia não substitui o consumo atômico: duas requisições com o mesmo token geram
um único sucesso, e uma falha dentro da transação desfaz o consumo e a auditoria.

## Convite

1. O administrador informa nome, e-mail e, opcionalmente, perfis (exige `usuarios:atribuir-perfis`).
2. A conta é criada como `CONVIDADO` e o link é enviado.
3. O destinatário define a senha e a conta passa a `ATIVO`. Não há login automático.

E-mail já existente: `CONVITE_PENDENTE` (use o reenvio), `EMAIL_EM_USO` (ativo) ou
`USUARIO_DESATIVADO`. Nada da conta existente é alterado: o reenvio não reativa contas nem muda
perfis.

## Recuperação e alteração

- `POST /auth/senha/recuperacao` responde sempre **202** com a mesma mensagem. A busca da conta e o
  envio rodam em segundo plano, para que o tempo de resposta não revele se a conta existe. Só
  contas `ATIVO` recebem link; contas convidadas ou desativadas, não.
- `POST /auth/senha/redefinicao` consome o token, troca a senha e encerra todas as sessões.
- `POST /auth/senha/alteracao` exige a senha atual. A sessão atual continua e as demais são
  encerradas.

### Concorrência na troca e na redefinição

- **Troca autenticada**: a senha atual é conferida contra um instantâneo (hash e versão de
  segurança) fora de transação. A gravação acontece numa transação curta, com a linha do usuário
  travada (`SELECT ... FOR UPDATE`), e só prossegue se a conta continua ativa e o instantâneo ainda é
  o atual. Se uma redefinição, outra troca ou uma desativação concluiu no intervalo, nada é gravado:
  409 `CREDENCIAL_ALTERADA`, sem auditoria de sucesso, sem invalidar tokens e sem limpar contadores.
  O Argon2 nunca roda com a linha travada.
- **Redefinição por link**: também trava a conta na transação; conta que deixou de estar ativa faz a
  transação falhar e o token volta a não consumido.
- **Sessão atual na troca**: cada sessão tem uma chave interna aleatória (guardada só no servidor).
  Na mesma transação que incrementa a versão de segurança, a troca grava em `sessao_renovada` que
  *esta* sessão (hash da chave) continua válida na nova versão. Uma requisição da própria sessão que
  chegue antes de o principal ser regravado é aceita e o filtro promove o principal; as demais
  sessões não têm esse registro e são recusadas, sem janela de tolerância. Redefinição por link
  apaga os registros (encerra todas as sessões); desativação continua bloqueando porque a conta
  deixa de estar ativa.

## E-mail

SMTP via Spring Mail (Mailpit no local), texto simples em português, com o nome da aplicação, a
validade e as instruções. Estratégia: **envio síncrono depois do commit** (nenhuma transação fica
aberta esperando o SMTP), com timeouts de 5 s para conexão e 10 s para leitura e escrita. Se o envio
falhar, o token recém-emitido é invalidado (ninguém o recebeu), a falha é auditada e:

- no convite, a API responde `conviteEnviado: false`, a interface avisa e o administrador pode
  reenviar;
- na recuperação, a resposta continua genérica e a pessoa pode pedir de novo.

Não se usa fila (RabbitMQ) nem tabela de saída: guardar o e-mail pendente exigiria armazenar o link
com o token em claro. Links e destinatários nunca vão para o log.

Configuração fora do local: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true` e
`EMCONTA_EMAIL_REMETENTE`. Sem host configurado, nenhum e-mail sai e a falha é informada.

## Autorização

Por ação e recurso, no servidor (`SecurityConfiguration`, uma regra por rota). O cliente nunca
envia autoridades: o `Ator` de cada operação é montado das permissões calculadas pelo servidor.

| Permissão | Operações |
|---|---|
| `usuarios:consultar` | `GET /api/v1/usuarios`, `GET /api/v1/usuarios/{id}`, `GET /api/v1/perfis` |
| `usuarios:convidar` | `POST /api/v1/usuarios`, `POST /api/v1/usuarios/{id}/convite` |
| `usuarios:editar` | `PATCH /api/v1/usuarios/{id}` (somente o nome) |
| `usuarios:atribuir-perfis` | `PUT /api/v1/usuarios/{id}/perfis` |
| `usuarios:alterar-situacao` | `POST /api/v1/usuarios/{id}/desativacao` e `/reativacao` |
| `associados:consultar` | `GET /api/v1/associados`, `GET /api/v1/associados/{id}` |
| `associados:cadastrar` | `POST /api/v1/associados` (com contatos iniciais) |
| `associados:editar` | `PUT /api/v1/associados/{id}`, `POST …/contatos`, `PUT …/contatos/{contatoId}`, `POST …/contatos/{contatoId}/remocao` |
| `associados:consultar-historico` | `GET /api/v1/associados/{id}/historico` |
| `ucs:consultar` | `GET /api/v1/associados/{id}/ucs`, `GET /api/v1/associados/{id}/ucs/{ucId}` |
| `ucs:cadastrar` | `POST /api/v1/associados/{id}/ucs` |
| `ucs:editar` | `PUT /api/v1/associados/{id}/ucs/{ucId}` |
| `usinas:consultar` | `GET /api/v1/usinas`, `GET /api/v1/usinas/{id}`, `GET /api/v1/fazendas`, `GET /api/v1/fazendas/{id}` |
| `usinas:cadastrar` | `POST /api/v1/usinas`, `POST /api/v1/fazendas` |
| `usinas:editar` | `PUT /api/v1/usinas/{id}`, `PUT /api/v1/fazendas/{id}` |
| `usinas:consultar-historico` | `GET /api/v1/usinas/{id}/historico`, `GET /api/v1/fazendas/{id}/historico` |
| `vinculos-usina:consultar` | `GET /api/v1/usinas/{id}/vinculos`, `GET /api/v1/associados/{id}/vinculos-usina` |
| `vinculos-usina:criar` | `GET /api/v1/vinculos-usina/ucs-candidatas`, `POST /api/v1/usinas/{id}/vinculos` |
| `vinculos-usina:encerrar` | `POST /api/v1/usinas/{id}/vinculos/{vinculoId}/encerramento` |
| `atendimento:consultar` | `GET /api/v1/atendimento/canal`, `GET`/`PUT …/presenca`, `GET …/atendimentos`, `GET …/atendimentos/{id}`, `GET …/atendimentos/{id}/linha-do-tempo` e `…/linha-do-tempo/situacoes`, `GET …/atendimentos/{id}/midia`, `GET …/atendimentos/{id}/anexos/{anexo}/conteudo` (só anexo da conversa do atendimento), `GET …/historico` |
| `atendimento:assumir` | `POST /api/v1/atendimento/atendimentos/{id}/assuncao` |
| `atendimento:responder` | `POST /api/v1/atendimento/atendimentos/{id}/mensagens`, `POST …/{id}/anexos` (multipart, com CSRF) e `POST …/{id}/anexos/{anexo}/nova-tentativa` (só o responsável; destino de transferência precisa desta permissão) |
| `atendimento:transferir` | `POST …/atendimentos/{id}/transferencia` (só o responsável atual, em andamento), `GET /api/v1/atendimento/responsaveis` |
| `atendimento:encerrar` | `POST …/atendimentos/{id}/encerramento` (só o responsável atual, em andamento) |
| `atendimento:vincular-associado` | `PUT …/atendimentos/{id}/associado`, `GET …/atendimentos/{id}/associados-sugeridos`, `GET /api/v1/atendimento/associados` |
| `atendimento:reprocessar` | `GET /api/v1/atendimento/webhook-falhas`, `POST …/webhook-eventos/{id}/reprocessamento` |
| `respostas-rapidas:consultar` | `GET /api/v1/atendimento/respostas-rapidas` e `GET …/respostas-rapidas/{id}` |
| `respostas-rapidas:cadastrar` | `POST /api/v1/atendimento/respostas-rapidas` |
| `respostas-rapidas:editar` | `PUT …/respostas-rapidas/{id}` (exige `versao`) |
| `respostas-rapidas:alterar-situacao` | `POST …/respostas-rapidas/{id}/ativacao` e `…/desativacao` (exigem `versao`) |
| (público, assinatura HMAC) | `GET`/`POST /api/v1/integracoes/whatsapp/webhook` — ver [atendimento.md](atendimento.md#provedor) |
| (sessão válida) | `GET /api/v1/auth/sessao`, `POST /api/v1/auth/senha/alteracao` |

Qualquer outra rota sob `/api/v1/associados/**`, `/api/v1/usinas/**`, `/api/v1/fazendas/**` ou
`/api/v1/vinculos-usina/**` é negada mesmo com sessão.

**Negação por padrão**: a configuração termina em `anyRequest().denyAll()`. Uma rota nova sem regra
explícita (e qualquer método HTTP não declarado numa rota existente) é negada, inclusive para o
administrador: 401 sem sessão, 403 com sessão (`AutorizacaoPadraoIntegrationTests`, com um endpoint
que só existe no contexto de teste).

Perfil técnico inicial: **`ADMINISTRADOR`**, com todas as permissões acima. As permissões de cada
perfil são listadas explicitamente em `Perfil` (não há `allOf`): permissões novas não são
concedidas a nenhum perfil sem revisão. Perfis e permissões vivem no código, por isso a concessão
das permissões de associados ao administrador é feita nessa lista versionada, e não por migration.

Proteções:

- **Escalada**: só é possível conceder ou retirar um perfil cujas permissões o ator já tenha.
- **Campos não autorizados**: JSON com campos desconhecidos é recusado (400). Os DTOs de entrada
  só têm os campos editáveis.
- **Último administrador**: retirar o perfil ou desativar o último administrador ativo resulta em
  409 `ULTIMO_ADMINISTRADOR`. As operações travam os administradores ativos
  (`SELECT ... FOR UPDATE`), então duas operações simultâneas não deixam o sistema sem
  administrador. Ninguém desativa a própria conta (`PROPRIA_CONTA`).
- **Reativação**: volta a `ATIVO` se a conta já tinha senha; senão volta a `CONVIDADO` e exige
  reenvio do convite. Os perfis são mantidos.

**Testes de permissão parcial**: `PermissoesEfetivas` (pacote `acesso.sessao`) é o ponto de
extensão do cálculo perfil → permissões. Em produção não há bean e vale `Permissao.de(perfis)`. Os
testes registram `PermissoesControladas`, que concede conjuntos exatos a usuários de teste
específicos; sessão, `SessaoUsuarioFilter` e regras de rota continuam os reais e nenhum perfil
fictício existe no código de produção (`AutorizacaoParcialIntegrationTests`).

**Pendente (DP-16)**: a matriz dos perfis comercial, atendimento, operação, financeiro e gestão
não foi aprovada. As permissões de negócio nascem com cada módulo (hoje: associados e UCs) e só o
administrador técnico as recebe até a matriz ser aprovada.

## Auditoria

`auditoria_seguranca` registra login (sucesso, falha, bloqueio), logout, criação, reenvio e aceite
de convite, solicitação de recuperação, redefinição e troca de senha, senha atual incorreta,
renomeação, mudança de perfis, desativação, reativação, falhas de e-mail e o bootstrap. Guarda o
ator, o alvo, o IP e detalhes não sensíveis. Nunca contém senha, token, hash, cookie ou o e-mail
digitado numa tentativa. Não há tela de consulta nesta etapa.

## Pendências

- Portal do cliente: autenticação própria (escopo restrito aos dados do cliente), em entrega
  separada.
- MFA, política de senha definitiva, verificação de senhas vazadas e tempos de sessão por perfil.
- Matriz de permissões por departamento.
- Alteração de e-mail com confirmação; tela de auditoria; aviso por e-mail de troca de senha.
- Ajustar `server.tomcat.remoteip.internal-proxies` e o SMTP reais no ambiente de produção.
