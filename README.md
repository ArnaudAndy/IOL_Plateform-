# IOL — Plateforme ETL pilotée par métadonnées

Plateforme d'intégration de données pour le secteur de la santé. L'utilisateur
décrit une **intention métier** ; la plateforme choisit seule le transport et le
moteur d'exécution.

> **État : préproduction durcie — pas encore un GO production.**
> Lisez [Suis-je prêt pour la production ?](#suis-je-prêt-pour-la-production)
> avant tout déploiement réel. Cette section n'est pas optionnelle.

---

## Sommaire

- [Ce que fait la plateforme](#ce-que-fait-la-plateforme)
- [Les deux règles qui expliquent tout](#les-deux-règles-qui-expliquent-tout)
- [Architecture](#architecture)
- [Prérequis](#prérequis)
- [Démarrage rapide avec Docker](#démarrage-rapide-avec-docker)
- [Premier workflow](#premier-workflow)
- [Ports et accès](#ports-et-accès)
- [Variables d'environnement](#variables-denvironnement)
- [Développement sans Docker](#développement-sans-docker)
- [Tests](#tests)
- [Dépannage](#dépannage)
- [Suis-je prêt pour la production ?](#suis-je-prêt-pour-la-production)
- [Sécurité](#sécurité)
- [Documentation](#documentation)

---

## Ce que fait la plateforme

Vous déclarez une source (base de données ou fichier), une destination et des
règles de transformation. La plateforme extrait, transporte et charge les
données selon l'**architecture en médaillon** :

| Couche | Contenu |
|---|---|
| **Bronze** | Données brutes, seuls les noms de colonnes sont normalisés |
| **Silver** | Données nettoyées et mappées vers un standard |
| **Gold** | Agrégations et jointures métier |

L'utilisateur ne choisit **jamais** Kafka, RustFS, Hop ni Spark. La plateforme
bascule automatiquement selon le volume.

**Sources supportées** : PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite,
Snowflake, Redshift, plus fichiers CSV, JSON, Parquet et Excel.

---

## Les deux règles qui expliquent tout

Presque chaque décision d'architecture découle de ces deux règles. En les gardant
à l'esprit, le reste du code devient prévisible.

**1. Un seul composant lit la source.** Ni Hop, ni Spark, ni le consumer
n'ouvrent de connexion vers la base d'un client. Les données sont extraites puis
*transportées* jusqu'au moteur, qui reçoit un artefact déjà matérialisé — jamais
les identifiants de la source.

**2. La plateforme orchestre, le moteur exécute.** IOL décide quoi faire et où
l'envoyer. Hop et Spark font le travail. C'est de l'**ELT** : les données brutes
atterrissent d'abord, la transformation se fait ensuite en SQL dans la base de
destination.

---

## Architecture

```
                    ┌──────────────┐
   Navigateur ─────▶│    Nginx     │  seul service exposé (80/443)
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   api-core   │  API REST, authentification, métadonnées
                    └──────┬───────┘
                           │ ordre de transport
                    ┌──────▼───────┐
                    │    Kafka     │  bus + transport de données
                    └──────┬───────┘
                           │
          ┌────────────────┴────────────────┐
          ▼                                 ▼
  ┌───────────────┐                 ┌───────────────┐
  │ source-gateway│                 │   pipeline-   │
  │ lit la source │                 │   consumer    │
  └───────┬───────┘                 └───────┬───────┘
          │ gros volumes                    │
          ▼                        ┌────────┴────────┐
    ┌──────────┐                   ▼                 ▼
    │  RustFS  │              ┌────────┐       ┌─────────┐
    │ staging  │              │  Hop   │       │  Spark  │
    └──────────┘              │ local  │       │ distrib.│
                              └────┬───┘       └────┬────┘
                                   └────────┬───────┘
                                            ▼
                                   ┌──────────────────┐
                                   │  Destination du  │
                                   │  client (Bronze/ │
                                   │  Silver/Gold)    │
                                   └──────────────────┘
```

**Deux décisions automatiques :**

| Décision | Condition | Résultat |
|---|---|---|
| Transport | < 10 M lignes **et** < 2 Gio | Kafka, par lots de 500 lignes |
| | seuil dépassé ou volume incertain | RustFS + manifeste dans Kafka |
| Moteur | charge normale | Apache Hop, en local |
| | Big Data | Spark, via `spark-submit` |

**Stockages :**

| Composant | Rôle |
|---|---|
| MongoDB | Métadonnées : workflows, connexions, journaux, audit |
| PostgreSQL (IOL) | Comptes, jetons, verrou d'exécution distribué |
| PostgreSQL (client) | Le lakehouse cible — **peut être sur un autre serveur** |
| Kafka | Bus événementiel **et** transport des données |
| RustFS | Staging temporaire des gros volumes (compatible S3) |

> Les données métier finales vont **où vous configurez la destination** : une base
> par client est possible. Ce qui est mutualisé, c'est l'orchestration —
> métadonnées, file Kafka, staging. Détails dans
> [CONTRATS_PARTAGE_ISOLATION_MULTI_ORGANISATION.md](docs/CONTRATS_PARTAGE_ISOLATION_MULTI_ORGANISATION.md).

---

## Prérequis

| Outil | Version | Nécessaire pour |
|---|---|---|
| Docker + Compose v2 | 24+ | Tout |
| RAM disponible | **8 Gio minimum**, 16 recommandé | Spark et ClamAV sont gourmands |
| Espace disque | 20 Gio | Images et volumes |
| Java | 17 | Développement backend uniquement |
| Maven | 3.9+ | Développement backend uniquement |
| Node.js | 20 | Développement frontend uniquement |

Vérifiez avant de commencer :

```bash
docker --version && docker compose version
docker info | grep -i "total memory"
```

---

## Démarrage rapide avec Docker

### 1. Récupérer le dépôt

```bash
git clone https://github.com/ArnaudAndy/IOL-project.git
cd IOL-project
```

### 2. Créer le fichier d'environnement

```bash
cp backend/.env.example backend/.env
```

`backend/.env` est **ignoré par git** et ne doit jamais être commité. Pour un
démarrage local, les valeurs par défaut suffisent. L'assistant SQL par IA reste
inactif tant que `GEMINI_API_KEY` et `GROQ_API_KEY` sont vides — tout le reste
fonctionne normalement.

### 3. Construire le frontend

Nginx sert les fichiers statiques depuis `frontend/dist`. **Ce dossier doit
exister avant de démarrer la stack**, sinon vous obtiendrez une erreur 403.

```bash
cd frontend
npm ci
npm run build
cd ..
```

### 4. Démarrer

```bash
cd backend
docker compose up -d
```

Le premier démarrage prend **10 à 20 minutes** : il compile trois applications
Java et télécharge Apache Hop, Spark et les signatures ClamAV.

### 5. Vérifier

```bash
docker compose ps
```

Attendez que les services soient `healthy`. ClamAV est le plus lent — il
télécharge ses bases antivirales au premier lancement et peut rester `starting`
plusieurs minutes.

```bash
curl -f http://localhost/actuator/health
```

Ouvrez ensuite **http://localhost**.

### 6. Créer le premier administrateur

```bash
curl -X POST http://localhost/api/auth/create-initial-admin \
  -H "Content-Type: application/json" \
  -d '{"name":"Admin","email":"admin@exemple.fr","password":"ChoisissezUnMotDePasseSolide"}'
```

Cette route est **refusée dès qu'un administrateur existe**. Connectez-vous
ensuite par l'interface.

### Arrêter

```bash
docker compose down          # arrête, conserve les données
docker compose down -v       # arrête et SUPPRIME toutes les données
```

---

## Premier workflow

1. **Connexions** — déclarez votre source et votre destination
2. **Workflows** — créez un workflow et choisissez la source
3. **Découverte** — la plateforme lit le schéma et propose les colonnes
4. **Mappings** — associez les colonnes aux termes de votre standard
5. **SQL Silver/Gold** — écrivez ou faites générer le SQL, puis validez-le
6. **Exécuter** — suivez l'avancement dans **Exécutions**

L'exécution est **asynchrone** : l'API répond immédiatement `202 Accepted` avec un
identifiant de journal. Le suivi se fait dans l'interface, pas en attendant la
réponse HTTP.

---

## Ports et accès

| Adresse | Service |
|---|---|
| http://localhost | Interface web et API (via Nginx) |
| http://localhost/swagger-ui.html | Documentation interactive de l'API |
| http://localhost:8025 | Mailpit — boîte mail de test |
| http://127.0.0.1:8088 | Interface Spark master |
| http://127.0.0.1:9001 | Console RustFS |

**Seul Nginx publie des ports vers l'extérieur.** Les autres services vivent sur
un réseau Docker interne, injoignables depuis l'hôte. Spark et RustFS sont liés à
`127.0.0.1` uniquement.

### Principales routes API

| Route | Usage |
|---|---|
| `POST /api/auth/login` | Authentification, retourne un JWT |
| `GET`/`POST` `/api/workflows` | Gestion des workflows |
| `POST /api/orchestrator/run/{id}` | Lancer une exécution → **202** |
| `GET /api/logs` | Journaux d'exécution |
| `POST /api/files/upload` | Dépôt de fichier, scanné par ClamAV |
| `POST /api/sql/**` | Atelier SQL |
| `POST /api/ai/**` | Assistant SQL |
| `GET`/`POST` `/api/connections` | Connexions source et destination |
| `GET /api/v1/audit/**` | Journal d'audit (ADMIN) |

---

## Variables d'environnement

Les valeurs par défaut conviennent au développement local.

| Variable | Défaut | Rôle |
|---|---|---|
| `GEMINI_API_KEY` / `GROQ_API_KEY` | *(vide)* | Assistant SQL — **schéma uniquement, jamais de données** |
| `SPARK_ROW_THRESHOLD` | `10000000` | Bascule vers Spark |
| `SPARK_FILE_SIZE_THRESHOLD_BYTES` | `2147483648` | Bascule fichier (2 Gio) |
| `APP_EXECUTION_POOL_SIZE` | `4` | Transports simultanés ; au-delà → **429** |
| `APP_EXECUTION_MAX_SOURCE_CONNECTIONS` | `8` | Connexions source simultanées |
| `APP_KAFKA_ROW_BATCH_ROWS` | `500` | Lignes par lot Kafka |
| `APP_KAFKA_MAX_IN_FLIGHT_BATCHES` | `64` | Lots en vol avant attente |
| `API_CORE_MEMORY_LIMIT` | `4g` | Plafond mémoire d'api-core |
| `MALWARE_SCAN_ENABLED` | `false` en dev | Scan antivirus des dépôts |

Ces seuils sont des **valeurs de départ**, pas des constantes universelles.
Validez-les par un test de charge avec la largeur réelle de vos lignes.

---

## Développement sans Docker

Les dépendances doivent tourner — le plus simple est de les démarrer par Compose,
puis de lancer les applications depuis votre IDE.

```bash
cd backend && docker compose up -d mongodb postgres kafka

cd backend/api-core          && mvn spring-boot:run   # :8084
cd backend/source-gateway    && mvn spring-boot:run   # :8087
cd backend/pipeline-consumer && mvn spring-boot:run

cd frontend && npm run dev                             # :5173
```

Le serveur Vite proxifie `/api` vers le backend : aucun problème de CORS en
développement.

---

## Tests

```bash
cd backend/api-core          && mvn test    # 134 tests
cd backend/source-gateway    && mvn test    #  28 tests
cd backend/pipeline-consumer && mvn test    #  21 tests
cd backend/iol-mediator      && npm test    #  51 tests

cd frontend && npm run lint && npm run test:mappers && npm run build

pip install pytest && pytest -q tests test_mapping_engine.py
python scripts/validate_contracts.py
```

Vérification de la topologie de production (nécessite Docker) :

```bash
python scripts/validate_production_security.py
```

---

## Dépannage

**403 Forbidden sur http://localhost**
Nginx sert un dossier vide. Construisez le frontend (`cd frontend && npm run build`),
puis `docker compose up -d --force-recreate nginx`.

**Un service reste `unhealthy`**
```bash
docker compose logs --tail=50 <service>
```
ClamAV met plusieurs minutes à télécharger ses signatures au premier démarrage :
c'est attendu.

**La compilation échoue par manque de mémoire**
Augmentez la RAM allouée à Docker Desktop (8 Gio minimum), ou compilez hors
Docker : `MAVEN_OPTS="-Xmx1024m" mvn package -DskipTests`.

**HTTP 429 au lancement d'un workflow**
La capacité d'exécution est saturée — c'est un refus explicite, pas une panne.
Réessayez, ou augmentez `APP_EXECUTION_POOL_SIZE`.

**HTTP 409 au lancement d'un workflow**
Une exécution est déjà en cours pour ce workflow. Deux exécutions simultanées
écriraient dans les mêmes tables ; attendez la fin de la première.

**Repartir de zéro**
```bash
docker compose down -v && docker compose up -d --build
```

---

## Suis-je prêt pour la production ?

**Non — et voici précisément ce qui manque.**

Le dépôt est un **candidat de préproduction durci**. Le code est sain et les
mécanismes de sécurité sont réels, mais des preuves d'exploitation manquent.

### Ce qui est solide

- **234 tests** verts, aucun TODO ni FIXME dans le code
- Le backend **refuse de démarrer** en production sans TLS, mTLS, Keycloak,
  Vault Transit et ClamAV en mode fail-closed
- **80+ invariants de topologie** vérifiés en intégration continue
- L'assistant IA ne reçoit **jamais** de données : neuf motifs bloquent emails,
  identifiants, adresses IP, dates et URL de connexion avant tout appel externe
- Mots de passe chiffrés par enveloppe Vault, jamais en clair dans les messages
- SQL utilisateur validé par analyse syntaxique réelle, pas par expressions
  régulières

### Bloqueurs à traiter

| Priorité | Point | Action |
|---|---|---|
| 🔴 | **Clés API à rotationner** | Révoquer les clés Gemini et Groq de développement, en créer pour la production |
| 🔴 | **Magasin d'objets à qualifier** | RustFS `1.0.0-beta.12` est pré-GA. Exécutez `backend/ops/tests/rustfs-qualification.sh` sur l'infrastructure cible et conservez sa sortie — la porte de sécurité l'exige |
| 🟠 | **PostgreSQL en instance unique** | Il porte le verrou d'exécution : sa perte arrête la chaîne. Patroni ou repmgr derrière PgBouncer |
| 🟠 | **Nginx en instance unique** | Seul point d'entrée public. keepalived et IP flottante, ou un répartiteur en amont |
| 🟠 | **Restauration non prouvée** | Exécuter un cycle complet de sauvegarde puis restauration hors site |
| 🟠 | **Tests de charge absents** | Les seuils automatiques n'ont jamais été validés sous charge réelle |
| 🟡 | **Multi-organisation non isolé** | Rester à **une organisation par instance** |

### Points de rupture connus

Sous forte charge simultanée, dans cet ordre :

1. **HTTP 429** — capacité d'exécution saturée. Refus explicite, pas une panne.
2. **Saturation mémoire** — api-core est plafonné (4 Gio en dev, 6 en prod) et
   redémarre proprement plutôt que de se dégrader.
3. **Perte de PostgreSQL ou de Nginx** — arrêt de service. Ce sont les deux
   points de défaillance unique restants.

### Avant d'ouvrir le service

- [ ] Clés de développement révoquées et remplacées
- [ ] Qualification du magasin d'objets réussie, sortie du script conservée
- [ ] Restauration de sauvegarde démontrée sur un autre hôte
- [ ] Tests de charge conformes à vos objectifs de latence
- [ ] Sondes reliées au répartiteur ou à l'orchestrateur
- [ ] Vault opérationnel avec auto-déverrouillage et rotation
- [ ] Répétition de retour arrière effectuée
- [ ] Mode **une seule organisation** confirmé

Procédure détaillée : [PRODUCTION_RUNBOOK.md](docs/PRODUCTION_RUNBOOK.md)

---

## Sécurité

**Ne commitez jamais `backend/.env`.** Il est gitignoré ; vérifiez-le avant tout
push. Si une clé a fuité, **révoquez-la** — la retirer de l'historique ne suffit
pas.

**L'assistant IA ne reçoit que des noms de colonnes.** Jamais une ligne, une
valeur d'exemple, une URL de connexion ni un identifiant. Un garde applicatif
refuse la requête en amont si l'instruction ressemble à des données.

**Signaler une vulnérabilité** : voir [SECURITY.md](docs/SECURITY.md). N'ouvrez
pas d'issue publique.

---

## Documentation

| Document | Contenu |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture détaillée |
| [CODE_GUIDE.md](docs/CODE_GUIDE.md) | Lecture du code et des flux |
| [PRODUCTION_RUNBOOK.md](docs/PRODUCTION_RUNBOOK.md) | Procédure d'exploitation |
| [GUIDE_MISE_EN_PRODUCTION.md](docs/GUIDE_MISE_EN_PRODUCTION.md) | Mise en production |
| [DURCISSEMENT_PRODUCTION_PHASE_2.md](docs/DURCISSEMENT_PRODUCTION_PHASE_2.md) | Durcissement, phase 2 |
| [SECURITY.md](docs/SECURITY.md) | Politique de sécurité |
| [INTEROPERABILITY.md](docs/INTEROPERABILITY.md) | FHIR, ISO 20022, Ed-Fi |
| [AI_SCHEMA_ONLY.md](docs/AI_SCHEMA_ONLY.md) | Confidentialité de l'assistant IA |
