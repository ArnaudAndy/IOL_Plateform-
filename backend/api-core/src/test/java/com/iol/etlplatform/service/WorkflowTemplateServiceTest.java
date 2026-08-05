package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowTemplateServiceTest {

    @Test
    void loadsVersionedTemplatesWithoutRuntimeBinding() {
        WorkflowTemplateService service = new WorkflowTemplateService(new ObjectMapper().findAndRegisterModules());

        service.loadTemplates();

        assertFalse(service.findAll().isEmpty());
        service.findAll().forEach(template -> {
            assertNull(template.getWorkflow().getId());
            assertNull(template.getWorkflow().getCreatedBy());
            assertNull(template.getWorkflow().getDestinationConnectionId());
            assertNull(template.getWorkflow().getExecutionMode());
        });
    }
}
