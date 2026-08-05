package com.iol.etlplatform.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import java.util.Map;

/**
 * Connexion de destination réutilisable et nommée (le data warehouse cible).
 *
 * Centralise les credentials d'une base de destination pour éviter de les
 * resaisir dans chaque workflow et garantir que toutes les tables Silver/Gold
 * d'un workflow multi-source vivent dans la même base (cohérence des JOIN Gold).
 *
 * Le mot de passe est chiffré avant toute persistance. Le champ legacy
 * {@code password} n'est conservé que le temps de migrer les documents créés
 * avant l'introduction de Vault Transit.
 */
@Data
@Document(collection = "destination_connections")
public class DestinationConnection {

    @Id
    private String id;

    private String name;          // "Lakehouse Prod", "Lakehouse Test"
    private String description;
    private String dbType;        // POSTGRES (extensible plus tard)
    private String host;
    private String port;
    private String database;
    private String username;
    private CredentialEnvelope credential;

    @Deprecated
    @JsonIgnore
    @Field("password")
    private String password;
    private String schema;
    private Map<String, Object> additionalProperties;
    private boolean isDefault;    // une seule connexion par défaut
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
