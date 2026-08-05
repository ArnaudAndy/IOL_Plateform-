package com.iol.etlplatform.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiSqlResponse {
    private String generatedSql;
}
