package com.iol.etlplatform.service;

import com.iol.etlplatform.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SourceLoadEstimatorService {

    private static final List<String> JDBC_PROTOCOLS = List.of(
            "POSTGRES", "MYSQL", "MARIADB", "MSSQL", "ORACLE", "SQLITE", "SNOWFLAKE", "REDSHIFT");
    private static final List<String> SPARK_FILE_PROTOCOLS = List.of("CSV", "JSON", "PARQUET", "ORC");

    private final UploadedFileService uploadedFileService;
    private final SourceConnectionLimiter sourceConnectionLimiter;

    @Value("${app.spark.auto-selection.enabled:true}")
    private boolean enabled;

    @Value("${app.spark.auto-selection.jdbc-query-timeout-seconds:15}")
    private int jdbcQueryTimeoutSeconds;

    @Value("${app.spark.auto-selection.jdbc-unknown-mode:SPARK}")
    private String jdbcUnknownMode;

    @Value("${app.spark.auto-selection.file-size-threshold-bytes:268435456}")
    private long fileSizeThresholdBytes;

    @Value("${app.spark.auto-selection.jdbc-rows-per-partition:250000}")
    private long jdbcRowsPerPartition;

    @Value("${app.spark.auto-selection.jdbc-max-partitions:16}")
    private int jdbcMaxPartitions;

    public LoadAssessment assess(List<Map<String, Object>> sources, long rowThreshold) {
        if (!enabled || sources == null || sources.isEmpty()) {
            return LoadAssessment.notAssessed();
        }

        long rows = 0L;
        long bytes = 0L;
        boolean hasRows = false;
        boolean hasBytes = false;
        boolean complete = true;

        for (int index = 0; index < sources.size(); index++) {
            Map<String, Object> source = sources.get(index);
            String protocol = string(source != null ? source.get("source_name") : null).toUpperCase(Locale.ROOT);
            Map<String, Object> config = map(source != null ? source.get("config") : null);

            if (JDBC_PROTOCOLS.contains(protocol)) {
                try {
                    long sourceRows = estimateJdbcRows(protocol, config, rowThreshold);
                    rows = saturatedAdd(rows, sourceRows);
                    hasRows = true;
                    if (rowThreshold > 0 && rows > rowThreshold) {
                        return new LoadAssessment(rows, hasBytes ? bytes : -1L, true,
                                "JDBC_ROW_THRESHOLD", true);
                    }
                } catch (Exception error) {
                    complete = false;
                    log.warn("Estimation JDBC impossible pour la source {} ({}): {}",
                            index, protocol, rootMessage(error));
                    if ("SPARK".equalsIgnoreCase(jdbcUnknownMode)) {
                        return new LoadAssessment(hasRows ? rows : -1L, hasBytes ? bytes : -1L, true,
                                "JDBC_ESTIMATION_UNCERTAIN", false);
                    }
                }
                continue;
            }

            if ("API".equals(protocol)) {
                long maximumRows = apiMaximumRows(config);
                if (maximumRows >= 0) {
                    rows = saturatedAdd(rows, maximumRows);
                    hasRows = true;
                    complete = false;
                    if (rowThreshold > 0 && rows > rowThreshold) {
                        return new LoadAssessment(rows, hasBytes ? bytes : -1L, true,
                                "API_CONFIGURED_MAXIMUM", false);
                    }
                }
                continue;
            }

            if (SPARK_FILE_PROTOCOLS.contains(protocol)) {
                try {
                    long sourceBytes = Files.size(resolveFile(config));
                    bytes = saturatedAdd(bytes, sourceBytes);
                    hasBytes = true;
                    if (fileSizeThresholdBytes > 0 && bytes > fileSizeThresholdBytes) {
                        return new LoadAssessment(hasRows ? rows : -1L, bytes, true,
                                "FILE_SIZE_THRESHOLD", complete);
                    }
                } catch (Exception error) {
                    complete = false;
                    log.debug("Taille de la source fichier {} indisponible: {}", index, rootMessage(error));
                }
            }
        }

        return new LoadAssessment(hasRows ? rows : -1L, hasBytes ? bytes : -1L, false,
                hasRows || hasBytes ? "BELOW_THRESHOLDS" : "NO_MEASURABLE_SOURCE", complete);
    }

    long estimateJdbcRows(String protocol, Map<String, Object> config, long rowThreshold) throws Exception {
        // L'estimation s'execute sur un thread HTTP, non borne: sans permis, N
        // requetes concurrentes ouvrent N connexions vers la base du client.
        return sourceConnectionLimiter.withPermit("estimation JDBC",
                () -> estimateJdbcRowsWithConnection(protocol, config, rowThreshold));
    }

    private long estimateJdbcRowsWithConnection(
            String protocol, Map<String, Object> config, long rowThreshold) throws Exception {
        Map<String, Object> sourceConfig = map(config.get("source_config"));
        String query = string(sourceConfig.get("query"));
        if (query.isBlank()) {
            throw new BadRequestException("Requete SQL source manquante pour " + protocol);
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if ((!lower.startsWith("select ") && !lower.startsWith("with "))
                || query.replaceFirst(";\\s*$", "").contains(";")) {
            throw new BadRequestException("La requete source doit contenir une seule lecture SELECT/WITH.");
        }
        query = applyIncrementalFilter(query.replaceFirst(";\\s*$", ""), config);

        JdbcConnectionInfo jdbc = jdbcInfo(protocol, config);
        registerDriver(protocol);
        try (Connection connection = DriverManager.getConnection(jdbc.url(), jdbc.username(), jdbc.password())) {
            try {
                connection.setReadOnly(true);
            } catch (Exception ignored) {
                // Certains pilotes ne permettent pas de changer ce drapeau après connexion.
            }
            PartitionCandidate candidate = findPartitionCandidate(connection, query);
            String aggregateQuery = candidate == null
                    ? "SELECT COUNT(*) FROM (" + query + ") _iol_load_estimate"
                    : "SELECT COUNT(*), MIN(" + candidate.quotedColumn() + "), MAX("
                            + candidate.quotedColumn() + ") FROM (" + query + ") _iol_load_estimate";
            try (Statement statement = timedStatement(connection);
                 ResultSet resultSet = statement.executeQuery(aggregateQuery)) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Le comptage JDBC n'a retourne aucune ligne.");
                }
                long rows = Math.max(0L, resultSet.getLong(1));
                if (candidate != null && rowThreshold > 0 && rows > rowThreshold) {
                    configureAutomaticPartitioning(config, candidate, resultSet.getObject(2), resultSet.getObject(3), rows);
                }
                return rows;
            }
        }
    }

    private Statement timedStatement(Connection connection) throws Exception {
        Statement statement = connection.createStatement();
        statement.setQueryTimeout(Math.max(1, jdbcQueryTimeoutSeconds));
        return statement;
    }

    private PartitionCandidate findPartitionCandidate(Connection connection, String query) {
        String metadataQuery = "SELECT * FROM (" + query + ") _iol_partition_probe WHERE 1=0";
        try (Statement statement = timedStatement(connection);
             ResultSet resultSet = statement.executeQuery(metadataQuery)) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            String quote = connection.getMetaData().getIdentifierQuoteString();
            quote = quote == null ? "" : quote.trim();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                int sqlType = metadata.getColumnType(index);
                String partitionType = partitionType(sqlType);
                if (partitionType == null) continue;
                String label = metadata.getColumnLabel(index);
                String quoted = quote.isBlank() ? label : quote + label.replace(quote, quote + quote) + quote;
                return new PartitionCandidate(label, quoted, partitionType);
            }
        } catch (Exception error) {
            log.debug("Aucune colonne de partition JDBC detectee automatiquement: {}", rootMessage(error));
        }
        return null;
    }

    private void configureAutomaticPartitioning(
            Map<String, Object> config,
            PartitionCandidate candidate,
            Object lowerBound,
            Object upperBound,
            long rows) {
        if (Boolean.TRUE.equals(configuredBoolean(config.get("jdbc_partitioning_enabled")))
                || lowerBound == null || upperBound == null || lowerBound.equals(upperBound)) {
            return;
        }
        long rowsPerPartition = Math.max(10_000L, jdbcRowsPerPartition);
        int maxPartitions = Math.max(2, Math.min(jdbcMaxPartitions, 32));
        int partitions = (int) Math.min(maxPartitions, Math.max(2L,
                (long) Math.ceil((double) rows / rowsPerPartition)));
        config.put("jdbc_partitioning_enabled", true);
        config.put("partition_column", candidate.column());
        config.put("partition_type", candidate.partitionType());
        config.put("partition_lower_bound", String.valueOf(lowerBound));
        config.put("partition_upper_bound", String.valueOf(upperBound));
        config.put("partition_count", partitions);
        config.put("partition_parallelism", Math.min(partitions, 8));
        config.put("partitioning_origin", "AUTO");
        log.info("Partitionnement JDBC automatique: column={} type={} partitions={} rows={}",
                candidate.column(), candidate.partitionType(), partitions, rows);
    }

    private Boolean configuredBoolean(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        String normalized = string(value).toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        return null;
    }

    private String partitionType(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE -> "NUMERIC";
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "DATE";
            default -> null;
        };
    }

    private long apiMaximumRows(Map<String, Object> config) {
        Map<String, Object> sourceConfig = map(config.get("source_config"));
        if (sourceConfig.isEmpty()) sourceConfig = config;
        Map<String, Object> pagination = map(sourceConfig.get("pagination"));
        String type = string(pagination.get("type")).toUpperCase(Locale.ROOT);
        if (type.isBlank() || "NONE".equals(type)) return -1L;
        long pageSize = positiveLong(pagination.get("page_size"), 100L);
        long maxPages = positiveLong(pagination.get("max_pages"), 100L);
        return saturatedMultiply(pageSize, maxPages);
    }

    private Path resolveFile(Map<String, Object> config) {
        String uploadId = string(config.get("upload_id"));
        if (!uploadId.isBlank()) return uploadedFileService.resolve(uploadId);
        String rawPath = firstNonBlank(config, "file_path", "uri");
        if (rawPath.startsWith("file:")) return Path.of(URI.create(rawPath)).toAbsolutePath().normalize();
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private JdbcConnectionInfo jdbcInfo(String protocol, Map<String, Object> config) {
        String rawUri = string(config.get("uri"));
        String username = string(config.get("username"));
        String password = string(config.get("password"));
        if (!rawUri.isBlank() && !rawUri.startsWith("jdbc:")) {
            URI uri = URI.create(rawUri);
            String[] credentials = uri.getUserInfo() != null ? uri.getUserInfo().split(":", 2) : new String[0];
            if (username.isBlank() && credentials.length > 0) username = credentials[0];
            if (password.isBlank() && credentials.length > 1) password = credentials[1];
            String database = uri.getPath() != null ? uri.getPath().replaceFirst("^/", "") : "";
            rawUri = buildJdbcUrl(protocol, uri.getHost(), uri.getPort(), database);
        } else if (rawUri.isBlank()) {
            rawUri = buildJdbcUrl(protocol, string(config.get("host")), parsePort(config.get("port")),
                    string(config.get("database")));
        }
        return new JdbcConnectionInfo(rawUri, username, password);
    }

    private String buildJdbcUrl(String protocol, String host, int configuredPort, String database) {
        int port = configuredPort > 0 ? configuredPort : defaultPort(protocol);
        return switch (protocol) {
            case "POSTGRES" -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case "MYSQL" -> "jdbc:mysql://" + host + ":" + port + "/" + database;
            case "MARIADB" -> "jdbc:mariadb://" + host + ":" + port + "/" + database;
            case "MSSQL" -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database + ";encrypt=false";
            case "ORACLE" -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
            case "SQLITE" -> "jdbc:sqlite:" + database;
            case "SNOWFLAKE" -> "jdbc:snowflake://" + host + "/?db=" + database;
            case "REDSHIFT" -> "jdbc:redshift://" + host + ":" + port + "/" + database;
            default -> throw new BadRequestException("Protocole JDBC non supporte: " + protocol);
        };
    }

    private void registerDriver(String protocol) throws ClassNotFoundException {
        String className = switch (protocol) {
            case "POSTGRES" -> "org.postgresql.Driver";
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "MARIADB" -> "org.mariadb.jdbc.Driver";
            case "MSSQL" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "SQLITE" -> "org.sqlite.JDBC";
            case "SNOWFLAKE" -> "net.snowflake.client.jdbc.SnowflakeDriver";
            case "REDSHIFT" -> "com.amazon.redshift.jdbc42.Driver";
            default -> "";
        };
        if (!className.isBlank()) Class.forName(className);
    }

    private String applyIncrementalFilter(String query, Map<String, Object> config) {
        String column = string(config.get("incremental_column"));
        String watermark = string(config.get("last_watermark"));
        if (column.isBlank() || watermark.isBlank()) return query;
        if (!column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new BadRequestException("Colonne incrementale invalide: " + column);
        }
        if (!watermark.matches("[0-9TtZz+:. \\-]+")) {
            throw new BadRequestException("Watermark invalide.");
        }
        return "SELECT * FROM (" + query + ") _iol_incremental WHERE " + column + " > '" + watermark + "'";
    }

    private int defaultPort(String protocol) {
        return switch (protocol) {
            case "POSTGRES" -> 5432;
            case "MYSQL", "MARIADB" -> 3306;
            case "MSSQL" -> 1433;
            case "ORACLE" -> 1521;
            case "REDSHIFT" -> 5439;
            default -> -1;
        };
    }

    private long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private long saturatedMultiply(long left, long right) {
        if (left > 0 && right > Long.MAX_VALUE / left) return Long.MAX_VALUE;
        return left * right;
    }

    private long positiveLong(Object value, long fallback) {
        try {
            long parsed = Long.parseLong(string(value));
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int parsePort(Object value) {
        try {
            return Integer.parseInt(string(value));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String firstNonBlank(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = string(values.get(key));
            if (!value.isBlank()) return value;
        }
        throw new BadRequestException("Chemin du fichier source manquant.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : new LinkedHashMap<>();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private record JdbcConnectionInfo(String url, String username, String password) { }
    private record PartitionCandidate(String column, String quotedColumn, String partitionType) { }

    public record LoadAssessment(
            long estimatedRows,
            long estimatedBytes,
            boolean distributedRecommended,
            String reason,
            boolean complete) {

        public static LoadAssessment notAssessed() {
            return new LoadAssessment(-1L, -1L, false, "NOT_ASSESSED", false);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            if (estimatedRows >= 0) result.put("estimatedRows", estimatedRows);
            if (estimatedBytes >= 0) result.put("estimatedBytes", estimatedBytes);
            result.put("distributedRecommended", distributedRecommended);
            result.put("reason", reason);
            result.put("complete", complete);
            return result;
        }
    }
}
