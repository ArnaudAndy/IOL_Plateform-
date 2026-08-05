package com.iol.etlplatform.config.health;

import com.iol.etlplatform.service.security.ClamAvMalwareScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("clamAvHealthIndicator")
@RequiredArgsConstructor
public class ClamAvHealthIndicator implements HealthIndicator {

    private final ClamAvMalwareScanner scanner;

    @Override
    public Health health() {
        if (!scanner.isEnabled()) {
            return Health.up().withDetail("status", "disabled").build();
        }
        try {
            return Health.up().withDetail("ping", scanner.ping()).build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
