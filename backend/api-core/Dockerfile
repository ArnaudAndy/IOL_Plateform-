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
EXPOSE 8084
ENTRYPOINT ["/usr/local/bin/iol-api-entrypoint"]
