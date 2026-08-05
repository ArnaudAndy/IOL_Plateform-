# Test RustFS, API et ingestion JDBC partitionnée

## Architecture mise en place

- `api-core` est le seul composant autorise a ouvrir une source JDBC.
- En charge normale, toutes les lignes JDBC sont envoyees dans Kafka en lots JSON types.
- En Big Data, `api-core` diffuse les lignes JDBC vers RustFS en multipart streaming et Kafka recoit seulement le manifeste.
- `pipeline-consumer` reconstruit les lots ou telecharge l'objet, verifie SHA-256, puis donne le chemin local a Hop ou Spark.
- Hop et Spark refusent une source JDBC directe et ne recoivent aucun secret source.
- Les anciens messages `KAFKA_CHUNKED` restent lisibles pour la compatibilité.

RustFS est auto-hébergé. L'utilisation du protocole S3 ne signifie pas qu'AWS S3 est utilisé.

## Démarrage

Depuis `backend` :

```powershell
docker compose up -d --build rustfs kafka mongodb postgres mailpit spark-master spark-worker api-core pipeline-consumer nginx
docker compose ps
```

La console RustFS est disponible sur `http://127.0.0.1:9001`.
La console Spark master est disponible sur `http://127.0.0.1:8088` et celle du worker sur `http://127.0.0.1:8089`.

Identifiants de développement par défaut :

```text
utilisateur: rustfsadmin
mot de passe: rustfsadmin
bucket: iol-source-data
```

Définir `RUSTFS_ACCESS_KEY` et `RUSTFS_SECRET_KEY` dans `backend/.env` avant tout déploiement partagé.

## Test d'une source API

Dans **Nouveau traitement > Sources > API** :

```text
URL: https://jsonplaceholder.typicode.com/users
Méthode: GET
Chemin des lignes: laisser vide (la racine est un tableau)
Pagination: Aucune
Authentification: Aucune
```

Lancer la découverte, sélectionner quelques colonnes, puis configurer Bronze, Silver et Gold. Après exécution :

1. Pour une petite reponse, verifier `transport=KAFKA_CHUNKED` dans le manifeste.
2. Pour une charge classee Big Data, ouvrir la console RustFS.
3. Ouvrir le bucket `iol-source-data`.
4. Verifier un objet sous `source-data/<workflow>/<execution>/...`.
5. Verifier que Kafka contient alors `transport=OBJECT_STORAGE`, la taille et le SHA-256.

Le transport n'est pas un choix utilisateur : `transport_mode=OBJECT_STORAGE` est ignore pour une petite charge.

Pour une API protégée, renseigner uniquement des noms de variables d'environnement, par exemple `HOSPITAL_API_TOKEN`. Ajouter la valeur réelle dans l'environnement de `api-core`; elle n'est pas enregistrée dans le workflow.

## Test JDBC transporte

Dans l'etape **Chargement incremental** d'une source PostgreSQL/MySQL/etc. :

```text
Mode de chargement: complet ou incremental
Colonne de reprise: updated_at (si incremental)
```

La requête source reste une requête `SELECT`.

- En charge normale, `api-core` lit JDBC par curseur et publie les lignes JSON dans Kafka.
- En Big Data, `api-core` lit JDBC et diffuse du JSON Lines vers RustFS sans fichier temporaire complet.
- Le consumer verifie l'integrite avant Hop ou Spark.
- Dans les deux modes, aucune ligne JDBC n'est convertie en CSV.
- Dans les deux modes, Hop et Spark n'ouvrent aucune connexion vers la source.

Pour une date :

```text
Type: Date / heure
Borne inférieure: 2026-01-01T00:00:00Z
Borne supérieure: 2027-01-01T00:00:00Z
```

Le point de reprise reduit le volume lu par `api-core`. Le runtime de calcul est ensuite choisi automatiquement.

## Parallélisme

Le compose utilise trois partitions par topic Kafka, trois threads par topic et deux instances de `pipeline-consumer` par défaut. Pour changer le nombre d'instances :

```powershell
$env:PIPELINE_CONSUMER_REPLICAS=3
docker compose up -d pipeline-consumer
```

La clé Kafka et le verrou PostgreSQL utilisent la destination. Deux destinations différentes peuvent être traitées en parallèle ; les workflows écrivant vers la même connexion restent sérialisés même s'ils arrivent sur des instances différentes.

## Exécution Spark distribuée

Le mode `LOCAL` continue d'utiliser Hop et `moteur_universel.py`. Le mode `SPARK` utilise un job PySpark distinct, soumis avec `spark-submit` au master `spark://spark-master:7077`. Il ne lance pas pandas dans un transform Hop et ne prétend pas qu'un processus Python local est distribué.

La plateforme mesure automatiquement le volume JDBC avant l'execution. En
configuration de production, elle bascule en mode distribue si le volume
depasse `SPARK_ROW_THRESHOLD` (10 000 000 par defaut), si un fichier depasse
2 Gio ou si le diagnostic expire. Le volume manuel reste reserve a
l'administration comme valeur de repli.

```text
Volume estime (administration uniquement): valeur de repli facultative
```

Dans Silver et Gold, le parcours principal doit parler de mode de transformation, pas de moteur interne :

- `SQL dans la base cible` pour conserver l'ELT dans la destination ;
- `Traitement distribué` pour les jointures et agrégations massives.

Les vues temporaires disponibles dans Spark SQL sont :

```text
bronze_0, bronze_1, ...
silver_0, silver_1, ...
```

Les champs `SQL avant transformation`, `SQL après transformation` et les index sont optionnels. Les index PostgreSQL/SQLite sont créés avec `IF NOT EXISTS`. Snowflake et Redshift doivent utiliser leurs mécanismes de clustering ou sort key.

## Test distribué reproductible

La métadonnée [SPARK_DISTRIBUTED_TEST_METADATA.json](./SPARK_DISTRIBUTED_TEST_METADATA.json) teste :

```text
PostgreSQL partitionné
patient_external_id -> patient_id
Bronze -> Silver Spark SQL -> Gold Spark SQL
deux index de destination
```

Préparer la source :

```powershell
docker exec iol-postgres psql -U etl_user -d lakehouse -v ON_ERROR_STOP=1 `
  -c "CREATE SCHEMA IF NOT EXISTS bronze; CREATE SCHEMA IF NOT EXISTS silver; CREATE SCHEMA IF NOT EXISTS gold;" `
  -c "DROP TABLE IF EXISTS public.spark_hospital_a; CREATE TABLE public.spark_hospital_a (id BIGINT PRIMARY KEY, patient_external_id VARCHAR(40), full_name VARCHAR(120));" `
  -c "INSERT INTO public.spark_hospital_a VALUES (1, 'HOSP-A-001', 'Alice Mbarga'), (2, 'HOSP-A-002', 'Brice Nsom'), (3, 'HOSP-A-003', 'Carine Etoa'), (4, 'HOSP-A-004', 'Daniel Mballa');"
```

Après une exécution depuis le frontend, contrôler :

```powershell
docker exec iol-postgres psql -U etl_user -d lakehouse `
  -c "SELECT * FROM bronze.spark_patients ORDER BY id" `
  -c "SELECT * FROM silver.spark_patients ORDER BY id" `
  -c "SELECT * FROM gold.spark_patients ORDER BY patient_id"
```

La metadonnee de test represente maintenant l'artefact JSON deja transporte et materialise par le consumer. Elle ne contient aucune connexion source. Le fichier Excel reste reserve au mode local ; pour Spark, utiliser JSON, CSV, Parquet ou ORC.
