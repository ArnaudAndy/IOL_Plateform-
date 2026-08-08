# Backend

Ce dossier contient la partie serveur du projet IOL ETL.

## Structure principale

- api-core : API Spring Boot principale, orchestration, sécurité et logique métier.
- source-gateway : lecture des sources externes et préparation des données.
- pipeline-consumer : consommation des messages, contrôle d’intégrité et exécution.
- iol-mediator : médiateurs et composants d’intégration.
- openhim : configuration OpenHIM et médiateurs associés.
- keycloak, vault, mongodb, postgres, rustfs, kafka : services d’infrastructure.
- ops : scripts d’exploitation, sécurité et automatisation.

## Démarrage rapide

```bash
cd backend
cp .env.example .env
docker compose up -d
```

## Développement local

```bash
cd backend/api-core
mvn spring-boot:run
```

## Points importants

- Le backend est le point central du contrôle du système.
- Les secrets doivent rester hors du dépôt.
- Les services d’infrastructure sont gérés via Docker Compose.
- Le module api-core est le cœur fonctionnel de l’application.
