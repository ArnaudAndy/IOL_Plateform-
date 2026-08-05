package com.iol.etlplatform.service.credential;

import com.iol.etlplatform.entity.CredentialEnvelope;

/** Abstraction commune à Vault Transit et au chiffrement local de développement. */
public interface CredentialCipher {
    CredentialEnvelope encrypt(String plaintext, CredentialContext context);

    String decrypt(CredentialEnvelope envelope, CredentialContext context);

    String provider();
}
