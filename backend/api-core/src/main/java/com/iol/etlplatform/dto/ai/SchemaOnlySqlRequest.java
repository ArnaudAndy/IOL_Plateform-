package com.iol.etlplatform.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SchemaOnlySqlRequest {
    @NotBlank
    @Size(max = 4000)
    private String instruction;

    @NotEmpty
    @Size(max = 200)
    private List<@NotBlank @Size(max = 128) String> columns;

    @Size(max = 160)
    private String sourceTable;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 160) String> sourceTables;

    @Size(max = 160)
    private String targetTable;

    @Size(max = 80)
    private String workflowId;

    @Size(max = 80)
    private String destinationConnectionId;

    @Size(max = 32)
    private String databaseType;

    private GenerationType generationType = GenerationType.CUSTOM;

    public enum GenerationType {
        SELECT, CLEANING, AGGREGATION, MAPPING, CUSTOM
    }
}
