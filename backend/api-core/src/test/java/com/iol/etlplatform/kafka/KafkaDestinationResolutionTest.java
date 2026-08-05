package com.iol.etlplatform.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.embedded.GoldConfigGlobal;
import com.iol.etlplatform.entity.embedded.SourceDefinition;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.service.DestinationConnectionService;
import com.iol.etlplatform.service.SourceLoadEstimatorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Vérifie la résolution de la destination au niveau workflow (Module 3).
 *
 * Garantie clé pour Hop/Python : le JSON final conserve target_connection PAR SOURCE.
 * Seule la provenance change — résolue depuis la connexion nommée du workflow.
 */
class KafkaDestinationResolutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SourceDefinition source(String name, String targetTable) {
        SourceDefinition sd = new SourceDefinition();
        sd.setSourceName(name);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("target_table", targetTable);
        sd.setConfig(config);
        return sd;
    }

    @Test
    void estimatedRowsAboveThresholdPromotesCommandToSparkBeforeSourceTransport() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_big");
        workflow.setWorkflowName("Big JDBC workload");
        workflow.setPriority(3);
        workflow.setExecutionMode("LOCAL");
        workflow.setEstimatedRows(2_000_000L);
        workflow.setSources(List.of(source("POSTGRES", "bronze.big_source")));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);
        ReflectionTestUtils.setField(service, "sparkRowThreshold", 1_000_000L);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_big");

        assertEquals("SPARK", command.get("executionMode"),
                "Le volume estime doit promouvoir le payload avant la preparation des sources.");
        assertEquals(2_000_000L, command.get("estimatedRows"));
    }

    @Test
    void distributedSilverStagePromotesCommandToSparkWithoutExplicitWorkflowMode() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        SourceDefinition source = source("POSTGRES", "bronze.patient");
        source.getConfig().put("silver_config", Map.of(
                "enabled", true,
                "execution_engine", "SPARK",
                "target_table_silver", "silver.patient",
                "spark_sql", "SELECT * FROM bronze_0"));

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_distributed_stage");
        workflow.setWorkflowName("Distributed stage");
        workflow.setPriority(3);
        workflow.setSources(List.of(source));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);
        ReflectionTestUtils.setField(service, "sparkRowThreshold", 1_000_000L);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_stage");

        assertEquals("SPARK", command.get("executionMode"),
                "Une etape distribuee doit suffire a choisir le runtime distribue.");
    }

    @Test
    void measuredSourceLoadPromotesCommandWithoutUserEstimate() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);
        SourceLoadEstimatorService estimator = mock(SourceLoadEstimatorService.class);
        when(estimator.assess(anyList(), eq(1_000_000L))).thenReturn(
                new SourceLoadEstimatorService.LoadAssessment(
                        1_500_000L, -1L, true, "JDBC_ROW_THRESHOLD", true));

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_measured");
        workflow.setWorkflowName("Measured workload");
        workflow.setPriority(3);
        workflow.setSources(List.of(source("CSV", "bronze.measured")));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);
        ReflectionTestUtils.setField(service, "sparkRowThreshold", 1_000_000L);
        ReflectionTestUtils.setField(service, "sourceLoadEstimatorService", estimator);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_measured");

        assertEquals("SPARK", command.get("executionMode"));
        assertEquals(1_500_000L, command.get("estimatedRows"));
        assertTrue(command.containsKey("loadAssessment"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void namedJdbcSourceCarriesDirectConnectionMetadataForLocalEngine() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);
        DestinationConnection sourceConnection = new DestinationConnection();
        sourceConnection.setId("source-conn");
        sourceConnection.setName("Hospital source");
        sourceConnection.setDbType("POSTGRES");
        sourceConnection.setHost("hospital-db");
        sourceConnection.setPort("5432");
        sourceConnection.setDatabase("hospital");
        sourceConnection.setUsername("reader");
        sourceConnection.setPassword("secret");
        when(destinationService.getEntityByIdForOwner("source-conn", "owner@iol.local"))
                .thenReturn(sourceConnection);
        when(destinationService.resolveRuntimeHost("hospital-db")).thenReturn("hospital-db");
        when(destinationService.resolvePassword(sourceConnection)).thenReturn("secret");

        SourceDefinition jdbc = source("POSTGRES", "bronze.patient");
        jdbc.getConfig().put("source_connection_id", "source-conn");
        jdbc.getConfig().put("source_config", Map.of("query", "SELECT id, name FROM patient"));
        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf-direct");
        workflow.setWorkflowName("Direct JDBC");
        workflow.setCreatedBy("owner@iol.local");
        workflow.setSources(List.of(jdbc));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);
        ReflectionTestUtils.setField(service, "sparkRowThreshold", 1_000_000L);
        Map<String, Object> command = service.buildCommandPayload(workflow, "log-direct");

        Map<String, Object> emittedSource = ((List<Map<String, Object>>) command.get("sources")).get(0);
        Map<String, Object> config = (Map<String, Object>) emittedSource.get("config");
        Map<String, Object> sourceConfig = (Map<String, Object>) config.get("source_config");
        Map<String, Object> directConnection = (Map<String, Object>) sourceConfig.get("source_connection");

        assertEquals("direct-jdbc://postgres", config.get("uri"));
        assertEquals("hospital-db", directConnection.get("host"));
        assertEquals("reader", directConnection.get("username"));
        assertEquals("secret", directConnection.get("password"));
        assertEquals(50_000, sourceConfig.get("jdbc_chunk_rows"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void workflowDestinationIsResolvedIntoEverySource() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        // Connexion nommée résolue
        DestinationConnection conn = new DestinationConnection();
        conn.setId("conn1");
        conn.setDbType("POSTGRES");
        conn.setHost("lakehouse.local");
        conn.setPort("5432");
        conn.setDatabase("lakehouse");
        conn.setUsername("etl_user");
        conn.setPassword("secret123");
        when(destinationService.getEntityByIdForOwner("conn1", "owner@iol.local")).thenReturn(conn);
        when(destinationService.resolveRuntimeHost("lakehouse.local")).thenReturn("lakehouse.local");

        // Workflow multi-source AVEC destinationConnectionId, 2 sources SANS target_connection
        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_1");
        workflow.setWorkflowName("Fusion DGI + Impots");
        workflow.setPriority(3);
        workflow.setCreatedBy("owner@iol.local");
        workflow.setDestinationConnectionId("conn1");
        workflow.setSources(List.of(
                source("CSV", "stg_dgi_ventes"),
                source("POSTGRES", "stg_impots")
        ));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_1");

        List<Map<String, Object>> sources = (List<Map<String, Object>>) command.get("sources");
        assertEquals(2, sources.size(), "Les 2 sources doivent être présentes");

        Map<String, Object> tc0 = (Map<String, Object>) ((Map<String, Object>) sources.get(0).get("config")).get("target_connection");
        Map<String, Object> tc1 = (Map<String, Object>) ((Map<String, Object>) sources.get(1).get("config")).get("target_connection");

        assertNotNull(tc0, "Source 0 doit avoir un target_connection résolu");
        assertNotNull(tc1, "Source 1 doit avoir un target_connection résolu");

        // Valeurs de connexion identiques entre les deux sources
        assertEquals("lakehouse.local", tc0.get("host"));
        assertEquals("5432", tc0.get("port"));
        assertEquals("lakehouse", tc0.get("database"));
        assertEquals("etl_user", tc0.get("username"));
        assertFalse(tc0.containsKey("password"),
                "Le credential de destination ne doit jamais entrer dans le payload Kafka.");
        assertEquals("POSTGRES", tc0.get("db_type"));
        assertEquals("IOL_DESTINATION_POSTGRES", tc0.get("hop_connection_name"));

        assertEquals(tc0.get("host"), tc1.get("host"));
        assertEquals(tc0.get("database"), tc1.get("database"));
        assertEquals(tc0.get("username"), tc1.get("username"));
        assertFalse(tc1.containsKey("password"));

        // target_table propre à chaque source préservé
        assertEquals("stg_dgi_ventes", tc0.get("target_table"));
        assertEquals("stg_impots", tc1.get("target_table"));

        verify(destinationService, times(1)).getEntityByIdForOwner("conn1", "owner@iol.local");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceAndDestinationConnectionsAreResolvedSeparately() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        DestinationConnection sourceConnection = new DestinationConnection();
        sourceConnection.setId("hospital_a");
        sourceConnection.setName("Hospital A");
        sourceConnection.setDbType("POSTGRES");
        sourceConnection.setHost("hospital-a-db");
        sourceConnection.setPort("5432");
        sourceConnection.setDatabase("operational");
        sourceConnection.setUsername("reader");
        sourceConnection.setPassword("source-secret");

        DestinationConnection targetConnection = new DestinationConnection();
        targetConnection.setId("hospital_b");
        targetConnection.setName("Hospital B Analytics");
        targetConnection.setDbType("POSTGRES");
        targetConnection.setHost("hospital-b-db");
        targetConnection.setPort("5432");
        targetConnection.setDatabase("analytics");
        targetConnection.setUsername("writer");
        targetConnection.setPassword("target-secret");

        when(destinationService.getEntityByIdForOwner("hospital_a", "owner@iol.local")).thenReturn(sourceConnection);
        when(destinationService.getEntityByIdForOwner("hospital_b", "owner@iol.local")).thenReturn(targetConnection);
        when(destinationService.resolveRuntimeHost(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(destinationService.resolvePassword(sourceConnection)).thenReturn("source-secret");

        SourceDefinition source = source("POSTGRES", "bronze.patients");
        source.getConfig().put("source_connection_id", "hospital_a");
        source.getConfig().put("source_config", Map.of("query", "select patient_id from patients"));

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_hospitals");
        workflow.setWorkflowName("Hospital A vers B");
        workflow.setPriority(3);
        workflow.setCreatedBy("owner@iol.local");
        workflow.setDestinationConnectionId("hospital_b");
        workflow.setSources(List.of(source));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);
        Map<String, Object> command = service.buildCommandPayload(workflow, "log_hospitals");

        Map<String, Object> sourceConfig = (Map<String, Object>)
                ((List<Map<String, Object>>) command.get("sources")).get(0).get("config");
        Map<String, Object> target = (Map<String, Object>) sourceConfig.get("target_connection");

        assertEquals("hospital-a-db", sourceConfig.get("host"));
        assertEquals("operational", sourceConfig.get("database"));
        assertEquals("reader", sourceConfig.get("username"));
        assertEquals("hospital-b-db", target.get("host"));
        assertEquals("analytics", target.get("database"));
        assertEquals("writer", target.get("username"));
        assertEquals("IOL_DESTINATION_POSTGRES", target.get("hop_connection_name"));
        assertEquals("source-secret", sourceConfig.get("password"),
                "Le secret source n'existe que dans la map de travail avant son extraction par api-core.");
        assertFalse(target.containsKey("password"),
                "Le consumer obtient le secret cible via un lease court, jamais via Kafka.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyWorkflowWithoutDestinationIsUntouched() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        // Source legacy avec son propre target_connection, AUCUN destinationConnectionId
        SourceDefinition sd = new SourceDefinition();
        sd.setSourceName("POSTGRES");
        Map<String, Object> legacyTc = new LinkedHashMap<>();
        legacyTc.put("host", "old-db.local");
        legacyTc.put("database", "olddb");
        legacyTc.put("target_table", "stg_legacy");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("target_connection", legacyTc);
        sd.setConfig(config);

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_legacy");
        workflow.setWorkflowName("Legacy");
        workflow.setPriority(3);
        workflow.setSources(List.of(sd));
        // destinationConnectionId == null

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_legacy");

        List<Map<String, Object>> sources = (List<Map<String, Object>>) command.get("sources");
        Map<String, Object> tc = (Map<String, Object>) ((Map<String, Object>) sources.get(0).get("config")).get("target_connection");

        // target_connection legacy inchangé
        assertEquals("old-db.local", tc.get("host"));
        assertEquals("olddb", tc.get("database"));
        assertEquals("stg_legacy", tc.get("target_table"));

        // La destination n'est jamais résolue quand aucun ID n'est fourni
        verify(destinationService, never()).getEntityByIdForOwner(anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void perSourceGoldIsStrippedAndWorkflowGoldKeptAtRoot() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        // Source legacy contenant un gold_config par source (déprécié)
        SourceDefinition sd = new SourceDefinition();
        sd.setSourceName("CSV");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("target_table", "stg_dgi");
        Map<String, Object> perSourceGold = new LinkedHashMap<>();
        perSourceGold.put("target_table_gold", "gold.should_be_removed");
        perSourceGold.put("elt_scripts_gold", "CREATE TABLE gold.should_be_removed AS ...");
        config.put("gold_config", perSourceGold);
        sd.setConfig(config);

        // Gold UNIQUE au niveau workflow
        GoldConfigGlobal gold = new GoldConfigGlobal();
        gold.setTargetTableGold("gold.fact_consolide");
        gold.setEltScriptsGold("CREATE TABLE gold.fact_consolide AS SELECT ... FROM cln_dgi");

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_gold");
        workflow.setWorkflowName("Gold unique");
        workflow.setPriority(3);
        workflow.setSources(List.of(sd));
        workflow.setGoldConfigGlobal(gold);

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_gold");

        // Le gold_config par source a été retiré
        List<Map<String, Object>> sources = (List<Map<String, Object>>) command.get("sources");
        Map<String, Object> srcConfig = (Map<String, Object>) sources.get(0).get("config");
        assertFalse(srcConfig.containsKey("gold_config"), "Le gold_config par source doit être retiré");

        // Le Gold unique du workflow est présent au niveau racine
        assertTrue(command.containsKey("gold_config_global"), "gold_config_global doit être au niveau workflow");
        GoldConfigGlobal rootGold = (GoldConfigGlobal) command.get("gold_config_global");
        assertEquals("gold.fact_consolide", rootGold.getTargetTableGold());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboundPushCommandCarriesPivotAndRequiredRoutingFields() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_inbound");
        workflow.setWorkflowName("Inbound CUSTOM");
        workflow.setPriority(3);
        workflow.setExecutionMode("LOCAL");
        workflow.setDirection(com.iol.etlplatform.entity.enums.WorkflowDirection.INBOUND);
        workflow.setStandardId("std_custom");
        workflow.setSources(List.of(source("PUSH", "stg_custom_inbound")));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildInboundPushCommandPayload(
                workflow,
                "log_inbound",
                "std_custom",
                "external",
                "corr-1",
                "tx-openhim-1",
                Map.of("patient_id", "P001"));

        assertEquals(KafkaPipelineEventService.EVENT_TYPE_PIPELINE_EXECUTION_REQUESTED, command.get("eventType"));
        assertEquals("INBOUND", command.get("direction"));
        assertEquals("std_custom", command.get("standardId"));
        assertEquals("corr-1", command.get("correlationId"));
        assertEquals("tx-openhim-1", command.get("openhimTransactionId"));

        List<Map<String, Object>> sources = (List<Map<String, Object>>) command.get("sources");
        assertEquals(1, sources.size());
        assertEquals("PUSH", sources.get(0).get("source_name"));
        assertEquals("PUSH", sources.get(0).get("type"));

        Map<String, Object> config = (Map<String, Object>) sources.get(0).get("config");
        Map<String, Object> sourceConfig = (Map<String, Object>) config.get("source_config");
        assertEquals("PUSH", sourceConfig.get("mode"));
        assertEquals(true, sourceConfig.get("already_pivot"));
        assertEquals(Map.of("patient_id", "P001"), sourceConfig.get("data"));
        assertEquals("tx-openhim-1", sourceConfig.get("openhim_transaction_id"));
        assertEquals("append", config.get("write_mode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void outboundDeliveryCommandCarriesConfigAndPivotRows() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_out");
        workflow.setWorkflowName("Outbound clients");
        workflow.setDirection(WorkflowDirection.OUTBOUND);
        workflow.setOutboundConfig(new LinkedHashMap<>(Map.of(
                "targetStandardId", "std_partner",
                "targetSystem", "hospital_b",
                "targetAdapter", "generic-json",
                "source", Map.of("goldTable", "gold.client_orders"),
                "destination", Map.of("openhimChannel", "outbound-client"))));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildOutboundDeliveryCommandPayload(
                workflow,
                "log_out",
                "corr-out",
                "tx-openhim-out",
                List.of(Map.of("client_id", "C001")));

        assertEquals(KafkaPipelineEventService.EVENT_TYPE_OUTBOUND_DELIVERY_REQUESTED, command.get("eventType"));
        assertEquals("wf_out", command.get("workflowId"));
        assertEquals("log_out", command.get("execLogId"));
        assertEquals("OUTBOUND", command.get("direction"));
        assertEquals("corr-out", command.get("correlationId"));
        assertEquals("tx-openhim-out", command.get("openhimTransactionId"));
        assertEquals("std_partner", command.get("targetStandardId"));
        assertEquals("hospital_b", command.get("targetSystem"));
        assertEquals("generic-json", command.get("targetAdapter"));
        assertEquals(1, command.get("rowCount"));

        Map<String, Object> outboundConfig = (Map<String, Object>) command.get("outboundConfig");
        assertEquals("outbound-client", ((Map<String, Object>) outboundConfig.get("destination")).get("openhimChannel"));
        assertEquals(List.of(Map.of("client_id", "C001")), command.get("pivotRows"));
        assertNotNull(command.get("requestedAt"));
    }
}
