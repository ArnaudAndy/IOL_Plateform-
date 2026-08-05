package com.iol.openhim.runtime;

import org.springframework.http.HttpHeaders;

public interface DomainPayloadAdapter {

    AdaptedPayload adapt(byte[] body, String contentType, String requestPath, HttpHeaders headers);

    String standardName();
}
