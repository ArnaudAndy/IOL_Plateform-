package com.iol.etlplatform.sourcegateway.service;

import com.iol.etlplatform.sourcegateway.contract.TransportOrder;

/**
 * Sequence complete d'un transport, du workflow a la commande publiee.
 *
 * L'implementation doit respecter cet ordre, qui porte l'invariant de la
 * plateforme :
 *
 *   1. lire la configuration du workflow (MongoDB, lecture seule)
 *   2. resoudre les identifiants source (Vault Transit)
 *   3. ouvrir la source, extraire et transporter (Kafka par lots ou RustFS)
 *   4. PURGER les identifiants source de la commande
 *   5. verifier qu'aucun secret ne subsiste
 *   6. publier la commande d'execution — DERNIER effet observable
 *
 *  L'etape 4 n'est pas une precaution: c'est ce qui garantit que les moteurs
 *  Hop et Spark ne recoivent jamais de quoi joindre la source. L'etape 6 doit
 *  rester la derniere, car le listener n'acquitte qu'apres son retour.
 */
public interface TransportPipeline {

    /**
     * @throws Exception toute panne avant la publication; l'ordre sera redelivre
     */
    void run(TransportOrder order, Runnable assertOwnership) throws Exception;
}
