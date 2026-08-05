package com.iol.etlplatform.dto.workflow;

import lombok.Data;

@Data
public class ValidateSqlResponse {
    private boolean valid;
    private String message;
}
