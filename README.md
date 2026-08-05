# IOL ETL Platform

Plateforme ETL pilotée par métadonnées — Spring Boot · MongoDB · PostgreSQL · Kafka · React.

## Guides de test

- [Guide complet de lecture du code et des flux](docs/GUIDE_LECTURE_CODE_IOL.md)
- [ETL PostgreSQL vers MySQL local](docs/TEST_ETL_POSTGRESQL_VERS_MYSQL_LOCAL.md)
- [OpenHIM et interopérabilité](docs/TEST_OPENHIM_INTEROPERABILITE.md)
- [Guide de mise en production](docs/GUIDE_MISE_EN_PRODUCTION.md)
- [Durcissement production, phase 2](docs/DURCISSEMENT_PRODUCTION_PHASE_2.md)

## Stack

| Composant | Rôle |
|-----------|------|
| `backend/api-core` | API REST Spring Boot (port 8084) |
| `backend/pipeline-consumer` | Consumer Kafka, staging JSONL, exécution Hop/Spark |
| `backend/iol-mediator` | Médiateur générique OpenHIM et livraison OUTBOUND |
| `backend/openhim-mediators` | Packs FHIR R4, ISO 20022 et Ed-Fi |
| `frontend` | Interface React + Vite servie par Nginx |
| MongoDB | Stockage des métadonnées workflows |
| PostgreSQL | Lakehouse cible (Bronze/Silver/Gold) |
| Kafka | Bus événementiel asynchrone entre api-core et pipeline-consumer |

## Démarrage

### 1. Infrastructure
```bash
cd backend
docker compose up -d
# → PostgreSQL :5432, MongoDB :27017, Kafka :9092
```

### 2. API (api-core)
```bash
cd backend/api-core
mvn spring-boot:run
# → http://localhost:8084
# → Swagger : http://localhost:8084/swagger-ui.html
```

### 3. Pipeline Consumer
```bash
cd backend/pipeline-consumer
mvn spring-boot:run
# → écoute le topic Kafka iol.pipeline.events
```

### 4. Frontend
```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

## Authentification

```bash
# Créer un compte
POST /api/auth/register  { "email": "...", "password": "..." }

# Se connecter → JWT token
POST /api/auth/login     { "email": "...", "password": "..." }

# Utiliser le token dans Swagger : bouton Authorize → Bearer <token>
```

## Variables d'environnement clés

| Variable | Défaut | Description |
|----------|--------|-------------|
| `MONGODB_URI` | `mongodb://localhost:27017/iol_metadata` | URI MongoDB |
| `POSTGRES_URL` | `jdbc:postgresql://localhost:5432/lakehouse` | URL PostgreSQL |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Brokers Kafka |
| `GEMINI_API_KEY` / `GROQ_API_KEY` | *(vide)* | Fournisseurs de l'assistant SQL, métadonnées de schéma uniquement |
| `JWT_SECRET` | *(base64 dev)* | Secret JWT — **changer en production** |

## Flux Kafka

```
Utilisateur configure workflow
        ↓
POST /api/orchestrator/run/{id}
        ↓
api-core transporte d'abord toutes les données dans Kafka ou RustFS
        ↓
api-core publie la commande → topic iol.pipeline.commands
        ↓
pipeline-consumer reconstitue le staging JSONL et choisit automatiquement Hop/Spark
        ↓
Erreur → Dead Letter Queue (iol.pipeline.commands.dlq)
```

## Créer un admin

Les utilisateurs sont stockés dans PostgreSQL (table `users`). Pour créer le
premier administrateur, appeler l'endpoint dédié (refusé si un admin existe déjà) :

```bash
POST /api/auth/create-initial-admin
{ "name": "...", "email": "...", "password": "..." }
```
