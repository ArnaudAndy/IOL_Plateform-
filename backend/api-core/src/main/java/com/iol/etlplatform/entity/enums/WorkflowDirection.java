package com.iol.etlplatform.entity.enums;

/**
 * Sens d'un workflow dans la plateforme.
 *
 * <ul>
 *   <li>{@link #INTERNAL} — comportement existant : PULL depuis une source →
 *       médaillon Bronze/Silver/Gold → lakehouse. C'est le défaut.</li>
 *   <li>{@link #INBOUND} — préparation de la couche d'interopérabilité (phase 3 :
 *       OpenHIM + médiateurs). Un système externe pousse des données vers la
 *       plateforme, qui les normalise puis les traite via le médaillon.</li>
 *   <li>{@link #OUTBOUND} — après raffinage Gold, la plateforme prépare puis
 *       livre le résultat vers un système externe cible, via OpenHIM quand un
 *       channel sortant est configuré.</li>
 * </ul>
 */
public enum WorkflowDirection {
    INTERNAL,
    INBOUND,
    OUTBOUND
}
