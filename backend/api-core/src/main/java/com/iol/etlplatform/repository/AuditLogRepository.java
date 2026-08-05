package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository pour AuditLog
 */
@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    List<AuditLog> findAllByOrderByTimestampDesc();

    /**
     * Trouver tous les logs d'un utilisateur
     */
    List<AuditLog> findByUserIdOrderByTimestampDesc(String userId);

    /**
     * Trouver les actions sur une ressource
     */
    List<AuditLog> findByResourceTypeAndResourceIdOrderByTimestampDesc(String resourceType, String resourceId);

    /**
     * Trouver les actions entre deux dates
     */
    List<AuditLog> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Trouver les actions échouées
     */
    List<AuditLog> findByStatusOrderByTimestampDesc(AuditLog.AuditStatus status);
}
