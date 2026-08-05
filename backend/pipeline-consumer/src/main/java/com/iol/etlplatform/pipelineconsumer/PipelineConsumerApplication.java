package com.iol.etlplatform.pipelineconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PipelineConsumerApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PipelineConsumerApplication.class);

        // Auto-détection du profil OS si aucun profil n'a été fourni explicitement
        // (--spring.profiles.active=... ou SPRING_PROFILES_ACTIVE). Windows → "windows",
        // sinon → "linux". Un profil fourni manuellement n'est jamais écrasé.
        boolean profileProvided = System.getProperty("spring.profiles.active") != null
                || System.getenv("SPRING_PROFILES_ACTIVE") != null;
        if (!profileProvided) {
            String os = System.getProperty("os.name", "").toLowerCase();
            app.setAdditionalProfiles(os.contains("win") ? "windows" : "linux");
        }

        app.run(args);
    }
}
