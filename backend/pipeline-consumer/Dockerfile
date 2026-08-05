# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package \
    && mvn -q dependency:copy-dependencies \
      -DincludeScope=runtime \
      -DincludeArtifactIds=postgresql,mysql-connector-j,mariadb-java-client,mssql-jdbc,ojdbc11,sqlite-jdbc,snowflake-jdbc,redshift-jdbc42 \
      -DoutputDirectory=/workspace/jdbc

FROM apache/hop:2.18.0 AS hop-runtime
FROM eclipse-temurin:21-jre-jammy AS hop-java

FROM apache/spark:3.5.7-java17-python3
USER root
WORKDIR /app
COPY --from=build /workspace/target/pipeline-consumer-*.jar /app/pipeline-consumer.jar
COPY --from=build /workspace/jdbc /opt/iol/jdbc
COPY requirements.txt /app/requirements.txt
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-pip curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip3 install --no-cache-dir --prefer-binary --timeout 120 --retries 8 \
    -r /app/requirements.txt
RUN chmod +x /app/docker-entrypoint.sh
COPY --from=hop-runtime /opt/hop /opt/hop
COPY --from=hop-java /opt/java/openjdk /opt/iol/java21

# Variables par défaut — surchargées par docker-compose.yml
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092
ENV APP_KAFKA_COMMANDS_TOPIC=iol.pipeline.commands
ENV APP_KAFKA_STATUS_TOPIC=iol.pipeline.status
ENV APP_KAFKA_DLQ_TOPIC=iol.pipeline.commands.dlq
ENV HOP_HOME=/opt/hop
ENV HOP_JAVA_HOME=/opt/iol/java21
ENV HOP_RUN_CONFIG=local
ENV HOP_PIPELINES_DIR=/opt/hop/pipelines/iol
ENV HOP_TEMP_DIR=/tmp/iol
ENV SPARK_SUBMIT_PATH=/opt/spark/bin/spark-submit
ENV SPARK_JDBC_JARS_DIR=/opt/iol/jdbc

ENTRYPOINT ["/app/docker-entrypoint.sh"]
