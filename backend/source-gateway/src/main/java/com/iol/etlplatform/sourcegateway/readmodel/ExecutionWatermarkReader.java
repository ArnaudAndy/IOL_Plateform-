package com.iol.etlplatform.sourcegateway.readmodel;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Lit le filigrane du dernier chargement incremental reussi.
 *
 * Un workflow incremental n'extrait que les lignes apparues depuis sa derniere
 * execution reussie. Le repere est la valeur maximale de la colonne de suivi,
 * enregistree par le moteur a la fin du traitement.
 *
 * En LECTURE SEULE: c'est le pipeline-consumer qui ecrit ce filigrane au retour
 * du moteur, jamais le gateway.
 */
@Repository
public class ExecutionWatermarkReader {

    /**
     * Projection minimale: seuls les filigranes sont lus.
     *
     * Les valeurs sont des chaines: un filigrane peut etre un entier, un
     * horodatage ou un identifiant selon la colonne de suivi, et il est
     * reinjecte tel quel dans la requete source.
     */
    public record Watermarks(Map<String, String> perSource, String legacy) {
        public static Watermarks empty() {
            return new Watermarks(Collections.emptyMap(), null);
        }
    }

    private final MongoTemplate mongoTemplate;

    public ExecutionWatermarkReader(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Filigranes du dernier succes du workflow.
     *
     * Retourne des filigranes vides si aucune execution n'a encore reussi: le
     * premier chargement incremental est alors un chargement complet, ce qui est
     * le comportement attendu.
     */
    @SuppressWarnings("unchecked")
    public Watermarks lastSuccessful(String workflowId) {
        Query query = Query.query(Criteria
                        .where("workflowId").is(workflowId)
                        .and("status").is("SUCCESS"))
                .with(Sort.by(Sort.Direction.DESC, "endTime"))
                .limit(1);

        Optional<Map> document = Optional.ofNullable(
                mongoTemplate.findOne(query, Map.class, "execution_logs"));
        if (document.isEmpty()) {
            return Watermarks.empty();
        }
        Map<String, Object> log = document.get();
        Object legacy = log.get("last_successful_watermark");

        Map<String, String> perSource = Collections.emptyMap();
        if (log.get("last_successful_watermarks") instanceof Map<?, ?> stored) {
            perSource = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : stored.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    perSource.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return new Watermarks(perSource, legacy == null ? null : String.valueOf(legacy));
    }
}
