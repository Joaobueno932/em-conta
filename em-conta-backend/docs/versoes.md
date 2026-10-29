# Versões do backend

Consultadas em 28/10/2026 no Spring Initializr (start.spring.io), no Maven Central e no Docker
Hub. Todas são versões finais (sem snapshot, milestone ou RC) e fixadas, sem faixas dinâmicas.

| Componente | Versão | Motivo |
|---|---|---|
| Java | 25 (LTS) | LTS mais recente suportada pelo Boot 4.1; o 27 não é LTS |
| Spring Boot | 4.1.2 | Versão estável padrão do Initializr (4.2 ainda em RC) |
| Spring Framework / Security | 7.x / 7.1.2 | Gerenciados pelo Boot |
| Spring Session JDBC | 4.1.1 | Gerenciado pelo Boot |
| Spring Modulith | 2.1.2 | Linha 2.1 compatível com Boot 4.1 (o Initializr sugeriu 2.1.1; 2.1.2 é patch) |
| springdoc-openapi | 3.2.0 | Linha 3.x para Boot 4; a 3.2.0 é compilada contra Boot 4.1.2 |
| Flyway | 12.4.0 (gerenciado pelo Boot) | Sem sobrescrita de versão |
| Hibernate ORM | 7.4.13.Final (gerenciado pelo Boot) | Sem sobrescrita de versão |
| MySQL Connector/J | 9.7.0 (gerenciado pelo Boot) | Sem sobrescrita de versão |
| Testcontainers | 2.0.5 (gerenciado pelo Boot) | Módulos `testcontainers-mysql` e `-junit-jupiter` |
| Spotless Maven Plugin | 3.10.4 | Formatação com Palantir Java Format |
| BouncyCastle (`bcprov-jdk18on`) | 1.86 | Exigido pelo `Argon2PasswordEncoder` do Spring Security; não é gerenciado pelo Boot |
| Spring Boot Mail (Angus Mail) | gerenciado pelo Boot | Envio SMTP de convite e recuperação |
| Maven (wrapper) | 3.9.16 via Maven Wrapper 3.3.4 | Linha 3.9 estável; 4.0 ainda em RC |

## Imagens Docker

| Uso | Imagem |
|---|---|
| Build do backend | `maven:3.9.16-eclipse-temurin-25` |
| Runtime do backend | `eclipse-temurin:25-jre-noble` |
| MySQL | `mysql:8.4.13` (8.4 LTS; mesma versão nos testes) |
| RabbitMQ | `rabbitmq:4.2.9-management-alpine` |
| MinIO | `cgr.dev/chainguard/minio@sha256:e70b2b1e…a5a7` (MinIO `RELEASE.2026-10-23T20-06-51Z`) |
| Mailpit | `axllent/mailpit:v1.31.6` |

**MinIO**: a MinIO deixou de publicar as imagens `minio/minio` (Docker Hub) e o `quay.io/minio`
exige autenticação. Foi usada a imagem pública da Chainguard, fixada por **digest** (tag única
`latest` no plano gratuito). Para atualizar: `docker pull cgr.dev/chainguard/minio:latest`,
`docker inspect --format '{{index .RepoDigests 0}}' cgr.dev/chainguard/minio:latest` e trocar o
digest em `infra/compose.yaml` (nos serviços `minio` e `minio-init`). Alternativa futura: compilar
o MinIO a partir do código-fonte ou adotar outro storage compatível com S3; decidir quando o
armazenamento de documentos for implementado.

## Atualização

Mudanças de versão devem passar por `./mvnw verify`. Antes de atualizar a versão menor do Boot,
confira a compatibilidade de Spring Modulith e springdoc nas respectivas notas de versão.
