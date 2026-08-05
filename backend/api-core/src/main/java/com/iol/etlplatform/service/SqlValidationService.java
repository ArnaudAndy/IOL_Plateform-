package com.iol.etlplatform.service;

import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.iol.etlplatform.dto.workflow.ValidateSqlRequest;
import com.iol.etlplatform.dto.workflow.ValidateSqlResponse;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

@Service
public class SqlValidationService {

    public ValidateSqlResponse validate(ValidateSqlRequest request) {
        String sql = request.getSqlScript();
        String step = normalizeStep(request.getWorkflowStep());
        String expected = request.getExpectedSourceTable();

        ValidateSqlResponse resp = new ValidateSqlResponse();
        resp.setValid(false);

        if (sql == null || sql.trim().isEmpty()) {
            resp.setMessage("Le script SQL est vide.");
            return resp;
        }

        String trimmedSql = sql.trim();
        if (hasMultipleStatements(trimmedSql)) {
            resp.setMessage("Un seul SELECT est autorisé par étape de pipeline.");
            return resp;
        }

        String lower = " " + trimmedSql.toLowerCase(Locale.ROOT) + " ";
        if (containsForbiddenKeyword(lower)) {
            resp.setMessage("Le SQL de pipeline ne doit pas contenir d'instruction destructive ou d'écriture.");
            return resp;
        }

        try {
            // Parse using JSqlParser
            Statement stmt = CCJSqlParserUtil.parse(trimmedSql);
            if (!(stmt instanceof Select)) {
                resp.setMessage("Seules les requêtes SELECT sont autorisées.");
                return resp;
            }

            Select select = (Select) stmt;
            if (!(select.getSelectBody() instanceof PlainSelect)) {
                resp.setMessage("Ce type de SELECT n'est pas encore supporté par le validateur.");
                return resp;
            }

            PlainSelect body = (PlainSelect) select.getSelectBody();
            String from = Objects.toString(body.getFromItem(), "");
            String normalizedFrom = from.toLowerCase(Locale.ROOT);

            if ("STANDARDISATION".equals(step)) {
                if (from.isBlank()) {
                    resp.setMessage("Le SQL de standardisation doit lire une table source dans la clause FROM.");
                    return resp;
                }
            }

            if ("AGGREGATION".equals(step)) {
                String normalizedSelect = body.getSelectItems().toString().toLowerCase(Locale.ROOT);
                boolean hasAggregate = normalizedSelect.contains("count(")
                        || normalizedSelect.contains("sum(")
                        || normalizedSelect.contains("avg(")
                        || normalizedSelect.contains("min(")
                        || normalizedSelect.contains("max(");

                if (!hasAggregate && body.getGroupBy() == null) {
                    resp.setMessage("Le SQL d'agrégation doit contenir au moins une fonction d'agrégation ou une clause GROUP BY.");
                    return resp;
                }
            }

            // If expected source table is provided, check it appears
            if (expected != null && !expected.trim().isEmpty()) {
                if (!normalizedFrom.contains(expected.toLowerCase(Locale.ROOT))) {
                    resp.setMessage("La table source découverte n'est pas référencée dans la clause FROM.");
                    return resp;
                }
            }

            resp.setValid(true);
            resp.setMessage("Structure SQL valide pour l'étape " + step + ".");
            return resp;
        } catch (JSQLParserException e) {
            resp.setMessage("Impossible d'analyser le SQL: " + e.getMessage());
            return resp;
        }
    }

    private String normalizeStep(String workflowStep) {
        if (workflowStep == null || workflowStep.isBlank()) {
            return "STANDARDISATION";
        }

        String value = workflowStep.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "SILVER" -> "STANDARDISATION";
            case "GOLD" -> "AGGREGATION";
            case "BRUTE" -> "DONNEES_BRUTES";
            default -> value;
        };
    }

    private boolean hasMultipleStatements(String sql) {
        String withoutTrailingSemicolon = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        return withoutTrailingSemicolon.contains(";");
    }

    private boolean containsForbiddenKeyword(String lowerSql) {
        return lowerSql.contains(" delete ")
                || lowerSql.contains(" insert ")
                || lowerSql.contains(" update ")
                || lowerSql.contains(" merge ")
                || lowerSql.contains(" create ")
                || lowerSql.contains(" drop ")
                || lowerSql.contains(" alter ")
                || lowerSql.contains(" truncate ")
                || lowerSql.contains(" grant ")
                || lowerSql.contains(" revoke ");
    }
}
