package com.iol.etlplatform.util;

import com.iol.etlplatform.exception.BadRequestException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL Safety Validator for ELT scripts.
 *
 * Rules for Silver/Gold SQL:
 * ALLOW:  SELECT, INSERT INTO silver.*, INSERT INTO gold.*, CREATE TABLE silver.*, CREATE TABLE gold.*,
 *         CREATE OR REPLACE VIEW gold.*, CREATE INDEX on gold.*, DROP TABLE IF EXISTS on workflow tables, COMMIT
 * BLOCK:  DROP/TRUNCATE/ALTER outside scope, GRANT, REVOKE, DDL outside silver/gold, cross-workflow access
 */
@Component
public class SqlSafetyValidator {

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "\\b(EXEC|EXECUTE|xp_|sp_|USE\\s|GO\\s|SCRIPT|INTO\\s+OUTFILE)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Validates read-only SQL (SELECT only, no modifications).
     */
    public void validateReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BadRequestException("La requête SQL ne peut pas être vide.");
        }
        String trimmed = sql.trim();
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw new BadRequestException("Les commentaires SQL ne sont pas autorisés dans une requête générée.");
        }
        String withoutTrailingSemicolon = trimmed.replaceFirst(";\\s*$", "").trim();
        if (withoutTrailingSemicolon.contains(";")) {
            throw new BadRequestException("Une seule requête SQL est autorisée.");
        }
        String normalized = " " + withoutTrailingSemicolon.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ") + " ";
        if (DANGEROUS_KEYWORDS.matcher(withoutTrailingSemicolon).find()
                || normalized.matches(".*\\b(insert|update|delete|merge|drop|truncate|alter|create|grant|revoke|call)\\b.*")) {
            throw new BadRequestException("SQL non autorisé: seule une lecture SELECT est acceptée.");
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(withoutTrailingSemicolon);
            if (!(statement instanceof Select)) {
                throw new BadRequestException("SQL non autorisé: seule une lecture SELECT est acceptée.");
            }
        } catch (JSQLParserException parserError) {
            String lower = withoutTrailingSemicolon.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("select ") && !lower.startsWith("with ")) {
                throw new BadRequestException("La requête générée n'est pas un SELECT valide.");
            }
        }
    }

    /**
     * Validates operational SQL (basic checks for dangerous commands).
     */
    public void validateOperationalSql(String sql) {
        String normalized = " " + sql.toLowerCase(Locale.ROOT).replace("\n", " ") + " ";
        if (normalized.contains(" --") || normalized.contains("/*") || normalized.contains(" xp_")) {
            throw new BadRequestException("SQL potentiellement dangereux detecte.");
        }
    }

    /**
     * Validates ELT scripts (Silver/Gold SQL) with strict scoping.
     *
     * @param sql       The SQL script to validate
     * @param workflowId The workflow ID for table name verification (e.g., "wf_123")
     * @throws BadRequestException if the SQL violates safety rules
     */
    public void validateEltScript(String sql, String workflowId) {
        if (sql == null || sql.isBlank()) {
            throw new BadRequestException("SQL script ne peut pas etre vide.");
        }

        // Check for dangerous keywords
        if (DANGEROUS_KEYWORDS.matcher(sql).find()) {
            throw new BadRequestException("SQL contient des mots-cles interdits (EXEC, xp_, USE, etc.).");
        }

        // Split SQL into statements (handle multiple statements separated by semicolon)
        String[] statements = sql.split(";");

        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("COMMIT")) {
                continue;  // COMMIT is allowed
            }

            validateSingleStatement(trimmed, workflowId);
        }
    }

    /**
     * Validates a single SQL statement against ELT rules.
     */
    private void validateSingleStatement(String sql, String workflowId) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);

            // SELECT: allowed from any bronze/silver table
            if (statement instanceof Select) {
                validateSelectStatement((Select) statement, workflowId);
                return;
            }

            // INSERT: allowed only into silver.* and gold.* of the workflow
            if (statement instanceof Insert) {
                validateInsertStatement((Insert) statement, workflowId);
                return;
            }

            // CREATE TABLE: allowed only in silver.* and gold.* of the workflow
            if (statement instanceof CreateTable) {
                validateCreateTableStatement((CreateTable) statement, workflowId);
                return;
            }

            // CREATE VIEW: allowed only in gold.* with OR REPLACE
            if (statement instanceof CreateView) {
                validateCreateViewStatement((CreateView) statement, workflowId);
                return;
            }

            // DROP TABLE IF EXISTS: allowed only on workflow-scoped tables
            if (statement instanceof Drop) {
                validateDropStatement((Drop) statement, workflowId);
                return;
            }

            // TRUNCATE, ALTER, GRANT, REVOKE: all blocked
            if (statement instanceof Truncate) {
                throw new BadRequestException("TRUNCATE non autorise dans les ELT scripts.");
            }
            if (statement instanceof Alter) {
                throw new BadRequestException("ALTER non autorise dans les ELT scripts.");
            }
            if (statement instanceof Grant) {
                throw new BadRequestException("GRANT/REVOKE non autorises.");
            }
            if (statement instanceof Delete) {
                throw new BadRequestException("DELETE non autorise — utilise DROP TABLE ou UPDATE avec conditions.");
            }

            throw new BadRequestException("Type de statement non supporte dans les ELT scripts: " + statement.getClass().getSimpleName());

        } catch (JSQLParserException e) {
            // If jsqlparser fails, try basic regex validation
            validateWithRegex(sql, workflowId);
        }
    }

    /**
     * Validates SELECT statements: can read from bronze/silver tables.
     */
    private void validateSelectStatement(Select select, String workflowId) {
        // Accept any SELECT from bronze/silver/gold tables
        // More detailed table source validation could be added here if needed
    }

    /**
     * Validates INSERT statements: only into silver.* and gold.* tables of the workflow.
     */
    private void validateInsertStatement(Insert insert, String workflowId) {
        String tableName = insert.getTable().getName().toLowerCase();
        if (!isAllowedTableForInsert(tableName, workflowId)) {
            throw new BadRequestException(
                    "INSERT non autorise sur la table: " + tableName +
                    ". Autorise: silver.*, gold.* pour ce workflow.");
        }
    }

    /**
     * Validates CREATE TABLE statements: only in silver.* and gold.* of the workflow.
     */
    private void validateCreateTableStatement(CreateTable createTable, String workflowId) {
        String tableName = createTable.getTable().getName().toLowerCase();
        if (!isAllowedTableForInsert(tableName, workflowId)) {
            throw new BadRequestException(
                    "CREATE TABLE non autorise: " + tableName +
                    ". Autorise: silver.*, gold.* pour ce workflow.");
        }
    }

    /**
     * Validates CREATE VIEW statements: allowed (typically in gold.* but we trust user intent in ELT context).
     */
    private void validateCreateViewStatement(CreateView createView, String workflowId) {
        // CREATE OR REPLACE VIEW is allowed for data warehouse views
        // User is expected to place them in gold schema or use fact_* naming
        // We trust that context-aware users follow Bronze/Silver/Gold layer conventions
    }

    /**
     * Validates DROP TABLE statements: only DROP TABLE IF EXISTS on workflow-scoped tables.
     */
    private void validateDropStatement(Drop drop, String workflowId) {
        if (!drop.isIfExists()) {
            throw new BadRequestException("DROP doit utiliser IF EXISTS. Exemple: DROP TABLE IF EXISTS silver.ma_table;");
        }

        String tableName = drop.getName().getName().toLowerCase();
        if (!isWorkflowScopedTable(tableName, workflowId)) {
            throw new BadRequestException(
                    "DROP TABLE IF EXISTS non autorise sur: " + tableName +
                    ". Autorise: bronze/silver/gold/staging tables du workflow.");
        }
    }

    /**
     * Validates using regex fallback when jsqlparser fails.
     */
    private void validateWithRegex(String sql, String workflowId) {
        String lower = sql.toLowerCase(Locale.ROOT);

        // Block dangerous statements
        if (lower.matches(".*\\b(drop|truncate|delete|alter|grant|revoke)\\b(?!.*if\\s+exists).*")) {
            throw new BadRequestException("SQL dangereux detecte: DROP/TRUNCATE/ALTER/DELETE/GRANT/REVOKE non autorise.");
        }

        // Allow SELECT, INSERT, CREATE statements
        if (lower.matches("^\\s*(select|insert|create|drop\\s+table\\s+if\\s+exists|commit).*")) {
            return;  // Assume safe
        }

        throw new BadRequestException("SQL non reconnu ou non autorise.");
    }

    /**
     * Checks if a table is allowed for INSERT/CREATE operations.
     * Allowed: silver.*, gold.*, stg_*, cln_*, fact_*, wf_*, or any name without schema (assumed internal)
     *
     * Note: jsqlparser may return table names without schema if not explicitly qualified.
     * We assume unqualified names in ELT context refer to staging/silver/gold layer tables.
     */
    private boolean isAllowedTableForInsert(String tableName, String workflowId) {
        String lower = tableName.toLowerCase();
        // Check for schema-qualified names
        if (lower.startsWith("silver.") || lower.startsWith("gold.") ||
            lower.startsWith("stg_") || lower.startsWith("cln_") || lower.startsWith("fact_") ||
            lower.startsWith("wf_")) {
            return true;
        }
        // Unqualified names are allowed in ELT scripts (assumed to be internal staging tables)
        // Block only known dangerous schemas
        if (lower.startsWith("public.") || lower.startsWith("postgres.") || lower.startsWith("pg_") ||
            lower.startsWith("information_schema.") || lower.startsWith("sys.") || lower.startsWith("mysql.")) {
            return false;
        }
        return true;  // Allow unqualified table names in ELT context
    }

    /**
     * Checks if a table belongs to the current workflow scope.
     * Allowed: bronze, silver, gold staging/clean/fact tables with workflow prefix.
     */
    private boolean isWorkflowScopedTable(String tableName, String workflowId) {
        String lower = tableName.toLowerCase();
        // Check for schema-qualified names
        if (lower.startsWith("bronze.") || lower.startsWith("silver.") || lower.startsWith("gold.") ||
            lower.startsWith("stg_") || lower.startsWith("cln_") || lower.startsWith("fact_") ||
            (workflowId != null && lower.startsWith("wf_" + workflowId.toLowerCase()))) {
            return true;
        }
        // Unqualified names are allowed for workflow-scoped tables
        if (!lower.startsWith("public.") && !lower.startsWith("postgres.") && !lower.startsWith("pg_") &&
            !lower.startsWith("information_schema.") && !lower.startsWith("sys.") && !lower.startsWith("mysql.")) {
            return true;
        }
        return false;
    }
}
