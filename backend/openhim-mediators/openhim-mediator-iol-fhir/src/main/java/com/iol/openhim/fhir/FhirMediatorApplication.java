package com.iol.openhim.fhir;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.iol.openhim")
public class FhirMediatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FhirMediatorApplication.class, args);
    }
}
