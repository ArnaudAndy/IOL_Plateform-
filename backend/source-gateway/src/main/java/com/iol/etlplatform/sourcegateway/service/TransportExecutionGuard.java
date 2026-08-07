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

    /**
     * Reserve l'execution pour ce processus.
     *
     * @return {@code true} si la reservation est acquise et le transport doit
     *         demarrer; {@code false} si une autre instance detient deja
     *         l'execution ou si elle est deja terminee — cas normal apres un
     *         rebalance Kafka, pas une erreur.
     */
    boolean claim(TransportOrder order);

    /**
     * Lit la source, transporte les donnees, purge les identifiants source puis
     * publie la commande d'execution.
     *
     * La publication de la commande doit etre le DERNIER effet observable: le
     * listener n'acquitte qu'apres le retour de cette methode.
     *
     * @throws Exception toute panne; l'ordre sera alors redelivre
     */
    void transportAndPublish(TransportOrder order) throws Exception;

    /**
     * Relache la reservation apres un echec, pour qu'une redelivrance puisse
     * reprendre le travail.
     */
    void release(TransportOrder order, Throwable cause);
}
