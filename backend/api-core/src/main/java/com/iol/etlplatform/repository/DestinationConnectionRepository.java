package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.DestinationConnection;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository pour les connexions de destination réutilisables (MongoDB).
 */
@Repository
public interface DestinationConnectionRepository extends MongoRepository<DestinationConnection, String> {

    Optional<DestinationConnection> findFirstByIsDefaultTrue();

    List<DestinationConnection> findByIsDefaultTrue();

    List<DestinationConnection> findByCreatedByOrderByNameAsc(String createdBy);

    List<DestinationConnection> findAllByOrderByNameAsc();

    List<DestinationConnection> findByCreatedByAndIsDefaultTrue(String createdBy);
}
