package com.iol.etlplatform.service;

import com.iol.etlplatform.util.ColumnNameSanitizer;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Service Discovery - Extraction dynamique du schéma de base de données
 * 
 * Utilise JDBC DatabaseMetaData pour:
 * - Découvrir les tables disponibles
 * - Extraire les colonnes et leurs propriétés
 * - Déterminer les types de données
 * - Identifier les clés primaires
 */
@Service
public class DiscoveryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DiscoveryService.class);

    /*
     * Découvrir le schéma complet d'une base de données
     */
    public DatabaseSchema discoverDatabase(Connection connection, String schemaPattern) throws SQLException {
        return discoverDatabase(connection, schemaPattern, null);
    }

    public DatabaseSchema discoverDatabase(Connection connection, String schemaPattern, String tablePattern) throws SQLException {
        log.info("Découverte du schéma: {}, table: {}", schemaPattern, tablePattern);

        DatabaseMetaData metadata = connection.getMetaData();

        DatabaseSchema schema = new DatabaseSchema();
        schema.setDatabaseProductName(metadata.getDatabaseProductName());
        schema.setDatabaseVersion(metadata.getDatabaseProductVersion());
        schema.setDriverName(metadata.getDriverName());

        try (ResultSet tableRs = metadata.getTables(null, normalizeSchemaPattern(schemaPattern), normalizeTablePattern(tablePattern), new String[]{"TABLE", "VIEW"})) {
            List<TableSchema> tables = new ArrayList<>();

            while (tableRs.next()) {
                String tableSchema = tableRs.getString("TABLE_SCHEM");
                String tableName = tableRs.getString("TABLE_NAME");
                String tableType = tableRs.getString("TABLE_TYPE");
                String remarks = tableRs.getString("REMARKS");

                List<ColumnSchema> columns = discoverColumns(connection, tableSchema != null ? tableSchema : schemaPattern, tableName);

                Set<String> primaryKeyColumns = discoverPrimaryKey(connection, tableSchema != null ? tableSchema : schemaPattern, tableName);

                TableSchema table = new TableSchema();
                table.setTableName(tableName);
                table.setTableType(tableType);
                table.setDescription(remarks);
                table.setColumnCount(columns.size());
                table.setColumns(columns);
                table.setPrimaryKeyColumns(primaryKeyColumns);

                tables.add(table);
            }

            schema.setTables(tables);
            schema.setTableCount(tables.size());
        }

        return schema;
    }

    /*
     * Découvrir toutes les colonnes d'une table avec leurs propriétés
     */
    public List<ColumnSchema> discoverColumns(Connection connection, String schemaPattern, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<ColumnSchema> columns = new ArrayList<>();

        try (ResultSet columnRs = metadata.getColumns(null, normalizeSchemaPattern(schemaPattern), tableName, "%")) {
            while (columnRs.next()) {
                String columnName = columnRs.getString("COLUMN_NAME");
                int dataType = columnRs.getInt("DATA_TYPE");
                String typeName = columnRs.getString("TYPE_NAME");
                int columnSize = columnRs.getInt("COLUMN_SIZE");
                int decimalDigits = columnRs.getInt("DECIMAL_DIGITS");
                int nullable = columnRs.getInt("NULLABLE");
                String remarks = columnRs.getString("REMARKS");
                String defaultValue = columnRs.getString("COLUMN_DEF");

                ColumnSchema column = new ColumnSchema();
                column.setColumnName(columnName);
                column.setCleanedName(ColumnNameSanitizer.sanitize(columnName));
                column.setSqlType(typeName);
                column.setJavaType(mapSqlTypeToJava(dataType, typeName));
                column.setColumnSize(columnSize > 0 ? columnSize : null);
                column.setDecimalDigits(decimalDigits > 0 ? decimalDigits : null);
                column.setNullable(nullable == DatabaseMetaData.columnNullable);
                column.setDescription(remarks);
                column.setDefaultValue(defaultValue);
                column.setSqlTypeCode(dataType);

                columns.add(column);
            }
        }

        return columns;
    }

    /*
     * Découvrir les clés primaires d'une table
     */
    public Set<String> discoverPrimaryKey(Connection connection, String schemaPattern, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> primaryKeyColumns = new LinkedHashSet<>();

        try (ResultSet pkRs = metadata.getPrimaryKeys(null, normalizeSchemaPattern(schemaPattern), tableName)) {
            while (pkRs.next()) {
                String columnName = pkRs.getString("COLUMN_NAME");
                primaryKeyColumns.add(columnName);
            }
        }

        return primaryKeyColumns;
    }

    /*
     * Découvrir les clés étrangères d'une table
     */
    public List<ForeignKeySchema> discoverForeignKeys(Connection connection, String schemaPattern, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<ForeignKeySchema> foreignKeys = new ArrayList<>();

        try (ResultSet fkRs = metadata.getImportedKeys(null, normalizeSchemaPattern(schemaPattern), tableName)) {
            while (fkRs.next()) {
                ForeignKeySchema fk = new ForeignKeySchema();
                fk.setColumnName(fkRs.getString("FKCOLUMN_NAME"));
                fk.setPkTableName(fkRs.getString("PKTABLE_NAME"));
                fk.setPkColumnName(fkRs.getString("PKCOLUMN_NAME"));
                fk.setConstraintName(fkRs.getString("FK_NAME"));

                foreignKeys.add(fk);
            }
        }

        return foreignKeys;
    }

    /*
     * Découvrir les index d'une table
     */
    public List<IndexSchema> discoverIndexes(Connection connection, String schemaPattern, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<IndexSchema> indexes = new ArrayList<>();
        Map<String, IndexSchema> indexMap = new LinkedHashMap<>();

        try (ResultSet indexRs = metadata.getIndexInfo(null, normalizeSchemaPattern(schemaPattern), tableName, false, true)) {
            while (indexRs.next()) {
                String indexName = indexRs.getString("INDEX_NAME");
                if (indexName != null && !indexRs.getBoolean("NON_UNIQUE")) {
                    String columnName = indexRs.getString("COLUMN_NAME");

                    IndexSchema index = indexMap.computeIfAbsent(indexName, k -> {
                        IndexSchema value = new IndexSchema();
                        value.setIndexName(k);
                        value.setColumnNames(new ArrayList<>());
                        value.setUnique(true);
                        return value;
                    });

                    index.getColumnNames().add(columnName);
                }
            }
        }

        indexes.addAll(indexMap.values());
        return indexes;
    }

    private String normalizeSchemaPattern(String schemaPattern) {
        return schemaPattern == null || schemaPattern.isBlank() ? null : schemaPattern;
    }

    private String normalizeTablePattern(String tablePattern) {
        return tablePattern == null || tablePattern.isBlank() ? "%" : tablePattern;
    }

    /*
     * Mapper le type SQL JDBC au type Java
     */
    private String mapSqlTypeToJava(int sqlType, String sqlTypeName) {
        return switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR -> "String";
            case Types.NUMERIC, Types.DECIMAL -> "BigDecimal";
            case Types.BIT, Types.BOOLEAN -> "Boolean";
            case Types.TINYINT, Types.BIGINT, Types.SMALLINT, Types.INTEGER -> "Long";
            case Types.FLOAT, Types.DOUBLE -> "Double";
            case Types.DATE -> "LocalDate";
            case Types.TIME -> "LocalTime";
            case Types.TIMESTAMP -> "LocalDateTime";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "byte[]";
            default -> sqlTypeName != null ? sqlTypeName : "Object";
        };
    }

    /*
     * Générer une requête SELECT de sample data
     */
    public List<Map<String, Object>> sampleData(Connection connection, String schemaPattern, String tableName, int limit) throws SQLException {
        String qualifiedTable = schemaPattern == null || schemaPattern.isBlank() ? tableName : schemaPattern + "." + tableName;
        String query = String.format("SELECT * FROM %s LIMIT %d", qualifiedTable, limit);
        List<Map<String, Object>> results = new ArrayList<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            ResultSetMetaData rsMetadata = rs.getMetaData();
            int columnCount = rsMetadata.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String colName = rsMetadata.getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(colName, value);
                }
                results.add(row);
            }
        }

        return results;
    }

    // ==================== DTOs ====================

    public static class DatabaseSchema {
        private String databaseProductName;
        private String databaseVersion;
        private String driverName;
        private Integer tableCount;
        private List<TableSchema> tables;

        public String getDatabaseProductName() { return this.databaseProductName; }
        public void setDatabaseProductName(String databaseProductName) { this.databaseProductName = databaseProductName; }
        public String getDatabaseVersion() { return this.databaseVersion; }
        public void setDatabaseVersion(String databaseVersion) { this.databaseVersion = databaseVersion; }
        public String getDriverName() { return this.driverName; }
        public void setDriverName(String driverName) { this.driverName = driverName; }
        public List<TableSchema> getTables() { return this.tables; }
        public void setTables(List<TableSchema> tables) { this.tables = tables; }
        public Integer getTableCount() { return this.tableCount; }
        public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }
    }

    public static class TableSchema {
        private String tableName;
        private String tableType;
        private String description;
        private Integer columnCount;
        private List<ColumnSchema> columns;
        private Set<String> primaryKeyColumns;
        private List<ForeignKeySchema> foreignKeys;
        private List<IndexSchema> indexes;

        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getTableName() { return this.tableName; }
        public void setTableType(String tableType) { this.tableType = tableType; }
        public String getTableType() { return this.tableType; }
        public String getDescription() { return this.description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getColumnCount() { return this.columnCount; }
        public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }
        public List<ColumnSchema> getColumns() { return this.columns; }
        public void setColumns(List<ColumnSchema> columns) { this.columns = columns; }
        public Set<String> getPrimaryKeyColumns() { return this.primaryKeyColumns; }
        public void setPrimaryKeyColumns(Set<String> primaryKeyColumns) { this.primaryKeyColumns = primaryKeyColumns; }
        public List<ForeignKeySchema> getForeignKeys() { return this.foreignKeys; }
        public void setForeignKeys(List<ForeignKeySchema> foreignKeys) { this.foreignKeys = foreignKeys; }
        public List<IndexSchema> getIndexes() { return this.indexes; }
        public void setIndexes(List<IndexSchema> indexes) { this.indexes = indexes; }
    }

    public static class ColumnSchema {
        private String columnName;
        private String cleanedName;
        private String sqlType;
        private String javaType;
        private Integer columnSize;
        private Integer decimalDigits;
        private Boolean nullable;
        private String description;
        private String defaultValue;
        private Integer sqlTypeCode;

        public String getColumnName() { return this.columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getCleanedName() { return this.cleanedName; }
        public void setCleanedName(String cleanedName) { this.cleanedName = cleanedName; }
        public String getSqlType() { return this.sqlType; }
        public void setSqlType(String sqlType) { this.sqlType = sqlType; }
        public String getJavaType() { return this.javaType; }
        public void setJavaType(String javaType) { this.javaType = javaType; }
        public Integer getColumnSize() { return this.columnSize; }
        public void setColumnSize(Integer columnSize) { this.columnSize = columnSize; }
        public Integer getDecimalDigits() { return this.decimalDigits; }
        public void setDecimalDigits(Integer decimalDigits) { this.decimalDigits = decimalDigits; }
        public Boolean getNullable() { return this.nullable; }
        public void setNullable(Boolean nullable) { this.nullable = nullable; }
        public String getDescription() { return this.description; }
        public void setDescription(String description) { this.description = description; }
        public String getDefaultValue() { return this.defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
        public Integer getSqlTypeCode() { return this.sqlTypeCode; }
        public void setSqlTypeCode(Integer sqlTypeCode) { this.sqlTypeCode = sqlTypeCode; }
    }

    public static class ForeignKeySchema {
        private String columnName;
        private String pkTableName;
        private String pkColumnName;
        private String constraintName;

        public String getColumnName() { return this.columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }
        public String getPkTableName() { return this.pkTableName; }
        public void setPkTableName(String pkTableName) { this.pkTableName = pkTableName; }
        public String getPkColumnName() { return this.pkColumnName; }
        public void setPkColumnName(String pkColumnName) { this.pkColumnName = pkColumnName; }
        public String getConstraintName() { return this.constraintName; }
        public void setConstraintName(String constraintName) { this.constraintName = constraintName; }
    }

    public static class IndexSchema {
        private String indexName;
        private List<String> columnNames;
        private Boolean unique;

        public List<String> getColumnNames() { return this.columnNames; }
        public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }
        public String getIndexName() { return this.indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }
        public Boolean getUnique() { return this.unique; }
        public void setUnique(Boolean unique) { this.unique = unique; }
    }
}
