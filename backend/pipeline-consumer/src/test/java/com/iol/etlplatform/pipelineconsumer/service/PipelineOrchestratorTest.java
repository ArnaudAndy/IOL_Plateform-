package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.*;

public class PipelineOrchestratorTest {

    @Test
    void selectedDestinationIsInjectedIntoHopEnvironment() throws Exception {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        JsonNode command = new ObjectMapper().readTree("""
                {
                  "sources": [{
                    "source_name": "CSV",
                    "config": {
                      "target_connection": {
                        "db_type": "POSTGRES",
                        "host": "hospital-b-db",
                        "port": "5432",
                        "database": "analytics",
                        "username": "etl_user",
                        "password": "runtime-secret"
                      }
                    }
                  }]
                }
                """);

        Map<String, String> environment = orchestrator.targetEnvironment(command);

        assertEquals("hospital-b-db", environment.get("TARGET_HOST"));
        assertEquals("analytics", environment.get("TARGET_DATABASE"));
        assertEquals("etl_user", environment.get("TARGET_USER"));
        assertEquals("runtime-secret", environment.get("TARGET_PASSWORD"));
        assertEquals("IOL_DESTINATION_POSTGRES", environment.get("TARGET_HOP_CONNECTION"));
    }

    @Test
    void mysqlDestinationSelectsMysqlHopConnection() throws Exception {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        JsonNode command = new ObjectMapper().readTree("""
                {"sources":[{"config":{"target_connection":{
                  "db_type":"MYSQL","host":"mysql-target","port":"3306",
                  "database":"analytics","username":"etl_user","password":"runtime-secret"
                }}}]}
                """);

        Map<String, String> environment = orchestrator.targetEnvironment(command);

        assertEquals("MYSQL", environment.get("TARGET_DB_TYPE"));
        assertEquals("IOL_DESTINATION_MYSQL", environment.get("TARGET_HOP_CONNECTION"));
    }

    @Test
    void mariaDbDestinationSelectsMariaDbHopConnection() throws Exception {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        JsonNode command = new ObjectMapper().readTree("""
                {"sources":[{"config":{"target_connection":{
                  "db_type":"MARIADB","host":"mariadb-target","port":"3306",
                  "database":"analytics","username":"etl_user","password":"runtime-secret"
                }}}]}
                """);

        Map<String, String> environment = orchestrator.targetEnvironment(command);

        assertEquals("MARIADB", environment.get("TARGET_DB_TYPE"));
        assertEquals("IOL_DESTINATION_MARIADB", environment.get("TARGET_HOP_CONNECTION"));
    }

    @Test
    void allConfiguredDestinationTypesSelectTheirHopConnection() throws Exception {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        Map<String, String> expected = Map.of(
                "MSSQL", "IOL_DESTINATION_MSSQL",
                "ORACLE", "IOL_DESTINATION_ORACLE",
                "SQLITE", "IOL_DESTINATION_SQLITE",
                "SNOWFLAKE", "IOL_DESTINATION_SNOWFLAKE",
                "REDSHIFT", "IOL_DESTINATION_REDSHIFT");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String target = "SQLITE".equals(entry.getKey())
                    ? "{\"db_type\":\"SQLITE\",\"database\":\"/tmp/iol.db\"}"
                    : "{\"db_type\":\"" + entry.getKey() + "\",\"host\":\"target\","
                    + "\"database\":\"analytics\",\"username\":\"etl\",\"password\":\"secret\"}";
            JsonNode command = new ObjectMapper().readTree(
                    "{\"sources\":[{\"config\":{\"target_connection\":" + target + "}}]}");
            assertEquals(entry.getValue(), orchestrator.targetEnvironment(command).get("TARGET_HOP_CONNECTION"));
        }
    }

    @Test
    void disabledSilverAndGoldAreReportedAsSkipped() throws Exception {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        JsonNode command = new ObjectMapper().readTree("""
                {
                  "sources": [{"config": {"silver_enabled": false, "silver_config": {"enabled": false}}}],
                  "gold_config_global": {"enabled": false}
                }
                """);

        Map<String, String> statuses = orchestrator.stageStatuses(command, true, "");

        assertEquals("SUCCESS", statuses.get("BRONZE"));
        assertEquals("SKIPPED", statuses.get("SILVER"));
        assertEquals("SKIPPED", statuses.get("SILVER:0"));
        assertEquals("SKIPPED", statuses.get("GOLD"));
        assertEquals("SUCCESS", statuses.get("DESTINATION"));
    }

    @Test
    void progressReportsOnlyTheCurrentEnabledStageAsRunning() throws Exception {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        JsonNode command = new ObjectMapper().readTree("""
                {
                  "sources": [{"config": {"silver_config": {"enabled": true}}}],
                  "gold_config_global": {"enabled": true}
                }
                """);

        Map<String, String> statuses = orchestrator.progressStageStatuses(command, "SILVER");

        assertEquals("SUCCESS", statuses.get("PREPARATION"));
        assertEquals("SUCCESS", statuses.get("BRONZE"));
        assertEquals("RUNNING", statuses.get("SILVER"));
        assertEquals("NOT_RUN", statuses.get("GOLD"));
        assertEquals("NOT_RUN", statuses.get("DESTINATION"));
        assertEquals(1, statuses.values().stream().filter("RUNNING"::equals).count());
    }

    @Test
    void executionLockIsScopedByDestinationConnection() throws Exception {
        KafkaEventListenerService listener = new KafkaEventListenerService(
                Mockito.mock(PipelineOrchestrator.class),
                Mockito.mock(KafkaDataChunkStore.class),
                Mockito.mock(DistributedExecutionLockService.class));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree("""
                {"sources":[{"config":{"target_connection":{"connection_id":"mysql-hospital-b"}}}]}
                """);
        JsonNode second = mapper.readTree("""
                {"sources":[{"config":{"target_connection":{"connection_id":"postgres-hospital-c"}}}]}
                """);

        assertEquals("destination:mysql-hospital-b", listener.executionLockKey(first, "wf-1"));
        assertEquals("destination:postgres-hospital-c", listener.executionLockKey(second, "wf-1"));
        assertNotEquals(listener.executionLockKey(first, "wf-1"), listener.executionLockKey(second, "wf-1"));
    }

    @Test
    void detectsLastFailedPipelineStage() {
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));

        assertEquals("SILVER", orchestrator.detectFailedStage(
                "Extraction terminee\nBronze OK\nSilver loop ERROR relation absente", null));
        assertEquals("DESTINATION", orchestrator.detectFailedStage(
                "", "target_connection.password est obligatoire"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesStatusWhenHopLaunchFails() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(kafkaTemplate);

        // hopHome/hopTempDir non injectés (test unitaire sans Spring) → le lancement Hop
        // échoue, l'orchestrateur capture l'erreur et publie un statut FAILED (+ DLQ).
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode command = objectMapper.readTree("{" +
                "\"workflowName\":\"test\"," +
                "\"executionMode\":\"LOCAL\"," +
                "\"sources\":[{\"source_name\":\"POSTGRES\",\"config\":{\"target_table\":\"stg_test\"}}]" +
                "}");

        orchestrator.execute(command, "wf-test", "log-test");

        // Au moins une publication Kafka (statut FAILED et/ou DLQ) — signature send(topic, key, value).
        // any() (et pas anyString()) car le topic n'est pas injecté hors contexte Spring (null).
        verify(kafkaTemplate, atLeastOnce()).send(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void refusesAnyDirectJdbcSourceBeforeHopOrSpark() throws Exception {
        PipelineOrchestrator orchestrator =
                new PipelineOrchestrator(Mockito.mock(KafkaTemplate.class));
        JsonNode command = new ObjectMapper().readTree("""
                {
                  "direction": "INTERNAL",
                  "executionMode": "SPARK",
                  "sources": [{
                    "source_name": "POSTGRES",
                    "config": {
                      "host": "source-db",
                      "source_config": {
                        "query": "SELECT * FROM patients",
                        "source_connection": {"username": "reader", "password": "secret"}
                      }
                    }
                  }]
                }
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> orchestrator.prepareCommandForHop(command, "wf-direct", "log-direct"));
        assertTrue(error.getMessage().contains("ne peuvent pas se connecter directement"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboundPushSourceIsMaterializedAsNativeJsonLinesForTheEngine() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(kafkaTemplate);
        Path tempDir = Files.createTempDirectory("iol-push-test");
        ReflectionTestUtils.setField(orchestrator, "hopTempDir", tempDir.toString());

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode command = objectMapper.readTree("{" +
                "\"direction\":\"INBOUND\"," +
                "\"sources\":[{\"source_name\":\"PUSH\",\"type\":\"PUSH\",\"config\":{" +
                "\"target_table\":\"stg_custom\"," +
                "\"source_config\":{\"data\":{\"patient_id\":\"P001\",\"amount\":12.5}}" +
                "}}]" +
                "}");

        PipelineOrchestrator.PreparedCommand prepared = orchestrator.prepareCommandForHop(
                command, "wf-inbound", "log-inbound");

        try {
            JsonNode source = prepared.command().path("sources").get(0);
            assertEquals("JSON", source.path("source_name").asText());
            assertEquals("JSON", source.path("type").asText());
            assertTrue(source.path("inbound_push").asBoolean());
            assertTrue(source.path("transport_materialized").asBoolean());

            Path jsonLines = prepared.tempFiles().get(0);
            assertTrue(Files.exists(jsonLines));
            assertTrue(jsonLines.getFileName().toString().endsWith(".jsonl"));
            JsonNode record = objectMapper.readTree(Files.readString(jsonLines).trim());
            assertEquals("P001", record.path("patient_id").asText());
            assertEquals(12.5, record.path("amount").asDouble());
            assertEquals(jsonLines.toAbsolutePath().toString(), source.path("config").path("file_path").asText());
            assertEquals("KAFKA_NATIVE_JSON", source.path("config").path("data_transport").asText());
            assertEquals("PUSH", source.path("config").path("source_config").path("mode").asText());
            assertEquals("JSON", source.path("config").path("source_config").path("materialized_format").asText());
        } finally {
            for (Path file : prepared.tempFiles()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboundFailurePublishesInteropContextAndNoSqlDlq() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(kafkaTemplate);
        ReflectionTestUtils.setField(orchestrator, "statusTopic", "status-topic");
        ReflectionTestUtils.setField(orchestrator, "dlqTopic", "dlq-topic");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode command = objectMapper.readTree("{" +
                "\"direction\":\"INBOUND\"," +
                "\"standardId\":\"std_custom\"," +
                "\"sourceSystem\":\"external\"," +
                "\"correlationId\":\"corr-1\"," +
                "\"openhimTransactionId\":\"tx-openhim-1\"," +
                "\"sources\":[{\"source_name\":\"PUSH\",\"type\":\"PUSH\",\"config\":{" +
                "\"source_config\":{\"data\":{\"patient_id\":\"P001\"}}" +
                "}}]" +
                "}");

        orchestrator.publishFailure(command, "log-inbound", "wf-inbound", "Hop failed");

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dlqCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(Mockito.eq("status-topic"), Mockito.eq("wf-inbound"), statusCaptor.capture());
        verify(kafkaTemplate).send(Mockito.eq("dlq-topic"), Mockito.eq("wf-inbound"), dlqCaptor.capture());

        JsonNode status = objectMapper.readTree(statusCaptor.getValue());
        assertEquals("FAILED", status.path("status").asText());
        assertEquals("INBOUND", status.path("direction").asText());
        assertEquals("std_custom", status.path("standardId").asText());
        assertEquals("corr-1", status.path("correlationId").asText());
        assertEquals("tx-openhim-1", status.path("openhimTransactionId").asText());

        JsonNode dlq = objectMapper.readTree(dlqCaptor.getValue());
        assertEquals("log-inbound", dlq.path("log_id").asText());
        assertEquals("external", dlq.path("source_id").asText());
        assertEquals("std_custom", dlq.path("standard_id").asText());
        assertEquals("corr-1", dlq.path("correlation_id").asText());
        assertEquals("tx-openhim-1", dlq.path("openhim_transaction_id").asText());
        assertEquals("PIPELINE_CONSUMER", dlq.path("error_context").path("step").asText());
        assertEquals("ERROR", dlq.path("error_context").path("severity").asText());
        assertFalse(dlq.path("original_data").path("command").isMissingNode());
        assertFalse(dlq.path("timestamp").asText().isBlank());
    }
}
