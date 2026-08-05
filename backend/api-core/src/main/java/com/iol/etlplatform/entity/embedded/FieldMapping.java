package com.iol.etlplatform.entity.embedded;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * FieldMapping represents the mapping from source columns to IOL standard terms.
 *
 * Supports multiple mapping types:
 * - DIRECT: 1:1 mapping (source column → IOL term)
 * - COMPUTED: Expression-based (multiple sources → 1 target via SQL expression)
 * - COALESCE: NULL-handling (prefer first non-null source)
 * - SPLIT: 1:N mapping (one source → multiple targets)
 */
@Data
public class FieldMapping {
    // Core fields (existing)
    private String sourceName;
    private String type;
    private String iolTerm;
    private Map<String, Object> cleaningRules;

    // Enhanced mapping (NEW - Module 5)
    /**
     * Type of mapping: DIRECT (default), COMPUTED, COALESCE, SPLIT.
     * Purely declarative - describes the intent, not the execution.
     */
    private String mappingType;

    /**
     * Source columns involved in this mapping (for COMPUTED, COALESCE, SPLIT).
     * Contains cleaned column names as they appear in Bronze.
     */
    private List<String> sourceFields;

    /**
     * SQL expression for computed mappings.
     * Example: "CONCAT(nom,' ',prenom)" or "COALESCE(col1, col2, col3)"
     * Optional - only used when mappingType is COMPUTED or special logic needed.
     */
    private String expression;
}
