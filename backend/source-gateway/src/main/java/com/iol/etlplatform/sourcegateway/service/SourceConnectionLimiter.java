package com.iol.etlplatform.sourcegateway.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.iol.etlplatform.sourcegateway.exception.BadRequestException;

/**
 * Plafond global des connexions ouvertes vers les bases source.
 *
 * Les connexions source ne passent par aucun pool: chaque decouverte, chaque
 * estimation et chaque transport ouvre sa propre connexion via DriverManager.
 * Sans plafond, N requetes concurrentes ouvrent N connexions — ce qui peut
 * saturer la base d'un client avant meme de saturer IOL, et transformer une
 * charge interne en incident chez le client.
 *
 * Le semaphore est volontairement porte par le processus et non par workflow:
 * la ressource protegee est la capacite de connexion sortante dans son ensemble.
 */
@Component
public class SourceConnectionLimiter {

    private static final Logger log = LoggerFactory.getLogger(SourceConnectionLimiter.class);

    private final Semaphore permits;
    private final long waitSeconds;

    public SourceConnectionLimiter(
            @Value("${app.execution.max-source-connections:8}") int maxSourceConnections,
            @Value("${app.execution.source-connection-wait-seconds:60}") long waitSeconds) {
        this.permits = new Semaphore(Math.max(1, maxSourceConnections), true);
        this.waitSeconds = Math.max(1, waitSeconds);
    }

    /**
     * Execute l'action en detenant un permis de connexion source.
     *
     * Attend qu'un permis se libere plutot que de refuser immediatement: une
     * decouverte de schema est courte, la file s'ecoule vite. Au-dela du delai,
     * l'echec est explicite — mieux vaut un message clair qu'une attente infinie
     * qui immobilise un thread HTTP.
     */
    public <T> T withPermit(String usage, ThrowingSupplier<T> action) throws Exception {
        boolean acquired = permits.tryAcquire(waitSeconds, TimeUnit.SECONDS);
        if (!acquired) {
            log.warn("Plafond de connexions source atteint; {} refuse apres {} s", usage, waitSeconds);
            throw new BadRequestException(
                    "Trop de connexions source simultanees. Reessayez dans quelques instants.");
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }

    /** Nombre de permis encore disponibles, pour la supervision et les tests. */
    public int availablePermits() {
        return permits.availablePermits();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
