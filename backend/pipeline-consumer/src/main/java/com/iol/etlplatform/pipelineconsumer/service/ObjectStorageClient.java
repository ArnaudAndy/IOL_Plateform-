package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class ObjectStorageClient {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageClient.class);

    @Value("${app.object-storage.enabled:false}")
    private boolean enabled;

    @Value("${app.object-storage.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${app.object-storage.region:us-east-1}")
    private String region;

    @Value("${app.object-storage.access-key:}")
    private String accessKey;

    @Value("${app.object-storage.secret-key:}")
    private String secretKey;

    @Value("${app.object-storage.bucket:iol-source-data}")
    private String bucket;

    @Value("${app.object-storage.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${app.object-storage.cleanup.failed-retention-hours:72}")
    private long failedRetentionHours;

    @Value("${app.hop.temp.dir:/tmp/iol}")
    private String tempDir;

    private volatile S3Client client;

    public Path materialize(JsonNode manifest, String execLogId) throws Exception {
        if (!enabled) {
            throw new IllegalStateException("Le manifeste utilise RustFS mais le stockage objet est désactivé.");
        }
        String bucket = required(manifest, "bucket");
        String objectKey = required(manifest, "objectKey");
        String expectedChecksum = required(manifest, "sha256").toLowerCase(Locale.ROOT);
        String fileName = manifest.path("fileName").asText("source.csv");
        Path root = Path.of(tempDir, "object-storage", safe(execLogId)).toAbsolutePath().normalize();
        Files.createDirectories(root);
        // createTempFile reserve un nom unique en creant le fichier, mais le
        // transformer synchrone exige de creer lui-meme sa cible: on libere donc
        // l'emplacement juste avant le transfert, le nom restant reserve pour nous.
        Path output = Files.createTempFile(root, "source-", "-" + safe(fileName));
        try {
            Files.delete(output);
            s3().getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build(),
                    ResponseTransformer.toFile(output));
            String actual = sha256(output);
            if (!MessageDigest.isEqual(expectedChecksum.getBytes(), actual.getBytes())) {
                throw new IllegalStateException("Somme SHA-256 invalide pour " + objectKey);
            }
            return output;
        } catch (Exception e) {
            Files.deleteIfExists(output);
            throw e;
        }
    }

    public int deleteTransferredObjects(JsonNode command) {
        if (!enabled || command == null) return 0;
        JsonNode manifest = command.path("sourceDataManifest");
        if (!manifest.isArray()) return 0;

        int deleted = 0;
        for (JsonNode item : manifest) {
            if (!"OBJECT_STORAGE".equalsIgnoreCase(item.path("transport").asText(""))) continue;
            String itemBucket = item.path("bucket").asText("").trim();
            String objectKey = item.path("objectKey").asText("").trim();
            if (itemBucket.isBlank() || objectKey.isBlank()) continue;
            s3().deleteObject(DeleteObjectRequest.builder()
                    .bucket(itemBucket)
                    .key(objectKey)
                    .build());
            deleted++;
        }
        if (deleted > 0) {
            log.info("{} objet(s) source RustFS supprimé(s) après acquittement Kafka.", deleted);
        }
        return deleted;
    }

    @Scheduled(
            fixedDelayString = "${app.object-storage.cleanup.scan-interval-ms:3600000}",
            initialDelayString = "${app.object-storage.cleanup.scan-interval-ms:3600000}")
    void purgeExpiredTemporaryObjects() {
        if (!enabled || !cleanupEnabled || bucket == null || bucket.isBlank()) return;
        Instant cutoff = Instant.now().minus(Duration.ofHours(Math.max(1L, failedRetentionHours)));
        String continuationToken = null;
        int deleted = 0;
        try {
            do {
                var response = s3().listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix("source-data/")
                        .continuationToken(continuationToken)
                        .build());
                for (var item : response.contents()) {
                    if (item.lastModified() != null && item.lastModified().isBefore(cutoff)) {
                        s3().deleteObject(DeleteObjectRequest.builder()
                                .bucket(bucket)
                                .key(item.key())
                                .build());
                        deleted++;
                    }
                }
                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null && !continuationToken.isBlank());
            if (deleted > 0) {
                log.info("{} objet(s) RustFS expiré(s) supprimé(s) par la rétention de secours.", deleted);
            }
        } catch (Exception error) {
            log.warn("Nettoyage périodique RustFS impossible: {}", error.getMessage());
        }
    }

    private S3Client s3() {
        if (client != null) return client;
        synchronized (this) {
            if (client == null) {
                if (accessKey.isBlank() || secretKey.isBlank()) {
                    throw new IllegalStateException("Identifiants RustFS absents du pipeline-consumer.");
                }
                client = S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.of(region))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .build();
            }
        }
        return client;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException("Champ manifeste manquant: " + field);
        return value;
    }

    private String safe(String value) {
        String result = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return result.isBlank() ? "unknown" : result;
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }
}
