package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.dto.workflow.WorkflowConfigDto;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.mapper.WorkflowConfigMapper;
import com.iol.etlplatform.repository.StandardRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Socle interopérabilité (phase 3) : persistance de `direction` (défaut INTERNAL),
 * du lien `standardId`, et validation du rattachement Standard (rejet 400).
 */
class WorkflowConfigDirectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowConfigMapper mapper = Mappers.getMapper(WorkflowConfigMapper.class);

    // ---- Jackson : défaut INTERNAL quand absent, INBOUND/OUTBOUND quand fourni ----

    @Test
    void directionDefaultsToInternalWhenAbsentInJson() throws Exception {
        WorkflowConfigDto dto = objectMapper.readValue(
                "{\"workflowName\":\"wf\",\"protocol\":\"POSTGRES\"}", WorkflowConfigDto.class);
        assertEquals(WorkflowDirection.INTERNAL, dto.getDirection());
        assertNull(dto.getStandardId(), "standardId reste nullable par défaut");
    }

    @Test
    void directionInboundIsDeserialized() throws Exception {
        WorkflowConfigDto dto = objectMapper.readValue(
                "{\"workflowName\":\"wf\",\"direction\":\"INBOUND\",\"standardId\":\"std_1\"}",
                WorkflowConfigDto.class);
        assertEquals(WorkflowDirection.INBOUND, dto.getDirection());
        assertEquals("std_1", dto.getStandardId());
    }

    @Test
    void directionOutboundIsDeserializedWithConfig() throws Exception {
        WorkflowConfigDto dto = objectMapper.readValue(
                """
                {
                  "workflowName":"wf",
                  "direction":"OUTBOUND",
                  "outboundConfig":{
                    "targetStandardId":"std_target",
                    "targetAdapter":"generic-json",
                    "source":{"goldTable":"gold.clients"},
                    "destination":{"openhimChannel":"outbound-client"}
                  }
                }
                """,
                WorkflowConfigDto.class);

        assertEquals(WorkflowDirection.OUTBOUND, dto.getDirection());
        assertEquals("std_target", dto.getOutboundConfig().get("targetStandardId"));
    }

    // ---- Mapper : direction + standardId mappés dans les deux sens ----

    @Test
    void mapperCarriesDirectionAndStandardIdBothWays() {
        WorkflowConfigDto dto = new WorkflowConfigDto();
        dto.setWorkflowName("wf");
        dto.setDirection(WorkflowDirection.INBOUND);
        dto.setStandardId("std_42");
        dto.setOutboundConfig(Map.of("targetAdapter", "generic-json"));

        WorkflowConfig entity = mapper.toEntity(dto);
        assertEquals(WorkflowDirection.INBOUND, entity.getDirection());
        assertEquals("std_42", entity.getStandardId());
        assertEquals("generic-json", entity.getOutboundConfig().get("targetAdapter"));

        WorkflowConfigDto back = mapper.toDto(entity);
        assertEquals(WorkflowDirection.INBOUND, back.getDirection());
        assertEquals("std_42", back.getStandardId());
        assertEquals("generic-json", back.getOutboundConfig().get("targetAdapter"));
    }

    // ---- WorkflowService : validation du rattachement Standard (400) ----

    private WorkflowService serviceWith(StandardRepository standardRepository,
                                        WorkflowConfigRepository repo,
                                        WorkflowConfigMapper wfMapper) {
        return new WorkflowService(repo, wfMapper, objectMapper,
                mock(DiscoveryService.class), mock(OrchestrationService.class),
                mock(com.iol.etlplatform.service.scheduler.PipelineSchedulerService.class),
                mock(DestinationConnectionService.class), standardRepository,
                mock(com.iol.etlplatform.util.SqlSafetyValidator.class), mock(ApiSourceClient.class));
    }

    private WorkflowConfigDto validDtoWithStandard(String standardId) {
        WorkflowConfigDto dto = new WorkflowConfigDto();
        dto.setWorkflowName("wf");
        dto.setProtocol("POSTGRES");
        dto.setStandardId(standardId);
        return dto;
    }

    @Test
    void createRejectsUnknownStandardId() {
        StandardRepository standardRepository = mock(StandardRepository.class);
        when(standardRepository.findById("missing")).thenReturn(Optional.empty());
        WorkflowService service = serviceWith(standardRepository,
                mock(WorkflowConfigRepository.class), mapper);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.create(validDtoWithStandard("missing")));
        assertTrue(ex.getMessage().contains("introuvable"));
    }

    @Test
    void createRejectsNonActiveStandard() {
        StandardRepository standardRepository = mock(StandardRepository.class);
        Standard deprecated = Standard.builder().id("std_dep")
                .status(Standard.StandardStatus.DEPRECATED).build();
        when(standardRepository.findById("std_dep")).thenReturn(Optional.of(deprecated));
        WorkflowService service = serviceWith(standardRepository,
                mock(WorkflowConfigRepository.class), mapper);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.create(validDtoWithStandard("std_dep")));
        assertTrue(ex.getMessage().contains("non ACTIVE"));
    }

    @Test
    void createAcceptsActiveStandardAndPersistsDirection() {
        StandardRepository standardRepository = mock(StandardRepository.class);
        Standard active = Standard.builder().id("std_ok")
                .status(Standard.StandardStatus.ACTIVE).build();
        when(standardRepository.findById("std_ok")).thenReturn(Optional.of(active));

        WorkflowConfigRepository repo = mock(WorkflowConfigRepository.class);
        when(repo.save(any(WorkflowConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowService service = serviceWith(standardRepository, repo, mapper);

        WorkflowConfigDto dto = validDtoWithStandard("std_ok");
        dto.setDirection(WorkflowDirection.INBOUND);

        WorkflowConfigDto result = assertDoesNotThrow(() -> service.create(dto));
        assertEquals("std_ok", result.getStandardId());
        assertEquals(WorkflowDirection.INBOUND, result.getDirection());
        verify(standardRepository).findById("std_ok");

        // Le défaut INTERNAL est appliqué quand la direction n'est pas fournie
        WorkflowConfigDto internalDto = validDtoWithStandard("std_ok");
        internalDto.setDirection(null);
        WorkflowConfigDto internalResult = service.create(internalDto);
        assertEquals(WorkflowDirection.INTERNAL, internalResult.getDirection());
    }

    @Test
    void createRejectsOutboundWithoutDeliveryConfig() {
        WorkflowService service = serviceWith(mock(StandardRepository.class),
                mock(WorkflowConfigRepository.class), mapper);

        WorkflowConfigDto dto = validDtoWithStandard(null);
        dto.setDirection(WorkflowDirection.OUTBOUND);

        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.create(dto));
        assertTrue(ex.getMessage().contains("outboundConfig"));
    }

    @Test
    void createAcceptsOutboundWithDeliveryConfig() {
        StandardRepository standardRepository = mock(StandardRepository.class);
        Standard active = Standard.builder().id("std_target")
                .status(Standard.StandardStatus.ACTIVE).build();
        when(standardRepository.findById("std_target")).thenReturn(Optional.of(active));

        WorkflowConfigRepository repo = mock(WorkflowConfigRepository.class);
        when(repo.save(any(WorkflowConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowService service = serviceWith(standardRepository, repo, mapper);
        WorkflowConfigDto dto = validDtoWithStandard(null);
        dto.setDirection(WorkflowDirection.OUTBOUND);
        dto.setOutboundConfig(Map.of(
                "targetStandardId", "std_target",
                "targetSystem", "hospital_b",
                "targetAdapter", "generic-json",
                "source", Map.of("goldTable", "gold.clients"),
                "destination", Map.of("openhimChannel", "outbound-client")));

        WorkflowConfigDto result = assertDoesNotThrow(() -> service.create(dto));

        assertEquals(WorkflowDirection.OUTBOUND, result.getDirection());
        assertEquals("std_target", result.getOutboundConfig().get("targetStandardId"));
    }
}
