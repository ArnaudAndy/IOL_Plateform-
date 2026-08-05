package com.iol.etlplatform.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WorkflowExportResponse {
    private String workflowId;
    private String workflowJson;
}
