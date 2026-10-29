# Imagem do backend. A mesma imagem roda como API ou worker, conforme SPRING_PROFILES_ACTIVE.
#
#   docker build -t em-conta-backend .
#
# Redes com inspeção TLS ou downloads instáveis (opcional, nada fica gravado na imagem final):
#   docker build --secret id=extra_ca,src=caminho/ca.pem \
#     --build-arg MAVEN_MIRROR_URL=https://maven-central.storage-download.googleapis.com/maven2 -t em-conta-backend .

FROM maven:3.9.16-eclipse-temurin-25 AS build
ARG MAVEN_MIRROR_URL=""
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=cache,target=/root/.m2 \
    set -e; \
    if [ -f /run/secrets/extra_ca ]; then \
      csplit -s -z -f /tmp/ca- /run/secrets/extra_ca "/-----BEGIN CERTIFICATE-----/" "{*}"; \
      for f in /tmp/ca-*; do keytool -importcert -noprompt -cacerts -storepass changeit -alias "extra-$(basename "$f")" -file "$f" >/dev/null; done; \
    fi; \
    if [ -n "$MAVEN_MIRROR_URL" ]; then \
      printf "<settings><mirrors><mirror><id>central-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>" "$MAVEN_MIRROR_URL" > /tmp/settings.xml; \
      export MAVEN_ARGS="-s /tmp/settings.xml" MVNW_REPOURL="$MAVEN_MIRROR_URL"; \
    fi; \
    sh ./mvnw -B -ntp package -DskipTests -Dspotless.check.skip=true; \
    cp target/em-conta-backend-*.jar /tmp/app.jar

FROM eclipse-temurin:25-jre-noble
RUN groupadd --system emconta && useradd --system --gid emconta --no-create-home emconta
WORKDIR /app
COPY --from=build /tmp/app.jar app.jar
USER emconta
ENV TZ=UTC \
    SPRING_PROFILES_ACTIVE=api \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
