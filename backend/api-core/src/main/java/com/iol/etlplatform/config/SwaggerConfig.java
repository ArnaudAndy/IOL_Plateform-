package com.iol.etlplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Configuration Swagger/OpenAPI pour la plateforme ETL
 * 
 * Cette classe configure la documentation API OpenAPI 3.0 via Springdoc-OpenAPI.
 * Elle définit les informations générales de l'API, la sécurité JWT, et les règles d'authentification.
 */
@Configuration
public class SwaggerConfig {

    /**
     * Configure la définition OpenAPI 3.0 pour la plateforme ETL
     * 
     * @return Objet OpenAPI configuré avec les métadonnées et la sécurité
     * 
     * Description:
     * - Définit le schéma de sécurité JWT Bearer
     * - Configure les informations de l'API (titre, description, version)
     * - Ajoute les contacts et informations de licence
     * - Applique le schéma de sécurité globalement à toutes les APIs
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Plateforme ETL - API Documentation")
                        .description("API complète de la Plateforme ETL pilotée par métadonnées. " +
                                "Cette plateforme permet de créer, configurer et exécuter des workflows ETL " +
                                "pour l'extraction, la transformation et le chargement de données. " +
                                "Elle supporte plusieurs sources de données et formats de sortie.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Équipe ETL")
                                .email("etl-team@example.com")
                                .url("https://example.com")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("bearerAuth"))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT token pour l'authentification. " +
                                                "Format: Bearer <token>")));
    }
}
