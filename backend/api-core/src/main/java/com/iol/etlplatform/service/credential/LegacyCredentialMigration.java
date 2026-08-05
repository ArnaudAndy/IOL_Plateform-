package com.iol.etlplatform.service.credential;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.repository.DestinationConnectionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Migration idempotente des anciens documents MongoDB contenant `password`.
 * La sauvegarde du document chiffré et la suppression du champ legacy ont lieu
 * dans la même écriture MongoDB.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegacyCredentialMigration implements ApplicationRunner {
    private final DestinationConnectionRepository repository;
    private final CredentialCipher credentialCipher;

    @Value("${app.credentials.environment:development}")
    private String environment;

    @Value("${app.credentials.migrate-legacy-on-startup:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        int migrated = 0;
        for (DestinationConnection connection : repository.findAll()) {
            String legacy = connection.getPassword();
            if (connection.getCredential() != null || legacy == null || legacy.isBlank()) continue;
            CredentialContext context = new CredentialContext(
                    environment, connection.getCreatedBy(), connection.getId(), "jdbc-password");
            connection.setCredential(credentialCipher.encrypt(legacy, context));
            connection.setPassword(null);
            repository.save(connection);
            migrated++;
        }
        if (migrated > 0) {
            log.warn("Migration terminée: {} credential(s) legacy chiffré(s), champ password supprimé.", migrated);
        }
    }
}
