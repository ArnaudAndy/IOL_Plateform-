package com.iol.etlplatform.sourcegateway.exception;

/**
 * Demande inexploitable en l'etat.
 *
 * Volontairement propre au gateway plutot que partagee avec api-core: les deux
 * services doivent pouvoir evoluer sans se contraindre. Le transport ne
 * dependait de toute facon que de cette seule classe cote api-core.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
