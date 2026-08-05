package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.ai.AiSqlResponse;
import com.iol.etlplatform.dto.ai.SchemaOnlySqlRequest;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.repository.QueryHistoryRepository;
import com.iol.etlplatform.repository.StandardRepository;
import com.iol.etlplatform.repository.StandardTermRepository;
import com.iol.etlplatform.service.security.AiPromptPrivacyGuard;
import com.iol.etlplatform.util.SqlSafetyValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiServiceTest {

    @Test
    void usesDestinationDialectFallsBackAndNeverSendsConnectionSecrets() throws Exception {
        AtomicInteger geminiCalls = new AtomicInteger();
        AtomicInteger groqCalls = new AtomicInteger();
        AtomicReference<String> groqBody = new AtomicReference<>("");

        HttpServer gemini = server(exchange -> {
            geminiCalls.incrementAndGet();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        HttpServer groq = server(exchange -> {
            groqCalls.incrementAndGet();
            groqBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"content":"SELECT customer_id, SUM(amount) AS total FROM sales GROUP BY customer_id"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        try {
            WorkflowService workflowService = mock(WorkflowService.class);
            DestinationConnectionService destinationService = mock(DestinationConnectionService.class);
            QueryHistoryRepository historyRepository = mock(QueryHistoryRepository.class);
            WorkflowConfig workflow = new WorkflowConfig();
            workflow.setId("wf-sales");
            workflow.setDestinationConnectionId("mysql-target");
            DestinationConnection destination = new DestinationConnection();
            destination.setId("mysql-target");
            destination.setDbType("MYSQL");
            destination.setHost("private-database.internal");
            destination.setUsername("etl-secret-user");
            destination.setPassword("never-send-this-password");

            when(workflowService.getEntityById("wf-sales")).thenReturn(workflow);
            when(destinationService.getEntityById("mysql-target")).thenReturn(destination);
            when(destinationService.normalizeDbType("MYSQL")).thenReturn("MYSQL");
            when(historyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            AiService service = new AiService(
                    workflowService,
                    historyRepository,
                    WebClient.builder().build(),
                    new SqlSafetyValidator(),
                    mock(StandardRepository.class),
                    mock(StandardTermRepository.class),
                    destinationService,
                    new AiPromptPrivacyGuard());
            ReflectionTestUtils.setField(service, "geminiEndpoint", endpoint(gemini));
            ReflectionTestUtils.setField(service, "geminiApiKey", "gemini-test-key");
            ReflectionTestUtils.setField(service, "geminiModel", "gemini-test");
            ReflectionTestUtils.setField(service, "groqEndpoint", endpoint(groq));
            ReflectionTestUtils.setField(service, "groqApiKey", "groq-test-key");
            ReflectionTestUtils.setField(service, "groqModel", "groq-test");
            ReflectionTestUtils.setField(service, "timeoutSeconds", 5L);

            SchemaOnlySqlRequest request = new SchemaOnlySqlRequest();
            request.setWorkflowId("wf-sales");
            request.setInstruction("Calculer le total par client");
            request.setColumns(List.of("customer_id", "amount"));
            request.setSourceTable("sales");
            request.setTargetTable("sales_totals");
            request.setGenerationType(SchemaOnlySqlRequest.GenerationType.AGGREGATION);

            AiSqlResponse response = service.generateSchemaOnlySql(request);

            assertTrue(response.getGeneratedSql().startsWith("SELECT customer_id"));
            assertEquals(1, geminiCalls.get());
            assertEquals(1, groqCalls.get());
            assertTrue(groqBody.get().contains("MYSQL"));
            assertTrue(groqBody.get().contains("customer_id"));
            assertFalse(groqBody.get().contains("private-database.internal"));
            assertFalse(groqBody.get().contains("etl-secret-user"));
            assertFalse(groqBody.get().contains("never-send-this-password"));
        } finally {
            gemini.stop(0);
            groq.stop(0);
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
        return server;
    }

    private String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }
}
