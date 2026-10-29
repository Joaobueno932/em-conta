# CI do backend

O provedor Git ainda não foi definido, por isso não há arquivo de pipeline (GitHub Actions,
GitLab CI etc.) neste repositório. O pipeline deve executar o mesmo comando usado localmente.

## Comando de verificação

```sh
./mvnw -B -ntp verify
```

Requisitos do runner: JDK 25 e **Docker** acessível (Testcontainers sobe `mysql:8.4.13`). Sem JDK
no runner, use `sh scripts/mvnw-docker.sh -B -ntp verify` (requer só Docker e o socket
`/var/run/docker.sock`).

Etapas sugeridas para qualquer provedor:

1. Checkout.
2. Cache de `~/.m2/repository` (chave: hash do `pom.xml`).
3. `./mvnw -B -ntp verify`.
4. Publicar `target/surefire-reports/` (JUnit XML) e, opcionalmente,
   `target/spring-modulith-docs/`.
5. Opcional: `docker build -t em-conta-backend:<sha> .` Publicar a imagem só depois de definir
   registry e credenciais (não configurados).

Exemplo genérico (adaptar à sintaxe do provedor):

```yaml
image: maven:3.9.16-eclipse-temurin-25   # ou runner com JDK 25
services: [docker]                        # Testcontainers precisa de Docker
script:
  - ./mvnw -B -ntp verify
artifacts:
  - target/surefire-reports/**
```

Ao criar o arquivo real do pipeline, adicione-o a este repositório e atualize este documento.

## Redes corporativas

Na rede onde a fundação foi criada há inspeção TLS (FortiGate) e cortes em downloads grandes do
Maven Central. Ajustes que não alteram a máquina nem o projeto:

- `EXTRA_CA_FILE=<pem>`: CA corporativa importada **somente** no truststore do container
  (`scripts/mvnw-docker.*`) ou passada ao build como secret
  (`docker build --secret id=extra_ca,src=<pem>`).
- `MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2`: espelho público
  oficial do Maven Central, usado também pelo Maven Wrapper (`MVNW_REPOURL`).

Para exportar a CA do Windows (PowerShell), substitua o filtro pelo emissor da sua rede:

```powershell
$certs = Get-ChildItem Cert:\LocalMachine\Root | Where-Object Subject -match 'O=Fortinet'
$pem = $certs | ForEach-Object { "-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($_.RawData, 'InsertLineBreaks') + "`n-----END CERTIFICATE-----" }
[IO.File]::WriteAllText("$env:TEMP\corp-ca.pem", ($pem -join "`n").Replace("`r`n", "`n"))
```

Nunca versione o arquivo da CA.
