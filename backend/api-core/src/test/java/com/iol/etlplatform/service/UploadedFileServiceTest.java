package com.iol.etlplatform.service;

import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.service.security.MalwareScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadedFileServiceTest {

    @Test
    void storesAndResolvesUploadedFile(@TempDir Path tempDir) throws Exception {
        Path approved = tempDir.resolve("approved");
        Path quarantine = tempDir.resolve("quarantine");
        UploadedFileService service = new UploadedFileService(
                approved.toString(),
                quarantine.toString(),
                1024 * 1024,
                path -> MalwareScanResult.clean(path.toString()));
        MockMultipartFile file = new MockMultipartFile(
                "file", "patients.csv", "text/csv", "patientId\nP001\n".getBytes());

        var uploaded = service.store(file);
        Path resolved = service.resolve(uploaded.getUploadId());

        assertTrue(Files.isRegularFile(resolved));
        assertEquals("patientId\nP001\n", Files.readString(resolved));
        assertEquals("patients.csv", uploaded.getOriginalName());
        assertEquals("CLEAN", uploaded.getScanStatus());
        assertFalse(Files.exists(quarantine.resolve(uploaded.getUploadId())));
    }

    @Test
    void infectedFileRemainsInQuarantine(@TempDir Path tempDir) throws Exception {
        Path approved = tempDir.resolve("approved");
        Path quarantine = tempDir.resolve("quarantine");
        UploadedFileService service = new UploadedFileService(
                approved.toString(),
                quarantine.toString(),
                1024 * 1024,
                path -> MalwareScanResult.infected("Eicar-Signature", "Eicar-Signature FOUND"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "patients.csv", "text/csv", "unsafe".getBytes());

        BadRequestException error = assertThrows(BadRequestException.class, () -> service.store(file));

        assertTrue(error.getMessage().contains("quarantaine"));
        assertTrue(Files.walk(quarantine).anyMatch(Files::isRegularFile));
        assertFalse(Files.exists(approved));
    }
}
