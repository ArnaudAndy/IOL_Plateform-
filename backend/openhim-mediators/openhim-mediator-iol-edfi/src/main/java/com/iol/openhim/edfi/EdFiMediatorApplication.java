package com.iol.openhim.edfi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.iol.openhim")
public class EdFiMediatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdFiMediatorApplication.class, args);
    }
}
