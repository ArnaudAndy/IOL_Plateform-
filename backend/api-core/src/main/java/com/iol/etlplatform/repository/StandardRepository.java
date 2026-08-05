package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.Standard;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository pour Standard
 * Gère la persistence des standards dans MongoDB
 */
@Repository
public interface StandardRepository extends MongoRepository<Standard, String> {

    /**
     * Trouve un standard par son nom
     */
    Optional<Standard> findByName(String name);

    /**
     * Trouve tous les standards d'un domaine
     */
    List<Standard> findByDomain(Standard.StandardDomain domain);

    /**
     * Trouve tous les standards actifs
     */
    List<Standard> findByStatus(Standard.StandardStatus status);

    /**
     * Trouve tous les standards actifs d'un domaine spécifique
     */
    List<Standard> findByDomainAndStatus(Standard.StandardDomain domain, Standard.StandardStatus status);
}
