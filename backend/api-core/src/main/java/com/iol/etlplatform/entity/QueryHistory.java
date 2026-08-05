package com.iol.etlplatform.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "query_history")
public class QueryHistory {
    @Id
    private String id;
    private String userPrompt;
    private String generatedSql;
    private Instant createdAt;
}
