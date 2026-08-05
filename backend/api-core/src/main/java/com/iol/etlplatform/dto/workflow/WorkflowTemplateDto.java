package com.iol.etlplatform.dto.workflow;

import lombok.Data;

@Data
public class WorkflowTemplateDto {
    private String id;
    private String name;
    private String description;
    private String category;
    private String version;
    private WorkflowConfigDto workflow;
}
