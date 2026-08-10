package com.iol.etlplatform.sourcegateway.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reservation d'une execution par une instance du gateway.
 *
 * L'identifiant du document est l'execLogId: l'unicite de la cle primaire
 * MongoDB devient la porte de concurrence. Deux instances qui recoivent le meme
 * ordre apres un rebalance tentent la meme insertion; une seule reussit.
 *
 * Le bail existe pour le cas ou l'instance gagnante disparait sans relacher sa
 * reservation: passe son expiration, une autre instance peut reprendre. Sans
 * bail, une execution resterait bloquee pour toujours.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "transport_claims")
public class TransportClaim {

    /** execLogId: l'unicite du _id est le mecanisme de verrouillage. */
    @Id
    private String executionLogId;

    private String workflowId;
    private String organizationId;

    /** Instance detentrice, pour le diagnostic. */
    private String owner;

    /** Jeton renouvele a chaque acquisition: interdit les ecritures d'un ancien detenteur. */
    private String fencingToken;

    /** Nombre persistant d'acquisitions, y compris apres redemarrage ou rebalance. */
    private int attempts;

    private Status status;

    private Instant claimedAt;
    private Instant leaseExpiresAt;
    private Instant completedAt;
    private Instant failedAt;

    private String failureReason;

    public enum Status {
        /** Transport en cours par le detenteur. */
        IN_PROGRESS,
        /** Commande publiee: etat terminal, aucune reprise possible. */
        COMPLETED,
        /** Nombre maximal de tentatives atteint: diagnostic conserve en DLQ. */
        FAILED
    }

    /** Vrai si le bail a expire et qu'une autre instance peut reprendre. */
    public boolean leaseExpired(Instant now) {
        return leaseExpiresAt != null && now.isAfter(leaseExpiresAt);
    }
}
