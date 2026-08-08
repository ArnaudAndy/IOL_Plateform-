package com.iol.etlplatform.sourcegateway.readmodel;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import com.iol.etlplatform.sourcegateway.exception.BadRequestException;

/**
 * Acces en lecture seule aux configurations de workflow.
 *
 * Volontairement expose comme un lecteur et non comme un {@code MongoRepository}:
 * l'interface Spring Data offrirait {@code save} et {@code delete}, que ce
 * service ne doit jamais appeler. Le compte MongoDB du gateway refuserait de
 * toute facon l'ecriture, mais mieux vaut que l'intention soit lisible dans le
 * code plutot que decouverte en production.
 */
@Repository
public class WorkflowConfigReader {

    private final MongoTemplate mongoTemplate;

    public WorkflowConfigReader(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * @throws BadRequestException si le workflow n'existe pas: un ordre qui
     *         reference un workflow disparu ne redeviendra jamais valide.
     */
    public WorkflowConfig requireById(String workflowId) {
        WorkflowConfig workflow = mongoTemplate.findById(workflowId, WorkflowConfig.class);
        if (workflow == null) {
            throw new BadRequestException("Workflow introuvable: " + workflowId);
        }
        return workflow;
    }
}
