package com.iol.etlplatform.dto.ai;

import lombok.Data;

@Data
public class ChatRequest {
    private String workflowId;
    private String page; // Mapping, SQL-Workbench, etc.
    private String message;
}
