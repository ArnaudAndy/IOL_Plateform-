package com.iol.etlplatform.entity.embedded;

import lombok.Data;

@Data
public class ConnectionDetails {
    private String host;
    private Integer port;
    private String database;
    private String username;
    private String password;
    private String url;

    // Explicit getter to avoid Lombok processing issues
    public String getUrl() {
        return this.url;
    }
}
