package com.iol.etlplatform.sourcegateway.readmodel;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Enveloppe persistée à la place d'un secret métier en clair.
 *
 * Le ciphertext est inutilisable sans le fournisseur cryptographique et le
 * contexte exact de la ressource. Aucune clé de chiffrement n'est stockée dans
 * MongoDB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CredentialEnvelope {
    private String provider;
    private String keyName;
    private String ciphertext;
    private Integer keyVersion;
    private Instant encryptedAt;
    private Integer schemaVersion;
}
