package com.iol.openhim.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenHimRegistrationServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void preservesReverseProxyPrefixAndInstallsMissingChannel() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, requests));
        server.start();

        MediatorProperties properties = new MediatorProperties();
        properties.setUrn("urn:mediator:iol-test");
        properties.setName("IOL Test Mediator");
        properties.setChannelPattern("^/interop/test(/.*)?$");
        properties.getOpenhim().setApiUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/openhim-api");
        properties.getOpenhim().setRegistrationEnabled(true);
        properties.getOpenhim().setInstallChannels(true);

        OpenHimRegistrationService service = new OpenHimRegistrationService(properties);
        service.registerAtStartup();

        assertTrue(service.isRegistered());
        assertEquals(List.of(
                "POST /openhim-api/mediators",
                "GET /openhim-api/channels",
                "POST /openhim-api/mediators/urn%3Amediator%3Aiol-test/channels"), requests);
    }

    @Test
    void concurrentStartupAndHeartbeatRegisterOnlyOnce() throws Exception {
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger mediatorRegistrations = new AtomicInteger();
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getRawPath().endsWith("/mediators")) {
                mediatorRegistrations.incrementAndGet();
                registrationEntered.countDown();
                try {
                    releaseRegistration.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            handle(exchange, requests);
        });
        server.start();

        MediatorProperties properties = propertiesForServer();
        OpenHimRegistrationService service = new OpenHimRegistrationService(properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var startup = executor.submit(service::registerAtStartup);
            assertTrue(registrationEntered.await(5, TimeUnit.SECONDS));
            var overlappingHeartbeat = executor.submit(service::heartbeat);
            overlappingHeartbeat.get(5, TimeUnit.SECONDS);
            releaseRegistration.countDown();
            startup.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRegistration.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, mediatorRegistrations.get());
        assertTrue(service.isRegistered());
    }

    private MediatorProperties propertiesForServer() {
        MediatorProperties properties = new MediatorProperties();
        properties.setUrn("urn:mediator:iol-test");
        properties.setName("IOL Test Mediator");
        properties.setChannelPattern("^/interop/test(/.*)?$");
        properties.getOpenhim().setApiUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/openhim-api");
        properties.getOpenhim().setRegistrationEnabled(true);
        properties.getOpenhim().setInstallChannels(true);
        return properties;
    }

    private void handle(HttpExchange exchange, List<String> requests) throws IOException {
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
        byte[] response = ("GET".equals(exchange.getRequestMethod()) ? "[]" : "{}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
