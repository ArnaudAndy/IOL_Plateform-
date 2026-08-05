package com.iol.openhim.iso20022;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.openhim.runtime.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Iso20022PayloadAdapterTest {

    private final Iso20022PayloadAdapter adapter =
            new Iso20022PayloadAdapter(new ObjectMapper(), "pain,pacs,camt", 1024 * 1024);

    @Test
    void identifiesAnIso20022Message() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.11">
                  <CstmrCdtTrfInitn>
                    <GrpHdr>
                      <MsgId>MSG-001</MsgId>
                      <CreDtTm>2026-07-30T12:00:00Z</CreDtTm>
                      <NbOfTxs>0</NbOfTxs>
                      <InitgPty><Nm>IOL</Nm></InitgPty>
                    </GrpHdr>
                  </CstmrCdtTrfInitn>
                </Document>
                """;

        var adapted = adapter.adapt(
                xml.getBytes(StandardCharsets.UTF_8),
                "application/xml",
                "/",
                new HttpHeaders());

        assertEquals(1, adapted.records().size());
        assertEquals("pain.001.001.11",
                adapted.records().get(0).get("iso20022_message_definition_id"));
    }

    @Test
    void blocksDoctypeAndExternalEntities() {
        String xml = """
                <!DOCTYPE Document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.11">&xxe;</Document>
                """;
        assertThrows(DomainValidationException.class, () -> adapter.adapt(
                xml.getBytes(StandardCharsets.UTF_8),
                "application/xml",
                "/",
                new HttpHeaders()));
    }
}
