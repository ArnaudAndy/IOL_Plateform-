package com.iol.openhim.runtime;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("openHimRegistration")
public class OpenHimRegistrationHealthIndicator implements HealthIndicator {

    private final MediatorProperties properties;
    private final OpenHimRegistrationService registrationService;

    public OpenHimRegistrationHealthIndicator(
            MediatorProperties properties,
            OpenHimRegistrationService registrationService) {
        this.properties = properties;
        this.registrationService = registrationService;
    }

    @Override
    public Health health() {
        if (!properties.getOpenhim().isRegistrationEnabled()) {
            return Health.up()
                    .withDetail("registration", "disabled")
                    .build();
        }
        if (registrationService.isRegistered()) {
            return Health.up()
                    .withDetail("urn", properties.getUrn())
                    .build();
        }
        return Health.down()
                .withDetail("urn", properties.getUrn())
                .withDetail("reason", "OpenHIM registration or channel installation pending")
                .build();
    }
}
