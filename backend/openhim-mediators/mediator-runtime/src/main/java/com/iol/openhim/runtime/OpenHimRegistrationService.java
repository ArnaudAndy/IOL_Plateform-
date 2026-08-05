package com.iol.openhim.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers a mediator and continuously reconciles its OpenHIM channel.
 *
 * Registration is not merely discovery: the reconciliation also enforces
 * routing priority and body-retention settings on an existing channel.
 */
@Component
public class OpenHimRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(OpenHimRegistrationService.class);

    private final MediatorProperties properties;
    private final RestClient restClient;
    private final AtomicBoolean registrationInProgress = new AtomicBoolean();
    private volatile boolean registered;

    public OpenHimRegistrationService(MediatorProperties properties) {
        this.properties = properties;
        String apiUrl = properties.getOpenhim().getApiUrl().replaceAll("/+$", "") + "/";
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(properties.getOpenhim().getUsername())) {
                        headers.setBasicAuth(
                                properties.getOpenhim().getUsername(),
                                properties.getOpenhim().getPassword());
                    }
                })
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAtStartup() {
        if (!properties.getOpenhim().isRegistrationEnabled()) return;
        // ApplicationReady and the first scheduled heartbeat can overlap on a
        // slow startup. Only one may install the uniquely named OpenHIM channel.
        if (registered || !registrationInProgress.compareAndSet(false, true)) return;
        try {
            restClient.post()
                    .uri("mediators")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mediatorConfig())
                    .retrieve()
                    .toBodilessEntity();
            installDefaultChannelIfMissing();
            registered = true;
            log.info("Médiateur {} enregistré auprès d'OpenHIM.", properties.getUrn());
        } catch (Exception error) {
            registered = false;
            log.warn("Enregistrement OpenHIM différé pour {}: {}",
                    properties.getUrn(), error.getMessage());
        } finally {
            registrationInProgress.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${mediator.openhim.heartbeat-ms:10000}")
    public void heartbeat() {
        if (!properties.getOpenhim().isRegistrationEnabled()) return;
        if (!registered) {
            registerAtStartup();
            return;
        }
        try {
            restClient.post()
                    .uri("mediators/{urn}/heartbeat", properties.getUrn())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("uptime", System.currentTimeMillis()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception error) {
            registered = false;
            log.warn("Heartbeat OpenHIM échoué pour {}: {}", properties.getUrn(), error.getMessage());
        }
    }

    public boolean isRegistered() {
        return registered;
    }

    private Map<String, Object> mediatorConfig() {
        Map<String, Object> route = routeConfig();
        Map<String, Object> channel = channelConfig(route);
        return Map.of(
                "urn", properties.getUrn(),
                "version", properties.getVersion(),
                "name", properties.getName(),
                "description", properties.getDescription(),
                "endpoints", List.of(route),
                "defaultChannelConfig", List.of(channel),
                "configDefs", List.of(
                        configDefinition("standardId", "IOL Standard ID"),
                        configDefinition("workflowId", "IOL Workflow ID")),
                "config", Map.of(
                        "standardId", properties.getDefaultStandardId(),
                        "workflowId", properties.getDefaultWorkflowId()));
    }

    private Map<String, Object> routeConfig() {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", properties.getName() + " route");
        route.put("host", properties.getHost());
        route.put("port", properties.getPort());
        if (StringUtils.hasText(properties.getRoutePath())) {
            route.put("path", properties.getRoutePath());
        }
        route.put("primary", true);
        route.put("type", "http");
        route.put("secured", false);
        route.put("status", "enabled");
        return route;
    }

    private Map<String, Object> channelConfig(Map<String, Object> route) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("name", channelName());
        channel.put("description", properties.getDescription());
        channel.put("urlPattern", properties.getChannelPattern());
        channel.put("type", "http");
        channel.put("methods", List.of("POST"));
        channel.put("authType", properties.getInboundAuthType());
        channel.put("allow", "private".equalsIgnoreCase(properties.getInboundAuthType())
                ? properties.getInboundAllowedRoles()
                : List.of());
        channel.put("routes", List.of(route));
        channel.put("priority", Math.max(1, properties.getChannelPriority()));
        channel.put("requestBody", false);
        channel.put("responseBody", false);
        channel.put("txRerunAcl", List.of());
        channel.put("txViewFullAcl", List.of());
        channel.put("status", "enabled");
        return channel;
    }

    @SuppressWarnings("unchecked")
    private void installDefaultChannelIfMissing() {
        if (!properties.getOpenhim().isInstallChannels()) return;
        Object response = restClient.get()
                .uri("channels")
                .retrieve()
                .body(Object.class);
        Map<String, Object> installed = response instanceof List<?> channels
                ? channels.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .filter(channel -> channelName().equals(
                            String.valueOf(channel.get("name"))))
                    .findFirst()
                    .orElse(null)
                : null;
        if (installed != null) {
            synchronizeInstalledChannel(installed);
            return;
        }

        restClient.post()
                .uri("mediators/{urn}/channels", properties.getUrn())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(channelName()))
                .retrieve()
                .toBodilessEntity();
        log.info("Canal OpenHIM {} installé.", channelName());
    }

    @SuppressWarnings("unchecked")
    private void synchronizeInstalledChannel(Map<String, Object> installed) {
        String channelId = String.valueOf(installed.getOrDefault("_id", ""));
        if (!StringUtils.hasText(channelId)) {
            log.warn("Canal OpenHIM {} trouvé sans identifiant; synchronisation ignorée.",
                    channelName());
            return;
        }

        Map<String, Object> updated = new LinkedHashMap<>(installed);
        Map<String, Object> expected = channelConfig(routeConfig());
        boolean changed = false;
        for (String field : List.of(
                "urlPattern",
                "type",
                "methods",
                "authType",
                "allow",
                "priority",
                "requestBody",
                "responseBody",
                "txRerunAcl",
                "txViewFullAcl",
                "status")) {
            if (!Objects.equals(updated.get(field), expected.get(field))) {
                updated.put(field, expected.get(field));
                changed = true;
            }
        }

        List<Map<String, Object>> routes = new ArrayList<>();
        Object rawRoutes = installed.get("routes");
        if (rawRoutes instanceof List<?> existingRoutes) {
            existingRoutes.stream()
                    .filter(Map.class::isInstance)
                    .map(route -> new LinkedHashMap<>((Map<String, Object>) route))
                    .forEach(routes::add);
        }
        Map<String, Object> expectedRoute = routeConfig();
        Map<String, Object> primaryRoute = routes.isEmpty()
                ? new LinkedHashMap<>()
                : routes.get(0);
        for (Map.Entry<String, Object> entry : expectedRoute.entrySet()) {
            if (!Objects.equals(primaryRoute.get(entry.getKey()), entry.getValue())) {
                primaryRoute.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!expectedRoute.containsKey("path") && primaryRoute.remove("path") != null) {
            changed = true;
        }
        if (routes.isEmpty()) {
            routes.add(primaryRoute);
            changed = true;
        }
        updated.put("routes", routes);

        if (!changed) return;
        restClient.put()
                .uri("channels/{channelId}", channelId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(updated)
                .retrieve()
                .toBodilessEntity();
        log.info("Canal OpenHIM {} synchronisé (priorité, route et confidentialité).",
                channelName());
    }

    private String channelName() {
        return properties.getName() + " INBOUND";
    }

    private Map<String, Object> configDefinition(String param, String displayName) {
        return Map.of(
                "param", param,
                "displayName", displayName,
                "description", displayName + " configured for this mediator channel.",
                "type", "string");
    }
}
