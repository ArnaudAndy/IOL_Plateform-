package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.StandardTerm;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository pour StandardTerm
 * Gère la persistence des termes standard dans MongoDB
 */
@Repository
public interface StandardTermRepository extends MongoRepository<StandardTerm, String> {

    /**
     * Trouve tous les termes d'un standard
     */
    List<StandardTerm> findByStandardId(String standardId);

    /**
     * Trouve un terme par son nom et son standard
     */
    Optional<StandardTerm> findByStandardIdAndTermName(String standardId, String termName);

    /**
     * Trouve tous les termes par type de données
     */
    List<StandardTerm> findByDataType(StandardTerm.DataType dataType);

    /**
     * Compte les termes d'un standard
     */
    long countByStandardId(String standardId);
}
