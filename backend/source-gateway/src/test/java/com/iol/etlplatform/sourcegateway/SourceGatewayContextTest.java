package com.iol.etlplatform.sourcegateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.iol.etlplatform.sourcegateway.service.TransportExecutionGuard;
import com.iol.etlplatform.sourcegateway.service.TransportPipeline;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifie que le contexte Spring demarre reellement, avec tous ses beans.
 *
 * Les tests unitaires construisent les services a la main et ne voient donc pas
 * une dependance manquante: le module compilait, ses tests passaient et son
 * image se construisait, alors que le conteneur aurait redemarre en boucle
 * faute de bean {@link TransportPipeline}. Ce test ferme cet angle mort.
 *
 * Aucune connexion reelle n'est etablie: le client MongoDB est paresseux et le
 * listener Kafka ne demarre pas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.data.mongodb.uri=mongodb://localhost:27017/iol_metadata_test"
})
class SourceGatewayContextTest {

    @Autowired
    private TransportExecutionGuard guard;

    @Autowired
    private TransportPipeline pipeline;

    @Test
    void leContexteDemarreAvecToutesSesDependances() {
        assertNotNull(guard, "le garde d'execution doit etre injectable");
        assertNotNull(pipeline,
                "un pipeline doit exister, meme de repli, sinon le service ne demarre pas");
    }
}
