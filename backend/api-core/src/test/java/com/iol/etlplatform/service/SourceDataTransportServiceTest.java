package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.service.security.MalwareScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class SourceDataTransportServiceTest {

    @Test
    void rejectsApiCsvThatExceedsLocalExtractionLimit(@TempDir Path tempDir) throws Exception {
        UploadedFileService uploads = uploadedFileService(tempDir);
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        ApiSourceClient apiSourceClient = mock(ApiSourceClient.class);
        when(apiSourceClient.writeCsv(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any(Path.class)))
                .thenAnswer(invocation -> {
                    Files.writeString(invocation.getArgument(1), "id,name\n1,Alice\n2,Bob\n");
                    return 2L;
                });
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(), provider, uploads, apiSourceClient, mock(ObjectStorageService.class),
                new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "tempDir", tempDir.resolve("transport").toString());
        ReflectionTestUtils.setField(service, "maxLocalExtractionBytes", 10L);
        ReflectionTestUtils.setField(service, "minFreeDiskBytes", 0L);
        ReflectionTestUtils.setField(service, "staleTempFileAgeSeconds", 3600L);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "API");
        source.put("config", new LinkedHashMap<>(Map.of("source_config", Map.of("url", "https://example.test"))));
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INTERNAL");
        command.put("executionMode", "LOCAL");
        command.put("sources", new ArrayList<>(List.of(source)));

        assertThrows(com.iol.etlplatform.exception.BadRequestException.class, () -> service.publishSourceData(
                "iol.pipeline.commands", "wf-1", "wf-1", "exec-1", command));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesUploadedFileAsBoundedKafkaChunksAndRewritesSource(@TempDir Path tempDir) {
        UploadedFileService uploads = uploadedFileService(tempDir);
        byte[] content = new byte[70_000];
        java.util.Arrays.fill(content, (byte) 'A');
        var uploaded = uploads.store(new MockMultipartFile("file", "patients.csv", "text/csv", content));

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);

        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        when(objectStorage.isEnabled()).thenReturn(false);
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(), provider, uploads, mock(ApiSourceClient.class), objectStorage,
                new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "chunkBytes", 65_536);
        ReflectionTestUtils.setField(service, "tempDir", tempDir.resolve("transport").toString());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upload_id", uploaded.getUploadId());
        config.put("transport_mode", "KAFKA_CHUNKED");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "CSV");
        source.put("config", config);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INTERNAL");
        command.put("sources", new ArrayList<>(List.of(source)));

        List<Map<String, Object>> manifest = service.publishSourceData(
                "iol.pipeline.commands", "wf-1", "wf-1", "exec-1", command);

        assertEquals(1, manifest.size());
        assertEquals(2, manifest.get(0).get("chunkCount"));
        assertEquals("KAFKA_CHUNKED", config.get("data_transport"));
        assertTrue(String.valueOf(config.get("file_path")).startsWith("kafka://"));
        verify(kafka, org.mockito.Mockito.times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void transportsBigJdbcSourceThroughRustFsWithoutLeakingSourceCredentials(@TempDir Path tempDir) throws Exception {
        UploadedFileService uploads = uploadedFileService(tempDir);
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        when(objectStorage.isEnabled()).thenReturn(true);
        when(objectStorage.storeStreaming(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(ObjectStorageService.StreamWriter.class)))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "iol-source-data", "source-data/wf-1/exec-1/source.jsonl", 42, "abc123"));
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(), provider, uploads, mock(ApiSourceClient.class), objectStorage,
                new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "tempDir", tempDir.resolve("transport").toString());
        ReflectionTestUtils.setField(service, "minFreeDiskBytes", 0L);
        Path database = createJdbcFixture(tempDir);

        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("query", "SELECT id, name FROM patients");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source_config", sourceConfig);
        config.put("database", database.toString());
        config.put("username", "etl");
        config.put("password", "secret");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "SQLITE");
        source.put("config", config);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INTERNAL");
        command.put("executionMode", "SPARK");
        command.put("sources", new ArrayList<>(List.of(source)));

        List<Map<String, Object>> manifest = service.publishSourceData(
                "iol.pipeline.commands", "wf-1", "wf-1", "exec-1", command);

        assertEquals("OBJECT_STORAGE", manifest.get(0).get("transport"));
        assertEquals("JSON", source.get("source_name"));
        assertEquals("OBJECT_STORAGE", config.get("data_transport"));
        assertTrue(!config.containsKey("database") && !config.containsKey("username")
                && !config.containsKey("password"));
        assertTrue(!config.containsKey("source_config"));
        verify(provider, never()).getIfAvailable();
    }

    @Test
    void transportsLocalJdbcRowsThroughKafkaAsJsonWithoutCsv(@TempDir Path tempDir) throws Exception {
        UploadedFileService uploads = uploadedFileService(tempDir);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(), provider, uploads,
                mock(ApiSourceClient.class), mock(ObjectStorageService.class),
                new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "rowBatchRows", 100);
        ReflectionTestUtils.setField(service, "maxRowBatchEventBytes", 1024 * 1024);
        Path database = createJdbcFixture(tempDir);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source_config", Map.of("query", "SELECT id, name FROM patients ORDER BY id"));
        config.put("database", database.toString());
        config.put("username", "reader");
        config.put("password", "secret");
        config.put("source_connection_id", "source-1");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "SQLITE");
        source.put("config", config);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INTERNAL");
        command.put("executionMode", "LOCAL");
        command.put("sources", new ArrayList<>(List.of(source)));

        List<Map<String, Object>> manifest = service.publishSourceData(
                "iol.pipeline.commands", "wf-1", "wf-1", "exec-local", command);

        assertEquals("KAFKA_ROW_BATCH", manifest.get(0).get("transport"));
        assertEquals("JSON", manifest.get(0).get("format"));
        assertEquals(2L, manifest.get(0).get("rowCount"));
        assertEquals("JSON", source.get("source_name"));
        assertEquals("KAFKA_ROW_BATCH", config.get("data_transport"));
        assertTrue(!config.containsKey("database") && !config.containsKey("username")
                && !config.containsKey("password") && !config.containsKey("source_connection_id"));
        assertTrue(!config.containsKey("source_config"));
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), event.capture());
        var payload = new ObjectMapper().readTree(event.getValue());
        assertEquals("JSON", payload.path("format").asText());
        assertEquals(2, payload.path("rows").size());
        assertTrue(!event.getValue().contains("secret"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesOnlyAnObjectReferenceWhenRustFsIsEnabled(@TempDir Path tempDir) {
        UploadedFileService uploads = uploadedFileService(tempDir);
        var uploaded = uploads.store(new MockMultipartFile(
                "file", "patients.csv", "text/csv", "patient_id,name\nP001,Alice\n".getBytes()));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        when(objectStorage.isEnabled()).thenReturn(true);
        when(objectStorage.store(org.mockito.ArgumentMatchers.any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString()))
                .thenReturn(new ObjectStorageService.StoredObject(
                        "iol-source-data", "source-data/wf-1/exec-1/source.csv", 27, "abc123"));
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(), provider, uploads, mock(ApiSourceClient.class), objectStorage,
                new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upload_id", uploaded.getUploadId());
        config.put("transport_mode", "OBJECT_STORAGE");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "CSV");
        source.put("config", config);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INTERNAL");
        command.put("executionMode", "SPARK");
        command.put("sources", new ArrayList<>(List.of(source)));

        List<Map<String, Object>> manifest = service.publishSourceData(
                "iol.pipeline.commands", "wf-1", "wf-1", "exec-1", command);

        assertEquals("OBJECT_STORAGE", manifest.get(0).get("transport"));
        assertEquals("RUSTFS", manifest.get(0).get("provider"));
        assertEquals("OBJECT_STORAGE", config.get("data_transport"));
        assertTrue(String.valueOf(config.get("file_path")).startsWith("s3://iol-source-data/"));
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignoresManualObjectTransportForSmallFilesAndUsesKafka(@TempDir Path tempDir) throws Exception {
        UploadedFileService uploads = uploadedFileService(tempDir);
        var uploaded = uploads.store(new MockMultipartFile(
                "file", "patients.csv", "text/csv", "patient_id,name\nP001,Alice\nP002,\"Bob, Jr\"\n".getBytes()));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(), provider, uploads, mock(ApiSourceClient.class), objectStorage,
                new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "rowBatchRows", 100);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upload_id", uploaded.getUploadId());
        config.put("transport_mode", "OBJECT_STORAGE");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "CSV");
        source.put("config", config);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INTERNAL");
        command.put("sources", new ArrayList<>(List.of(source)));

        List<Map<String, Object>> manifest = service.publishSourceData(
                "iol.pipeline.commands", "wf-1", "wf-1", "exec-1", command);

        assertEquals("KAFKA_CHUNKED", manifest.get(0).get("transport"));
        assertEquals("KAFKA_CHUNKED", config.get("data_transport"));
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), event.capture());
        assertEquals("PIPELINE_SOURCE_DATA_CHUNK",
                new ObjectMapper().readTree(event.getValue()).path("eventType").asText());
        verify(objectStorage, never()).store(org.mockito.ArgumentMatchers.any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString());
    }

    @Test
    void streamsNormalInboundRowsThroughKafkaWithoutBufferingTheWholeRequest() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(),
                provider,
                mock(UploadedFileService.class),
                mock(ApiSourceClient.class),
                mock(ObjectStorageService.class),
                new SourceConnectionLimiter(8, 5));
        configureInboundStream(service);
        Map<String, Object> command = inboundCommand();
        byte[] ndjson = ("{\"student_id\":\"S001\",\"grade\":5}\n"
                + "{\"student_id\":\"S002\",\"grade\":null}\n").getBytes();

        SourceDataTransportService.InboundStreamPublication publication =
                service.publishInboundStream(
                        "iol.pipeline.commands",
                        "iol-default:wf-1",
                        "iol-default",
                        "wf-1",
                        "exec-1",
                        new ByteArrayInputStream(ndjson),
                        2L,
                        (long) ndjson.length,
                        null,
                        command);

        assertEquals(2L, publication.recordCount());
        assertEquals("KAFKA_ROW_BATCH", command.get("dataTransport"));
        assertEquals(2L, command.get("rowCount"));
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(
                org.mockito.ArgumentMatchers.eq("iol.pipeline.commands"),
                org.mockito.ArgumentMatchers.eq("iol-default:wf-1"),
                event.capture());
        var payload = new ObjectMapper().readTree(event.getValue());
        assertEquals("PIPELINE_SOURCE_ROW_BATCH", payload.path("eventType").asText());
        assertEquals("iol-default", payload.path("organizationId").asText());
        assertEquals(2, payload.path("rows").size());
    }

    @Test
    void streamsBigInboundRowsDirectlyToRustFs() throws Exception {
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        when(objectStorage.isEnabled()).thenReturn(true);
        ByteArrayOutputStream storedBody = new ByteArrayOutputStream();
        when(objectStorage.storeStreaming(
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.any(ObjectStorageService.StreamWriter.class)))
                .thenAnswer(invocation -> {
                    ObjectStorageService.StreamWriter writer = invocation.getArgument(5);
                    writer.write(storedBody);
                    return new ObjectStorageService.StoredObject(
                            "iol-source-data",
                            "source-data/iol-default__wf-1/exec-1/inbound.jsonl",
                            storedBody.size(),
                            "sha256-test");
                });
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(),
                provider,
                mock(UploadedFileService.class),
                mock(ApiSourceClient.class),
                objectStorage,
                new SourceConnectionLimiter(8, 5));
        configureInboundStream(service);
        ReflectionTestUtils.setField(service, "inboundBigDataRowThreshold", 2L);
        Map<String, Object> command = inboundCommand();
        byte[] ndjson = "{\"student_id\":\"S001\"}\n{\"student_id\":\"S002\"}\n".getBytes();

        SourceDataTransportService.InboundStreamPublication publication =
                service.publishInboundStream(
                        "iol.pipeline.commands",
                        "iol-default:wf-1",
                        "iol-default",
                        "wf-1",
                        "exec-1",
                        new ByteArrayInputStream(ndjson),
                        2L,
                        (long) ndjson.length,
                        null,
                        command);

        assertEquals(2L, publication.recordCount());
        assertEquals("OBJECT_STORAGE", command.get("dataTransport"));
        assertEquals(new String(ndjson), storedBody.toString());
        verify(provider, never()).getIfAvailable();
    }

    @Test
    void abortsPartialKafkaTransferWhenThePivotSchemaChanges() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(kafka);
        SourceDataTransportService service = new SourceDataTransportService(
                new ObjectMapper(),
                provider,
                mock(UploadedFileService.class),
                mock(ApiSourceClient.class),
                mock(ObjectStorageService.class),
                new SourceConnectionLimiter(8, 5));
        configureInboundStream(service);
        Map<String, Object> command = inboundCommand();
        byte[] ndjson = "{\"student_id\":\"S001\"}\n{\"other\":\"S002\"}\n".getBytes();

        assertThrows(
                com.iol.etlplatform.exception.BadRequestException.class,
                () -> service.publishInboundStream(
                        "iol.pipeline.commands",
                        "iol-default:wf-1",
                        "iol-default",
                        "wf-1",
                        "exec-1",
                        new ByteArrayInputStream(ndjson),
                        2L,
                        (long) ndjson.length,
                        null,
                        command));

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(anyString(), anyString(), event.capture());
        assertEquals(
                "PIPELINE_SOURCE_TRANSFER_ABORTED",
                new ObjectMapper().readTree(event.getValue()).path("eventType").asText());
    }

    private void configureInboundStream(SourceDataTransportService service) {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "rowBatchRows", 100);
        ReflectionTestUtils.setField(service, "maxRowBatchEventBytes", 1024 * 1024);
        ReflectionTestUtils.setField(service, "inboundBigDataRowThreshold", 1_000L);
        ReflectionTestUtils.setField(service, "inboundBigDataByteThreshold", 1024 * 1024L);
        ReflectionTestUtils.setField(service, "maxInboundStreamBytes", 10 * 1024 * 1024L);
        ReflectionTestUtils.setField(service, "maxInboundNdjsonLineBytes", 1024 * 1024);
    }

    private Map<String, Object> inboundCommand() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("source_config", new LinkedHashMap<>(Map.of(
                "mode", "PUSH",
                "streaming", true)));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", "PUSH");
        source.put("config", config);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("direction", "INBOUND");
        command.put("executionMode", "LOCAL");
        command.put("sources", new ArrayList<>(List.of(source)));
        return command;
    }

    private Path createJdbcFixture(Path tempDir) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = tempDir.resolve("source.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE patients (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO patients(id, name) VALUES (1, 'Alice'), (2, 'Bob')");
        }
        return database;
    }

    private UploadedFileService uploadedFileService(Path tempDir) {
        return new UploadedFileService(
                tempDir.resolve("uploads").toString(),
                tempDir.resolve("quarantine").toString(),
                1024 * 1024,
                path -> MalwareScanResult.clean("test scanner"));
    }
}
