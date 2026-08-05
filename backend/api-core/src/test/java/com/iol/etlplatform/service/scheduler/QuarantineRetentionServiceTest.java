package com.iol.etlplatform.service.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarantineRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");

    @Test
    void purgesOnlyExpiredUploadDirectories(@TempDir Path tempDir) throws Exception {
        Path expired = createEntry(tempDir, "11111111-1111-1111-1111-111111111111", NOW.minusSeconds(31L * 86_400));
        Path recent = createEntry(tempDir, "22222222-2222-2222-2222-222222222222", NOW.minusSeconds(2L * 86_400));
        Path operatorDirectory = createEntry(tempDir, "manual-review", NOW.minusSeconds(90L * 86_400));

        QuarantineRetentionService service = new QuarantineRetentionService(
                tempDir.toString(), 30, Clock.fixed(NOW, ZoneOffset.UTC));

        service.purgeExpired();

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(operatorDirectory));
    }

    private Path createEntry(Path root, String name, Instant modifiedAt) throws Exception {
        Path directory = Files.createDirectories(root.resolve(name));
        Files.writeString(directory.resolve("sample.csv"), "id,name\n1,Alice\n");
        Files.setLastModifiedTime(directory, FileTime.from(modifiedAt));
        return directory;
    }
}
