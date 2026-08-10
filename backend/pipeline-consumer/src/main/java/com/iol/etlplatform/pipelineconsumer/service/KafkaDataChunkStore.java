package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.pipelineconsumer.entity.StagedKafkaChunk;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbox durable des donnees transportees par Kafka.
 *
 * Un lot n'est acquitte qu'apres son insertion idempotente dans MongoDB. Une
 * autre instance peut donc reconstruire la source apres un redemarrage ou un
 * rebalance. Le moteur recoit un JSON Lines temporaire; les lots JDBC ne sont
 * jamais convertis en CSV.
 */
@Service
public class KafkaDataChunkStore {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MongoTemplate mongoTemplate;

    /** Stockage limite aux tests unitaires construisant le service sans Spring. */
    private final Map<String, StagedKafkaChunk> localTestInbox = new ConcurrentHashMap<>();

    @Value("${app.hop.temp.dir:/tmp/iol}")
    private String tempDir;

    @Value("${app.kafka.data-staging.retention-hours:168}")
    private long retentionHours = 168;

    @Autowired
    public KafkaDataChunkStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    KafkaDataChunkStore() {
        this.mongoTemplate = null;
    }

    @PostConstruct
    void ensureIndexes() {
        if (mongoTemplate == null) return;
        var indexes = mongoTemplate.indexOps(StagedKafkaChunk.class);
        indexes.ensureIndex(new Index().on("transferId", Sort.Direction.ASC));
        indexes.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(Duration.ZERO));
    }

    public void accept(JsonNode event) throws Exception {
        String eventType = event.path("eventType").asText();
        String transferId = safeId(event.path("transferId").asText());
        int sequence;
        if ("PIPELINE_SOURCE_ROW_BATCH".equals(eventType)) {
            sequence = event.path("batchIndex").asInt(-1);
            if (sequence < 0 || !event.path("headers").isArray() || !event.path("rows").isArray()) {
                throw new IllegalArgumentException("Lot de lignes Kafka invalide.");
            }
        } else {
            sequence = event.path("chunkIndex").asInt(-1);
            int chunkCount = event.path("chunkCount").asInt(-1);
            if (sequence < 0 || chunkCount <= 0 || sequence >= chunkCount) {
                throw new IllegalArgumentException("Index de morceau Kafka invalide.");
            }
            // Valide la representation avant de rendre l'evenement durable.
            Base64.getDecoder().decode(event.path("payloadBase64").asText(""));
        }
        storeEvent(transferId, sequence, event);
    }

    public void abort(JsonNode event) {
        deleteTransfer(safeId(event.path("transferId").asText()));
    }

    public Path materialize(JsonNode manifest, String execLogId) throws Exception {
        if ("KAFKA_ROW_BATCH".equalsIgnoreCase(manifest.path("transport").asText())) {
            return materializeJsonRowBatches(manifest, execLogId);
        }

        String transferId = safeId(manifest.path("transferId").asText());
        int chunkCount = manifest.path("chunkCount").asInt(-1);
        if (chunkCount <= 0) throw new IllegalArgumentException("Manifeste Kafka sans morceaux.");

        Path outputRoot = outputRoot();
        String extension = extension(manifest.path("fileName").asText("source.data"));
        Path output = outputRoot.resolve("iol_kafka_" + safeId(execLogId) + "_" + transferId + extension);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (OutputStream target = Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < chunkCount; index++) {
                JsonNode event = requireEvent(transferId, index);
                byte[] bytes = Base64.getDecoder().decode(event.path("payloadBase64").asText(""));
                target.write(bytes);
                digest.update(bytes);
                total += bytes.length;
            }
        }
        verifyIntegrity(manifest, transferId, output, digest, total);
        return output;
    }

    private Path materializeJsonRowBatches(JsonNode manifest, String execLogId) throws Exception {
        String transferId = safeId(manifest.path("transferId").asText());
        int batchCount = manifest.path("batchCount").asInt(-1);
        if (batchCount <= 0) throw new IllegalArgumentException("Manifeste Kafka sans lots de lignes.");
        Path output = outputRoot().resolve(
                "iol_kafka_rows_" + safeId(execLogId) + "_" + transferId + ".jsonl");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        List<String> expectedHeaders = null;
        String expectedOrganizationId = manifest.path("organizationId").asText("");

        try (OutputStream target = Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < batchCount; index++) {
                JsonNode event = requireEvent(transferId, index);
                String eventOrganizationId = event.path("organizationId").asText("");
                if (!expectedOrganizationId.isBlank()
                        && !expectedOrganizationId.equals(eventOrganizationId)) {
                    throw new IllegalStateException("Organisation incoherente dans le lot Kafka " + index);
                }
                List<String> headers = stringList(event.path("headers"));
                if (expectedHeaders == null) {
                    expectedHeaders = headers;
                } else if (!expectedHeaders.equals(headers)) {
                    throw new IllegalStateException("En-tetes incoherents dans le lot Kafka " + index);
                }
                for (JsonNode rowNode : event.path("rows")) {
                    if (!rowNode.isArray() || rowNode.size() != headers.size()) {
                        throw new IllegalStateException("Ligne JSON incoherente dans le lot Kafka " + index);
                    }
                    Map<String, JsonNode> row = new LinkedHashMap<>();
                    for (int column = 0; column < headers.size(); column++) {
                        row.put(headers.get(column), rowNode.get(column));
                    }
                    byte[] json = objectMapper.writeValueAsBytes(row);
                    target.write(json);
                    target.write('\n');
                    digest.update(json);
                    digest.update((byte) '\n');
                    total += json.length + 1L;
                }
            }
        }
        verifyIntegrity(manifest, transferId, output, digest, total);
        return output;
    }

    /** Supprime l'inbox uniquement apres l'etat terminal durable de l'execution. */
    public void cleanup(JsonNode command) {
        JsonNode manifest = command == null ? null : command.path("sourceDataManifest");
        if (manifest == null || !manifest.isArray()) return;
        for (JsonNode item : manifest) {
            String transport = item.path("transport").asText("");
            if (transport.toUpperCase(java.util.Locale.ROOT).startsWith("KAFKA")) {
                deleteTransfer(safeId(item.path("transferId").asText()));
            }
        }
    }

    private void storeEvent(String transferId, int sequence, JsonNode event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);
        String digest = sha256(payload);
        String id = documentId(transferId, sequence);
        StagedKafkaChunk chunk = new StagedKafkaChunk(
                id,
                transferId,
                sequence,
                payload,
                digest,
                Instant.now().plus(Duration.ofHours(Math.max(1, retentionHours))));
        if (mongoTemplate == null) {
            StagedKafkaChunk existing = localTestInbox.putIfAbsent(id, chunk);
            if (existing != null) assertSameContent(existing, digest, sequence);
            return;
        }
        try {
            mongoTemplate.insert(chunk);
        } catch (DuplicateKeyException duplicate) {
            assertSameContent(mongoTemplate.findById(id, StagedKafkaChunk.class), digest, sequence);
        }
    }

    private JsonNode requireEvent(String transferId, int sequence) throws Exception {
        String id = documentId(transferId, sequence);
        StagedKafkaChunk chunk = mongoTemplate == null
                ? localTestInbox.get(id)
                : mongoTemplate.findById(id, StagedKafkaChunk.class);
        if (chunk == null) {
            throw new IllegalStateException("Lot Kafka manquant " + sequence + " pour " + transferId);
        }
        if (!sha256(chunk.getPayload()).equals(chunk.getSha256())) {
            throw new IllegalStateException("Integrite de l'inbox Kafka invalide pour " + id);
        }
        return objectMapper.readTree(chunk.getPayload());
    }

    private void assertSameContent(StagedKafkaChunk existing, String digest, int sequence) {
        if (existing == null || !digest.equals(existing.getSha256())) {
            throw new IllegalStateException(
                    "Lot Kafka duplique avec un contenu different: " + sequence);
        }
    }

    private void deleteTransfer(String transferId) {
        if (mongoTemplate == null) {
            String prefix = transferId + ":";
            localTestInbox.keySet().removeIf(key -> key.startsWith(prefix));
            return;
        }
        mongoTemplate.remove(
                Query.query(Criteria.where("transferId").is(transferId)),
                StagedKafkaChunk.class);
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
            throw new IllegalStateException("Controle d'integrite Kafka echoue pour " + transferId);
        }
    }

    private Path outputRoot() throws Exception {
        Path root = Path.of(tempDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) array.forEach(value -> result.add(value.asText("")));
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

    private String documentId(String transferId, int sequence) {
        return transferId + ":" + String.format("%08d", sequence);
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
