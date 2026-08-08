package com.iol.etlplatform.sourcegateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.credential.AesGcmCredentialCipher;
import com.iol.etlplatform.sourcegateway.credential.CredentialCipher;
import com.iol.etlplatform.sourcegateway.credential.VaultTransitCredentialCipher;

/**
 * Selection du fournisseur de dechiffrement des credentials source.
 *
 * Doit rester alignee sur la configuration equivalente d'api-core: les deux
 * services lisent les MEMES enveloppes. Un fournisseur different de part et
 * d'autre rendrait les secrets illisibles cote gateway.
 *
 * En production, le profil impose VAULT_TRANSIT avec l'AppRole dedie du
 * gateway, dont la politique n'accorde que le dechiffrement. LOCAL_AES_GCM ne
 * sert qu'au developpement hors ligne.
 */
@Configuration
public class CredentialCipherConfiguration {

    @Bean
    CredentialCipher credentialCipher(
            @Value("${app.credentials.provider:LOCAL_AES_GCM}") String provider,
            @Value("${app.credentials.aes-key:}") String aesKey,
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
        // Refuser plutot que retomber sur un defaut: un fournisseur mal orthographie
        // ne doit pas silencieusement basculer le service en chiffrement local.
        throw new IllegalStateException("Fournisseur de credentials inconnu: " + provider);
    }
}
