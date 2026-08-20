package com.iol.etlplatform.service;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.dto.workflow.WorkflowExportResponse;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.embedded.GoldConfigGlobal;
import com.iol.etlplatform.entity.embedded.SourceDefinition;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.mapper.WorkflowConfigMapper;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import com.iol.etlplatform.util.SqlSafetyValidator;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowConfigRepository repository;

    @Mock
    private WorkflowConfigMapper mapper;

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private OrchestrationService orchestrationService;

    @Mock
    private com.iol.etlplatform.service.scheduler.PipelineSchedulerService schedulerService;

    @Mock
    private DestinationConnectionService destinationConnectionService;

    @Mock
    private com.iol.etlplatform.repository.StandardRepository standardRepository;

    @Mock
    private SqlSafetyValidator sqlSafetyValidator;

    @Mock
    private ApiSourceClient apiSourceClient;

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        workflowService = new WorkflowService(repository, mapper, new ObjectMapper(), discoveryService,
                orchestrationService, schedulerService, destinationConnectionService, standardRepository,
                sqlSafetyValidator, apiSourceClient);
    }

    @Test
    void shouldThrowWhenWorkflowNotFound() {
        when(repository.findById("wf-404")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> workflowService.findById("wf-404"));
    }

    @Test
    void shouldExportWorkflowAsJson() {
        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf-1");
        workflow.setWorkflowName("WF ventes");
        // Sans contexte de sécurité, getCurrentUserEmail() renvoie "anonymous":
        // aligner le propriétaire pour passer le contrôle d'ownership.
        workflow.setCreatedBy("anonymous");
        when(repository.findById("wf-1")).thenReturn(Optional.of(workflow));

        WorkflowExportResponse response = workflowService.exportWorkflowAsJson("wf-1");

        assertEquals("wf-1", response.getWorkflowId());
        assertTrue(response.getWorkflowJson().contains("\"workflowName\":\"WF ventes\""));
    }

    @Test
    void disabledSilverAndGoldDoNotRequireSqlOrTargetTable() {
        SourceDefinition source = sourceWithSilver(Map.of("enabled", false));
        GoldConfigGlobal gold = new GoldConfigGlobal();
        gold.setEnabled(false);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                workflowService, "validateOptionalStages", List.of(source), gold, null));
    }

    @Test
    void enabledSilverRequiresSqlAndTargetTable() {
        SourceDefinition source = sourceWithSilver(Map.of("enabled", true));
        GoldConfigGlobal gold = new GoldConfigGlobal();
        gold.setEnabled(false);

        assertThrows(BadRequestException.class, () -> ReflectionTestUtils.invokeMethod(
                workflowService, "validateOptionalStages", List.of(source), gold, null));
    }

    @Test
    void bronzeMustBeSelectedWhenAllSilverStagesAreDisabled() {
        SourceDefinition source = sourceWithSilver(Map.of("enabled", false));
        GoldConfigGlobal gold = new GoldConfigGlobal();
        gold.setEnabled(true);
        gold.setInputLayer("SILVER");
        gold.setTargetTableGold("gold.patients");
        gold.setEltScriptsGold("SELECT 1");

        assertThrows(BadRequestException.class, () -> ReflectionTestUtils.invokeMethod(
                workflowService, "validateOptionalStages", List.of(source), gold, null));
    }

    @Test
    void goldWithoutDeclaredIndexesIsAccepted() {
        SourceDefinition source = sourceWithSilver(Map.of("enabled", false));
        GoldConfigGlobal gold = new GoldConfigGlobal();
        gold.setEnabled(true);
        gold.setInputLayer("BRONZE");
        gold.setTargetTableGold("gold.activite_service");
        gold.setEltScriptsGold("CREATE TABLE gold.activite_service AS SELECT 1");

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                workflowService, "validateOptionalStages", List.of(source), gold, null));
    }

    @Test
    void goldRejectsAnEmptyIndexDefinition() {
        SourceDefinition source = sourceWithSilver(Map.of("enabled", false));
        GoldConfigGlobal gold = new GoldConfigGlobal();
        gold.setEnabled(true);
        gold.setInputLayer("BRONZE");
        gold.setTargetTableGold("gold.activite_service");
        gold.setEltScriptsGold("CREATE TABLE gold.activite_service AS SELECT 1");
        gold.setIndexes(java.util.Collections.singletonList(null));

        assertThrows(BadRequestException.class, () -> ReflectionTestUtils.invokeMethod(
                workflowService, "validateOptionalStages", List.of(source), gold, null));
    }

    private SourceDefinition sourceWithSilver(Map<String, Object> silverConfig) {
        SourceDefinition source = new SourceDefinition();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("silver_config", new LinkedHashMap<>(silverConfig));
        source.setConfig(config);
        return source;
    }
}
