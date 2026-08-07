package com.iol.etlplatform.sourcegateway.service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.iol.etlplatform.sourcegateway.contract.TransportOrder;
import com.iol.etlplatform.sourcegateway.entity.TransportClaim;

/**
 * Reservation d'execution adossee a MongoDB.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  POURQUOI L'UNICITE DU _id PLUTOT QU'UN VERROU APPLICATIF
 * ═══════════════════════════════════════════════════════════════════════════
 *  Un "lire puis ecrire" laisse une fenetre entre les deux operations: deux
 *  instances peuvent lire "libre" puis reserver toutes les deux. En posant
 *  execLogId comme cle primaire, c'est le serveur MongoDB qui arbitre, en une
 *  seule operation atomique. La seconde insertion echoue, point.
 *
 *  Le meme motif est deja utilise par InboundIdempotencyService dans api-core.
 */
@Service
public class MongoTransportExecutionGuard implements TransportExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(MongoTransportExecutionGuard.class);

    private final MongoTemplate mongoTemplate;
    private final TransportPipeline transportPipeline;
    private final String owner;

    @Value("${app.execution.claim-lease-seconds:1800}")
    private long claimLeaseSeconds;

    public MongoTransportExecutionGuard(MongoTemplate mongoTemplate, TransportPipeline transportPipeline) {
        this.mongoTemplate = mongoTemplate;
        this.transportPipeline = transportPipeline;
        this.owner = resolveOwner();
    }

    @Override
    public boolean claim(TransportOrder order) {
        Instant now = Instant.now();
        TransportClaim claim = TransportClaim.builder()
                .executionLogId(order.execLogId())
                .workflowId(order.workflowId())
                .organizationId(order.organizationId())
                .owner(owner)
                .status(TransportClaim.Status.IN_PROGRESS)
                .claimedAt(now)
                .leaseExpiresAt(now.plus(Duration.ofSeconds(claimLeaseSeconds)))
                .build();
        try {
            mongoTemplate.insert(claim);
            return true;
        } catch (DuplicateKeyException alreadyClaimed) {
            return reclaimIfAbandoned(order, now);
        }
    }

    /**
     * Reprend une execution dont le detenteur a disparu.
     *
     * La mise a jour est conditionnee sur l'etat ET l'expiration du bail dans la
     * requete elle-meme: si une autre instance reprend au meme instant, une
     * seule voit un document modifie.
     */
    private boolean reclaimIfAbandoned(TransportOrder order, Instant now) {
        Query abandoned = Query.query(Criteria
                .where("_id").is(order.execLogId())
                .and("status").is(TransportClaim.Status.IN_PROGRESS)
                .and("leaseExpiresAt").lt(now));
        Update takeOver = new Update()
                .set("owner", owner)
                .set("claimedAt", now)
                .set("leaseExpiresAt", now.plus(Duration.ofSeconds(claimLeaseSeconds)));

        boolean taken = mongoTemplate.updateFirst(abandoned, takeOver, TransportClaim.class)
                .getModifiedCount() > 0;
        if (taken) {
            log.warn("Reprise d'une execution au bail expire: execLogId={}", order.execLogId());
        }
        return taken;
    }

    @Override
    public void transportAndPublish(TransportOrder order) throws Exception {
        transportPipeline.run(order);
        // Etat terminal: une redelivrance ulterieure ne doit plus rien relancer.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.execLogId())),
                new Update()
                        .set("status", TransportClaim.Status.COMPLETED)
                        .set("completedAt", Instant.now()),
                TransportClaim.class);
    }

    @Override
    public void release(TransportOrder order, Throwable cause) {
        // Le bail est ramene a maintenant plutot que le document supprime: on
        // conserve la trace de la tentative et de son motif d'echec, tout en
        // rendant l'execution immediatement reprenable.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.execLogId())
                        .and("status").is(TransportClaim.Status.IN_PROGRESS)),
                new Update()
                        .set("leaseExpiresAt", Instant.now())
                        .set("failureReason", cause == null ? "inconnu" : String.valueOf(cause.getMessage())),
                TransportClaim.class);
    }

    private static String resolveOwner() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception unresolved) {
            return "source-gateway-" + java.util.UUID.randomUUID();
        }
    }
}
