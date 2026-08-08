package com.iol.etlplatform.sourcegateway.readmodel;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents a single source within a multi-source ETL workflow.
 *
 * Une source produit UNIQUEMENT sa table Silver nettoyée (cln_*). Le Gold est
 * unique au niveau workflow ({@link GoldConfigGlobal}) — il n'y a PLUS de
 * gold_config par source.
 *
 * Structure aligns with JSON format:
 * {
 *   "source_name": "CSV|POSTGRES|EXCEL|PARQUET|...",
 *   "config": {
 *     "file_path|host": "...",
 *     "target_table": "stg_...",
 *     "target_connection": { ... },
 *     "source_config": { ... },
 *     "fields": [ ... ],
 *     "silver_config": { "target_table_silver": "...", "elt_scripts_silver": "..." }
 *     // PAS de gold_config ici — le Gold est défini au niveau workflow
 *   }
 * }
 */
public class SourceDefinition {

    @JsonProperty("source_name")
    private String sourceName;  // Type: CSV, POSTGRES, EXCEL, ORACLE, PARQUET, etc.

    private Map<String, Object> config;  // Flexible: contains file_path/uri, target_table, connections, fields, silver/gold configs

    // Getters and Setters
    public String getSourceName() {
        return this.sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public Map<String, Object> getConfig() {
        return this.config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }
}
