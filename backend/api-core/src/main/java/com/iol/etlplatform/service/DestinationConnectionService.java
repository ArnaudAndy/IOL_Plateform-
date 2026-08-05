package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.connection.DestinationConnectionDto;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.repository.DestinationConnectionRepository;
import com.iol.etlplatform.service.credential.CredentialCipher;
import com.iol.etlplatform.service.credential.CredentialContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD des connexions de destination réutilisables + test de connexion JDBC réel.
 *
 * Le password n'est jamais exposé en lecture et n'est jamais persisté en clair.
 * En mise à jour, un password vide ou "***" conserve l'enveloppe existante.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DestinationConnectionService {

    private static final String MASKED_PASSWORD = "***";

    private final DestinationConnectionRepository repository;
    private final CredentialCipher credentialCipher;

    @Value("${app.credentials.environment:development}")
    private String credentialEnvironment;

    @Value("${app.credentials.allow-legacy-plaintext:true}")
    private boolean allowLegacyPlaintext;

    // ==================== CRUD ====================

    public DestinationConnectionDto create(DestinationConnectionDto dto) {
        validatePayload(dto);

        DestinationConnection entity = new DestinationConnection();
        entity.setId(UUID.randomUUID().toString());
        applyDto(entity, dto, true);
        entity.setCreatedBy(getCurrentUserEmail());
        updateCredential(entity, dto.getPassword(), true);
        String now = now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        // Une seule connexion par défaut
        if (entity.isDefault()) {
            clearOtherDefaults(null);
        }

        DestinationConnection saved = repository.save(entity);
        log.info("Connexion de destination créée: {} ({})", saved.getName(), saved.getId());
        return toMaskedDto(saved);
    }

    public List<DestinationConnectionDto> findAll() {
        List<DestinationConnection> connections = isCurrentUserAdmin()
                ? repository.findAllByOrderByNameAsc()
                : repository.findByCreatedByOrderByNameAsc(getCurrentUserEmail());
        return connections.stream()
                .map(this::toMaskedDto)
                .collect(Collectors.toList());
    }

    public DestinationConnectionDto findById(String id) {
        return toMaskedDto(getEntityById(id));
    }

    public DestinationConnectionDto getDefault() {
        List<DestinationConnection> defaults = isCurrentUserAdmin()
                ? repository.findByIsDefaultTrue()
                : repository.findByCreatedByAndIsDefaultTrue(getCurrentUserEmail());
        return defaults.stream().findFirst().map(this::toMaskedDto).orElse(null);
    }

    public DestinationConnectionDto update(String id, DestinationConnectionDto dto) {
        DestinationConnection entity = getEntityById(id);
        validatePayload(dto);

        applyDto(entity, dto, false);
        updateCredential(entity, dto.getPassword(), false);
        entity.setUpdatedAt(now());

        if (entity.isDefault()) {
            clearOtherDefaults(entity.getId());
        }

        DestinationConnection saved = repository.save(entity);
        log.info("Connexion de destination mise à jour: {} ({})", saved.getName(), saved.getId());
        return toMaskedDto(saved);
    }

    public void delete(String id) {
        repository.delete(getEntityById(id));
        log.info("Connexion de destination supprimée: {}", id);
    }

    /**
     * Retourne l'entité persistée. Le secret reste chiffré dans l'enveloppe et
     * doit être obtenu explicitement avec {@link #resolvePassword}.
     */
    public DestinationConnection getEntityById(String id) {
        DestinationConnection connection = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connexion de destination introuvable: " + id));
        assertCanAccess(connection);
        return connection;
    }

    public DestinationConnection getEntityByIdForOwner(String id, String ownerEmail) {
        DestinationConnection connection = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Connexion de destination introuvable: " + id));
        if (isCurrentUserAdmin() || (ownerEmail != null && ownerEmail.equals(connection.getCreatedBy()))) {
            return connection;
        }
        throw new BadRequestException("Acces refuse a cette connexion.");
    }

    public boolean existsById(String id) {
        return id != null && !id.isBlank() && repository.existsById(id);
    }

    /**
     * Déchiffre le credential au dernier moment pour un usage en mémoire.
     * Aucune valeur déchiffrée n'est réécrite dans l'entité ou dans MongoDB.
     */
    public String resolvePassword(DestinationConnection connection) {
        if (connection == null) {
            throw new BadRequestException("Connexion absente pour la résolution du credential.");
        }
        if (connection.getCredential() != null) {
            return credentialCipher.decrypt(connection.getCredential(), credentialContext(connection));
        }
        if (allowLegacyPlaintext && connection.getPassword() != null && !connection.getPassword().isBlank()) {
            log.warn("Credential legacy en clair utilisé pour la connexion {}. Lancez la migration immédiatement.",
                    connection.getId());
            return connection.getPassword();
        }
        throw new BadRequestException("Aucun credential chiffré n'est configuré pour cette connexion.");
    }

    public String resolveRuntimeHost(String host) {
        if (isRunningInDocker() && isLocalhost(host)) {
            return "host.docker.internal";
        }
        return host;
    }

    // ==================== TEST DE CONNEXION ====================

    public String testConnection(String id) {
        DestinationConnection conn = getEntityById(id);
        String url = buildJdbcUrl(conn);
        try {
            registerDriver(conn.getDbType());
            try (Connection ignored = DriverManager.getConnection(url, conn.getUsername(), resolvePassword(conn))) {
                log.info("Test connexion OK: {} -> {}", conn.getName(), url);
                return "Connexion réussie à " + url;
            }
        } catch (Exception e) {
            log.warn("Test connexion échoué pour {}: {}", conn.getName(), e.getMessage());
            throw new BadRequestException("Connexion impossible: " + e.getMessage() + dockerConnectionHint(conn));
        }
    }

    // ==================== HELPERS ====================

    private String dockerConnectionHint(DestinationConnection conn) {
        if (!isRunningInDocker() || conn == null || !isLocalhost(conn.getHost())) {
            return "";
        }

        return " Info: api-core tourne dans Docker, donc localhost a été traduit en host.docker.internal. "
                + "Si vous voulez la base Postgres du docker-compose, utilisez host=postgres.";
    }

    private boolean isRunningInDocker() {
        return Files.exists(Paths.get("/.dockerenv"));
    }

    private boolean isLocalhost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private void validatePayload(DestinationConnectionDto dto) {
        if (dto == null) {
            throw new BadRequestException("Payload connexion manquant.");
        }
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BadRequestException("Le nom de la connexion est obligatoire.");
        }
        String dbType = normalizeDbType(dto.getDbType());
        if (dto.getDatabase() == null || dto.getDatabase().isBlank()) {
            throw new BadRequestException("La base de données est obligatoire"
                    + ("SQLITE".equals(dbType) ? " (chemin du fichier SQLite)." : "."));
        }
        if ("SQLITE".equals(dbType)) {
            return;
        }
        if (dto.getHost() == null || dto.getHost().isBlank()) {
            throw new BadRequestException("Le host est obligatoire.");
        }
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new BadRequestException("Le username est obligatoire.");
        }
    }

    /**
     * Copie les champs du DTO vers l'entité.
     * @param isCreate true à la création (password requis), false en update
     *                 (password vide/"***" conserve l'ancien).
     */
    private void applyDto(DestinationConnection entity, DestinationConnectionDto dto, boolean isCreate) {
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        String dbType = normalizeDbType(dto.getDbType());
        entity.setDbType(dbType);
        entity.setHost(dto.getHost());
        entity.setPort(dto.getPort() != null && !dto.getPort().isBlank() ? dto.getPort() : defaultPort(dbType));
        entity.setDatabase(dto.getDatabase());
        entity.setUsername(dto.getUsername());
        entity.setSchema(dto.getSchema());
        entity.setAdditionalProperties(dto.getAdditionalProperties());
        entity.setDefault(dto.isDefault());

        // Le credential est traité séparément après l'identification du
        // propriétaire afin de construire un contexte cryptographique stable.
    }

    private void updateCredential(DestinationConnection entity, String incomingPassword, boolean create) {
        boolean keepExisting = incomingPassword == null || incomingPassword.isBlank()
                || MASKED_PASSWORD.equals(incomingPassword);
        if (keepExisting) {
            if (create && !"SQLITE".equals(normalizeDbType(entity.getDbType()))) {
                throw new BadRequestException("Le mot de passe de la connexion est obligatoire.");
            }
            return;
        }
        entity.setCredential(credentialCipher.encrypt(incomingPassword, credentialContext(entity)));
        entity.setPassword(null);
    }

    private CredentialContext credentialContext(DestinationConnection entity) {
        return new CredentialContext(
                credentialEnvironment,
                entity.getCreatedBy(),
                entity.getId(),
                "jdbc-password");
    }

    private void clearOtherDefaults(String exceptId) {
        String owner = getCurrentUserEmail();
        List<DestinationConnection> defaults = isCurrentUserAdmin()
                ? repository.findByIsDefaultTrue()
                : repository.findByCreatedByAndIsDefaultTrue(owner);
        for (DestinationConnection other : defaults) {
            if (exceptId == null || !other.getId().equals(exceptId)) {
                other.setDefault(false);
                repository.save(other);
            }
        }
    }

    private DestinationConnectionDto toMaskedDto(DestinationConnection entity) {
        DestinationConnectionDto dto = new DestinationConnectionDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setDbType(entity.getDbType());
        dto.setHost(entity.getHost());
        dto.setPort(entity.getPort());
        dto.setDatabase(entity.getDatabase());
        dto.setUsername(entity.getUsername());
        dto.setSchema(entity.getSchema());
        dto.setAdditionalProperties(entity.getAdditionalProperties());
        boolean configured = entity.getCredential() != null
                || (entity.getPassword() != null && !entity.getPassword().isBlank());
        dto.setPassword(configured ? MASKED_PASSWORD : "");
        dto.setDefault(entity.isDefault());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    String buildJdbcUrl(DestinationConnection conn) {
        String dbType = normalizeDbType(conn.getDbType());
        String host = resolveRuntimeHost(conn.getHost());
        String port = conn.getPort() != null && !conn.getPort().isBlank() ? conn.getPort() : defaultPort(dbType);
        String database = conn.getDatabase();
        return switch (dbType) {
            case "POSTGRES"  -> String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
            case "MYSQL"     -> String.format("jdbc:mysql://%s:%s/%s", host, port, database);
            case "MARIADB"   -> String.format("jdbc:mariadb://%s:%s/%s", host, port, database);
            case "MSSQL"     -> String.format(
                    "jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=true;trustServerCertificate=false",
                    host, port, database);
            case "ORACLE"    -> String.format("jdbc:oracle:thin:@//%s:%s/%s", host, port, database);
            case "SQLITE"    -> String.format("jdbc:sqlite:%s", database);
            case "SNOWFLAKE" -> String.format("jdbc:snowflake://%s/?db=%s", host, database);
            case "REDSHIFT"  -> String.format("jdbc:redshift://%s:%s/%s", host, port, database);
            default           -> throw new BadRequestException("Type de base non supporté pour le test: " + dbType);
        };
    }

    void registerDriver(String dbType) throws ClassNotFoundException {
        String type = normalizeDbType(dbType);
        switch (type) {
            case "POSTGRES"  -> Class.forName("org.postgresql.Driver");
            case "MYSQL"     -> Class.forName("com.mysql.cj.jdbc.Driver");
            case "MARIADB"   -> Class.forName("org.mariadb.jdbc.Driver");
            case "MSSQL"     -> Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            case "ORACLE"    -> Class.forName("oracle.jdbc.OracleDriver");
            case "SQLITE"    -> Class.forName("org.sqlite.JDBC");
            case "SNOWFLAKE" -> Class.forName("net.snowflake.client.jdbc.SnowflakeDriver");
            case "REDSHIFT"  -> Class.forName("com.amazon.redshift.jdbc42.Driver");
            default           -> { /* normalizeDbType a déjà validé le type. */ }
        }
    }

    String normalizeDbType(String dbType) {
        String normalized = dbType == null || dbType.isBlank()
                ? "POSTGRES"
                : dbType.trim().toUpperCase(Locale.ROOT).replace("-", "_");
        return switch (normalized) {
            case "POSTGRES", "POSTGRESQL", "PG" -> "POSTGRES";
            case "MYSQL" -> "MYSQL";
            case "MARIADB", "MARIA_DB" -> "MARIADB";
            case "MSSQL", "SQLSERVER", "SQL_SERVER", "MICROSOFT_SQL_SERVER" -> "MSSQL";
            case "ORACLE" -> "ORACLE";
            case "SQLITE", "SQLITE3" -> "SQLITE";
            case "SNOWFLAKE" -> "SNOWFLAKE";
            case "REDSHIFT", "AWS_REDSHIFT" -> "REDSHIFT";
            default -> throw new BadRequestException(
                    "Type de base non supporté pour le test: " + dbType
                            + ". Types supportés: POSTGRES, MYSQL, MARIADB, MSSQL, ORACLE, SQLITE, SNOWFLAKE, REDSHIFT.");
        };
    }

    private String defaultPort(String dbType) {
        return switch (normalizeDbType(dbType)) {
            case "POSTGRES" -> "5432";
            case "MYSQL", "MARIADB" -> "3306";
            case "MSSQL" -> "1433";
            case "ORACLE" -> "1521";
            case "REDSHIFT" -> "5439";
            default -> "";
        };
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "anonymous";
    }

    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private void assertCanAccess(DestinationConnection connection) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return;
        }
        if (!isCurrentUserAdmin() && !getCurrentUserEmail().equals(connection.getCreatedBy())) {
            throw new BadRequestException("Acces refuse a cette connexion.");
        }
    }
}
