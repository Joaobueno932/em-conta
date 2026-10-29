# Equivalente PowerShell de scripts/mvnw-docker.sh: executa o Maven Wrapper num container com JDK 25.
#
#   .\scripts\mvnw-docker.ps1 verify
#   $env:EXTRA_CA_FILE = "C:\caminho\ca.pem"           # opcional: CA de proxy corporativo
#   $env:MAVEN_MIRROR_URL = "https://maven-central.storage-download.googleapis.com/maven2"  # opcional
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)

$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

$dockerArgs = @(
    'run', '--rm',
    '-v', "${projectDir}:/workspace",
    '-v', 'em-conta-m2:/root/.m2',
    '-v', '/var/run/docker.sock:/var/run/docker.sock',
    '-e', "MAVEN_MIRROR_URL=$($env:MAVEN_MIRROR_URL)",
    '-e', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal',
    '-w', '/workspace'
)
if ($env:EXTRA_CA_FILE) {
    $dockerArgs += @('-v', "$((Resolve-Path $env:EXTRA_CA_FILE).Path):/tmp/extra-ca.pem:ro")
}

$dockerArgs += @('maven:3.9.16-eclipse-temurin-25', 'sh', 'scripts/in-container-mvnw.sh') + $MavenArgs
& docker @dockerArgs
exit $LASTEXITCODE
