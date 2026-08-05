#!/usr/bin/env python3
import argparse
import datetime
import json
import os
import re
import sys
import unicodedata
from urllib.parse import quote_plus

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from sqlalchemy import create_engine


JDBC_PROTOCOLS = {
    "POSTGRES", "MYSQL", "MARIADB", "MSSQL",
    "ORACLE", "SQLITE", "SNOWFLAKE", "REDSHIFT",
}
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_$]*$")
COMPUTED_EXPRESSION = re.compile(r"[A-Za-z0-9_+*/(). '\"-]+")


def log(message):
    print(str(message).encode("ascii", "replace").decode("ascii"), flush=True)


def clean_column_name(raw):
    value = "" if raw is None else str(raw).strip()
    normalized = unicodedata.normalize("NFD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii")
    sanitized = re.sub(r"[^a-zA-Z0-9_]", "_", ascii_value)
    sanitized = re.sub(r"^_+", "", sanitized)
    sanitized = re.sub(r"_+", "_", sanitized).lower() or "column"
    return "c_" + sanitized if sanitized[0].isdigit() else sanitized


def unique_columns(columns):
    counts = {}
    result = []
    for raw in columns:
        base = clean_column_name(raw)
        count = counts.get(base, 0)
        counts[base] = count + 1
        result.append(base if count == 0 else f"{base}_{count + 1}")
    return result


def value(mapping, *keys, default=None):
    for key in keys:
        if key in mapping and mapping[key] is not None:
            return mapping[key]
    return default


def normalize_db_type(raw):
    normalized = str(raw or "").strip().upper().replace("-", "_")
    aliases = {
        "POSTGRESQL": "POSTGRES",
        "PG": "POSTGRES",
        "SQLSERVER": "MSSQL",
        "SQL_SERVER": "MSSQL",
        "MARIA_DB": "MARIADB",
    }
    return aliases.get(normalized, normalized)


def jdbc_connection(db_type, config):
    db_type = normalize_db_type(db_type)
    host = str(config.get("host") or "")
    port = str(config.get("port") or default_port(db_type))
    database = str(config.get("database") or "")
    username = str(config.get("username") or "")
    # Le worker injecte le credential éphémère hors du fichier de métadonnées.
    password = os.environ.get("TARGET_PASSWORD") or str(config.get("password") or "")
    extra = config.get("additional_properties") or {}

    if db_type == "POSTGRES":
        url = f"jdbc:postgresql://{host}:{port}/{database}"
        driver = "org.postgresql.Driver"
    elif db_type == "REDSHIFT":
        url = f"jdbc:redshift://{host}:{port}/{database}"
        driver = "com.amazon.redshift.jdbc.Driver"
    elif db_type == "MYSQL":
        url = f"jdbc:mysql://{host}:{port}/{database}?useSSL=false&allowPublicKeyRetrieval=true"
        driver = "com.mysql.cj.jdbc.Driver"
    elif db_type == "MARIADB":
        url = f"jdbc:mariadb://{host}:{port}/{database}"
        driver = "org.mariadb.jdbc.Driver"
    elif db_type == "MSSQL":
        url = (
            f"jdbc:sqlserver://{host}:{port};databaseName={database};"
            "encrypt=false;trustServerCertificate=true"
        )
        driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    elif db_type == "ORACLE":
        url = f"jdbc:oracle:thin:@//{host}:{port}/{database}"
        driver = "oracle.jdbc.OracleDriver"
    elif db_type == "SQLITE":
        url = f"jdbc:sqlite:{database}"
        driver = "org.sqlite.JDBC"
    elif db_type == "SNOWFLAKE":
        account = str(extra.get("account") or host).replace(".snowflakecomputing.com", "")
        query = [f"db={quote_plus(database)}"]
        if config.get("schema"):
            query.append("schema=" + quote_plus(str(config["schema"])))
        for key in ("warehouse", "role"):
            if extra.get(key):
                query.append(key + "=" + quote_plus(str(extra[key])))
        url = f"jdbc:snowflake://{account}.snowflakecomputing.com/?{'&'.join(query)}"
        driver = "net.snowflake.client.jdbc.SnowflakeDriver"
    else:
        raise ValueError(f"Unsupported JDBC database type: {db_type}")

    return {
        "db_type": db_type,
        "url": url,
        "driver": driver,
        "user": username,
        "password": password,
        "database": database,
        "host": host,
        "port": port,
        "schema": str(config.get("schema") or ""),
        "extra": extra,
    }


def default_port(db_type):
    return {
        "POSTGRES": 5432,
        "REDSHIFT": 5439,
        "MYSQL": 3306,
        "MARIADB": 3306,
        "MSSQL": 1433,
        "ORACLE": 1521,
        "SNOWFLAKE": 443,
    }.get(db_type, "")


def sqlalchemy_url(connection):
    user = quote_plus(connection["user"])
    password = quote_plus(connection["password"])
    host = connection["host"]
    port = connection["port"]
    database = quote_plus(connection["database"])
    db_type = connection["db_type"]
    if db_type == "POSTGRES":
        return f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{database}"
    if db_type == "REDSHIFT":
        return f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{database}"
    if db_type == "MYSQL":
        return f"mysql+pymysql://{user}:{password}@{host}:{port}/{database}"
    if db_type == "MARIADB":
        return f"mariadb+pymysql://{user}:{password}@{host}:{port}/{database}"
    if db_type == "MSSQL":
        return f"mssql+pymssql://{user}:{password}@{host}:{port}/{database}"
    if db_type == "ORACLE":
        return f"oracle+oracledb://{user}:{password}@{host}:{port}/?service_name={database}"
    if db_type == "SQLITE":
        return "sqlite:///" + connection["database"].replace("\\", "/")
    if db_type == "SNOWFLAKE":
        account = str(connection["extra"].get("account") or host).replace(
            ".snowflakecomputing.com", "")
        schema = connection["schema"]
        path = f"/{quote_plus(schema)}" if schema else ""
        query = []
        for key in ("warehouse", "role"):
            if connection["extra"].get(key):
                query.append(key + "=" + quote_plus(str(connection["extra"][key])))
        suffix = "?" + "&".join(query) if query else ""
        return f"snowflake://{user}:{password}@{account}/{database}{path}{suffix}"
    raise ValueError(f"SQL execution is not configured for {db_type}")


def read_file_source(spark, protocol, config):
    path = str(config.get("file_path") or config.get("uri") or "")
    if not path:
        raise ValueError(f"Missing file path for {protocol}")
    nested = config.get("source_config") or {}
    if protocol == "CSV":
        delimiter = str(nested.get("delimiter") or config.get("delimiter") or ",")
        return (
            spark.read.option("header", "true")
            .option("inferSchema", "false")
            .option("delimiter", delimiter)
            .option("encoding", str(nested.get("encoding") or config.get("encoding") or "UTF-8"))
            .csv(path)
        )
    if protocol == "JSON":
        return spark.read.option("multiLine", str(config.get("multi_line", False)).lower()).json(path)
    if protocol == "PARQUET":
        return spark.read.parquet(path)
    if protocol == "ORC":
        return spark.read.orc(path)
    if protocol == "AVRO":
        return spark.read.format("avro").load(path)
    if protocol == "EXCEL":
        raise ValueError("EXCEL is not supported in distributed mode; convert it to CSV or Parquet.")
    raise ValueError(f"Unsupported Spark file source: {protocol}")


def apply_projection(frame, config):
    raw_fields = config.get("fields") or []
    fields = [str(field) for field in raw_fields if str(field).strip()]
    incremental = str(config.get("incremental_column") or "")
    if incremental and incremental not in fields:
        fields.append(incremental)
    present = [field for field in fields if field in frame.columns]
    return frame.select(*present) if present else frame


def mapping_sources(mapping):
    fields = value(mapping, "sourceFields", "source_fields", default=[])
    if isinstance(fields, str):
        fields = [fields]
    return [str(field) for field in fields or [] if str(field).strip()]


def apply_mappings(frame, config):
    nested = config.get("source_config") or {}
    mappings = nested.get("mapping_config") or []
    if nested.get("already_pivot") or not mappings:
        return frame
    result = frame
    for mapping in mappings:
        mapping_type = str(value(mapping, "mappingType", "mapping_type", default="DIRECT")).upper()
        target = str(value(mapping, "iolTerm", "iol_term", default="")).strip()
        present = [field for field in mapping_sources(mapping) if field in result.columns]
        if not target or not present:
            continue
        if mapping_type == "DIRECT":
            source = present[0]
            result = result.withColumn(target, F.col(source))
            if source != target:
                result = result.drop(source)
        elif mapping_type == "COALESCE":
            result = result.withColumn(target, F.coalesce(*[F.col(field) for field in present]))
            for source in present:
                if source != target:
                    result = result.drop(source)
        elif mapping_type == "COMPUTED":
            expression = str(mapping.get("expression") or "").strip()
            if not expression or not COMPUTED_EXPRESSION.fullmatch(expression):
                raise ValueError(f"Forbidden computed mapping expression for {target}")
            result = result.withColumn(target, F.expr(expression))
        else:
            raise ValueError(f"Unsupported mapping type: {mapping_type}")
    return result


def target_connection(source_config):
    target = source_config.get("target_connection") or {}
    if not target:
        raise ValueError("Missing resolved target_connection")
    return jdbc_connection(target.get("db_type"), target)


def write_jdbc(frame, connection, table, mode, config):
    if not table or not re.fullmatch(
            r"[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)?", table):
        raise ValueError(f"Invalid target table: {table}")
    partitions = max(1, int(config.get("spark_write_partitions") or config.get("partition_count") or 4))
    if connection["db_type"] == "SQLITE":
        partitions = 1
    output = frame.coalesce(partitions) if frame.rdd.getNumPartitions() > partitions else frame
    writer = (
        output.write.format("jdbc")
        .option("url", connection["url"])
        .option("driver", connection["driver"])
        .option("dbtable", table)
        .option("user", connection["user"])
        .option("password", connection["password"])
        .option("batchsize", max(100, int(config.get("sql_batch_rows") or 5000)))
        .option("isolationLevel", "READ_COMMITTED")
    )
    if mode == "overwrite":
        writer = writer.option("truncate", "true")
    writer.mode(mode).save()


def read_target_table(spark, connection, table):
    return (
        spark.read.format("jdbc")
        .option("url", connection["url"])
        .option("driver", connection["driver"])
        .option("dbtable", table)
        .option("user", connection["user"])
        .option("password", connection["password"])
        .load()
    )


def split_sql_script(script):
    return [statement.strip() for statement in str(script or "").split(";") if statement.strip()]


def execute_database_sql(connection, script):
    if not str(script or "").strip():
        return
    engine = create_engine(sqlalchemy_url(connection), pool_pre_ping=True)
    try:
        with engine.begin() as database:
            for statement in split_sql_script(script):
                database.exec_driver_sql(statement)
    finally:
        engine.dispose()


def stage_enabled(stage):
    if not stage:
        return False
    raw = stage.get("enabled")
    return True if raw is None else bool(raw)


def index_sql(connection, target_table, indexes):
    if not indexes or connection["db_type"] in {"SNOWFLAKE", "REDSHIFT"}:
        return ""
    statements = []
    for definition in indexes:
        columns = [str(column) for column in definition.get("columns") or []]
        if not columns:
            continue
        name = str(definition.get("name") or (
            "idx_" + target_table.replace(".", "_") + "_" + "_".join(columns)))
        unique = "UNIQUE " if definition.get("unique") else ""
        if connection["db_type"] in {"POSTGRES", "SQLITE"}:
            statements.append(
                f"CREATE {unique}INDEX IF NOT EXISTS {name} "
                f"ON {target_table} ({', '.join(columns)})"
            )
        else:
            statements.append(
                f"CREATE {unique}INDEX {name} ON {target_table} ({', '.join(columns)})"
            )
    return ";".join(statements)


def register_views(frame, *names):
    for raw in names:
        name = clean_column_name(raw)
        if name:
            frame.createOrReplaceTempView(name)


def run_spark_stage(spark, stage, target_key, connection, view_name, write_config):
    target_table = str(stage.get(target_key) or "")
    execute_database_sql(connection, stage.get("pre_sql"))
    result = spark.sql(str(stage.get("spark_sql") or ""))
    write_jdbc(result, connection, target_table, "overwrite", write_config)
    execute_database_sql(connection, stage.get("post_sql"))
    execute_database_sql(connection, index_sql(connection, target_table, stage.get("indexes")))
    register_views(result, view_name, target_table)
    return result


def process(metadata):
    workflow_id = str(metadata.get("workflowId") or "unknown")
    execution_id = str(metadata.get("execLogId") or "unknown")
    spark = (
        SparkSession.builder
        .appName(f"iol-{workflow_id}-{execution_id}")
        .config("spark.sql.adaptive.enabled", "true")
        .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    source_results = []
    try:
        for source_index, source in enumerate(metadata.get("sources") or []):
            protocol = normalize_db_type(source.get("source_name") or source.get("type"))
            config = source.get("config") or {}
            log(f"IOL_STAGE::EXTRACTION::START source={source_index} protocol={protocol}")
            if protocol in JDBC_PROTOCOLS:
                raise ValueError(
                    "Direct JDBC source access is forbidden; api-core must transport data through Kafka or RustFS."
                )
            frame = read_file_source(spark, protocol, config)
            frame = apply_projection(frame, config)
            frame = apply_mappings(frame, config)
            frame = frame.toDF(*unique_columns(frame.columns))
            frame = frame.withColumn("extracted_at", F.current_timestamp())
            load_mode = str(config.get("load_mode") or "FULL").upper()
            write_mode = str(config.get("write_mode") or (
                "append" if load_mode == "INCREMENTAL" else "replace")).lower()
            if load_mode == "INCREMENTAL":
                write_mode = "append"
            spark_mode = "overwrite" if write_mode == "replace" else "append"
            target_table = str(config.get("target_table") or "")
            connection = target_connection(config)
            log(f"IOL_STAGE::BRONZE::START source={source_index} table={target_table}")
            write_jdbc(frame, connection, target_table, spark_mode, config)
            register_views(frame, f"source_{source_index}", f"bronze_{source_index}", target_table)
            log(f"IOL_STAGE::BRONZE::SUCCESS source={source_index}")

            incremental = str(config.get("incremental_column") or "")
            if incremental and incremental in frame.columns:
                watermark = frame.agg(F.max(F.col(incremental)).alias("watermark")).first()["watermark"]
                if watermark is not None:
                    log(f"IOL_WATERMARK::{target_table}::{watermark}")

            silver = config.get("silver_config") or {}
            silver_frame = None
            if not stage_enabled(silver):
                log(f"IOL_STAGE::SILVER::SKIPPED source={source_index}")
            elif str(silver.get("execution_engine") or "SQL").upper() == "SPARK":
                log(f"IOL_STAGE::SILVER::SPARK source={source_index}")
                silver_frame = run_spark_stage(
                    spark, silver, "target_table_silver", connection,
                    f"silver_{source_index}", config)
            else:
                log(f"IOL_STAGE::SILVER::SQL source={source_index}")
                execute_database_sql(connection, silver.get("elt_scripts_silver"))
                silver_table = str(silver.get("target_table_silver") or "")
                silver_frame = read_target_table(spark, connection, silver_table)
                register_views(silver_frame, f"silver_{source_index}", silver_table)
            source_results.append({
                "bronze": frame,
                "silver": silver_frame,
                "connection": connection,
                "config": config,
            })

        gold = metadata.get("gold_config_global") or {}
        if not stage_enabled(gold):
            log("IOL_STAGE::GOLD::SKIPPED")
        elif not source_results:
            raise ValueError("Gold cannot run without a source")
        elif str(gold.get("execution_engine") or "SQL").upper() == "SPARK":
            log("IOL_STAGE::GOLD::SPARK")
            run_spark_stage(
                spark, gold, "target_table_gold", source_results[0]["connection"],
                "gold", source_results[0]["config"])
        else:
            log("IOL_STAGE::GOLD::SQL")
            execute_database_sql(
                source_results[0]["connection"],
                gold.get("elt_scripts_gold"))
        log("IOL_STAGE::DESTINATION::SUCCESS")
    finally:
        spark.stop()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True)
    args = parser.parse_args()
    with open(args.metadata, encoding="utf-8") as handle:
        metadata = json.load(handle)
    process(metadata)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        log(f"IOL_SPARK_ERROR::{type(error).__name__}: {error}")
        raise
