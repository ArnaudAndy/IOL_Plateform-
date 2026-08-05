package com.iol.etlplatform.dto.ai;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiSqlRequest {
    @NotBlank
    private String workflowId;
    @NotBlank
    private String userPrompt;
}
