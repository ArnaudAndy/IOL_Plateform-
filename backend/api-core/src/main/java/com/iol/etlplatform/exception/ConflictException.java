package com.iol.etlplatform.exception;

/**
 * Etat courant incompatible avec la demande, sans que la demande soit invalide.
 *
 * Cas principal: une execution est deja en cours pour ce workflow. Rejouer la
 * meme demande plus tard peut reussir, ce qui distingue ce cas d'une
 * {@link BadRequestException}. Traduit en HTTP 409.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
