FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl openhim-mediator-iol-edfi -am -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --create-home mediator
WORKDIR /app
COPY --from=build /workspace/openhim-mediator-iol-edfi/target/openhim-mediator-iol-edfi-*.jar app.jar
COPY runtime-entrypoint.sh /usr/local/bin/iol-java-mediator-entrypoint
RUN chmod 0555 /usr/local/bin/iol-java-mediator-entrypoint
USER mediator
EXPOSE 3103
ENTRYPOINT ["/usr/local/bin/iol-java-mediator-entrypoint"]
