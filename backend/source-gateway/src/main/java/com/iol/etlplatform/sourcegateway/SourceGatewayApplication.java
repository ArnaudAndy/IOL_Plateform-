package com.iol.etlplatform.sourcegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Passerelle de lecture des sources.
 *
 * Seul service autorise a ouvrir une connexion vers la base d'un client. Il ne
 * publie aucun port vers l'exterieur: son entree metier est le topic des ordres
 * de transport, et son interface interne est protegee par mTLS sur le reseau
 * isole.
 */
@SpringBootApplication
public class SourceGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SourceGatewayApplication.class, args);
    }
}
