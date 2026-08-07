package com.iol.etlplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.RejectedExecutionException;

/**
 * Configuration applicative centrale.
 *
 * - TaskScheduler : thread pool pour les crons de pipeline (16 threads max)
 * - pipelineExecutionExecutor : pool BORNÉ pour l'extraction et le transport
 * - RestTemplate  : client HTTP pour appels internes
 * - EnableScheduling : active le support @Scheduled et TaskScheduler Spring
 * - EnableAsync    : active le support @Async
 */
@Configuration
@EnableScheduling
@EnableAsync
public class AppConfig {

    @Value("${app.execution.pool-size:4}")
    private int executionPoolSize;

    @Value("${app.execution.queue-capacity:32}")
    private int executionQueueCapacity;

    /**
     * Pool dédié à l'extraction et au transport des données source.
     *
     * Pourquoi il est borné et séparé des threads HTTP : un transport tient son
     * thread pendant toute la durée de l'extraction. Exécuté sur le pool Tomcat,
     * il consomme les threads qui servent le portail — une fois les 200 threads
     * pris, l'interface se fige d'un coup. Ici la charge lourde est confinée à
     * {@code pool-size} threads et {@code queue-capacity} exécutions en attente.
     *
     * En saturation, la politique de rejet lève une exception plutôt que de faire
     * exécuter la tâche par l'appelant : un refus explicite et immédiat vaut mieux
     * qu'un blocage silencieux du thread appelant.
     */
    @Bean("pipelineExecutionExecutor")
    public ThreadPoolTaskExecutor pipelineExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(executionPoolSize);
        executor.setMaxPoolSize(executionPoolSize);
        executor.setQueueCapacity(executionQueueCapacity);
        executor.setThreadNamePrefix("iol-pipeline-exec-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            throw new RejectedExecutionException(
                    "Capacite d'execution saturee: " + executionPoolSize + " transports actifs et "
                            + executionQueueCapacity + " en attente. Reessayez dans quelques instants.");
        });
        // Laisse les transports en cours se terminer proprement à l'arrêt plutôt
        // que d'abandonner des objets RustFS et des lots Kafka à mi-chemin.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }

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
