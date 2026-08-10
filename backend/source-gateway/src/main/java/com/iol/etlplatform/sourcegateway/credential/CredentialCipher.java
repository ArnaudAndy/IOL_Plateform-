package com.iol.etlplatform.sourcegateway.credential;

import com.iol.etlplatform.sourcegateway.readmodel.CredentialEnvelope;

/**
 * Dechiffrement des identifiants de connexion source.
 *
 * Volontairement DEPOURVUE de methode {@code encrypt}, contrairement a son
 * homologue d'api-core. Enregistrer ou faire tourner un secret de connexion
 * reste une operation d'api-core, declenchee par un utilisateur authentifie.
 *
 * Cette asymetrie n'est pas qu'une convention: la politique Vault du gateway
 * (`backend/vault/policies/iol-source-gateway.hcl`) n'accorde que
 * {@code transit/decrypt}. Exposer un {@code encrypt} ici produirait du code
 * mort qui echouerait en production. L'interface reflete donc les droits reels.
 */
public interface CredentialCipher {

    String decrypt(CredentialEnvelope envelope, CredentialContext context);

    String provider();

    /** Verifie que le fournisseur peut servir avant d'accepter du trafic. */
    default void assertReady() {
        // Le chiffrement local n'a pas de dependance reseau.
    }
}
