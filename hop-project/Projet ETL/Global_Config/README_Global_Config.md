# Global_Config — Contrat JSON plateforme → Apache Hop

Ce dossier contient les pipelines/workflows Apache Hop du flux d'ingestion IOL
(médaillon Bronze → Silver → Gold). Ce document décrit le **document d'exécution**
(JSON) que `api-core` publie sur Kafka et que `pipeline-consumer` écrit dans un fichier
temporaire passé à Hop via `-p IOL_METADATA_FILE=<chemin>`.

## Principe directeur : QUAND vs COMBIEN vs QUELLES colonnes

| Dimension | Champ | Qui le gère | Portée |
|-----------|-------|-------------|--------|
| **QUAND** (planification) | `schedule` | Spring `PipelineSchedulerService` (CronTrigger) | Workflow. Hop **n'en dépend pas** ; il exécute à la demande. |
| **COMBIEN** (incrémental) | `load_mode`, `incremental_column`, `last_watermark`, `write_mode` | api-core (injection) + moteur (application) | **PAR SOURCE** (`sources[].config`). |
| **QUELLES colonnes** (projection) | `fields` | Frontend → api-core → moteur | **PAR SOURCE** (`sources[].config`). Sélection uniquement, **pas de renommage**. |

`schedule` peut rester présent dans le JSON (inoffensif) mais **aucun** pipeline Hop ne
doit le lire. Le cron ne circule jamais jusqu'à Hop.

## Noms canoniques (snake_case, imposés partout)

- `load_mode` — `FULL` | `INCREMENTAL` — combien on lit à la source.
- `write_mode` — `append` | `replace` — comment on écrit en Bronze (**défaut `append`**, Bronze immuable).
- `incremental_column` — colonne servant de borne.
- `last_watermark` — dernière borne haute atteinte (valeur, pas date de run).
- `fields` — liste des colonnes **brutes** à extraire (noms d'origine, avant nettoyage).

**Bannis** : `loadMode`, `incrementalColumn`, `lastWatermark` (camelCase, ex-racine du payload)
et `last_run`.

## Structure du document d'exécution

```json
{
  "workflowId": "...", "execLogId": "...", "workflowName": "...",
  "executionMode": "LOCAL", "priority": 3,

  "schedule": { "enabled": true, "frequency": "DAILY", "time": "02:00" },

  "sources": [
    {
      "source_name": "POSTGRES",
      "config": {
        "uri": "postgresql://user:pass@host:5432/db",
        "target_table": "stg_transactions",
        "target_connection": { "host": "...", "port": "...", "database": "...", "username": "...", "password": "..." },
        "source_config": { "query": "SELECT ..." },
        "fields": ["date_op", "tx_id", "amount"],
        "load_mode": "INCREMENTAL",
        "incremental_column": "date_op",
        "last_watermark": "2026-06-15T02:00:00Z",
        "write_mode": "append",
        "silver_config": { "target_table_silver": "cln_transactions", "elt_scripts_silver": "..." }
      }
    },
    {
      "source_name": "CSV",
      "config": {
        "uri": "C:/data/douane.csv",
        "target_table": "stg_douane",
        "target_connection": { "...": "..." },
        "source_config": { "delimiter": ";", "encoding": "UTF-8" },
        "fields": ["date_op", "montant", "bureau"],
        "load_mode": "FULL",
        "write_mode": "append",
        "silver_config": { "...": "..." }
      }
    }
  ],

  "gold_config_global": { "target_table_gold": "...", "elt_scripts_gold": "..." }
}
```

### Règles

- `load_mode` / `incremental_column` / `last_watermark` / `write_mode` sont **par source**,
  jamais à la racine.
- `fields` est **par source** : liste des colonnes brutes à extraire (projection). Absent ou
  vide ⇒ **toutes** les colonnes (rétrocompatible). Sélection uniquement, pas de renommage.
- **Sûreté incrémentale** : si `fields` est fourni **et** `incremental_column` défini, le moteur
  force l'inclusion de `incremental_column` dans les colonnes extraites (sinon le filtre casse).
- Repli legacy : un ancien workflow portant `loadMode`/`incrementalColumn` dans `schedule` les
  voit appliqués comme **défauts par source**. Une source sans `incremental_column` ⇒ `FULL`.
- `target_connection` est **obligatoire et complet** pour l'écriture Bronze (fail-fast côté moteur
  sur variable `${...}` non résolue). Pour les fichiers, le chemin passe par `uri`.

## Boucle du watermark (incrémental de bout en bout)

1. `api-core` injecte `last_watermark` par source depuis le **dernier run réussi** de cette
   source (`ExecutionLog.lastSuccessfulWatermarks`, clé = `target_table`).
2. `read_sources.hpl` émet `load_mode` / `incremental_column` / `last_watermark` / `fields`
   (`$.sources[*].config.*`) ; propagés via `wf_main_ingestion.hwf` → `process_one_source.hwf`
   → `bronze_loop.hpl` (step *Get variables* + *JavaScript*).
3. `moteur_universel.py` applique le `WHERE incremental_column > last_watermark` (SQL), la
   projection `fields`, puis — après un chargement Bronze réussi — **émet** sur stdout :
   `IOL_WATERMARK::<target_table>::<nouvelle_valeur>` (valeur = `MAX(incremental_column)`).
4. `pipeline-consumer` (`PipelineOrchestrator.parseWatermarks`) lit ces lignes et ajoute un
   objet `watermarks` `{ target_table: valeur }` au message publié sur `iol.pipeline.status`.
5. `api-core` (`KafkaStatusListenerService`) persiste ces valeurs par source dans
   `ExecutionLog.lastSuccessfulWatermarks` → réinjectées au run suivant (étape 1).

## Tester en local

```bash
hop-run.sh --file="wf_main_ingestion.hwf" --runconfig=local \
  -p IOL_METADATA_FILE="C:/Users/ANDY/Desktop/ING5/Stage/Projet ETL/Global_Config/sample_metadata_multisource.json"
```

Le fichier `sample_metadata_multisource.json` fournit une source **INCREMENTAL** (POSTGRES,
projection partielle `fields`) et une source **FULL** (CSV, projection partielle). Adapter
`uri`, `target_connection` et les chemins de fichiers à l'environnement.
