package com.iol.etlplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class EtlPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(EtlPlatformApplication.class, args);
    }
}
