# syntax=docker/dockerfile:1.7

# --- Étape build : compile le JAR ---
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
ENV MAVEN_OPTS="-Xmx768m"
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

# --- Étape runtime ---
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

# Ce service porte le transport des donnees source: il streame de gros volumes
# et doit etre borne dans la limite memoire du conteneur. ExitOnOutOfMemoryError
# le fait mourir vite pour que l'orchestrateur le remplace, plutot que de le
# laisser degrade en tenant une reservation d'execution.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

# Aucun port publie: l'entree metier est un topic Kafka. Le port applicatif ne
# sert qu'a l'interface interne mTLS, le port de management aux sondes.
EXPOSE 8087 8088

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
