package com.iol.etlplatform.sourcegateway.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.contract.TransportOrder;
import com.iol.etlplatform.sourcegateway.readmodel.WorkflowConfig;
import com.iol.etlplatform.sourcegateway.readmodel.WorkflowConfigReader;

/**
 * Sequence complete d'un transport, du workflow a la commande publiee.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  L'ORDRE DES ETAPES PORTE L'INVARIANT DE LA PLATEFORME
 * ═══════════════════════════════════════════════════════════════════════════
 *  Le pipeline-consumer ne doit JAMAIS voir une commande dont les donnees ne
 *  sont pas integralement transportees et dont les identifiants source ne sont
 *  pas purges. Deux etapes le garantissent, et leur ordre n'est pas negociable:
 *
 *   - le transport PURGE les identifiants source du payload une fois les
 *     donnees materialisees: a partir de la, la commande ne contient plus de
 *     quoi joindre la source, seulement de quoi lire l'artefact transporte;
 *   - la publication est le DERNIER effet observable, apres la verification
 *     qu'aucun secret ne subsiste.
 *
 *  Le listener n'acquitte qu'apres le retour de cette methode: un echec avant
 *  la publication laisse l'ordre redelivrable.
 */
@Service
public class DefaultTransportPipeline implements TransportPipeline {

    private static final Logger log = LoggerFactory.getLogger(DefaultTransportPipeline.class);
    private static final String LEGACY_REVISION = "legacy-unversioned";

    /** Cles refusees dans une commande publiee, quelle que soit leur profondeur. */
    private static final Set<String> CLES_SECRETES =
            Set.of("password", "authorization", "token", "apikey", "secret");

    private final WorkflowConfigReader workflowReader;
    private final CommandBuilder commandBuilder;
    private final SourceDataTransportService transportService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topics.commands.high:iol.pipeline.high}")
    private String topicHigh;

    @Value("${app.kafka.topics.commands.default:iol.pipeline.commands}")
    private String topicDefault;

    @Value("${app.kafka.topics.commands.low:iol.pipeline.low}")
    private String topicLow;

    public DefaultTransportPipeline(
            WorkflowConfigReader workflowReader,
            CommandBuilder commandBuilder,
            SourceDataTransportService transportService,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.workflowReader = workflowReader;
        this.commandBuilder = commandBuilder;
        this.transportService = transportService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(TransportOrder order, Runnable assertOwnership) throws Exception {
        // 1 & 2. Configuration du workflow et resolution des identifiants source.
        //        Le constructeur de commande dechiffre les credentials via Vault.
        WorkflowConfig workflow = workflowReader.requireById(order.workflowId());
        assertWorkflowRevision(order, workflow);
        Map<String, Object> command = commandBuilder.buildCommandPayload(workflow, order.execLogId());

        String topic = topicFor(order, workflow);

        // 3 & 4. Transport des donnees PUIS purge des identifiants source du
        //        payload. Les deux se font dans le meme appel: la purge suit
        //        immediatement la materialisation, sans fenetre intermediaire.
        List<Map<String, Object>> manifest = transportService.publishSourceData(
                topic, order.executionKey(), order.workflowId(), order.execLogId(), command);
        if (!manifest.isEmpty()) {
            command.put("sourceDataManifest", manifest);
            command.put("dataTransport", transportsOf(manifest));
        }

        // 5. Derniere barriere avant le fil. Si la purge a laisse passer quoi que
        //    ce soit, rien n'est publie.
        assertAucunSecretEnClair(command, "command");

        // 6. Juste avant l'effet final, le detenteur prouve que son bail est
        //    encore valide. Un worker lent dont le bail a ete repris s'arrete
        //    ici et ne peut pas publier une seconde commande.
        assertOwnership.run();

        // 7. Publication: dernier effet observable.
        kafkaTemplate.send(topic, order.executionKey(), objectMapper.writeValueAsString(command))
                .get(60, java.util.concurrent.TimeUnit.SECONDS);

        log.info("Commande publiee: workflow='{}' execLogId={} topic={} sources={}",
                 workflow.getWorkflowName(), order.execLogId(), topic, manifest.size());
    }

    /** Un seul mode de transport, ou MIXED si les sources different. */
    private String transportsOf(List<Map<String, Object>> manifest) {
        Set<String> modes = manifest.stream()
                .map(item -> String.valueOf(item.getOrDefault("transport", "KAFKA_CHUNKED")))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return modes.size() == 1 ? modes.iterator().next() : "MIXED";
    }

    private String topicFor(TransportOrder order, WorkflowConfig workflow) {
        int priority = order.priority() != null && order.priority() > 0
                ? order.priority()
                : (workflow.getPriority() > 0 ? workflow.getPriority() : 3);
        if (priority <= 2) return topicHigh;
        if (priority >= 4) return topicLow;
        return topicDefault;
    }

    private void assertWorkflowRevision(TransportOrder order, WorkflowConfig workflow) {
        String actual = workflow.getUpdatedAt() == null || workflow.getUpdatedAt().isBlank()
                ? LEGACY_REVISION
                : workflow.getUpdatedAt();
        if (!actual.equals(order.workflowRevision())) {
            throw new IllegalStateException(
                    "Le workflow a ete modifie apres la soumission de l'execution "
                            + order.execLogId() + ". Relancez-le pour utiliser la nouvelle configuration.");
        }
    }

    /**
     * Refuse de publier une commande contenant un secret en clair.
     *
     * Parcourt la structure en profondeur: un identifiant peut se cacher dans
     * une sous-configuration de source, pas seulement a la racine.
     */
    private void assertAucunSecretEnClair(Object value, String chemin) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String cle = String.valueOf(entry.getKey());
                String normalisee = cle.toLowerCase(java.util.Locale.ROOT)
                        .replace("-", "").replace("_", "");
                if (CLES_SECRETES.contains(normalisee)
                        && entry.getValue() != null
                        && !String.valueOf(entry.getValue()).isBlank()) {
                    throw new IllegalStateException(
                            "Credential en clair refuse avant publication Kafka: " + chemin + "." + cle);
                }
                assertAucunSecretEnClair(entry.getValue(), chemin + "." + cle);
            }
        } else if (value instanceof Iterable<?> items) {
            int index = 0;
            for (Object item : items) {
                assertAucunSecretEnClair(item, chemin + "[" + index++ + "]");
            }
        }
    }
}
