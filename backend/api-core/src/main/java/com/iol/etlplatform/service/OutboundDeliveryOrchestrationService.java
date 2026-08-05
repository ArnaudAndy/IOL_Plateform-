package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.kafka.KafkaPipelineEventService;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import com.iol.etlplatform.util.SqlSafetyValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Starts partner delivery after a successful OUTBOUND workflow.
 *
 * Only approved rows from the destination database are read here. The service
 * publishes a delivery command; serialization, authentication, SSRF checks,
 * OpenHIM policy checks and HTTP retries belong to the mediator worker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboundDeliveryOrchestrationService {

    private static final Pattern QUALIFIED_TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final WorkflowConfigRepository workflowConfigRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final KafkaPipelineEventService kafkaPipelineEventService;

    @Value("${app.outbound.max-rows:1000}")
    private int defaultMaxRows;

    public boolean requestDeliveryIfEligible(ExecutionLog execLog, JsonNode statusNode) {
        if (execLog == null || execLog.getStatus() != ExecutionStatus.SUCCESS) {
            return false;
        }
        if (execLog.getWorkflowId() == null || execLog.getWorkflowId().isBlank()) {
            log.warn("ExecutionLog {} sans workflowId: livraison OUTBOUND ignoree.", execLog.getId());
            return false;
        }

        Optional<WorkflowConfig> workflowOpt = workflowConfigRepository.findById(execLog.getWorkflowId());
        if (workflowOpt.isEmpty()) {
            log.warn("Workflow {} introuvable: livraison OUTBOUND ignoree.", execLog.getWorkflowId());
            return false;
        }

        WorkflowConfig workflow = workflowOpt.get();
        if (workflow.getDirection() != WorkflowDirection.OUTBOUND) {
            return false;
        }

        List<Map<String, Object>> rows = readPivotRows(workflow);
        String correlationId = firstNonBlank(
                text(statusNode, "correlationId"),
                executionParam(execLog, "correlationId"),
                "outbound-" + UUID.randomUUID());
        String openhimTransactionId = firstNonBlank(
                text(statusNode, "openhimTransactionId"),
                executionParam(execLog, "openhimTransactionId"));

        kafkaPipelineEventService.publishOutboundDeliveryRequested(
                workflow,
                execLog.getId(),
                correlationId,
                openhimTransactionId,
                rows);

        return true;
    }

    List<Map<String, Object>> readPivotRows(WorkflowConfig workflow) {
        Map<String, Object> outboundConfig = workflow.getOutboundConfig();
        if (outboundConfig == null || outboundConfig.isEmpty()) {
            throw new BadRequestException("outboundConfig est obligatoire pour la livraison OUTBOUND.");
        }

        Map<String, Object> source = mapValue(outboundConfig.get("source"));
        int maxRows = positiveInt(outboundConfig.get("maxRows"), defaultMaxRows);
        String query = stringValue(source.get("query"));
        if (!query.isBlank()) {
            String normalized = normalizeSelectQuery(query);
            return jdbcTemplate.queryForList("select * from (" + normalized + ") outbound_source limit " + maxRows);
        }

        String goldTable = firstNonBlank(stringValue(source.get("goldTable")), stringValue(source.get("gold_table")));
        if (goldTable.isBlank()) {
            throw new BadRequestException("outboundConfig.source.goldTable ou outboundConfig.source.query est obligatoire.");
        }
        if (!QUALIFIED_TABLE.matcher(goldTable).matches()) {
            throw new BadRequestException("Table Gold OUTBOUND invalide: " + goldTable);
        }
        return jdbcTemplate.queryForList("select * from " + goldTable + " limit " + maxRows);
    }

    private String normalizeSelectQuery(String query) {
        String trimmed = query.trim().replaceFirst(";\\s*$", "");
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("select ")) {
            throw new BadRequestException("La requete OUTBOUND doit etre un SELECT.");
        }
        if (trimmed.contains(";")) {
            throw new BadRequestException("La requete OUTBOUND doit contenir une seule instruction SELECT.");
        }
        sqlSafetyValidator.validateReadOnlySql(trimmed);
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return new LinkedHashMap<>((Map<String, Object>) raw);
        }
        return Map.of();
    }

    private int positiveInt(Object value, int fallback) {
        if (value == null) {
            return Math.max(1, fallback);
        }
        try {
            return Math.max(1, Integer.parseInt(value.toString()));
        } catch (NumberFormatException ignored) {
            return Math.max(1, fallback);
        }
    }

    private String executionParam(ExecutionLog execLog, String key) {
        Map<String, String> params = execLog.getExecutionParams();
        return params != null ? params.get(key) : null;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode()) {
            return null;
        }
        return node.path(fieldName).asText(null);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
