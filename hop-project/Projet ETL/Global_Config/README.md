# Global_Config — Contrat JSON plateforme → Apache Hop

Ce dossier contient les workflows et pipelines Apache Hop utilisés pour l’ingestion du projet, du niveau Bronze vers Silver puis Gold.

## Objectif

Il sert de support technique pour l’exécution des pipelines ETL pilotés par l’application. Le document JSON d’exécution est produit par l’API backend puis consommé par le pipeline consumer avant d’être transmis à Hop.

## Structure du dossier

- bronze_loop.hpl : chargement Bronze ;
- silver_loop.hpl : transformation Silver ;
- gold_elt_dynamique.hpl : agrégation Gold ;
- read_sources.hpl : lecture des sources définies dans le document d’exécution ;
- read_config.hpl : lecture des paramètres de configuration ;
- process_one_source.hwf : workflow unitaire par source ;
- wf_main_ingestion.hwf : workflow principal d’ingestion.

## Contrat d’exécution

Le document JSON transmis à Hop doit contenir :
- les informations de workflow ;
- la liste des sources à traiter ;
- les paramètres de chargement et d’écriture par source ;
- la configuration Gold globale.

### Champs principaux

- `workflowId`, `execLogId`, `workflowName` : identité de l’exécution ;
- `executionMode`, `priority` : contexte d’exécution ;
- `schedule` : information de planification, sans impact direct sur Hop ;
- `sources` : définition des sources avec leur configuration et leurs règles de traitement ;
- `gold_config_global` : configuration de l’agrégation finale.

## Règles de configuration

### Noms canoniques

Les noms suivants doivent être utilisés de façon uniforme :

- `load_mode` : `FULL` ou `INCREMENTAL` ;
- `write_mode` : `append` ou `replace` ;
- `incremental_column` : colonne de borne utilisée pour l’incrémental ;
- `last_watermark` : dernière valeur de borne traitée ;
- `fields` : colonnes brutes à extraire, sans renommage.

Les formes camelCase comme `loadMode` ou `incrementalColumn` ne doivent pas être utilisées dans ce contrat.

### Portée des paramètres

- `load_mode`, `incremental_column`, `last_watermark` et `write_mode` sont définis par source ;
- `fields` est également défini par source et sert à projeter les colonnes à lire ;
- `gold_config_global` contient les paramètres de l’étape Gold unique.

## Flux incrémental

Le mécanisme incrémental suit ce parcours :

1. le backend injecte le dernier watermark par source ;
2. les paramètres sont propagés jusqu’aux pipelines Hop ;
3. le moteur applique le filtre incrémental et les projections demandées ;
4. les nouveaux watermarks sont remontés à l’API et persistés pour le prochain run.

## Exécution locale

Un lancement typique ressemble à :

```bash
hop-run.sh --file="wf_main_ingestion.hwf" --runconfig=local \
  -p IOL_METADATA_FILE="<chemin_vers_le_fichier_json>"
```

Le fichier d’exemple fourni permet de tester le flux avec plusieurs sources et une configuration minimale.

