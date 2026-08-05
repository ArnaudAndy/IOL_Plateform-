package com.iol.etlplatform.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Centralised column name sanitisation utility.
 *
 * CRITICAL SYNC POINT: This logic MUST remain byte-for-byte synchronised with moteur_universel.py clean_column_name().
 * Any change here requires equivalent change in Python immediately.
 * Both methods MUST produce identical output for all test cases.
 * Test case: "Montant Total (€)" → "montant_total_"
 *
 * Location mapping:
 * - Java: com.iol.etlplatform.util.ColumnNameSanitizer.sanitize()
 * - Python: moteur_universel.py clean_column_name()
 */
public class ColumnNameSanitizer {

    private static final Pattern NON_ASCII_PATTERN = Pattern.compile("[^\\x00-\\x7F]");

    /**
     * Sanitises a single column name to match Bronze layer schema.
     *
     * Steps:
     * 1. Convert to ASCII (remove accents: é → e)
     * 2. Keep only [a-zA-Z0-9_], replace others with underscore or remove
     * 3. Convert to lowercase
     * 4. Prefix with 'c_' if starts with digit
     *
     * Examples:
     * - "Date_Op" → "date_op"
     * - "Montant Total (€)" → "montant_total_"
     * - "123_column" → "c_123_column"
     * - "Customer@Name#2" → "customer_name_2"
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "column";
        }

        String trimmed = raw.trim();

        // Step 1: Normalize to ASCII (decompose and remove diacritics)
        String normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD);
        String ascii = NON_ASCII_PATTERN.matcher(normalized).replaceAll("");

        // Step 2: Keep only [a-zA-Z0-9_], replace others with underscore
        String sanitised = ascii.replaceAll("[^a-zA-Z0-9_]", "_");

        // Remove leading underscores only, collapse consecutive underscores
        sanitised = sanitised.replaceAll("^_+", "");
        sanitised = sanitised.replaceAll("_+", "_");

        // Step 3: Convert to lowercase
        sanitised = sanitised.toLowerCase();

        // Handle empty result after sanitisation
        if (sanitised.isBlank()) {
            return "column";
        }

        // Step 4: Prefix with 'c_' if starts with digit
        if (Character.isDigit(sanitised.charAt(0))) {
            sanitised = "c_" + sanitised;
        }

        return sanitised;
    }

    /**
     * Sanitises a list of column names.
     */
    public static List<String> sanitizeAll(List<String> names) {
        List<String> result = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                result.add(sanitize(name));
            }
        }
        return result;
    }
}
