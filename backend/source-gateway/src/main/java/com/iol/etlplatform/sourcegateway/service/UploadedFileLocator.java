package com.iol.etlplatform.sourcegateway.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.iol.etlplatform.sourcegateway.exception.BadRequestException;

/**
 * Localise un fichier deja charge et approuve, en LECTURE SEULE.
 *
 * Le depot, l'analyse antivirus et la promotion depuis la quarantaine restent
 * dans api-core: ce sont les operations declenchees par une route publique.
 * Le gateway ne fait que lire un fichier deja declare sain, via un montage en
 * lecture seule du meme volume.
 *
 * Ce decoupage est deliberé: le service qui ecrit et celui qui lit ne sont pas
 * le meme, et le gateway ne peut ni deposer, ni promouvoir, ni supprimer.
 */
@Service
public class UploadedFileLocator {

    /** Identifiant d'upload: un UUID, rien d'autre. */
    private static final String UPLOAD_ID_PATTERN = "[0-9a-fA-F-]{36}";

    private final Path uploadRoot;

    public UploadedFileLocator(@Value("${app.upload.root:/data/iol/uploads}") String uploadRoot) {
        this.uploadRoot = Path.of(uploadRoot).toAbsolutePath().normalize();
    }

    /**
     * Resout le chemin du fichier approuve d'un upload.
     *
     * @throws BadRequestException si l'identifiant est malforme, si le chemin
     *         sort de la racine, ou si aucun fichier n'est disponible
     */
    public Path resolve(String uploadId) {
        if (uploadId == null || !uploadId.matches(UPLOAD_ID_PATTERN)) {
            throw new BadRequestException("Identifiant de fichier invalide.");
        }
        Path directory = uploadRoot.resolve(uploadId).normalize();
        // Un identifiant construit ne doit jamais permettre de remonter hors de
        // la racine des uploads.
        if (!directory.startsWith(uploadRoot)) {
            throw new BadRequestException("Chemin de fichier refuse.");
        }
        if (!Files.isDirectory(directory)) {
            throw new BadRequestException("Fichier charge introuvable: " + uploadId);
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).findFirst()
                    .orElseThrow(() -> new BadRequestException("Fichier charge introuvable: " + uploadId));
        } catch (BadRequestException known) {
            throw known;
        } catch (Exception error) {
            throw new BadRequestException("Impossible de lire le fichier charge: " + error.getMessage());
        }
    }
}
