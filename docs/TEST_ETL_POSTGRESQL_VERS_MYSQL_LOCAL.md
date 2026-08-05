# Test ETL : PostgreSQL vers MySQL local

Ce scénario utilise une source PostgreSQL accessible par la plateforme et écrit Bronze, Silver et Gold dans le MySQL 8 installé sur Windows. Aucun serveur MySQL Docker n'est nécessaire.

## 1. Chemin des données

1. `api-core` lit la source PostgreSQL par JDBC.
2. Les lignes sont découpées et transportées dans Kafka.
3. `pipeline-consumer` reconstitue le fichier CSV.
4. Apache Hop appelle `moteur_universel.py`.
5. Bronze, Silver et Gold sont écrits dans MySQL Windows.

Depuis un conteneur, `localhost` désigne le conteneur lui-même. La plateforme traduit donc automatiquement `localhost` en `host.docker.internal` pour atteindre Windows.

## 2. Connexion MySQL locale

Dans **Connexions**, utiliser :

| Champ | Valeur |
|---|---|
| Nom | `MySQL local - Destination` |
| Type | `MYSQL` |
| Hôte | `localhost` |
| Port | `3306` |
| Base | `fine_track` |
| Utilisateur | `root` |
| Mot de passe | Mot de passe du compte MySQL local |

La connexion existante `mysql_source` du compte `ngonoarnaudandy@gmail.com` possède déjà ces valeurs, mot de passe compris. Elle peut être sélectionnée directement comme destination ; cliquer sur **Tester** permet de vérifier la connexion depuis la plateforme.

La connectivité Docker vers `host.docker.internal:3306/fine_track` a été validée avec MySQL `8.0.34`.

## 3. Préparer une source PostgreSQL

Dans le PostgreSQL source, créer un petit jeu de données :

```sql
DROP TABLE IF EXISTS hospital_patients_source;
CREATE TABLE hospital_patients_source (
  patient_id varchar(32) PRIMARY KEY,
  full_name varchar(120) NOT NULL,
  birth_date date NOT NULL,
  status_code varchar(20) NOT NULL,
  risk_score numeric(5,2) NOT NULL
);

INSERT INTO hospital_patients_source VALUES
  ('PG-001', 'Alice Nkom', '1987-03-12', 'active', 12.50),
  ('PG-002', 'Bob Talla', '1976-11-25', 'inactive', 67.00),
  ('PG-003', 'Claire Mballa', '1994-06-04', 'active', 34.25);
```

## 4. Configurer le workflow

### Général

| Champ | Valeur |
|---|---|
| Nom | `ETL PostgreSQL vers MySQL local` |
| Direction | `INTERNAL` |
| Mode | `LOCAL` |
| Actif | Oui |
| Destination | `MySQL local - Destination` |

### Source PostgreSQL

| Champ | Valeur |
|---|---|
| Type | `POSTGRES` |
| Connexion | votre connexion PostgreSQL source |
| Table Bronze | `bronze_pg_patients` |
| Chargement | `FULL` |
| Écriture | `replace` |

Requête source :

```sql
SELECT patient_id, full_name, birth_date, status_code, risk_score
FROM hospital_patients_source
ORDER BY patient_id
```

## 5. SQL Silver compatible MySQL

Table Silver : `silver_local_patients`

```sql
DROP TABLE IF EXISTS silver_local_patients;
CREATE TABLE silver_local_patients AS
SELECT
  patient_id,
  full_name,
  STR_TO_DATE(birth_date, '%Y-%m-%d') AS birth_date,
  UPPER(status_code) AS status,
  CAST(risk_score AS DECIMAL(5,2)) AS risk_score,
  extracted_at
FROM bronze_pg_patients;
```

Ne pas utiliser les casts PostgreSQL `::date` ou `::numeric` dans une destination MySQL.

## 6. SQL Gold compatible MySQL

Table Gold : `gold_local_patient_risk`

```sql
DROP TABLE IF EXISTS gold_local_patient_risk;
CREATE TABLE gold_local_patient_risk AS
SELECT
  patient_id,
  full_name,
  status,
  risk_score,
  CASE
    WHEN risk_score >= 60 THEN 'HIGH'
    WHEN risk_score >= 30 THEN 'MEDIUM'
    ELSE 'LOW'
  END AS risk_level
FROM silver_local_patients;
```

## 7. Exécuter et vérifier

1. Enregistrer le workflow.
2. Cliquer sur **Exécuter**.
3. Vérifier le statut dans **Exécutions** et **Monitoring du flux**.
4. Dans MySQL Workbench, ouvrir `fine_track` et exécuter :

```sql
SELECT patient_id, full_name, status, risk_score, risk_level
FROM fine_track.gold_local_patient_risk
ORDER BY patient_id;
```

Résultat attendu :

| patient_id | full_name | status | risk_score | risk_level |
|---|---|---|---:|---|
| PG-001 | Alice Nkom | ACTIVE | 12.50 | LOW |
| PG-002 | Bob Talla | INACTIVE | 67.00 | HIGH |
| PG-003 | Claire Mballa | ACTIVE | 34.25 | MEDIUM |

## 8. Diagnostic

| Symptôme | Vérification |
|---|---|
| Connexion refusée | Le service Windows `MySQL80` doit être démarré et écouter sur `3306` |
| `localhost` inaccessible depuis Hop | Contrôler que le runtime reçoit `host.docker.internal` |
| Accès refusé pour `root` | Autoriser ce compte depuis Docker Desktop ou créer un utilisateur ETL dédié |
| Bronze absent | Lire les logs `iol-pipeline-consumer` et vérifier la destination choisie |
| Silver en erreur | Utiliser la syntaxe SQL MySQL, pas PostgreSQL |

Ne pas exécuter `docker compose down -v` pour un simple redémarrage : cette commande supprime les volumes PostgreSQL et MongoDB de la plateforme.
