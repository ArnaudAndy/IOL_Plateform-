package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSourceClientTest {

    @Test
    void paginatedApiIsStreamedToCsv(@TempDir Path tempDir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/patients", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String body = query != null && query.contains("page=1")
                    ? "[{\"patientId\":\"P001\",\"name\":\"Alice\"},{\"patientId\":\"P002\",\"name\":\"Bob, Jr\"}]"
                    : "[{\"patientId\":\"P003\",\"name\":\"Claire\"}]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            ApiSourceClient client = new ApiSourceClient(new ObjectMapper());
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("type", "PAGE");
            pagination.put("page_size", 2);
            pagination.put("max_pages", 5);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/patients");
            config.put("method", "GET");
            config.put("pagination", pagination);

            Path output = tempDir.resolve("patients.csv");
            long count = client.writeCsv(config, output);
            String csv = Files.readString(output);

            assertEquals(3, count);
            assertTrue(csv.startsWith("patientId,name"));
            assertTrue(csv.contains("P001,Alice"));
            assertTrue(csv.contains("P002,\"Bob, Jr\""));
            assertTrue(csv.contains("P003,Claire"));
        } finally {
            server.stop(0);
        }
    }
}
