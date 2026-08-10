# Documentation de référence

Cette page décrit l’état actuel du projet sans détailler les travaux historiques ou les documents de preuve obsolètes.

## Objet du projet

IOL est une plateforme d’intégration de données orientée métadonnées. Elle permet de définir des workflows, d’extraire des données depuis des sources variées, puis de les transformer et charger vers une destination.

## Composants réellement présents

- Frontend React/Vite : interface utilisateur.
- API Core Spring Boot : plan de contrôle, orchestration et services métier.
- Source Gateway : accès aux lignes source et transport Kafka/RustFS.
- Pipeline Consumer : exécution technique, contrôle d’intégrité et reprise persistante.
- Infrastructure Docker Compose : Kafka, MongoDB, PostgreSQL, RustFS, Keycloak, Vault, OpenHIM, ClamAV.

## Ce qu’il faut retenir

- `api-core` contrôle les exécutions ; `source-gateway` transporte les données.
- Hop et Spark ne disposent jamais des credentials de la source.
- Les secrets ne doivent jamais être stockés dans Git.
- La plateforme est en préproduction durcie, pas encore pleinement qualifiée pour une production complète.
- Le démarrage local se fait séparément pour le backend et le frontend.

## Documents utiles

- [../README.md](../README.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
- [SECURITY.md](SECURITY.md)
- [PRODUCTION_RUNBOOK.md](PRODUCTION_RUNBOOK.md)
