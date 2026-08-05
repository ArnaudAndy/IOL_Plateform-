package com.iol.etlplatform.dto.workflow;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.iol.etlplatform.entity.embedded.SourceDefinition;
import com.iol.etlplatform.entity.embedded.GoldConfigGlobal;
import com.iol.etlplatform.entity.enums.WorkflowDirection;

import lombok.Data;

@Data
public class WorkflowConfigDto {
    private String id;
    @JsonProperty("workflowName")
    @JsonAlias("workflow_name")
    private String workflowName;
    private String description;
    private String protocol;
    @JsonProperty("standardDomain")
    @JsonAlias("standard_domain")
    private String standardDomain;

    // Référence typée (nullable) vers Standard.id — source de vérité du rattachement.
    @JsonProperty("standardId")
    @JsonAlias("standard_id")
    private String standardId;

    // Sens du workflow : INTERNAL (défaut), INBOUND ou OUTBOUND.
    // Absent à la désérialisation => INTERNAL.
    @JsonProperty("direction")
    @JsonAlias("direction")
    private WorkflowDirection direction = WorkflowDirection.INTERNAL;

    // Configuration de livraison OUTBOUND, nullable et requise seulement si direction=OUTBOUND.
    @JsonProperty("outboundConfig")
    @JsonAlias({"outbound_config", "outboundConfig"})
    private Map<String, Object> outboundConfig;
    @JsonProperty("metadataFileName")
    @JsonAlias("metadata_file_name")
    private String metadataFileName;
    @JsonProperty("metadataJson")
    @JsonAlias("metadata_json")
    private String metadataJson;
    @JsonProperty("metadataVersion")
    @JsonAlias("metadata_version")
    private String metadataVersion;
    private Map<String, Object> schedule;

    // Multi-source architecture (NEW)
    private List<SourceDefinition> sources;

    // Global Gold configuration (NEW)
    @JsonProperty("gold_config_global")
    @JsonAlias("gold_config_global")
    private GoldConfigGlobal goldConfigGlobal;

    // Destination au niveau workflow (référence DestinationConnection.id)
    @JsonProperty("destinationConnectionId")
    @JsonAlias("destination_connection_id")
    private String destinationConnectionId;

    // LEGACY fields (kept for backward compatibility)
    @JsonProperty("sourceConfig")
    @JsonAlias("source_config")
    private Map<String, Object> sourceConfig;
    private List<FieldMappingDto> fields;
    @JsonProperty("aggregationScripts")
    @JsonAlias("aggregation_scripts")
    private String aggregationScripts;
    @JsonProperty("executionMode")
    @JsonAlias("execution_mode")
    private String executionMode;
    private int priority = 3;
    @JsonProperty("estimatedRows")
    @JsonAlias("estimated_rows")
    private Long estimatedRows;
    private boolean isActive;
    private String createdBy;
}
