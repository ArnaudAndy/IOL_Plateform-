# Documentation Technique — IOL ETL Platform

## Rôles utilisateur

| Rôle | Droits |
|------|--------|
| `ADMIN` | CRUD workflows, exécution, gestion utilisateurs, statistiques |
| `USER` | Lecture workflows, discovery, SQL workbench, logs |

## Modèle de données MongoDB

### Collection `workflow_configs`
```json
{
  "id": "string",
  "workflowName": "string",
  "protocol": "POSTGRES | MYSQL | MONGODB | CSV | REST | ...",
  "direction": "INTERNAL | INBOUND | OUTBOUND",
  "standardId": "string | null",
  "standardDomain": "string (déprécié)",
  "sourceConfig": { "host": "...", "database": "...", "table": "..." },
  "fieldsJson": "[{\"sourceName\":\"...\",\"type\":\"...\",\"iolTerm\":\"...\"}]",
  "metadataJson": "{ ... JSON complet envoyé à Hop ... }",
  "aggregationScripts": "SELECT ...",
  "active": true
}
```

**Interopérabilité (socle phase 3, additif) :**
- `direction` : sens du workflow. `INTERNAL` (defaut, retrocompatible : PULL -> medaillon -> lakehouse),
  `INBOUND` (preparation OpenHIM/mediateurs) ou `OUTBOUND` (livraison post-Gold vers un systeme externe).
  Aucune logique de comportement n'y est encore attachée : le champ est seulement persisté.
- `standardId` : référence **typée et nullable** vers `standards.id` — source de vérité du rattachement
  à un référentiel. À la création/mise à jour, si renseigné, le Standard doit exister et être `ACTIVE`
  (sinon rejet `400`). Un workflow INTERNAL peut ne pas être rattaché (`standardId` = null).
- `standardDomain` : **déprécié**. Texte libre conservé pour la rétrocompatibilité et l'usage existant
  (AiService). Utiliser `standardId` comme lien faisant foi.

### Collection `execution_logs`
```json
{
  "workflowId": "string",
  "status": "RUNNING | SUCCESS | FAILED",
  "startTime": "ISO-8601",
  "endTime": "ISO-8601",
  "logOutput": "string"
}
```

## Types de sources supportés

| Catégorie | Types |
|-----------|-------|
| Relationnel (JDBC) | POSTGRES, MYSQL, ORACLE, MSSQL, MARIADB, SQLITE, SNOWFLAKE, REDSHIFT |
| NoSQL | MONGODB, CASSANDRA |
| Fichier | CSV, EXCEL, XML, TEXT, PARQUET, AVRO, ORC |
| API | REST, HTTP, API |

## Créer un premier workflow (flux complet)

```
1. S'authentifier → POST /api/auth/login
2. Configurer le workflow (UI) → POST /api/workflows
3. Lancer la discovery → POST /api/workflows/discover
4. Mapper les colonnes dans l'atelier de mapping (UI)
5. Valider les scripts SQL
6. Sauvegarder → déclenche automatiquement POST /api/orchestrator/run/{id}
7. Suivre l'exécution → GET /api/logs/{workflowId}
```
