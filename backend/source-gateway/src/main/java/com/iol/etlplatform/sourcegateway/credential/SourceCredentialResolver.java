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

    /** Charge la connexion puis dechiffre son mot de passe. */
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
