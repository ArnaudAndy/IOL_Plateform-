package com.iol.etlplatform.dto.workflow;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FieldMappingDto {
    // Core fields
    @JsonProperty("sourceName")
    @JsonAlias("source_name")
    private String sourceName;
    private String type;
    @JsonProperty("iolTerm")
    @JsonAlias("iol_term")
    private String iolTerm;
    @JsonProperty("cleaningRules")
    @JsonAlias("cleaning_rules")
    private Map<String, Object> cleaningRules;

    // Enhanced mapping (Module 5)
    @JsonProperty("mappingType")
    @JsonAlias("mapping_type")
    private String mappingType;  // DIRECT | COMPUTED | COALESCE | SPLIT

    @JsonProperty("sourceFields")
    @JsonAlias("source_fields")
    private List<String> sourceFields;  // Cleaned column names involved

    private String expression;  // SQL expression for COMPUTED types
}
