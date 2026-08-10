package com.iol.etlplatform.sourcegateway.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.iol.etlplatform.sourcegateway.exception.BadRequestException;
import com.iol.etlplatform.sourcegateway.readmodel.DestinationConnection;

/**
 * Resout le mot de passe d'une connexion source a partir de son enveloppe
 * chiffree.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  LE CONTEXTE EST UNE DONNEE AUTHENTIFIEE
 * ═══════════════════════════════════════════════════════════════════════════
 *  Il ne sert pas a documenter: il entre dans le calcul cryptographique. Un
 *  seul caractere different de celui utilise au chiffrement par api-core et le
 *  dechiffrement echoue.
 *
 *  Sa composition doit donc rester STRICTEMENT identique a
 *  {@code DestinationConnectionService.credentialContext()} d'api-core:
 *  (environnement, proprietaire, identifiant de connexion, "jdbc-password").
 *  Toute evolution de ce quadruplet cote api-core rend illisibles les
 *  enveloppes existantes cote gateway.
 */
@Service
public class SourceCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(SourceCredentialResolver.class);

    /** Usage declare a la creation du secret par api-core. */
    private static final String PURPOSE = "jdbc-password";

    private final MongoTemplate mongoTemplate;
    private final CredentialCipher credentialCipher;

    @Value("${app.credentials.environment:development}")
    private String credentialEnvironment;

    /**
     * Le plaintext historique n'est tolere qu'en developpement. En production, le
     * profil impose Vault Transit et cette porte reste fermee.
     */
    @Value("${app.credentials.allow-legacy-plaintext:false}")
    private boolean allowLegacyPlaintext;

    public SourceCredentialResolver(MongoTemplate mongoTemplate, CredentialCipher credentialCipher) {
        this.mongoTemplate = mongoTemplate;
        this.credentialCipher = credentialCipher;
    }

    /** Charge la connexion sans controle de propriete. */
    public DestinationConnection requireConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new BadRequestException("Identifiant de connexion source absent.");
        }
        DestinationConnection connection =
                mongoTemplate.findById(connectionId, DestinationConnection.class);
        if (connection == null) {
            throw new BadRequestException("Connexion source introuvable: " + connectionId);
        }
        return connection;
    }

    /**
     * Charge une connexion en verifiant qu'elle appartient au proprietaire du
     * workflow.
     *
     * Le gateway s'execute hors de toute session utilisateur: il n'y a pas de
     * contexte de securite dont deduire l'appelant. Le controle se fait donc sur
     * le proprietaire porte par le workflow, comme le fait
     * {@code getEntityByIdForOwner} dans api-core. Sans lui, un workflow pourrait
     * referencer la connexion d'un autre utilisateur et lire sa base.
     */
    public DestinationConnection requireConnectionForOwner(String connectionId, String ownerEmail) {
        DestinationConnection connection = requireConnection(connectionId);
        if (ownerEmail == null || !ownerEmail.equals(connection.getCreatedBy())) {
            throw new BadRequestException(
                    "Acces refuse a la connexion " + connectionId + " pour ce workflow.");
        }
        return connection;
    }

    /**
     * Traduit une adresse locale en nom joignable depuis un conteneur.
     *
     * Une connexion saisie avec « localhost » designe la machine de
     * l'utilisateur, pas le conteneur qui execute le transport. Sans cette
     * traduction, le gateway tenterait de se connecter a lui-meme.
     */
    public String resolveRuntimeHost(String host) {
        if (host == null || host.isBlank()) {
            return host;
        }
        String trimmed = host.trim();
        boolean local = "localhost".equalsIgnoreCase(trimmed)
                || "127.0.0.1".equals(trimmed)
                || "::1".equals(trimmed);
        if (local && isRunningInContainer()) {
            return "host.docker.internal";
        }
        return trimmed;
    }

    private boolean isRunningInContainer() {
        return java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"));
    }

    public String resolvePassword(DestinationConnection connection) {
        if (connection == null) {
            throw new BadRequestException("Connexion absente pour la resolution du credential.");
        }
        if (connection.getCredential() != null) {
            return credentialCipher.decrypt(connection.getCredential(), contextOf(connection));
        }
        if (allowLegacyPlaintext
                && connection.getPassword() != null && !connection.getPassword().isBlank()) {
            log.warn("Credential legacy en clair utilise pour la connexion {}. Migrez-la immediatement.",
                     connection.getId());
            return connection.getPassword();
        }
        throw new BadRequestException(
                "Aucun credential chiffre n'est configure pour la connexion " + connection.getId() + ".");
    }

    private CredentialContext contextOf(DestinationConnection connection) {
        return new CredentialContext(
                credentialEnvironment,
                connection.getCreatedBy(),
                connection.getId(),
                PURPOSE);
    }
}
