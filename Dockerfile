# syntax=docker/dockerfile:1.7
# =====================================================================================
# Backend Apartamentos Ochavada · Spring Boot 4 / Java 21
#   1) build: compila con Maven (caché de dependencias de BuildKit) y extrae el JAR por capas
#   2) runtime: JRE 21 mínimo, usuario sin privilegios, capas ordenadas de menos a más cambiantes
#      para que una nueva versión solo descargue la capa "application".
# =====================================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/apartamentos-ochavada-backend.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache tzdata \
 && addgroup -S -g 10001 app \
 && adduser -S -u 10001 -G app -h /app app
WORKDIR /app

COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./

USER app
# Memoria: heap = 70 % del límite del contenedor (mem_limit); metaspace y caché de código acotados para que
# heap + resto quepan en ese límite (con 1280m: ~900 MB de heap + ~320 MB máximo del resto).
ENV TZ=Europe/Madrid \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:MaxMetaspaceSize=200m -XX:ReservedCodeCacheSize=120m -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080

# El arranque incluye las migraciones Flyway: se da margen antes de marcar el contenedor como no sano.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
