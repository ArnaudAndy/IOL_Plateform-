package com.iol.etlplatform.sourcegateway.service;

import com.iol.etlplatform.sourcegateway.exception.BadRequestException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ObjectStorageService {

    @Value("${app.object-storage.enabled:false}")
    private boolean enabled;

    @Value("${app.object-storage.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${app.object-storage.region:us-east-1}")
    private String region;

    @Value("${app.object-storage.bucket:iol-source-data}")
    private String bucket;

    @Value("${app.object-storage.access-key:}")
    private String accessKey;

    @Value("${app.object-storage.secret-key:}")
    private String secretKey;

    @Value("${app.object-storage.multipart.part-size-bytes:67108864}")
    private long multipartPartSizeBytes;

    private final AtomicBoolean bucketReady = new AtomicBoolean(false);
    private volatile S3Client client;

    public boolean isEnabled() {
        return enabled;
    }

    public StoredObject store(Path path, String workflowId, String execLogId, int sourceIndex,
                              String fileName, String contentType) {
        if (!enabled) {
            throw new IllegalStateException("Le stockage objet est désactivé.");
        }
        try {
            ensureBucket();
            long size = Files.size(path);
            String checksum = sha256(path);
            String key = "source-data/" + safe(workflowId) + "/" + safe(execLogId) + "/"
                    + sourceIndex + "/" + UUID.randomUUID() + "-" + safe(fileName);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .metadata(java.util.Map.of("sha256", checksum))
                    .build();
            s3().putObject(request, RequestBody.fromFile(path));
            log.info("Source déposée dans RustFS: bucket={} key={} bytes={}", bucket, key, size);
            return new StoredObject(bucket, key, size, checksum);
        } catch (Exception e) {
            throw new BadRequestException("Dépôt de la source dans RustFS impossible: " + e.getMessage());
        }
    }

    public StoredObject storeStreaming(
            String workflowId,
            String execLogId,
            int sourceIndex,
            String fileName,
            String contentType,
            StreamWriter writer) {
        if (!enabled) {
            throw new IllegalStateException("Le stockage objet est désactivé.");
        }
        MultipartUploadStream output = null;
        try {
            ensureBucket();
            String key = objectKey(workflowId, execLogId, sourceIndex, fileName);
            output = new MultipartUploadStream(
                    s3(), bucket, key, contentType, validatedMultipartPartSize());
            writer.write(output);
            StoredObject stored = output.complete();
            log.info("Source diffusée vers RustFS: bucket={} key={} bytes={}",
                    stored.bucket(), stored.objectKey(), stored.sizeBytes());
            return stored;
        } catch (Exception error) {
            if (output != null) output.abort();
            throw new BadRequestException("Dépôt streaming dans RustFS impossible: " + rootMessage(error));
        }
    }

    private void ensureBucket() {
        if (bucketReady.get()) return;
        synchronized (bucketReady) {
            if (bucketReady.get()) return;
            try {
                s3().headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                s3().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
                if (e.statusCode() == 404) {
                    s3().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                } else {
                    throw e;
                }
            }
            bucketReady.set(true);
        }
    }

    private S3Client s3() {
        if (client != null) return client;
        synchronized (this) {
            if (client == null) {
                if (accessKey.isBlank() || secretKey.isBlank()) {
                    throw new IllegalStateException("OBJECT_STORAGE_ACCESS_KEY et OBJECT_STORAGE_SECRET_KEY sont requis.");
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

    private String safe(String value) {
        String result = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        return result.isBlank() ? "unknown" : result;
    }

    private String objectKey(
            String workflowId, String execLogId, int sourceIndex, String fileName) {
        return "source-data/" + safe(workflowId) + "/" + safe(execLogId) + "/"
                + sourceIndex + "/" + UUID.randomUUID() + "-" + safe(fileName);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private int validatedMultipartPartSize() {
        long minimum = 5L * 1024 * 1024;
        long maximum = 512L * 1024 * 1024;
        if (multipartPartSizeBytes < minimum || multipartPartSizeBytes > maximum) {
            throw new IllegalStateException(
                    "OBJECT_STORAGE_MULTIPART_PART_SIZE_BYTES doit être compris entre 5 Mio et 512 Mio.");
        }
        return Math.toIntExact(multipartPartSizeBytes);
    }

    @PreDestroy
    void close() {
        if (client != null) client.close();
    }

    @FunctionalInterface
    public interface StreamWriter {
        void write(OutputStream output) throws Exception;
    }

    private static final class MultipartUploadStream extends OutputStream {
        private static final int MAX_PARTS = 10_000;

        private final S3Client client;
        private final String bucket;
        private final String key;
        private final String contentType;
        private final int partBytes;
        private final byte[] buffer;
        private final List<CompletedPart> completedParts = new ArrayList<>();
        private final MessageDigest digest;
        private int buffered;
        private int partNumber;
        private long size;
        private String uploadId;
        private boolean completed;

        private MultipartUploadStream(
                S3Client client, String bucket, String key, String contentType, int partBytes) throws Exception {
            this.client = client;
            this.bucket = bucket;
            this.key = key;
            this.contentType = contentType;
            this.partBytes = partBytes;
            this.buffer = new byte[partBytes];
            this.digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public void write(int value) throws IOException {
            byte[] single = {(byte) value};
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (completed) throw new IOException("Le transfert RustFS est déjà terminé.");
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            digest.update(bytes, offset, length);
            size += length;
            int cursor = offset;
            int remaining = length;
            while (remaining > 0) {
                int copied = Math.min(remaining, partBytes - buffered);
                System.arraycopy(bytes, cursor, buffer, buffered, copied);
                buffered += copied;
                cursor += copied;
                remaining -= copied;
                if (buffered == partBytes) uploadBufferedPart();
            }
        }

        private void uploadBufferedPart() throws IOException {
            if (buffered == 0) return;
            ensureMultipart();
            if (++partNumber > MAX_PARTS) {
                long maximumBytes = (long) partBytes * MAX_PARTS;
                throw new IOException("Le transfert RustFS dépasse " + MAX_PARTS
                        + " parties, soit environ " + maximumBytes + " octets avec la configuration actuelle.");
            }
            try {
                var response = client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) buffered)
                                .build(),
                        RequestBody.fromBytes(Arrays.copyOf(buffer, buffered)));
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(response.eTag())
                        .build());
                buffered = 0;
            } catch (Exception error) {
                throw new IOException("Partie RustFS " + partNumber + " impossible.", error);
            }
        }

        private void ensureMultipart() throws IOException {
            if (uploadId != null) return;
            try {
                uploadId = client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType(contentType)
                                .build()).uploadId();
            } catch (Exception error) {
                throw new IOException("Initialisation multipart RustFS impossible.", error);
            }
        }

        private StoredObject complete() throws IOException {
            if (completed) throw new IOException("Le transfert RustFS est déjà terminé.");
            try {
                if (size == 0) {
                    client.putObject(
                            PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .contentType(contentType)
                                    .metadata(java.util.Map.of(
                                            "sha256", HexFormat.of().formatHex(digest.digest())))
                                    .build(),
                            RequestBody.empty());
                    completed = true;
                    return new StoredObject(
                            bucket, key, 0L,
                            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest()));
                }
                uploadBufferedPart();
                client.completeMultipartUpload(
                        CompleteMultipartUploadRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .multipartUpload(CompletedMultipartUpload.builder()
                                        .parts(completedParts)
                                        .build())
                                .build());
                completed = true;
                return new StoredObject(
                        bucket, key, size, HexFormat.of().formatHex(digest.digest()));
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("Finalisation multipart RustFS impossible.", error);
            }
        }

        private void abort() {
            if (uploadId == null || completed) return;
            try {
                client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .build());
            } catch (Exception ignored) {
                // Le cycle de vie RustFS nettoiera une partie abandonnée résiduelle.
            }
        }
    }

    public record StoredObject(String bucket, String objectKey, long sizeBytes, String sha256) { }
}
