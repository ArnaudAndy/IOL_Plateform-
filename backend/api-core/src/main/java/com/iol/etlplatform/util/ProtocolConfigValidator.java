package com.iol.etlplatform.util;

import com.iol.etlplatform.exception.BadRequestException;
import java.util.Map;

/**
 * Validates source configuration format for each protocol.
 * Only structured sources are supported: relational databases (JDBC),
 * tabular files (CSV, EXCEL) and columnar files (PARQUET, AVRO, ORC).
 */
public class ProtocolConfigValidator {

    /**
     * Validates that config matches the expected format for the given protocol.
     * Throws BadRequestException with helpful message if config is invalid.
     */
    public static void validateConfig(String protocol, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw new BadRequestException("Config vide pour protocole: " + protocol);
        }

        String protocolUpper = protocol.toUpperCase();

        switch (protocolUpper) {
            // JDBC Databases
            case "POSTGRES", "MYSQL", "ORACLE", "MSSQL", "MARIADB", "SQLITE", "SNOWFLAKE", "REDSHIFT" ->
                validateJdbcConfig(protocolUpper, config);

            // Tabular files
            case "CSV" ->
                validateCsvConfig(config);
            case "EXCEL" ->
                validateExcelConfig(config);

            // Columnar files
            case "PARQUET", "AVRO", "ORC" ->
                validateStructuredFileConfig(protocolUpper, config);
            case "API" ->
                validateApiConfig(config);

            default ->
                throw new BadRequestException("Protocole non reconnu: " + protocol);
        }
    }

    // ==================== JDBC ====================

    private static void validateJdbcConfig(String protocol, Map<String, Object> config) {
        String missingFields = "";

        if (isBlank(config.get("host"))) {
            missingFields += "host, ";
        }
        if (isBlank(config.get("database"))) {
            missingFields += "database, ";
        }
        if (isBlank(config.get("username"))) {
            missingFields += "username, ";
        }

        if (!missingFields.isEmpty()) {
            throw new BadRequestException(
                protocol + ": champs requis manquants: " + missingFields.replaceAll(", $", "") +
                "\nExemple: { \"host\": \"localhost\", \"port\": \"5432\", \"database\": \"mydb\", \"username\": \"admin\", \"password\": \"secret\" }"
            );
        }
    }

    // ==================== FILES ====================

    private static void validateCsvConfig(Map<String, Object> config) {
        if (isBlank(config.get("file_path"))) {
            throw new BadRequestException(
                "CSV: file_path manquant\n" +
                "Exemple: { \"file_path\": \"/data/file.csv\", \"delimiter\": \";\", \"encoding\": \"UTF-8\" }"
            );
        }
    }

    private static void validateExcelConfig(Map<String, Object> config) {
        if (isBlank(config.get("file_path"))) {
            throw new BadRequestException(
                "EXCEL: file_path manquant\n" +
                "Exemple: { \"file_path\": \"/data/export.xlsx\", \"sheet_name\": 0 }"
            );
        }
    }

    private static void validateStructuredFileConfig(String format, Map<String, Object> config) {
        if (isBlank(config.get("file_path"))) {
            throw new BadRequestException(
                format + ": file_path manquant\n" +
                "Exemple: { \"file_path\": \"/data/file." + format.toLowerCase() + "\" }"
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateApiConfig(Map<String, Object> config) {
        Object nested = config.get("source_config");
        Map<String, Object> api = nested instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : config;
        String url = api.get("url") == null ? "" : api.get("url").toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BadRequestException("API: source_config.url HTTP/HTTPS est obligatoire.");
        }
    }

    // ==================== HELPERS ====================

    private static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String str) {
            return str.isBlank();
        }
        return false;
    }
}
