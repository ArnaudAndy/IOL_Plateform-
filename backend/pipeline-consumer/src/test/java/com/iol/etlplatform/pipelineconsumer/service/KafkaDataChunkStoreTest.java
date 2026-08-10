package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KafkaDataChunkStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reconstructsOrderedChunksAndVerifiesIntegrity(@TempDir Path tempDir) throws Exception {
        KafkaDataChunkStore store = new KafkaDataChunkStore();
        ReflectionTestUtils.setField(store, "tempDir", tempDir.toString());
        String transferId = "transfer-123";
        byte[] first = "patient_id\nP001\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "P002\n".getBytes(StandardCharsets.UTF_8);

        store.accept(chunk(transferId, 0, 2, first));
        store.accept(chunk(transferId, 1, 2, second));

        byte[] expected = "patient_id\nP001\nP002\n".getBytes(StandardCharsets.UTF_8);
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("transferId", transferId);
        manifest.put("chunkCount", 2);
        manifest.put("sizeBytes", expected.length);
        manifest.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
        manifest.put("fileName", "patients.csv");

        Path materialized = store.materialize(manifest, "exec-1");
        assertEquals(new String(expected, StandardCharsets.UTF_8), Files.readString(materialized));
    }

    @Test
    void reconstructsStructuredRowBatchesAndVerifiesIntegrity(@TempDir Path tempDir) throws Exception {
        KafkaDataChunkStore store = new KafkaDataChunkStore();
        ReflectionTestUtils.setField(store, "tempDir", tempDir.toString());
        String transferId = "rows-123";
        String expected = "{\"patient_id\":\"P001\",\"name\":\"Alice\"}\n"
                + "{\"patient_id\":\"P002\",\"name\":\"Bob, Jr\"}\n";

        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventType", "PIPELINE_SOURCE_ROW_BATCH");
        event.put("transferId", transferId);
        event.put("batchIndex", 0);
        event.putArray("headers").add("patient_id").add("name");
        var rows = event.putArray("rows");
        rows.addArray().add("P001").add("Alice");
        rows.addArray().add("P002").add("Bob, Jr");
        store.accept(event);

        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("transport", "KAFKA_ROW_BATCH");
        manifest.put("format", "JSON");
        manifest.put("transferId", transferId);
        manifest.put("batchCount", 1);
        manifest.put("sizeBytes", bytes.length);
        manifest.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        manifest.put("fileName", "patients.jsonl");

        Path materialized = store.materialize(manifest, "exec-rows");
        assertEquals(expected, Files.readString(materialized));
    }

    @Test
    void reconstructsTypedJdbcRowsAsJsonLines(@TempDir Path tempDir) throws Exception {
        KafkaDataChunkStore store = new KafkaDataChunkStore();
        ReflectionTestUtils.setField(store, "tempDir", tempDir.toString());
        String transferId = "jdbc-json-123";
        String expected = "{\"id\":1,\"name\":\"Alice\",\"active\":true,\"note\":null}\n"
                + "{\"id\":2,\"name\":\"Bob\",\"active\":false,\"note\":\"ok\"}\n";

        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventType", "PIPELINE_SOURCE_ROW_BATCH");
        event.put("transferId", transferId);
        event.put("batchIndex", 0);
        event.put("format", "JSON");
        event.putArray("headers").add("id").add("name").add("active").add("note");
        var rows = event.putArray("rows");
        rows.addArray().add(1).add("Alice").add(true).addNull();
        rows.addArray().add(2).add("Bob").add(false).add("ok");
        store.accept(event);

        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("transport", "KAFKA_ROW_BATCH");
        manifest.put("format", "JSON");
        manifest.put("transferId", transferId);
        manifest.put("batchCount", 1);
        manifest.put("sizeBytes", bytes.length);
        manifest.put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        manifest.put("fileName", "jdbc-source.jsonl");

        Path materialized = store.materialize(manifest, "exec-json");
        assertEquals(expected, Files.readString(materialized));
    }

    @Test
    void abortRemovesEveryPartialBatch(@TempDir Path tempDir) throws Exception {
        KafkaDataChunkStore store = new KafkaDataChunkStore();
        ReflectionTestUtils.setField(store, "tempDir", tempDir.toString());
        String transferId = "aborted-123";
        ObjectNode event = objectMapper.createObjectNode();
        event.put("eventType", "PIPELINE_SOURCE_ROW_BATCH");
        event.put("transferId", transferId);
        event.put("batchIndex", 0);
        event.putArray("headers").add("student_id");
        event.putArray("rows").addArray().add("S001");
        store.accept(event);

        ObjectNode abort = objectMapper.createObjectNode();
        abort.put("eventType", "PIPELINE_SOURCE_TRANSFER_ABORTED");
        abort.put("transferId", transferId);
        store.abort(abort);

        assertFalse(Files.exists(
                tempDir.resolve("kafka-transfers").resolve(transferId)));
    }

    private ObjectNode chunk(String transferId, int index, int count, byte[] payload) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("transferId", transferId);
        node.put("chunkIndex", index);
        node.put("chunkCount", count);
        node.put("payloadBase64", Base64.getEncoder().encodeToString(payload));
        return node;
    }
}
