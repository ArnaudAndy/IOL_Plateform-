package com.iol.etlplatform.entity.enums;

public enum SourceType {
    // Relational databases (JDBC)
    POSTGRES,
    MYSQL,
    ORACLE,
    MSSQL,
    MARIADB,
    SQLITE,
    SNOWFLAKE,
    REDSHIFT,
    // Tabular files
    CSV,
    EXCEL,
    // Columnar files
    PARQUET,
    AVRO,
    ORC,
    // Pull from an HTTP JSON API
    API,
    // Inbound interoperability payload pushed by OpenHIM/IOL mediator
    PUSH
}
