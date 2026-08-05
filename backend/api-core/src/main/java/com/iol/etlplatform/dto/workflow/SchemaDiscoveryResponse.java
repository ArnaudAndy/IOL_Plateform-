package com.iol.etlplatform.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class SchemaDiscoveryResponse {
    private String sourceName;
    private Map<String, List<String>> tables;
}
