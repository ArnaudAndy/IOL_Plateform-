package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.standard.StandardDto;
import com.iol.etlplatform.dto.standard.StandardTermDto;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.entity.StandardTerm;
import com.iol.etlplatform.repository.StandardRepository;
import com.iol.etlplatform.repository.StandardTermRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Vérifie que StandardService est de nouveau opérationnel (fin du stub Lombok) :
 * création d'un Standard, ajout d'un StandardTerm, relecture, et validation d'un champ.
 * Tests unitaires purs (repos mockés) — aucun contexte Spring / Mongo.
 */
class StandardServiceTest {

    private final StandardRepository standardRepository = mock(StandardRepository.class);
    private final StandardTermRepository standardTermRepository = mock(StandardTermRepository.class);
    private final StandardService service = new StandardService(standardRepository, standardTermRepository);

    private StandardTerm term(String name, StandardTerm.DataType type,
                              Integer min, Integer max, String format, List<String> enums) {
        return StandardTerm.builder()
                .id("t_" + name)
                .standardId("std_1")
                .termName(name)
                .dataType(type)
                .minLength(min)
                .maxLength(max)
                .formatRule(format)
                .enumValues(enums)
                .build();
    }

    @Test
    void createStandardPersistsAsDraftAndReturnsDto() {
        when(standardRepository.save(any(Standard.class))).thenAnswer(inv -> inv.getArgument(0));

        StandardDto input = StandardDto.builder()
                .name("HL7v2")
                .domain(Standard.StandardDomain.HEALTH)
                .version("2.5")
                .build();

        StandardDto created = service.createStandard(input, "admin@iol");

        assertNotNull(created.getId(), "Un id doit être généré");
        assertEquals("HL7v2", created.getName());
        assertEquals(Standard.StandardStatus.DRAFT, created.getStatus(), "Un nouveau standard démarre en DRAFT");
        assertEquals("admin@iol", created.getCreatedBy());
        verify(standardRepository).save(any(Standard.class));
    }

    @Test
    void addTermThenReadItBack() {
        Standard std = Standard.builder().id("std_1").name("HL7v2")
                .domain(Standard.StandardDomain.HEALTH).status(Standard.StandardStatus.ACTIVE).build();
        when(standardRepository.findById("std_1")).thenReturn(Optional.of(std));
        when(standardTermRepository.findByStandardIdAndTermName("std_1", "PATIENT_ID")).thenReturn(Optional.empty());
        when(standardTermRepository.save(any(StandardTerm.class))).thenAnswer(inv -> inv.getArgument(0));
        when(standardTermRepository.countByStandardId("std_1")).thenReturn(1L);

        StandardTermDto termDto = StandardTermDto.builder()
                .termName("PATIENT_ID")
                .dataType(StandardTerm.DataType.STRING)
                .minLength(3)
                .maxLength(10)
                .build();

        StandardTermDto saved = service.addTermToStandard("std_1", termDto);
        assertEquals("PATIENT_ID", saved.getTermName());
        assertEquals("std_1", saved.getStandardId());
        assertNotNull(saved.getId());

        // Relecture des termes du standard
        when(standardTermRepository.findByStandardId("std_1"))
                .thenReturn(List.of(term("PATIENT_ID", StandardTerm.DataType.STRING, 3, 10, null, null)));
        List<StandardTermDto> terms = service.getTermsByStandard("std_1");
        assertEquals(1, terms.size());
        assertEquals("PATIENT_ID", terms.get(0).getTermName());
    }

    @Test
    void getStandardByIdReturnsStandardWithTerms() {
        Standard std = Standard.builder().id("std_1").name("HL7v2")
                .domain(Standard.StandardDomain.HEALTH).status(Standard.StandardStatus.ACTIVE).build();
        when(standardRepository.findById("std_1")).thenReturn(Optional.of(std));
        when(standardTermRepository.findByStandardId("std_1"))
                .thenReturn(List.of(term("SSN", StandardTerm.DataType.STRING, null, null, null, null)));

        StandardDto dto = service.getStandardById("std_1");
        assertEquals("HL7v2", dto.getName());
        assertEquals(1, dto.getTerms().size());
        assertEquals("SSN", dto.getTerms().get(0).getTermName());
    }

    @Test
    void updateStandardPersistsDomainAndDescriptiveFields() {
        Standard existing = Standard.builder()
                .id("std_1")
                .name("Norme initiale")
                .domain(Standard.StandardDomain.CUSTOM)
                .status(Standard.StandardStatus.DRAFT)
                .build();
        when(standardRepository.findById("std_1")).thenReturn(Optional.of(existing));
        when(standardRepository.save(any(Standard.class))).thenAnswer(inv -> inv.getArgument(0));
        when(standardTermRepository.findByStandardId("std_1")).thenReturn(List.of());

        StandardDto changes = StandardDto.builder()
                .name("Norme finance")
                .domain(Standard.StandardDomain.FINANCE)
                .description("Contrôles comptables")
                .referenceUrl("https://example.test/finance")
                .build();

        StandardDto updated = service.updateStandard("std_1", changes);

        assertEquals(Standard.StandardDomain.FINANCE, updated.getDomain());
        assertEquals("Contrôles comptables", updated.getDescription());
        assertEquals("https://example.test/finance", updated.getReferenceUrl());
        verify(standardRepository).save(existing);
    }

    @Test
    void validateFieldAgainstStandard_ok() {
        when(standardTermRepository.findByStandardIdAndTermName("std_1", "code"))
                .thenReturn(Optional.of(term("code", StandardTerm.DataType.STRING, 2, 5, "[a-z]+", null)));

        assertTrue(service.validateFieldAgainstStandard("std_1", "code", "abc", "STRING"));
    }

    @Test
    void validateFieldAgainstStandard_ko_cases() {
        StandardTerm t = term("code", StandardTerm.DataType.STRING, 2, 5, "[a-z]+", null);
        when(standardTermRepository.findByStandardIdAndTermName("std_1", "code")).thenReturn(Optional.of(t));

        assertFalse(service.validateFieldAgainstStandard("std_1", "code", "abc", "INTEGER"), "type incorrect");
        assertFalse(service.validateFieldAgainstStandard("std_1", "code", "a", "STRING"), "trop court");
        assertFalse(service.validateFieldAgainstStandard("std_1", "code", "abcdef", "STRING"), "trop long");
        assertFalse(service.validateFieldAgainstStandard("std_1", "code", "AB", "STRING"), "format invalide");

        when(standardTermRepository.findByStandardIdAndTermName("std_1", "status"))
                .thenReturn(Optional.of(term("status", StandardTerm.DataType.STRING, null, null, null, List.of("X", "Y"))));
        assertFalse(service.validateFieldAgainstStandard("std_1", "status", "Z", "STRING"), "hors enum");
        assertTrue(service.validateFieldAgainstStandard("std_1", "status", "X", "STRING"), "dans enum");
    }

    @Test
    void denormalizeFromPivotUsesTargetSystemMappings() {
        when(standardRepository.existsById("std_1")).thenReturn(true);
        StandardTerm patientId = term("patient_id", StandardTerm.DataType.STRING, null, null, null, null);
        patientId.setSystemMappings(Map.of("fhir", "identifier", "generic-json", "patientId"));
        StandardTerm fullName = term("full_name", StandardTerm.DataType.STRING, null, null, null, null);
        fullName.setSystemMappings(Map.of("fhir", "name_text"));
        when(standardTermRepository.findByStandardId("std_1")).thenReturn(List.of(patientId, fullName));

        List<Map<String, Object>> rows = service.denormalizeFromPivot(
                "std_1",
                List.of(Map.of(
                        "patient_id", "P001",
                        "full_name", "Ada Lovelace",
                        "ignored_extra", "not delivered")),
                "fhir");

        assertEquals(1, rows.size());
        assertEquals(Map.of(
                "identifier", "P001",
                "name_text", "Ada Lovelace"), rows.get(0));
    }
}
