# syntax=docker/dockerfile:1.7

# --- Étape build : compile le JAR ---
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Xmx768m"
COPY pom.xml .
COPY .mvn ./.mvn
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

# --- Étape runtime ---
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
COPY docker-entrypoint.sh /usr/local/bin/iol-api-entrypoint
RUN chmod 0555 /usr/local/bin/iol-api-entrypoint

# Le heap est borne DANS la limite memoire du conteneur: sans cela la JVM prend
# 25 % de la RAM visible et une extraction concurrente emporte l'API avec le
# portail et les probes. ExitOnOutOfMemoryError fait mourir vite le conteneur
# pour que l'orchestrateur le remplace, au lieu de le laisser degrade.
# L'entrypoint complete cette valeur avec les options TLS; toute surcharge par
# l'environnement reste possible.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

EXPOSE 8084
ENTRYPOINT ["/usr/local/bin/iol-api-entrypoint"]
