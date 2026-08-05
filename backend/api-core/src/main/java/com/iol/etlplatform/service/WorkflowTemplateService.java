package com.iol.etlplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.dto.workflow.WorkflowTemplateDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowTemplateService {

    private final ObjectMapper objectMapper;
    private List<WorkflowTemplateDto> templates = List.of();

    @PostConstruct
    void loadTemplates() {
        try (InputStream input = new ClassPathResource("workflow-templates.json").getInputStream()) {
            List<WorkflowTemplateDto> loaded = objectMapper.readValue(
                    input, new TypeReference<List<WorkflowTemplateDto>>() {});
            validate(loaded);
            templates = List.copyOf(loaded);
        } catch (Exception exception) {
            throw new IllegalStateException("Impossible de charger les modeles de workflows.", exception);
        }
    }

    public List<WorkflowTemplateDto> findAll() {
        return templates;
    }

    private void validate(List<WorkflowTemplateDto> loaded) {
        if (loaded == null || loaded.isEmpty()) {
            throw new IllegalStateException("Aucun modele de workflow n'est configure.");
        }
        Set<String> ids = new HashSet<>();
        for (WorkflowTemplateDto template : loaded) {
            if (template.getId() == null || template.getId().isBlank()
                    || template.getName() == null || template.getName().isBlank()
                    || template.getVersion() == null || template.getVersion().isBlank()
                    || template.getWorkflow() == null) {
                throw new IllegalStateException("Un modele de workflow est incomplet.");
            }
            if (!ids.add(template.getId())) {
                throw new IllegalStateException("Identifiant de modele duplique: " + template.getId());
            }
            if (template.getWorkflow().getDestinationConnectionId() != null
                    || template.getWorkflow().getCreatedBy() != null) {
                throw new IllegalStateException(
                        "Un modele ne doit contenir ni destinationConnectionId ni createdBy: " + template.getId());
            }
            template.getWorkflow().setId(null);
            template.getWorkflow().setActive(false);
            template.getWorkflow().setExecutionMode(null);
        }
    }
}
