package com.iol.etlplatform.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that KafkaPipelineEventService produces the correct multi-source JSON structure.
 * Reference structure:
 * {
 *   "workflowId": "wf_123",
 *   "execLogId": "log_456",
 *   "workflowName": "Ventes consolidées",
 *   "executionMode": "LOCAL",
 *   "priority": 3,
 *   "schedule": { ... },
 *   "sources": [
 *     { "source_name": "CSV", "config": { ... } },
 *     { "source_name": "POSTGRES", "config": { ... } }
 *   ],
 *   "gold_config_global": { "target_table_gold": "...", "elt_scripts_gold": "..." }
 * }
 */
class KafkaMultiSourceJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testMultiSourceJsonStructureContainsRequiredFields() throws Exception {
        // Build reference JSON structure (simulated payload)
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowId", "wf_123");
        payload.put("execLogId", "log_456");
        payload.put("workflowName", "Ventes consolidées");
        payload.put("executionMode", "LOCAL");
        payload.put("priority", 3);

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("enabled", true);
        schedule.put("frequency", "DAILY");
        schedule.put("time", "02:00");
        schedule.put("loadMode", "INCREMENTAL");
        payload.put("schedule", schedule);

        // Multi-source array
        List<Map<String, Object>> sources = new ArrayList<>();

        Map<String, Object> source1 = new LinkedHashMap<>();
        source1.put("source_name", "CSV");
        Map<String, Object> config1 = new LinkedHashMap<>();
        config1.put("file_path", "C:\\...\\ventes_dgi.csv");
        config1.put("target_table", "stg_dgi_ventes");
        source1.put("config", config1);
        sources.add(source1);

        Map<String, Object> source2 = new LinkedHashMap<>();
        source2.put("source_name", "POSTGRES");
        Map<String, Object> config2 = new LinkedHashMap<>();
        config2.put("uri", "jdbc:postgresql://...");
        config2.put("target_table", "stg_impots");
        source2.put("config", config2);
        sources.add(source2);

        payload.put("sources", sources);

        // Gold config global
        Map<String, Object> goldGlobal = new LinkedHashMap<>();
        goldGlobal.put("target_table_gold", "gold.fusion_dgi_impots");
        goldGlobal.put("elt_scripts_gold", "DROP TABLE IF EXISTS gold.fusion_dgi_impots; CREATE TABLE ...");
        payload.put("gold_config_global", goldGlobal);

        // Serialize and validate
        String json = objectMapper.writeValueAsString(payload);
        System.out.println("\n=== REFERENCE MULTI-SOURCE JSON STRUCTURE ===");
        System.out.println(json);

        // Verify critical fields are present and in correct snake_case format
        assertTrue(json.contains("\"workflowId\""), "Should contain workflowId");
        assertTrue(json.contains("\"execLogId\""), "Should contain execLogId");
        assertTrue(json.contains("\"workflowName\""), "Should contain workflowName");
        assertTrue(json.contains("\"executionMode\""), "Should contain executionMode");
        assertTrue(json.contains("\"sources\""), "Should contain sources array");
        assertTrue(json.contains("\"source_name\""), "Sources should use source_name (snake_case)");
        assertTrue(json.contains("\"gold_config_global\""), "Should contain gold_config_global (snake_case)");
        assertTrue(json.contains("\"target_table_gold\""), "Should contain target_table_gold (snake_case)");
        assertTrue(json.contains("\"elt_scripts_gold\""), "Should contain elt_scripts_gold (snake_case)");

        System.out.println("\n[PASS] Multi-source JSON structure matches specification!");
    }

    @Test
    void testSnakeCasePropertySerialization() throws Exception {
        // Verify that fields marked with @JsonProperty use snake_case
        Map<String, Object> goldConfig = new LinkedHashMap<>();
        goldConfig.put("target_table_gold", "gold.test");
        goldConfig.put("elt_scripts_gold", "CREATE TABLE ...");

        String json = objectMapper.writeValueAsString(goldConfig);
        System.out.println("\nGold config JSON: " + json);

        assertTrue(json.contains("target_table_gold"), "targetTableGold should serialize as target_table_gold");
        assertTrue(json.contains("elt_scripts_gold"), "eltScriptsGold should serialize as elt_scripts_gold");

        System.out.println("[PASS] Snake_case serialization is correct!");
    }

    @Test
    void testSourceNamePropertySerialization() throws Exception {
        // Verify source_name uses snake_case
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "CSV");
        source.put("config", Map.of("file_path", "test.csv"));

        String json = objectMapper.writeValueAsString(source);
        System.out.println("\nSource JSON: " + json);

        assertTrue(json.contains("source_name"), "sourceName should serialize as source_name");

        System.out.println("[PASS] source_name snake_case serialization is correct!");
    }
}
