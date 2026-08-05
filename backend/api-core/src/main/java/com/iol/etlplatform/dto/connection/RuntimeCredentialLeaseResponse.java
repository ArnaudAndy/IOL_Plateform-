package com.iol.etlplatform.dto.connection;

import java.time.Instant;

/** Réponse éphémère réservée au worker et transmise uniquement sur mTLS. */
public record RuntimeCredentialLeaseResponse(
        String connectionId,
        String password,
        Instant expiresAt) { }
