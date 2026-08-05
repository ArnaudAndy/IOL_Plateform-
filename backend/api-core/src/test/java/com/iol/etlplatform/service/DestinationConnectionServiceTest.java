package com.iol.etlplatform.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import org.mockito.junit.jupiter.MockitoExtension;

import com.iol.etlplatform.dto.connection.DestinationConnectionDto;
import com.iol.etlplatform.entity.CredentialEnvelope;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.repository.DestinationConnectionRepository;
import com.iol.etlplatform.service.credential.CredentialCipher;

@ExtendWith(MockitoExtension.class)
class DestinationConnectionServiceTest {

    @Mock
    private DestinationConnectionRepository repository;

    @Mock
    private CredentialCipher credentialCipher;

    private DestinationConnectionService service;

    @BeforeEach
    void setUp() {
        service = new DestinationConnectionService(repository, credentialCipher);
        lenient().when(repository.findByIsDefaultTrue()).thenReturn(List.of());
        lenient().when(repository.save(any(DestinationConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialCipher.encrypt(any(), any())).thenReturn(
                CredentialEnvelope.builder()
                        .provider("TEST")
                        .keyName("test-key")
                        .ciphertext("ciphertext")
                        .schemaVersion(1)
                        .build());
    }

    @Test
    void createNormalizesPostgresqlAliasAndDefaultPort() {
        DestinationConnectionDto dto = new DestinationConnectionDto();
        dto.setName("DWH");
        dto.setDbType("POSTGRESQL");
        dto.setHost("localhost");
        dto.setDatabase("lakehouse");
        dto.setUsername("postgres");
        dto.setPassword("postgres");

        DestinationConnectionDto saved = service.create(dto);

        assertEquals("POSTGRES", saved.getDbType());
        assertEquals("5432", saved.getPort());
    }

    @Test
    void jdbcUrlsSupportAllConfiguredDatabaseTypesAndAliases() {
        assertEquals("POSTGRES", service.normalizeDbType("POSTGRESQL"));
        assertEquals("MSSQL", service.normalizeDbType("SQLSERVER"));

        assertEquals("jdbc:postgresql://db.local:5432/lakehouse", service.buildJdbcUrl(conn("POSTGRESQL", "5432")));
        assertEquals("jdbc:mysql://db.local:3306/lakehouse", service.buildJdbcUrl(conn("MYSQL", "3306")));
        assertEquals("jdbc:mariadb://db.local:3306/lakehouse", service.buildJdbcUrl(conn("MARIADB", "3306")));
        assertEquals("jdbc:sqlserver://db.local:1433;databaseName=lakehouse;encrypt=true;trustServerCertificate=false", service.buildJdbcUrl(conn("SQLSERVER", "1433")));
        assertEquals("jdbc:oracle:thin:@//db.local:1521/lakehouse", service.buildJdbcUrl(conn("ORACLE", "1521")));
        assertEquals("jdbc:sqlite:C:/data/lakehouse.db", service.buildJdbcUrl(sqlite("C:/data/lakehouse.db")));
        assertEquals("jdbc:snowflake://db.local/?db=lakehouse", service.buildJdbcUrl(conn("SNOWFLAKE", "")));
        assertEquals("jdbc:redshift://db.local:5439/lakehouse", service.buildJdbcUrl(conn("REDSHIFT", "5439")));
    }

    @Test
    void sqliteDoesNotRequireHostOrUsername() {
        DestinationConnectionDto dto = new DestinationConnectionDto();
        dto.setName("Local SQLite");
        dto.setDbType("SQLITE");
        dto.setDatabase("C:/data/local.db");

        DestinationConnectionDto saved = service.create(dto);

        assertEquals("SQLITE", saved.getDbType());
        assertEquals("C:/data/local.db", saved.getDatabase());
    }

    private DestinationConnection conn(String dbType, String port) {
        DestinationConnection conn = new DestinationConnection();
        conn.setDbType(dbType);
        conn.setHost("db.local");
        conn.setPort(port);
        conn.setDatabase("lakehouse");
        conn.setUsername("user");
        conn.setPassword("secret");
        return conn;
    }

    private DestinationConnection sqlite(String databasePath) {
        DestinationConnection conn = new DestinationConnection();
        conn.setDbType("SQLITE");
        conn.setDatabase(databasePath);
        return conn;
    }
}
