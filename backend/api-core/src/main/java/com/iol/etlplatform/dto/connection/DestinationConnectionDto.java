package com.iol.etlplatform.dto.connection;

import lombok.Data;
import java.util.Map;

/**
 * DTO d'une connexion de destination.
 *
 * En lecture (réponses GET), le password est masqué ("***") et n'est jamais
 * renvoyé en clair. En écriture (POST/PUT), un password vide ou "***" signifie
 * "ne pas changer le mot de passe existant".
 */
@Data
public class DestinationConnectionDto {
    private String id;
    private String name;
    private String description;
    private String dbType;
    private String host;
    private String port;
    private String database;
    private String username;
    private String password;
    private String schema;
    private Map<String, Object> additionalProperties;
    private boolean isDefault;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
