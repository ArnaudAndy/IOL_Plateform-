package com.iol.etlplatform.service;

import com.iol.etlplatform.entity.AuditLog;
import com.iol.etlplatform.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service d'audit - Enregistre toutes les actions importantes
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Enregistrer une action
     */
    @Transactional
    public AuditLog logAction(String userId, String userRole, AuditLog.AuditAction action,
                              String resourceType, String resourceId, String description) {
        log.debug("Audit: {} par {} sur {}/{}", action, userId, resourceType, resourceId);

        HttpServletRequest request = null;
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                request = attrs.getRequest();
            }
        } catch (Exception e) {
            log.debug("Impossible de récupérer la requête HTTP pour l'audit");
        }

        AuditLog auditLog = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .userRole(userRole)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .description(description)
                .status(AuditLog.AuditStatus.SUCCESS)
                .timestamp(LocalDateTime.now())
                .ipAddress(request != null ? getClientIp(request) : "UNKNOWN")
                .userAgent(request != null ? request.getHeader("User-Agent") : "UNKNOWN")
                .build();

        return auditLogRepository.save(auditLog);
    }

    /**
     * Enregistrer une action échouée
     */
    @Transactional
    public AuditLog logFailedAction(String userId, String userRole, AuditLog.AuditAction action,
                                     String resourceType, String resourceId, String description,
                                     String errorMessage) {
        log.warn("Audit ÉCHEC: {} par {} - {}", action, userId, errorMessage);

        HttpServletRequest request = null;
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                request = attrs.getRequest();
            }
        } catch (Exception e) {
            log.debug("Impossible de récupérer la requête HTTP pour l'audit");
        }

        AuditLog auditLog = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .userRole(userRole)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .description(description)
                .status(AuditLog.AuditStatus.FAILURE)
                .errorMessage(errorMessage)
                .timestamp(LocalDateTime.now())
                .ipAddress(request != null ? getClientIp(request) : "UNKNOWN")
                .userAgent(request != null ? request.getHeader("User-Agent") : "UNKNOWN")
                .build();

        return auditLogRepository.save(auditLog);
    }

    /**
     * Enregistrer une mise à jour avec comparaison old/new
     */
    @Transactional
    public AuditLog logUpdate(String userId, String userRole, String resourceType,
                              String resourceId, String description,
                              Map<String, Object> oldValues, Map<String, Object> newValues) {
        log.debug("Audit UPDATE: {} par {} sur {}/{}", description, userId, resourceType, resourceId);

        AuditLog auditLog = AuditLog.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .userRole(userRole)
                .action(AuditLog.AuditAction.UPDATE)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .description(description)
                .oldValues(oldValues)
                .newValues(newValues)
                .status(AuditLog.AuditStatus.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build();

        return auditLogRepository.save(auditLog);
    }

    /**
     * Récupérer l'adresse IP du client
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * Récupérer tous les logs, du plus récent au plus ancien.
     */
    public List<AuditLog> getAllAuditLogs() {
        return auditLogRepository.findAllByOrderByTimestampDesc();
    }

    /**
     * Récupérer les logs d'audit pour une ressource
     */
    public List<AuditLog> getResourceAuditLog(String resourceType, String resourceId) {
        return auditLogRepository.findByResourceTypeAndResourceIdOrderByTimestampDesc(resourceType, resourceId);
    }

    /**
     * Récupérer les logs d'audit d'un utilisateur
     */
    public List<AuditLog> getUserAuditLog(String userId) {
        return auditLogRepository.findByUserIdOrderByTimestampDesc(userId);
    }

    /**
     * Récupérer les logs d'audit échoués
     */
    public List<AuditLog> getFailedAuditLogs() {
        return auditLogRepository.findByStatusOrderByTimestampDesc(AuditLog.AuditStatus.FAILURE);
    }
}
