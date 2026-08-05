package com.iol.etlplatform.util;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColumnNameSanitizerTest {

    @Test
    void testSanitizeBasicCases() {
        assertEquals("date_op", ColumnNameSanitizer.sanitize("Date_Op"));
        assertEquals("montant_total_", ColumnNameSanitizer.sanitize("Montant Total (€)"));
        assertEquals("customer_name", ColumnNameSanitizer.sanitize("Customer_Name"));
        assertEquals("id", ColumnNameSanitizer.sanitize("ID"));
    }

    @Test
    void testSanitizeAccents() {
        assertEquals("cafe", ColumnNameSanitizer.sanitize("Café"));
        assertEquals("resume", ColumnNameSanitizer.sanitize("Résumé"));
        assertEquals("nom_prenom", ColumnNameSanitizer.sanitize("Nom Prénom"));
    }

    @Test
    void testSanitizeSpecialCharacters() {
        assertEquals("customer_name_2", ColumnNameSanitizer.sanitize("Customer@Name#2"));
        assertEquals("montant_", ColumnNameSanitizer.sanitize("Montant-€$%"));
        assertEquals("data_col_", ColumnNameSanitizer.sanitize("Data.Col!"));
    }

    @Test
    void testSanitizeLeadingDigits() {
        assertEquals("c_123_column", ColumnNameSanitizer.sanitize("123_column"));
        assertEquals("c_2024", ColumnNameSanitizer.sanitize("2024"));
        assertEquals("c_9_test", ColumnNameSanitizer.sanitize("9_test"));
    }

    @Test
    void testSanitizeConsecutiveUnderscores() {
        assertEquals("a_b_c", ColumnNameSanitizer.sanitize("A___B___C"));
        assertEquals("test_column", ColumnNameSanitizer.sanitize("Test___Column"));
    }

    @Test
    void testSanitizeWhitespace() {
        assertEquals("a_b_c", ColumnNameSanitizer.sanitize("  A B C  "));
        assertEquals("test", ColumnNameSanitizer.sanitize("  test  "));
    }

    @Test
    void testSanitizeEmptyOrNull() {
        assertEquals("column", ColumnNameSanitizer.sanitize(""));
        assertEquals("column", ColumnNameSanitizer.sanitize("   "));
        assertEquals("column", ColumnNameSanitizer.sanitize(null));
    }

    @Test
    void testSanitizeComplexCases() {
        // Real-world examples
        assertEquals("date_operation", ColumnNameSanitizer.sanitize("Date Opération"));
        assertEquals("montant_total_ttc", ColumnNameSanitizer.sanitize("Montant Total TTC"));
        assertEquals("n_facture", ColumnNameSanitizer.sanitize("N° Facture"));
        assertEquals("statut_paiement", ColumnNameSanitizer.sanitize("Statut Paiement"));
    }

    @Test
    void testSanitizeAll() {
        List<String> input = Arrays.asList("Date Op", "Montant Total (€)", "ID", "123_column");
        List<String> expected = Arrays.asList("date_op", "montant_total_", "id", "c_123_column");
        List<String> result = ColumnNameSanitizer.sanitizeAll(input);
        assertEquals(expected, result);
    }

    @Test
    void testSanitizeAllEmpty() {
        List<String> result = ColumnNameSanitizer.sanitizeAll(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testLowercaseOutput() {
        String result = ColumnNameSanitizer.sanitize("UPPERCASE_COLUMN");
        assertTrue(result.chars().allMatch(c -> !Character.isUpperCase(c)),
            "Output should be lowercase");
    }

    @Test
    void testUnderscoreOnly() {
        String result = ColumnNameSanitizer.sanitize("___");
        assertEquals("column", result);
    }
}
