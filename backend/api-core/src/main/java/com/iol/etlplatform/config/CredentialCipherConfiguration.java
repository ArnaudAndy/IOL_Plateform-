package com.iol.etlplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.service.credential.AesGcmCredentialCipher;
import com.iol.etlplatform.service.credential.CredentialCipher;
import com.iol.etlplatform.service.credential.VaultTransitCredentialCipher;

/** Sélection explicite du fournisseur de chiffrement des credentials métier. */
@Configuration
public class CredentialCipherConfiguration {

    @Bean
    CredentialCipher credentialCipher(
            @Value("${app.credentials.provider:LOCAL_AES_GCM}") String provider,
            @Value("${app.credentials.aes-key}") String aesKey,
            @Value("${app.credentials.vault.address:}") String vaultAddress,
            @Value("${app.credentials.vault.transit-mount:transit}") String transitMount,
            @Value("${app.credentials.vault.key-name:iol-business-credentials}") String keyName,
            @Value("${app.credentials.vault.namespace:}") String namespace,
            @Value("${app.credentials.vault.token-file:}") String tokenFile,
            @Value("${app.credentials.vault.role-id-file:}") String roleIdFile,
            @Value("${app.credentials.vault.secret-id-file:}") String secretIdFile,
            ObjectMapper objectMapper) {
        if ("VAULT_TRANSIT".equalsIgnoreCase(provider)) {
            return new VaultTransitCredentialCipher(
                    vaultAddress, transitMount, keyName, namespace,
                    tokenFile, roleIdFile, secretIdFile, objectMapper);
        }
        if ("LOCAL_AES_GCM".equalsIgnoreCase(provider)) {
            return new AesGcmCredentialCipher(aesKey);
        }
        throw new IllegalStateException("Fournisseur de credentials inconnu: " + provider);
    }
}
