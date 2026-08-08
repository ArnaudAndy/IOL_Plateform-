# IOL ETL Platform

Plateforme d’intégration de données pilotée par métadonnées. Le dépôt contient une implémentation fonctionnelle de base pour l’orchestration, le transport et l’exécution de workflows ETL.

## État actuel du dépôt

Le projet comprend aujourd’hui :
- un backend Spring Boot dans [backend/api-core](backend/api-core) ;
- un frontend React/Vite dans [frontend](frontend) ;
- un gateway source dans [backend/source-gateway](backend/source-gateway) ;
- un consumer de pipeline dans [backend/pipeline-consumer](backend/pipeline-consumer) ;
- une stack d’infrastructure Docker Compose avec Nginx, Kafka, MongoDB, PostgreSQL, RustFS, Keycloak, Vault, OpenHIM et ClamAV.

## Ce que fait le système

Le flux courant est le suivant :
1. l’utilisateur configure une source, une destination et un workflow ;
2. l’API prépare l’exécution ;
3. les données sont transportées puis traitées selon un chemin Bronze/Silver/Gold ;
4. l’utilisateur suit l’avancement depuis l’interface.

## Composants principaux

- Frontend : interface d’administration et de suivi.
- API : point central d’orchestration, sécurité et logique métier.
- Source Gateway : lecture des sources et préparation des données.
- Pipeline Consumer : réception, contrôle d’intégrité et exécution technique.
- Infrastructure : Kafka, RustFS, MongoDB, PostgreSQL, Keycloak et Vault.

## Démarrage rapide

### Prérequis
- Docker Desktop avec Compose v2
- Java 17
- Maven 3.9+
- Node.js 20+

### 1. Préparer l’environnement
```bash
cd backend
cp .env.example .env
```

### 2. Construire le frontend
```bash
cd frontend
npm ci
npm run build
```

### 3. Démarrer la pile
```bash
cd backend
docker compose up -d
```

### 4. Vérifier l’accès
- interface : http://localhost
- API : http://localhost/api
- santé du backend : http://localhost/actuator/health

## Développement local

### Backend
```bash
cd backend/api-core
mvn spring-boot:run
```

### Frontend
```bash
cd frontend
npm run dev
```

## Statut opérationnel

Le dépôt est en état de préproduction durcie. Les composants principaux sont présents et configurés, mais la mise en production complète exige encore une qualification opérationnelle, une gouvernance stricte des secrets et des validations de sécurité spécifiques à l’environnement cible.

## Documentation de référence

- [docs/README.md](docs/README.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [docs/PRODUCTION_RUNBOOK.md](docs/PRODUCTION_RUNBOOK.md)
