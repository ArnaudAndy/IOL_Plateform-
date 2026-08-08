package com.iol.etlplatform.sourcegateway.credential;

/** Contexte authentifié qui lie un secret à une seule ressource et un usage. */
public record CredentialContext(
        String environment,
        String owner,
        String resourceId,
        String purpose) {

    public String associatedData() {
        return safe(environment) + "|" + safe(owner) + "|" + safe(resourceId) + "|" + safe(purpose);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
