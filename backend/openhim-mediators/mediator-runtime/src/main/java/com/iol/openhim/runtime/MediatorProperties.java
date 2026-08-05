package com.iol.openhim.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "mediator")
public class MediatorProperties {

    private String urn = "urn:mediator:iol-standard";
    private String name = "IOL Standard Mediator";
    private String version = "1.0.0";
    private String description = "IOL OpenHIM Java mediator";
    private String host = "localhost";
    private int port = 8080;
    private String routePath = "";
    private String channelPattern = "^/interop/standard(/.*)?$";
    private int channelPriority = 1;
    private String inboundAuthType = "private";
    private List<String> inboundAllowedRoles = new ArrayList<>(List.of("iol-inbound"));
    private String defaultStandardId = "";
    private String defaultWorkflowId = "";
    private String sourceSystem = "standard";
    private String iolMediatorUrl = "http://iol-mediator:3000";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
    private long maxRequestBytes = 268435456L;
    private final Openhim openhim = new Openhim();

    public String getUrn() { return urn; }
    public void setUrn(String urn) { this.urn = urn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getRoutePath() { return routePath; }
    public void setRoutePath(String routePath) { this.routePath = routePath; }
    public String getChannelPattern() { return channelPattern; }
    public void setChannelPattern(String channelPattern) { this.channelPattern = channelPattern; }
    public int getChannelPriority() { return channelPriority; }
    public void setChannelPriority(int channelPriority) {
        this.channelPriority = channelPriority;
    }
    public String getInboundAuthType() { return inboundAuthType; }
    public void setInboundAuthType(String inboundAuthType) { this.inboundAuthType = inboundAuthType; }
    public List<String> getInboundAllowedRoles() { return inboundAllowedRoles; }
    public void setInboundAllowedRoles(List<String> inboundAllowedRoles) {
        this.inboundAllowedRoles = inboundAllowedRoles == null
                ? new ArrayList<>()
                : new ArrayList<>(inboundAllowedRoles);
    }
    public String getDefaultStandardId() { return defaultStandardId; }
    public void setDefaultStandardId(String defaultStandardId) { this.defaultStandardId = defaultStandardId; }
    public String getDefaultWorkflowId() { return defaultWorkflowId; }
    public void setDefaultWorkflowId(String defaultWorkflowId) { this.defaultWorkflowId = defaultWorkflowId; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getIolMediatorUrl() { return iolMediatorUrl; }
    public void setIolMediatorUrl(String iolMediatorUrl) { this.iolMediatorUrl = iolMediatorUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public long getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }
    public Openhim getOpenhim() { return openhim; }

    public static class Openhim {
        private boolean registrationEnabled = true;
        private String apiUrl = "http://nginx/openhim-api";
        private String username = "";
        private String password = "";
        private long heartbeatMs = 10000;
        private boolean installChannels = true;

        public boolean isRegistrationEnabled() { return registrationEnabled; }
        public void setRegistrationEnabled(boolean registrationEnabled) {
            this.registrationEnabled = registrationEnabled;
        }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public long getHeartbeatMs() { return heartbeatMs; }
        public void setHeartbeatMs(long heartbeatMs) { this.heartbeatMs = heartbeatMs; }
        public boolean isInstallChannels() { return installChannels; }
        public void setInstallChannels(boolean installChannels) {
            this.installChannels = installChannels;
        }
    }
}
