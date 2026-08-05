package com.iol.openhim.fhir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.openhim.runtime.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FhirR4PayloadAdapterTest {

    private final FhirR4PayloadAdapter adapter = new FhirR4PayloadAdapter(new ObjectMapper());

    @Test
    void acceptsEveryResourceFromACollectionBundle() {
        String payload = """
                {
                  "resourceType":"Bundle",
                  "type":"collection",
                  "entry":[
                    {"fullUrl":"urn:uuid:1d9d23b5-eed4-4fd4-bb30-6f9e701795d3",
                     "resource":{"resourceType":"Patient","id":"p1"}},
                    {"fullUrl":"urn:uuid:4aa0e196-e97f-4e4d-bdd2-4fb77a289ca8",
                     "resource":{"resourceType":"Observation","id":"o1","status":"final",
                      "code":{"text":"Weight"}}}
                  ]
                }
                """;

        var adapted = adaptOrFail(payload);

        assertEquals(2, adapted.records().size());
        assertEquals("Patient", adapted.records().get(0).get("fhir_resource_type"));
        assertEquals("Observation", adapted.records().get(1).get("fhir_resource_type"));
    }

    @Test
    void rejectsMalformedFhir() {
        assertThrows(DomainValidationException.class, () -> adapter.adapt(
                "{\"id\":\"missing-resource-type\"}".getBytes(StandardCharsets.UTF_8),
                "application/fhir+json",
                "/",
                new HttpHeaders()));
    }

    private com.iol.openhim.runtime.AdaptedPayload adaptOrFail(String payload) {
        try {
            return adapter.adapt(
                    payload.getBytes(StandardCharsets.UTF_8),
                    "application/fhir+json",
                    "/",
                    new HttpHeaders());
        } catch (DomainValidationException error) {
            return fail(String.join(System.lineSeparator(), error.getIssues()), error);
        }
    }
}
