package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.file.UploadedFileResponse;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.service.security.MalwareScanResult;
import com.iol.etlplatform.service.security.MalwareScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class UploadedFileService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "csv", "tsv", "txt", "xls", "xlsx", "parquet", "avro", "orc");

    private final Path uploadRoot;
    private final Path quarantineRoot;
    private final long maxFileSizeBytes;
    private final MalwareScanner malwareScanner;

    @Autowired
    public UploadedFileService(
            @Value("${app.upload.root:/data/iol/uploads}") String uploadRoot,
            @Value("${app.upload.quarantine-root:/data/iol/quarantine}") String quarantineRoot,
            @Value("${app.upload.max-file-size-bytes:536870912}") long maxFileSizeBytes,
            MalwareScanner malwareScanner) {
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
        this.quarantineRoot = Path.of(quarantineRoot).toAbsolutePath().normalize();
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.malwareScanner = malwareScanner;
    }

    public UploadedFileResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Le fichier est vide.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BadRequestException("Le fichier depasse la taille maximale autorisee de "
                    + maxFileSizeBytes + " octets.");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bin";
        String safeName = sanitizeFilename(originalName);
        validateExtension(safeName);
        String uploadId = UUID.randomUUID().toString();
        Path quarantineDirectory = quarantineRoot.resolve(uploadId).normalize();
        Path quarantinedFile = quarantineDirectory.resolve(safeName).normalize();
        Path uploadDirectory = uploadRoot.resolve(uploadId).normalize();
        Path target = uploadDirectory.resolve(safeName).normalize();
        ensureInsideRoot(target, uploadRoot);
        ensureInsideRoot(quarantinedFile, quarantineRoot);

        try {
            Files.createDirectories(quarantineDirectory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, quarantinedFile, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = sha256(quarantinedFile);
            MalwareScanResult scan = malwareScanner.scan(quarantinedFile);
            assertScanAccepted(uploadId, safeName, sha256, scan);

            Files.createDirectories(uploadDirectory);
            moveToApprovedStorage(quarantinedFile, target);
            Files.deleteIfExists(quarantineDirectory);
            log.info("Fichier analyse et approuve: uploadId={} name={} size={} sha256={} scanStatus={}",
                    uploadId, safeName, file.getSize(), sha256, scan.status());
            return UploadedFileResponse.builder()
                    .uploadId(uploadId)
                    .originalName(originalName)
                    .contentType(file.getContentType())
                    .sizeBytes(file.getSize())
                    .sha256(sha256)
                    .scanStatus(scan.status().name())
                    .storagePath(target.toString())
                    .build();
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception e) {
            throw new BadRequestException("Impossible de stocker le fichier: " + e.getMessage());
        }
    }

    public Path resolve(String uploadId) {
        if (uploadId == null || !uploadId.matches("[0-9a-fA-F-]{36}")) {
            throw new BadRequestException("Identifiant de fichier invalide.");
        }
        Path directory = uploadRoot.resolve(uploadId).normalize();
        ensureInsideRoot(directory, uploadRoot);
        if (!Files.isDirectory(directory)) {
            throw new BadRequestException("Fichier charge introuvable: " + uploadId);
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).findFirst()
                    .orElseThrow(() -> new BadRequestException("Fichier charge introuvable: " + uploadId));
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Impossible de lire le fichier charge: " + e.getMessage());
        }
    }

    private String sanitizeFilename(String filename) {
        String leaf = Path.of(filename.replace('\\', '/')).getFileName().toString();
        String safe = leaf.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isBlank() || safe.startsWith(".")) {
            throw new BadRequestException("Nom de fichier invalide.");
        }
        return safe;
    }

    private void validateExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Format non autorise. Formats acceptes: "
                    + String.join(", ", ALLOWED_EXTENSIONS));
        }
    }

    private void assertScanAccepted(String uploadId, String safeName, String sha256,
                                    MalwareScanResult scan) {
        if (scan.status() == MalwareScanResult.Status.CLEAN
                || scan.status() == MalwareScanResult.Status.SKIPPED) {
            return;
        }
        log.warn("Fichier bloque en quarantaine: uploadId={} name={} sha256={} status={} signature={} detail={}",
                uploadId, safeName, sha256, scan.status(), scan.signature(), scan.detail());
        if (scan.status() == MalwareScanResult.Status.INFECTED) {
            throw new BadRequestException("Fichier dangereux detecte et place en quarantaine. Reference: "
                    + uploadId + ".");
        }
        throw new BadRequestException("Analyse antivirus incomplete; fichier conserve en quarantaine. Reference: "
                + uploadId + ".");
    }

    private void moveToApprovedStorage(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureInsideRoot(Path path, Path root) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new BadRequestException("Chemin de fichier invalide.");
        }
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
