package com.iol.etlplatform.service;

import com.iol.etlplatform.service.security.MalwareScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLoadEstimatorServiceTest {

    @Test
    void measuresJdbcRowsAndConfiguresSparkPartitionsAutomatically(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("source.db");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE patient (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO patient(id, name) VALUES (1, 'A'), (2, 'B'), (3, 'C'), (4, 'D'), (5, 'E')");
        }

        UploadedFileService uploads = uploadedFileService(tempDir);
        SourceLoadEstimatorService service = service(uploads);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("uri", "jdbc:sqlite:" + database);
        config.put("source_config", Map.of("query", "SELECT id, name FROM patient"));

        var assessment = service.assess(List.of(source("SQLITE", config)), 3L);

        assertTrue(assessment.distributedRecommended());
        assertEquals(5L, assessment.estimatedRows());
        assertEquals(true, config.get("jdbc_partitioning_enabled"));
        assertEquals("id", config.get("partition_column"));
        assertEquals("NUMERIC", config.get("partition_type"));
        assertEquals("AUTO", config.get("partitioning_origin"));
    }

    @Test
    void recommendsDistributedModeForLargeUploadedFile(@TempDir Path tempDir) {
        UploadedFileService uploads = uploadedFileService(tempDir);
        var uploaded = uploads.store(new MockMultipartFile(
                "file", "patients.csv", "text/csv", new byte[2_048]));
        SourceLoadEstimatorService service = service(uploads);
        ReflectionTestUtils.setField(service, "fileSizeThresholdBytes", 1_024L);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upload_id", uploaded.getUploadId());
        var assessment = service.assess(List.of(source("CSV", config)), 1_000_000L);

        assertTrue(assessment.distributedRecommended());
        assertEquals("FILE_SIZE_THRESHOLD", assessment.reason());
        assertEquals(2_048L, assessment.estimatedBytes());
    }

    @Test
    void usesApiPaginationAsConservativeUpperBound(@TempDir Path tempDir) {
        UploadedFileService uploads = uploadedFileService(tempDir);
        SourceLoadEstimatorService service = service(uploads);
        Map<String, Object> pagination = Map.of("type", "PAGE", "page_size", 2_000, "max_pages", 600);
        Map<String, Object> config = Map.of("source_config", Map.of("pagination", pagination));

        var assessment = service.assess(List.of(source("API", config)), 1_000_000L);

        assertTrue(assessment.distributedRecommended());
        assertEquals(1_200_000L, assessment.estimatedRows());
        assertEquals("API_CONFIGURED_MAXIMUM", assessment.reason());
        assertFalse(assessment.complete());
    }

    private SourceLoadEstimatorService service(UploadedFileService uploads) {
        SourceLoadEstimatorService service = new SourceLoadEstimatorService(uploads, new SourceConnectionLimiter(8, 5));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "jdbcQueryTimeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "jdbcUnknownMode", "SPARK");
        ReflectionTestUtils.setField(service, "jdbcRowsPerPartition", 250_000L);
        ReflectionTestUtils.setField(service, "jdbcMaxPartitions", 16);
        ReflectionTestUtils.setField(service, "fileSizeThresholdBytes", 268_435_456L);
        return service;
    }

    private Map<String, Object> source(String protocol, Map<String, Object> config) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("source_name", protocol);
        source.put("config", config);
        return source;
    }

    private UploadedFileService uploadedFileService(Path tempDir) {
        return new UploadedFileService(
                tempDir.resolve("uploads").toString(),
                tempDir.resolve("quarantine").toString(),
                1024 * 1024,
                path -> MalwareScanResult.clean("test scanner"));
    }
}
