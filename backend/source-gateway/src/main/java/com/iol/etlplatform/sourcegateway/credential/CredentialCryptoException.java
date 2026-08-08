package com.iol.etlplatform.sourcegateway.credential;

/** Erreur fermée : un échec cryptographique ne doit jamais produire un fallback en clair. */
public class CredentialCryptoException extends RuntimeException {
    public CredentialCryptoException(String message) {
        super(message);
    }

    public CredentialCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
