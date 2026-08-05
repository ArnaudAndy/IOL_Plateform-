package com.iol.etlplatform.dto.standard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandardValidationBatchRequest {

    @Valid
    @NotEmpty(message = "La liste des champs est obligatoire")
    private List<FieldValidationRequest> fields;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FieldValidationRequest {
        @NotBlank(message = "Le nom du champ est obligatoire")
        private String fieldName;

        private Object fieldValue;

        @NotBlank(message = "Le type de donnees est obligatoire")
        private String dataType;
    }
}
