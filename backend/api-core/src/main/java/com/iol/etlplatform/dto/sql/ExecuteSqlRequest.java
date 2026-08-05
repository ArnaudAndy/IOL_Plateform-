package com.iol.etlplatform.dto.sql;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class ExecuteSqlRequest {
    @JsonAlias("sql")
    private String sqlScript;
    private Integer limit;
    private String connectionId;
    private String workflowId;
}
