# syntax=docker/dockerfile:1.7

# Le contexte de build est la RACINE du depot, et non ce dossier: les moteurs
# Python vivent a la racine et doivent etre copies dans l'image. Les monter
# depuis l'hote laissait le code qui transforme reellement les donnees hors de
# la chaine signee — non signe, non scanne, absent des SBOM, et insensible a un
# retour arriere de version.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pipeline-consumer/pom.xml .
COPY backend/pipeline-consumer/src ./src
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
COPY backend/pipeline-consumer/requirements.txt /app/requirements.txt
COPY backend/pipeline-consumer/docker-entrypoint.sh /app/docker-entrypoint.sh

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-pip curl \
    && rm -rf /var/lib/apt/lists/*
RUN pip3 install --no-cache-dir --prefer-binary --timeout 120 --retries 8 \
    -r /app/requirements.txt
RUN chmod +x /app/docker-entrypoint.sh
COPY --from=hop-runtime /opt/hop /opt/hop
COPY --from=hop-java /opt/java/openjdk /opt/iol/java21

# Le projet Hop fait partie de la release : aucun dossier d'hote ni installation
# de Hop n'est requis sur le serveur cible. Les modifications d'un workflow
# passent donc par la construction d'une nouvelle image versionnee.
COPY hop-project/ /opt/iol/project/
RUN chmod -R a=rX /opt/iol/project

# Moteurs d'execution, embarques dans l'image et non plus montes depuis l'hote.
# Ils sont ainsi couverts par la signature cosign, les SBOM et le scan Trivy, et
# un retour arriere de version les ramene avec le reste.
#
# moteur_universel.py importe mapping_engine: les deux doivent rester dans le
# meme dossier, que Python ajoute au chemin d'import en executant le script.
#
# Place en fin de Dockerfile a dessein: une correction de moteur ne doit pas
# invalider les couches d'installation apt et pip, qui coutent plusieurs minutes.
COPY moteur_universel.py mapping_engine.py spark_etl.py /opt/iol/engine/
RUN chmod 0444 /opt/iol/engine/*.py

# Variables par défaut — surchargées par docker-compose.yml
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:9092
ENV APP_KAFKA_COMMANDS_TOPIC=iol.pipeline.commands
ENV APP_KAFKA_STATUS_TOPIC=iol.pipeline.status
ENV APP_KAFKA_DLQ_TOPIC=iol.pipeline.commands.dlq
ENV HOP_HOME=/opt/hop
ENV HOP_JAVA_HOME=/opt/iol/java21
ENV HOP_RUN_CONFIG=local
ENV HOP_PROJECT_HOME=/opt/iol/project
ENV HOP_PIPELINES_DIR="/opt/iol/project/Projet ETL/Global_Config"
ENV HOP_TEMP_DIR=/tmp/iol
ENV SPARK_SUBMIT_PATH=/opt/spark/bin/spark-submit
ENV SPARK_JDBC_JARS_DIR=/opt/iol/jdbc

ENTRYPOINT ["/app/docker-entrypoint.sh"]
