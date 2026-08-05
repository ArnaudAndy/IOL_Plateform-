package com.iol.etlplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration applicative centrale.
 *
 * - TaskScheduler : thread pool pour les crons de pipeline (16 threads max)
 * - RestTemplate  : client HTTP pour appels internes
 * - EnableScheduling : active le support @Scheduled et TaskScheduler Spring
 * - EnableAsync    : active le support @Async
 */
@Configuration
@EnableScheduling
@EnableAsync
public class AppConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(16);
        scheduler.setThreadNamePrefix("iol-pipeline-cron-");
        scheduler.setErrorHandler(t ->
                org.slf4j.LoggerFactory.getLogger(AppConfig.class)
                        .error("[SCHEDULER] Erreur non gérée dans un cron pipeline: {}", t.getMessage(), t));
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }
}
