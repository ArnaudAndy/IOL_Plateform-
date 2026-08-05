package com.iol.etlplatform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "RUSTFS_INTEGRATION", matches = "true")
class ObjectStorageServiceRustFsIT {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsSourceObject() throws Exception {
        String endpoint = env("OBJECT_STORAGE_ENDPOINT", "http://127.0.0.1:9000");
        String region = env("OBJECT_STORAGE_REGION", "us-east-1");
        String bucket = env("OBJECT_STORAGE_BUCKET", "iol-source-data");
        String accessKey = env("OBJECT_STORAGE_ACCESS_KEY", "rustfsadmin");
        String secretKey = env("OBJECT_STORAGE_SECRET_KEY", "rustfsadmin");
        byte[] content = "patient_id,full_name\nP-001,Ada Lovelace\n".getBytes(StandardCharsets.UTF_8);
        Path source = tempDir.resolve("patients.csv");
        Files.write(source, content);

        ObjectStorageService service = new ObjectStorageService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "endpoint", endpoint);
        ReflectionTestUtils.setField(service, "region", region);
        ReflectionTestUtils.setField(service, "bucket", bucket);
        ReflectionTestUtils.setField(service, "accessKey", accessKey);
        ReflectionTestUtils.setField(service, "secretKey", secretKey);
        ReflectionTestUtils.setField(service, "multipartPartSizeBytes", 5L * 1024 * 1024);

        ObjectStorageService.StoredObject stored = service.store(
                source, "rustfs-integration", "roundtrip", 0, source.getFileName().toString(), "text/csv");
        byte[] streamedContent = new byte[8 * 1024 * 1024 + 257];
        Arrays.fill(streamedContent, (byte) 'J');
        ObjectStorageService.StoredObject streamed = service.storeStreaming(
                "rustfs-integration", "multipart-roundtrip", 1,
                "patients.jsonl", "application/json",
                output -> output.write(streamedContent));

        try (S3Client client = s3(endpoint, region, accessKey, secretKey)) {
            byte[] downloaded = client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(stored.bucket())
                            .key(stored.objectKey())
                            .build())
                    .asByteArray();
            String checksum = client.headObject(HeadObjectRequest.builder()
                            .bucket(stored.bucket())
                            .key(stored.objectKey())
                            .build())
                    .metadata()
                    .get("sha256");

            assertArrayEquals(content, downloaded);
            assertEquals(stored.sha256(), checksum);
            byte[] streamedDownload = client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(streamed.bucket())
                            .key(streamed.objectKey())
                            .build())
                    .asByteArray();
            assertArrayEquals(streamedContent, streamedDownload);
            assertEquals(streamedContent.length, streamed.sizeBytes());
            assertEquals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(streamedContent)), streamed.sha256());

            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(stored.bucket())
                    .key(stored.objectKey())
                    .build());
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(streamed.bucket())
                    .key(streamed.objectKey())
                    .build());
        } finally {
            service.close();
        }
    }

    private S3Client s3(String endpoint, String region, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
