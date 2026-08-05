package com.iol.etlplatform.service.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

@Component
@Slf4j
public class QuarantineRetentionService {

    private final Path quarantineRoot;
    private final Duration retention;
    private final Clock clock;

    @Autowired
    public QuarantineRetentionService(
            @Value("${app.upload.quarantine-root:/data/iol/quarantine}") String quarantineRoot,
            @Value("${app.malware-scan.quarantine-retention-days:30}") long retentionDays) {
        this(quarantineRoot, retentionDays, Clock.systemUTC());
    }

    QuarantineRetentionService(String quarantineRoot, long retentionDays, Clock clock) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("La retention de quarantaine doit etre d'au moins un jour.");
        }
        this.quarantineRoot = Path.of(quarantineRoot).toAbsolutePath().normalize();
        this.retention = Duration.ofDays(retentionDays);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.malware-scan.quarantine-cleanup-interval-ms:3600000}")
    public void purgeExpired() {
        if (!Files.isDirectory(quarantineRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Instant cutoff = clock.instant().minus(retention);
        try (Stream<Path> entries = Files.list(quarantineRoot)) {
            entries
                    .filter(this::isUploadDirectory)
                    .filter(path -> isOlderThan(path, cutoff))
                    .forEach(this::deleteUploadDirectory);
        } catch (IOException exception) {
            log.error("Impossible de parcourir la quarantaine {}", quarantineRoot, exception);
        }
    }

    private boolean isUploadDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && path.getFileName().toString().matches("[0-9a-fA-F-]{36}");
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            log.warn("Impossible de lire la date de quarantaine pour {}", path, exception);
            return false;
        }
    }

    private void deleteUploadDirectory(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(quarantineRoot) || normalized.equals(quarantineRoot)) {
            log.error("Suppression de quarantaine refusee pour le chemin {}", candidate);
            return;
        }

        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            log.info("Element expire supprime de la quarantaine: {}", normalized.getFileName());
        } catch (IOException | UncheckedIOException exception) {
            log.error("Impossible de purger la quarantaine {}", normalized, exception);
        }
    }
}
