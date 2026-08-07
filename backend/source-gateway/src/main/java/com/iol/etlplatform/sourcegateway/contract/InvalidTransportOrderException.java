package com.iol.etlplatform.sourcegateway.contract;

/**
 * Ordre de transport inexploitable.
 *
 * Distingue le message empoisonne d'une panne transitoire: un ordre invalide ne
 * deviendra jamais valide, donc il part en file d'erreurs et la partition
 * avance. Une panne transitoire, elle, doit au contraire bloquer l'acquittement
 * pour que le message soit redelivre.
 */
public class InvalidTransportOrderException extends RuntimeException {
    public InvalidTransportOrderException(String message) {
        super(message);
    }
}
