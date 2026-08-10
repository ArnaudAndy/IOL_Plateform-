package com.iol.etlplatform.sourcegateway.service;

import com.iol.etlplatform.sourcegateway.contract.TransportOrder;

/**
 * Protege une execution contre le double transport et porte la sequence
 * transport → purge → publication.
 *
 * Separe volontairement en interface: la discipline d'acquittement du listener
 * se teste sans base ni source, et l'implementation reelle du transport arrive
 * a l'etape suivante sans modifier le listener.
 */
public interface TransportExecutionGuard {

    enum ClaimState {
        ACQUIRED,
        BUSY,
        COMPLETED,
        FAILED
    }

    /**
     * Resultat persistant d'une tentative de reservation.
     *
     * Le fencingToken identifie une acquisition precise du bail. Un ancien
     * detenteur ne peut donc plus terminer ou relacher le travail apres qu'une
     * autre instance a repris l'execution.
     */
    record Claim(ClaimState state, String fencingToken, int attempt) {
        public static Claim acquired(String fencingToken, int attempt) {
            return new Claim(ClaimState.ACQUIRED, fencingToken, attempt);
        }

        public static Claim of(ClaimState state, int attempt) {
            return new Claim(state, null, attempt);
        }

        public boolean acquired() {
            return state == ClaimState.ACQUIRED;
        }
    }

    /**
     * Reserve l'execution pour ce processus.
     *
     * @return l'etat durable de la reservation et, lorsqu'elle est acquise, le
     *         jeton de fencing qui doit accompagner tous les effets suivants
     */
    Claim claim(TransportOrder order);

    /**
     * Lit la source, transporte les donnees, purge les identifiants source puis
     * publie la commande d'execution.
     *
     * La publication de la commande doit etre le DERNIER effet observable: le
     * listener n'acquitte qu'apres le retour de cette methode.
     *
     * @throws Exception toute panne; l'ordre sera alors redelivre
     */
    void transportAndPublish(TransportOrder order, Claim claim) throws Exception;

    /**
     * Relache la reservation apres un echec, pour qu'une redelivrance puisse
     * reprendre le travail.
     */
    void release(TransportOrder order, Claim claim, Throwable cause);

    /**
     * Rend l'echec terminal apres epuisement des reprises. Le listener appelle
     * cette methode seulement apres avoir durablement publie l'ordre en DLQ.
     */
    void failPermanently(TransportOrder order, Claim claim, Throwable cause) throws Exception;
}
