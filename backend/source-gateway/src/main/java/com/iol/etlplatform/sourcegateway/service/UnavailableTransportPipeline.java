package com.iol.etlplatform.sourcegateway.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pipeline de repli, actif tant que l'implementation reelle n'existe pas.
 *
 * Sans lui, {@link MongoTransportExecutionGuard} n'obtiendrait aucun bean
 * {@link TransportPipeline} et le contexte Spring echouerait au demarrage. Les
 * tests unitaires ne le verraient pas: ils construisent le garde a la main. Le
 * conteneur redemarrerait donc en boucle avec une CI verte.
 *
 * Le repli laisse le service demarrer et rester observable, mais REFUSE tout
 * ordre au lieu de faire semblant d'avoir transporte. Aucun travail n'est perdu:
 * l'exception empeche l'acquittement, donc l'ordre sera redelivre une fois le
 * pipeline reel deploye.
 *
 * A supprimer avec l'arrivee de l'implementation reelle, qui prendra sa place
 * automatiquement grace a {@code @ConditionalOnMissingBean}.
 */
@Configuration
public class UnavailableTransportPipeline {

    // Le nom de la methode devient le nom du bean: il ne doit pas reprendre
    // celui de la classe de configuration, deja enregistree comme bean.
    @Bean
    @ConditionalOnMissingBean(TransportPipeline.class)
    public TransportPipeline fallbackTransportPipeline() {
        return order -> {
            throw new IllegalStateException(
                    "Pipeline de transport non deploye sur ce service; ordre refuse sans "
                            + "acquittement (execLogId=" + order.execLogId() + "), il sera redelivre.");
        };
    }
}
