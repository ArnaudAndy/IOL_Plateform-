package com.iol.etlplatform.dto.workflow;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class ValidateSqlRequest {
    @JsonAlias("sql")
    private String sqlScript;
    private String workflowStep; // SILVER | GOLD
    private String expectedSourceTable;
}
