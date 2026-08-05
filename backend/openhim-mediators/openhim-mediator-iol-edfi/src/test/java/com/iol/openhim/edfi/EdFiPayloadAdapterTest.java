package com.iol.openhim.edfi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.openhim.runtime.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EdFiPayloadAdapterTest {

    private final EdFiPayloadAdapter adapter =
            new EdFiPayloadAdapter(new ObjectMapper(), "6.1.0", "8", 1000);

    @Test
    void acceptsJsonArraysAsBulkResources() {
        String payload = """
                [
                  {"studentUniqueId":"S001","firstName":"Ada","lastSurname":"Lovelace"},
                  {"studentUniqueId":"S002","firstName":"Grace","lastSurname":"Hopper"}
                ]
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-EdFi-Resource", "students");

        var adapted = adapter.adapt(
                payload.getBytes(StandardCharsets.UTF_8),
                "application/json",
                "/",
                headers);

        assertEquals(2, adapted.records().size());
        assertEquals("students", adapted.records().get(0).get("edfi_resource"));
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(adapted.records().get(0).get("edfi_payload_json"))
                        .contains("\"studentUniqueId\":\"S001\""));
    }

    @Test
    void rejectsInvalidEdFiIdentifiers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-EdFi-Resource", "students");

        assertThrows(DomainValidationException.class, () -> adapter.adapt(
                "{\"id\":\"not-a-uuid\"}".getBytes(StandardCharsets.UTF_8),
                "application/json",
                "/",
                headers));
    }
}
