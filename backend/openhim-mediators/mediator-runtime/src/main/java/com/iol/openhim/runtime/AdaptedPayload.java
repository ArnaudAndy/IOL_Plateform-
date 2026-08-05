package com.iol.openhim.runtime;

import java.util.List;
import java.util.Map;

public record AdaptedPayload(
        List<Map<String, Object>> records,
        Map<String, Object> metadata) {

    public AdaptedPayload {
        records = records == null ? List.of() : List.copyOf(records);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
