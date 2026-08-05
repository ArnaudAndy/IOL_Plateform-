package com.iol.etlplatform.dto.standard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandardValidationBatchResponse {

    private boolean valid;
    private List<FieldValidationResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldValidationResult {
        private String fieldName;
        private String dataType;
        private boolean valid;
        private String message;
    }
}
