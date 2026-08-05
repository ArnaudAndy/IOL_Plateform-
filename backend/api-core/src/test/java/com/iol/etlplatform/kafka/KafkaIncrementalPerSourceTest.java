package com.iol.etlplatform.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.embedded.SourceDefinition;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.service.DestinationConnectionService;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Vérifie l'injection PAR SOURCE des options de chargement incrémental
 * (load_mode / incremental_column / write_mode / last_watermark) en snake_case,
 * et l'ABSENCE de tout champ incrémental à la racine du payload.
 */
class KafkaIncrementalPerSourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SourceDefinition source(String name, Map<String, Object> config) {
        SourceDefinition sd = new SourceDefinition();
        sd.setSourceName(name);
        sd.setConfig(config);
        return sd;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configOf(Map<String, Object> command, int index) {
        List<Map<String, Object>> sources = (List<Map<String, Object>>) command.get("sources");
        return (Map<String, Object>) sources.get(index).get("config");
    }

    /** (a) Un workflow mixte : 1 source INCREMENTAL + 1 source FULL. */
    @Test
    void incrementalAndFullSourcesCoexistWithPerSourceOptions() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);
        when(execLogRepo.findFirstByWorkflowIdAndStatusOrderByEndTimeDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> incConfig = new LinkedHashMap<>();
        incConfig.put("target_table", "stg_oracle");
        incConfig.put("load_mode", "INCREMENTAL");
        incConfig.put("incremental_column", "date_op");

        Map<String, Object> fullConfig = new LinkedHashMap<>();
        fullConfig.put("target_table", "stg_csv");

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_mix");
        workflow.setWorkflowName("Oracle inc + CSV full");
        workflow.setPriority(3);
        workflow.setSources(List.of(
                source("ORACLE", incConfig),
                source("CSV", fullConfig)
        ));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_mix");

        // Aucun champ incrémental à la racine (contrat : par source uniquement)
        assertFalse(command.containsKey("loadMode"), "loadMode ne doit plus exister à la racine");
        assertFalse(command.containsKey("incrementalColumn"), "incrementalColumn ne doit plus exister à la racine");
        assertFalse(command.containsKey("lastWatermark"), "lastWatermark ne doit plus exister à la racine");
        assertFalse(command.containsKey("load_mode"), "load_mode ne doit pas être à la racine");

        // Source 0 (ORACLE) : INCREMENTAL, snake_case, avec la colonne
        Map<String, Object> c0 = configOf(command, 0);
        assertEquals("INCREMENTAL", c0.get("load_mode"));
        assertEquals("date_op", c0.get("incremental_column"));
        assertEquals("append", c0.get("write_mode"), "write_mode défaut = append (Bronze immuable)");

        // Source 1 (CSV) : FULL, pas de colonne incrémentale
        Map<String, Object> c1 = configOf(command, 1);
        assertEquals("FULL", c1.get("load_mode"));
        assertFalse(c1.containsKey("incremental_column"), "Source FULL ne porte pas d'incremental_column");
        assertFalse(c1.containsKey("last_watermark"), "Source FULL ne porte pas de last_watermark");
        assertEquals("append", c1.get("write_mode"));
    }

    /** (b) Le last_watermark est injecté par source depuis le dernier run réussi (clé = target_table). */
    @Test
    void lastWatermarkIsInjectedPerSourceFromLastSuccessfulRun() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);

        ExecutionLog lastRun = new ExecutionLog();
        Map<String, String> watermarks = new LinkedHashMap<>();
        watermarks.put("stg_oracle", "2026-06-15T02:00:00Z");
        watermarks.put("stg_pg", "42");
        lastRun.setLastSuccessfulWatermarks(watermarks);
        when(execLogRepo.findFirstByWorkflowIdAndStatusOrderByEndTimeDesc("wf_inc", ExecutionStatus.SUCCESS))
                .thenReturn(Optional.of(lastRun));

        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("target_table", "stg_oracle");
        oracle.put("load_mode", "INCREMENTAL");
        oracle.put("incremental_column", "date_op");

        Map<String, Object> pg = new LinkedHashMap<>();
        pg.put("target_table", "stg_pg");
        pg.put("load_mode", "INCREMENTAL");
        pg.put("incremental_column", "tx_id");

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_inc");
        workflow.setWorkflowName("Deux sources incrémentales");
        workflow.setPriority(3);
        workflow.setSources(List.of(source("ORACLE", oracle), source("POSTGRES", pg)));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_inc");

        assertEquals("2026-06-15T02:00:00Z", configOf(command, 0).get("last_watermark"),
                "La source stg_oracle doit recevoir SON watermark");
        assertEquals("42", configOf(command, 1).get("last_watermark"),
                "La source stg_pg doit recevoir SON watermark");

        // Une seule requête au repository malgré 2 sources (chargement paresseux)
        verify(execLogRepo, times(1))
                .findFirstByWorkflowIdAndStatusOrderByEndTimeDesc("wf_inc", ExecutionStatus.SUCCESS);
    }

    /** Repli legacy : loadMode/incrementalColumn dans schedule s'appliquent à toutes les sources. */
    @Test
    void legacyScheduleLevelIncrementalAppliesAsPerSourceDefault() {
        DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
        ExecutionLogRepository execLogRepo = mock(ExecutionLogRepository.class);
        when(execLogRepo.findFirstByWorkflowIdAndStatusOrderByEndTimeDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("target_table", "stg_legacy");

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_legacy_inc");
        workflow.setWorkflowName("Legacy incremental in schedule");
        workflow.setPriority(3);
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("enabled", true);
        schedule.put("loadMode", "INCREMENTAL");
        schedule.put("incrementalColumn", "updated_at");
        workflow.setSchedule(schedule);
        workflow.setSources(List.of(source("POSTGRES", config)));

        KafkaPipelineEventService service = new KafkaPipelineEventService(
                objectMapper, null, execLogRepo, destinationService);

        Map<String, Object> command = service.buildCommandPayload(workflow, "log_legacy_inc");

        Map<String, Object> c0 = configOf(command, 0);
        assertEquals("INCREMENTAL", c0.get("load_mode"), "Le défaut schedule.loadMode s'applique à la source");
        assertEquals("updated_at", c0.get("incremental_column"), "Le défaut schedule.incrementalColumn s'applique");
        // schedule reste transmis tel quel (le QUAND), mais sans hisser l'incrémental à la racine
        assertFalse(command.containsKey("loadMode"));
        assertFalse(command.containsKey("incrementalColumn"));
    }
}
