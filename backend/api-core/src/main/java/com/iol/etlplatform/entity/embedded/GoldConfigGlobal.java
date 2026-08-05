package com.iol.etlplatform.entity.embedded;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Le Gold UNIQUE du workflow (configuration d'agrégation au niveau workflow).
 *
 * C'est le SQL de fusion qui consolide les tables Silver (cln_*) de toutes les
 * sources en une seule table Gold décisionnelle (agrégation ou JOIN).
 * Il existe au plus UN Gold par workflow — jamais de Gold par source.
 *
 * Note: la clé JSON reste "gold_config_global" pour compatibilité avec le code
 * et Hop existants, même s'il représente désormais le Gold unique du workflow.
 *
 * Example:
 * {
 *   "target_table_gold": "gold.fusion_dgi_impots",
 *   "elt_scripts_gold": "DROP TABLE IF EXISTS gold.fusion_dgi_impots; CREATE TABLE gold.fusion_dgi_impots AS SELECT ... JOIN ..."
 * }
 */
public class GoldConfigGlobal {

    /**
     * Null/absent in legacy documents means enabled when SQL is present. New
     * workflows always persist the explicit value.
     */
    private Boolean enabled = Boolean.TRUE;

    /** SILVER (default) or BRONZE when cleaning is intentionally bypassed. */
    @JsonProperty("input_layer")
    private String inputLayer = "SILVER";

    @JsonProperty("target_table_gold")
    private String targetTableGold;

    @JsonProperty("elt_scripts_gold")
    private String eltScriptsGold;

    @JsonProperty("execution_engine")
    private String executionEngine = "SQL";

    @JsonProperty("spark_sql")
    private String sparkSql;

    @JsonProperty("pre_sql")
    private String preSql;

    @JsonProperty("post_sql")
    private String postSql;

    private List<Map<String, Object>> indexes;

    public Boolean getEnabled() {
        return this.enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getInputLayer() {
        return this.inputLayer;
    }

    public void setInputLayer(String inputLayer) {
        this.inputLayer = inputLayer;
    }

    // Getters and Setters
    public String getTargetTableGold() {
        return this.targetTableGold;
    }

    public void setTargetTableGold(String targetTableGold) {
        this.targetTableGold = targetTableGold;
    }

    public String getEltScriptsGold() {
        return this.eltScriptsGold;
    }

    public void setEltScriptsGold(String eltScriptsGold) {
        this.eltScriptsGold = eltScriptsGold;
    }

    public String getExecutionEngine() {
        return executionEngine;
    }

    public void setExecutionEngine(String executionEngine) {
        this.executionEngine = executionEngine;
    }

    public String getSparkSql() {
        return sparkSql;
    }

    public void setSparkSql(String sparkSql) {
        this.sparkSql = sparkSql;
    }

    public String getPreSql() {
        return preSql;
    }

    public void setPreSql(String preSql) {
        this.preSql = preSql;
    }

    public String getPostSql() {
        return postSql;
    }

    public void setPostSql(String postSql) {
        this.postSql = postSql;
    }

    public List<Map<String, Object>> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<Map<String, Object>> indexes) {
        this.indexes = indexes;
    }
}
