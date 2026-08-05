# Backend — api-core

Spring Boot API — ETL piloté par métadonnées, multi-sources, assistant SQL IA.

## Prérequis

- Java 17+
- Maven 3.9+
- Docker + Docker Compose

## Démarrage

```bash
# 1. Infrastructure
docker compose up -d

# 2. (Optionnel) Cles backend pour l'assistant SQL schema-only
export GEMINI_API_KEY="votre_cle"
export GROQ_API_KEY="votre_cle"

# 3. Compiler et lancer
cd api-core
mvn spring-boot:run
```

## Swagger

```
http://localhost:8084/swagger-ui.html   ← interface interactive
http://localhost:8084/api-docs          ← JSON OpenAPI 3.0
```

**Utilisation avec JWT :**
1. `POST /api/auth/login` → copier le `token`
2. Cliquer **Authorize** (🔓) → saisir `Bearer <token>`

## Endpoints principaux

| Méthode | URL | Rôle requis |
|---------|-----|-------------|
| POST | `/api/auth/register` | — |
| POST | `/api/auth/login` | — |
| GET | `/api/workflows` | USER, ADMIN |
| POST | `/api/workflows` | ADMIN |
| POST | `/api/workflows/discover` | USER, ADMIN |
| POST | `/api/orchestrator/run/{id}` | ADMIN |
| GET | `/api/logs/{workflowId}` | USER, ADMIN |
| POST | `/api/ai/generate-schema-sql` | USER, ADMIN |

## Services Docker (`docker-compose.yml`)

| Service | Port | Usage |
|---------|------|-------|
| PostgreSQL | 5432 | Lakehouse Bronze/Silver/Gold |
| MongoDB | 27017 | Métadonnées workflows |
| Kafka | 9092 | Bus événementiel pipelines |
| Zookeeper | 2181 | Coordination Kafka |
