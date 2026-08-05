package com.iol.etlplatform.util;

import com.iol.etlplatform.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSafetyValidatorTest {

    private final SqlSafetyValidator validator = new SqlSafetyValidator();
    private static final String WORKFLOW_ID = "wf_test_001";

    // ============ LEGACY METHODS ============

    @Test
    void shouldRejectDestructiveReadOnlySql() {
        assertThrows(BadRequestException.class, () -> validator.validateReadOnlySql("SELECT * FROM t; DELETE FROM t;"));
    }

    @Test
    void shouldAcceptSafeReadOnlySql() {
        assertDoesNotThrow(() -> validator.validateReadOnlySql("SELECT client, SUM(montant) FROM ventes GROUP BY client"));
    }

    @Test
    void shouldRejectOperationalSqlWithCommentOrXp() {
        assertThrows(BadRequestException.class, () -> validator.validateOperationalSql("SELECT 1 -- comment"));
        assertThrows(BadRequestException.class, () -> validator.validateOperationalSql("SELECT xp_cmdshell('x')"));
    }

    // ============ NEW ELT SCRIPT VALIDATION ============

    @Test
    void testAllowSelectFromBronze() {
        String sql = "SELECT * FROM bronze.source_table WHERE id > 100;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testAllowInsertIntoSilver() {
        String sql = "INSERT INTO silver.cleaned_table SELECT * FROM bronze.source_table;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testAllowDropTableIfExistsSilver() {
        String sql = "DROP TABLE IF EXISTS silver.cleaned_data; COMMIT;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testAllowCreateTableInSilver() {
        String sql = "CREATE TABLE silver.cleaned_data AS SELECT * FROM bronze.raw_data;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testAllowCreateTableInGold() {
        String sql = "CREATE TABLE gold.fact_table AS SELECT * FROM silver.cleaned_data GROUP BY category;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testAllowCreateViewInGold() {
        String sql = "CREATE OR REPLACE VIEW gold.summary_view AS SELECT category, COUNT(*) FROM silver.data GROUP BY category;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockDropWithoutIfExists() {
        String sql = "DROP TABLE silver.cleaned_data;";
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockTruncate() {
        String sql = "TRUNCATE TABLE silver.cleaned_data;";
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockAlter() {
        String sql = "ALTER TABLE silver.cleaned_data ADD COLUMN new_col VARCHAR(100);";
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockDelete() {
        String sql = "DELETE FROM silver.cleaned_data WHERE id = 1;";
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockGrant() {
        String sql = "GRANT SELECT ON gold.fact_table TO user1;";
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockDangerousKeywordsInSql() {
        // xp_ keywords (SQL Server extended procedures) are blocked
        String sql = "SELECT * FROM bronze.data EXEC xp_cmdshell('rm -rf /');";
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testAllowMultipleStatementsWithCommit() {
        String sql = "DROP TABLE IF EXISTS silver.temp; " +
                     "CREATE TABLE silver.temp AS SELECT * FROM bronze.raw; " +
                     "INSERT INTO gold.fact SELECT * FROM silver.temp; " +
                     "COMMIT;";
        assertDoesNotThrow(() -> validator.validateEltScript(sql, WORKFLOW_ID));
    }

    @Test
    void testBlockEmptyScript() {
        assertThrows(BadRequestException.class, () -> validator.validateEltScript("", WORKFLOW_ID));
    }

    @Test
    void testBlockNullScript() {
        assertThrows(BadRequestException.class, () -> validator.validateEltScript(null, WORKFLOW_ID));
    }
}
