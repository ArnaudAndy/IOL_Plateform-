package com.iol.etlplatform.entity.enums;

/**
 * Énumération des rôles utilisateur avec hiérarchie
 * 
 * - ADMIN: Accès complet (gestion standards, workflows, sécurité)
 * - USER: Accès limité en lecture
 */
public enum UserRole {
    ADMIN,      // Administrateur - Accès complet
    USER        // Utilisateur - Accès en lecture
}
