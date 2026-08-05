package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reassembles source data already transported by Kafka.
 *
 * Every part is written once and duplicate indices must contain identical
 * bytes. The final SHA-256 and size checks make replay safe before exposing a
 * JSON Lines file to the local execution engine. This is staging, not a source
 * connection and not a CSV conversion.
 */
@Service
public class KafkaDataChunkStore {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.hop.temp.dir:/tmp/iol}")
    private String tempDir;

    public void accept(JsonNode event) throws Exception {
        if ("PIPELINE_SOURCE_ROW_BATCH".equals(event.path("eventType").asText())) {
            acceptRowBatch(event);
            return;
        }
        String transferId = safeId(event.path("transferId").asText());
        int chunkIndex = event.path("chunkIndex").asInt(-1);
        int chunkCount = event.path("chunkCount").asInt(-1);
        if (chunkIndex < 0 || chunkCount <= 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Index de morceau Kafka invalide.");
        }
        Path directory = transferDirectory(transferId);
        Files.createDirectories(directory);
        Path part = directory.resolve(String.format("%08d.part", chunkIndex));
        byte[] bytes = Base64.getDecoder().decode(event.path("payloadBase64").asText(""));
        if (Files.exists(part)) {
            byte[] existing = Files.readAllBytes(part);
            if (!java.util.Arrays.equals(existing, bytes)) {
                throw new IllegalStateException("Morceau Kafka duplique avec un contenu different: " + chunkIndex);
            }
            return;
        }
        Files.write(part, bytes, StandardOpenOption.CREATE_NEW);
    }

    public void abort(JsonNode event) throws Exception {
        String transferId = safeId(event.path("transferId").asText());
        deleteDirectory(transferDirectory(transferId));
    }

    public Path materialize(JsonNode manifest, String execLogId) throws Exception {
        if ("KAFKA_ROW_BATCH".equalsIgnoreCase(manifest.path("transport").asText())) {
            if ("JSON".equalsIgnoreCase(manifest.path("format").asText())) {
                return materializeJsonRowBatches(manifest, execLogId);
            }
            return materializeRowBatches(manifest, execLogId);
        }
        String transferId = safeId(manifest.path("transferId").asText());
        int chunkCount = manifest.path("chunkCount").asInt(-1);
        if (chunkCount <= 0) throw new IllegalArgumentException("Manifeste Kafka sans morceaux.");

        Path directory = transferDirectory(transferId);
        String extension = extension(manifest.path("fileName").asText("source.data"));
        Path outputRoot = Path.of(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path output = outputRoot.resolve("iol_kafka_" + safeId(execLogId) + "_" + transferId + extension);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (OutputStream target = Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < chunkCount; index++) {
                Path part = directory.resolve(String.format("%08d.part", index));
                if (!Files.isRegularFile(part)) {
                    throw new IllegalStateException("Morceau Kafka manquant " + index + "/" + chunkCount
                            + " pour " + transferId);
                }
                try (InputStream input = Files.newInputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        target.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        total += read;
                    }
                }
            }
        }

        long expectedSize = manifest.path("sizeBytes").asLong(-1);
        String expectedHash = manifest.path("sha256").asText("");
        String actualHash = HexFormat.of().formatHex(digest.digest());
        if ((expectedSize >= 0 && expectedSize != total)
                || (!expectedHash.isBlank() && !expectedHash.equalsIgnoreCase(actualHash))) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("Controle d'integrite Kafka echoue pour " + transferId);
        }
        deleteDirectory(directory);
        return output;
    }

    private Path materializeJsonRowBatches(JsonNode manifest, String execLogId) throws Exception {
        String transferId = safeId(manifest.path("transferId").asText());
        int batchCount = manifest.path("batchCount").asInt(-1);
        if (batchCount <= 0) throw new IllegalArgumentException("Manifeste Kafka sans lots de lignes.");
        Path directory = transferDirectory(transferId);
        Path outputRoot = Path.of(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path output = outputRoot.resolve(
                "iol_kafka_rows_" + safeId(execLogId) + "_" + transferId + ".jsonl");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        List<String> expectedHeaders = null;
        String expectedOrganizationId = manifest.path("organizationId").asText("");
        try (OutputStream target = Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < batchCount; index++) {
                Path part = directory.resolve(String.format("%08d.rows.json", index));
                if (!Files.isRegularFile(part)) {
                    throw new IllegalStateException("Lot Kafka manquant " + index + "/" + batchCount
                            + " pour " + transferId);
                }
                JsonNode event = objectMapper.readTree(Files.readAllBytes(part));
                String eventOrganizationId = event.path("organizationId").asText("");
                if (!expectedOrganizationId.isBlank()
                        && !expectedOrganizationId.equals(eventOrganizationId)) {
                    throw new IllegalStateException(
                            "Organisation incohérente dans le lot Kafka " + index);
                }
                List<String> headers = stringList(event.path("headers"));
                if (expectedHeaders == null) {
                    expectedHeaders = headers;
                } else if (!expectedHeaders.equals(headers)) {
                    throw new IllegalStateException("En-têtes incohérents dans le lot Kafka " + index);
                }
                for (JsonNode rowNode : event.path("rows")) {
                    if (!rowNode.isArray() || rowNode.size() != headers.size()) {
                        throw new IllegalStateException("Ligne JSON incohérente dans le lot Kafka " + index);
                    }
                    Map<String, JsonNode> row = new LinkedHashMap<>();
                    for (int column = 0; column < headers.size(); column++) {
                        row.put(headers.get(column), rowNode.get(column));
                    }
                    byte[] json = objectMapper.writeValueAsBytes(row);
                    byte[] canonical = java.util.Arrays.copyOf(json, json.length + 1);
                    canonical[canonical.length - 1] = '\n';
                    target.write(canonical);
                    digest.update(canonical);
                    total += canonical.length;
                }
            }
        }

        verifyIntegrity(manifest, transferId, output, digest, total);
        deleteDirectory(directory);
        return output;
    }

    private void acceptRowBatch(JsonNode event) throws Exception {
        String transferId = safeId(event.path("transferId").asText());
        int batchIndex = event.path("batchIndex").asInt(-1);
        if (batchIndex < 0 || !event.path("headers").isArray() || !event.path("rows").isArray()) {
            throw new IllegalArgumentException("Lot de lignes Kafka invalide.");
        }
        Path directory = transferDirectory(transferId);
        Files.createDirectories(directory);
        Path part = directory.resolve(String.format("%08d.rows.json", batchIndex));
        byte[] bytes = objectMapper.writeValueAsBytes(event);
        if (Files.exists(part)) {
            byte[] existing = Files.readAllBytes(part);
            if (!java.util.Arrays.equals(existing, bytes)) {
                throw new IllegalStateException("Lot Kafka dupliqué avec un contenu différent: " + batchIndex);
            }
            return;
        }
        Files.write(part, bytes, StandardOpenOption.CREATE_NEW);
    }

    private Path materializeRowBatches(JsonNode manifest, String execLogId) throws Exception {
        String transferId = safeId(manifest.path("transferId").asText());
        int batchCount = manifest.path("batchCount").asInt(-1);
        if (batchCount <= 0) throw new IllegalArgumentException("Manifeste Kafka sans lots de lignes.");
        Path directory = transferDirectory(transferId);
        Path outputRoot = Path.of(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(outputRoot);
        Path output = outputRoot.resolve("iol_kafka_rows_" + safeId(execLogId) + "_" + transferId + ".csv");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        List<String> expectedHeaders = null;
        try (OutputStream target = Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < batchCount; index++) {
                Path part = directory.resolve(String.format("%08d.rows.json", index));
                if (!Files.isRegularFile(part)) {
                    throw new IllegalStateException("Lot Kafka manquant " + index + "/" + batchCount
                            + " pour " + transferId);
                }
                JsonNode event = objectMapper.readTree(Files.readAllBytes(part));
                List<String> headers = stringList(event.path("headers"));
                if (expectedHeaders == null) {
                    expectedHeaders = headers;
                    total += writeCanonicalRow(target, digest, headers);
                } else if (!expectedHeaders.equals(headers)) {
                    throw new IllegalStateException("En-têtes incohérents dans le lot Kafka " + index);
                }
                for (JsonNode rowNode : event.path("rows")) {
                    total += writeCanonicalRow(target, digest, stringList(rowNode));
                }
            }
        }

        verifyIntegrity(manifest, transferId, output, digest, total);
        deleteDirectory(directory);
        return output;
    }

    private void verifyIntegrity(
            JsonNode manifest,
            String transferId,
            Path output,
            MessageDigest digest,
            long total) throws Exception {
        long expectedSize = manifest.path("sizeBytes").asLong(-1);
        String expectedHash = manifest.path("sha256").asText("");
        String actualHash = HexFormat.of().formatHex(digest.digest());
        if ((expectedSize >= 0 && expectedSize != total)
                || (!expectedHash.isBlank() && !expectedHash.equalsIgnoreCase(actualHash))) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("Contrôle d'intégrité des lots Kafka échoué pour " + transferId);
        }
    }

    private long writeCanonicalRow(OutputStream target, MessageDigest digest, List<String> values) throws Exception {
        byte[] bytes = (values.stream().map(this::csv).collect(java.util.stream.Collectors.joining(",")) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        target.write(bytes);
        digest.update(bytes);
        return bytes.length;
    }

    private List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) array.forEach(value -> result.add(value.asText("")));
        return result;
    }

    private String csv(String value) {
        String text = value == null ? "" : value;
        String escaped = text.replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                ? "\"" + escaped + "\"" : escaped;
    }

    private Path transferDirectory(String transferId) {
        Path root = Path.of(tempDir, "kafka-transfers").toAbsolutePath().normalize();
        Path result = root.resolve(transferId).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("Identifiant de transfert invalide.");
        return result;
    }

    private String safeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Identifiant de transfert invalide.");
        }
        return value;
    }

    private String extension(String filename) {
        String safe = filename.replace('\\', '/');
        int dot = safe.lastIndexOf('.');
        String extension = dot >= 0 ? safe.substring(dot) : ".data";
        return extension.matches("\\.[A-Za-z0-9]{1,10}") ? extension : ".data";
    }

    private void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
