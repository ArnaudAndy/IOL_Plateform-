package com.iol.openhim.iso20022;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.iol.openhim")
public class Iso20022MediatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(Iso20022MediatorApplication.class, args);
    }
}
