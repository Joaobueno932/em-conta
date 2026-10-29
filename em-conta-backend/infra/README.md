# Infraestrutura local

Docker Compose para desenvolvimento do backend **e** do frontend. Não é infraestrutura de produção.

```powershell
cd em-conta-backend\infra
Copy-Item .env.example .env      # bash: cp .env.example .env  (ajuste as senhas de exemplo)
docker compose up -d --wait      # sobe e espera os health checks
docker compose ps                # todos "healthy"; minio-init "Exited (0)"
docker compose logs -f mysql     # logs de um serviço
docker compose stop              # para, mantendo dados
docker compose down              # remove containers, mantendo volumes
```

> `docker compose down -v` **apaga os volumes** (dados do MySQL, filas, objetos e e-mails). Use só
> quando quiser recomeçar do zero.

## Serviços

Todas as portas são publicadas apenas em `127.0.0.1`.

| Serviço | Imagem | Porta local | Uso atual |
|---|---|---|---|
| `mysql` | `mysql:8.4.13` | `3310` → 3306 | Banco do backend (`utf8mb4`, UTC) |
| `rabbitmq` | `rabbitmq:4.2.9-management-alpine` | `5672` AMQP, `15672` painel | Preparação; ainda não usado |
| `minio` | Chainguard MinIO (digest fixo) | `9000` S3, `9001` console | Preparação; ainda não usado |
| `minio-init` | mesma do MinIO | — | Cria o bucket `MINIO_BUCKET` (idempotente) e encerra |
| `mailpit` | `axllent/mailpit:v1.31.6` | `1025` SMTP, `8025` painel | Recebe os e-mails de convite e recuperação de senha (nada sai para a internet) |

Painéis: RabbitMQ http://localhost:15672 · MinIO http://localhost:9001 · Mailpit
http://localhost:8025 (credenciais do `.env`).

**Porta do MySQL**: 3310, para não conflitar com um MySQL instalado na máquina (3306) nem com
outros projetos (3307). Se mudar `MYSQL_PORT`, rode o backend com `DB_PORT=<porta>`.

Dependências: `minio-init` depende de `minio` saudável. O backend precisa do `mysql` e, para os e-mails, do
`mailpit`; os demais são opcionais nesta etapa. O frontend não acessa a infraestrutura: ele fala apenas com o backend via
`/api`.

Volumes persistentes: `mysql-data`, `rabbitmq-data`, `minio-data`, `mailpit-data` (prefixo
`em-conta_`).

## Rodando o backend em container na mesma rede

```powershell
docker run --rm --network em-conta_default -p 127.0.0.1:8080:8080 `
  -e SPRING_PROFILES_ACTIVE=local,api -e DB_HOST=mysql -e DB_PORT=3306 em-conta-backend
```

## Segredos

`.env` é ignorado pelo Git. `.env.example` contém apenas valores de exemplo para uso local.
