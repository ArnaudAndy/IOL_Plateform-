package com.iol.etlplatform.sourcegateway.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.contract.TransportOrder;
import com.iol.etlplatform.sourcegateway.readmodel.WorkflowConfig;
import com.iol.etlplatform.sourcegateway.readmodel.WorkflowConfigReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verrouille l'invariant central de la plateforme.
 *
 * Le pipeline-consumer ne doit jamais voir une commande contenant de quoi
 * joindre la source. Ces tests verifient l'ordre des effets et la derniere
 * barriere avant publication.
 */
class DefaultTransportPipelineTest {

    private WorkflowConfigReader workflowReader;
    private CommandBuilder commandBuilder;
    private SourceDataTransportService transportService;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private DefaultTransportPipeline pipeline;

    private static final TransportOrder ORDER = new TransportOrder(
            TransportOrder.EVENT_TYPE, 2, "iol-default", "wf-1", "legacy-unversioned", "exec-1",
            "iol-default:wf-1", "2026-08-08T10:00:00Z", "clinicien@hopital.fr", 3);

    @BeforeEach
    void setUp() {
        workflowReader = mock(WorkflowConfigReader.class);
        commandBuilder = mock(CommandBuilder.class);
        transportService = mock(SourceDataTransportService.class);

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf-1");
        workflow.setWorkflowName("Patients");
        workflow.setPriority(3);
        when(workflowReader.requireById("wf-1")).thenReturn(workflow);

        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        pipeline = new DefaultTransportPipeline(
                workflowReader, commandBuilder, transportService, kafka, new ObjectMapper());
        ReflectionTestUtils.setField(pipeline, "topicHigh", "iol.pipeline.high");
        ReflectionTestUtils.setField(pipeline, "topicDefault", "iol.pipeline.commands");
        ReflectionTestUtils.setField(pipeline, "topicLow", "iol.pipeline.low");
    }

    private Map<String, Object> commandeAvec(Map<String, Object> configSource) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "POSTGRES");
        source.put("config", configSource);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("workflowId", "wf-1");
        command.put("sources", new ArrayList<>(List.of(source)));
        return command;
    }

    @Test
    void publieLaCommandeApresLeTransport() throws Exception {
        Map<String, Object> command = commandeAvec(new LinkedHashMap<>(Map.of("uri", "s3://bucket/objet")));
        when(commandBuilder.buildCommandPayload(any(), anyString())).thenReturn(command);
        when(transportService.publishSourceData(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(Map.of("transport", "KAFKA_ROW_BATCH")));

        pipeline.run(ORDER, () -> {});

        verify(transportService).publishSourceData(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(kafka).send(anyString(), anyString(), anyString());
        assertEquals("KAFKA_ROW_BATCH", command.get("dataTransport"));
    }

    @Test
    void refuseDePublierUnMotDePasseSourceRestant() throws Exception {
        // Simule une purge defaillante: le transport laisse le mot de passe.
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", "db.hopital.local");
        config.put("password", "secret-non-purge");
        Map<String, Object> command = commandeAvec(config);
        when(commandBuilder.buildCommandPayload(any(), anyString())).thenReturn(command);
        when(transportService.publishSourceData(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(Map.of("transport", "KAFKA_ROW_BATCH")));

        IllegalStateException refus = assertThrows(
                IllegalStateException.class, () -> pipeline.run(ORDER, () -> {}));

        assertTrue(refus.getMessage().contains("Credential en clair"));
        // Le point essentiel: RIEN n'est publie.
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void detecteUnSecretImbriqueEnProfondeur() throws Exception {
        // Un identifiant peut se cacher dans une sous-configuration, pas
        // seulement a la racine de la source.
        Map<String, Object> imbrique = new LinkedHashMap<>();
        imbrique.put("api_key", "cle-qui-ne-doit-pas-sortir");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source_config", imbrique);
        when(commandBuilder.buildCommandPayload(any(), anyString())).thenReturn(commandeAvec(config));
        when(transportService.publishSourceData(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> pipeline.run(ORDER, () -> {}));
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void unEchecDeTransportNePublieRien() throws Exception {
        when(commandBuilder.buildCommandPayload(any(), anyString()))
                .thenReturn(commandeAvec(new LinkedHashMap<>()));
        when(transportService.publishSourceData(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("source injoignable"));

        assertThrows(IllegalStateException.class, () -> pipeline.run(ORDER, () -> {}));
        // Sans donnees transportees, une commande publiee ferait echouer le
        // consumer sur un artefact absent.
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void refuseUneConfigurationModifieeApresLaSoumission() {
        WorkflowConfig modified = new WorkflowConfig();
        modified.setId("wf-1");
        modified.setUpdatedAt("2026-08-08T10:01:00Z");
        when(workflowReader.requireById("wf-1")).thenReturn(modified);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> pipeline.run(ORDER, () -> {}));

        assertTrue(error.getMessage().contains("modifie"));
        verify(commandBuilder, never()).buildCommandPayload(any(), anyString());
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void laPrioriteChoisitLeTopic() throws Exception {
        when(commandBuilder.buildCommandPayload(any(), anyString()))
                .thenReturn(commandeAvec(new LinkedHashMap<>()));
        when(transportService.publishSourceData(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of());

        TransportOrder urgent = new TransportOrder(
                TransportOrder.EVENT_TYPE, 2, "iol-default", "wf-1", "legacy-unversioned", "exec-1",
                "iol-default:wf-1", "2026-08-08T10:00:00Z", "user@x.fr", 1);
        pipeline.run(urgent, () -> {});

        verify(kafka).send(org.mockito.ArgumentMatchers.eq("iol.pipeline.high"), anyString(), anyString());
    }
}
