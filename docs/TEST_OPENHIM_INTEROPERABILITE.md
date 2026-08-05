# Test complet OpenHIM et interopérabilité

Ce guide vérifie un échange entrant `hospital_a -> OpenHIM -> IOL -> Kafka -> Hop -> Gold`, puis explique comment contrôler la transaction de bout en bout.

## 1. Architecture du test

```text
hospital_a
  -> Nginx /interop/*
  -> OpenHIM Core
  -> iol-mediator
  -> api-core /api/internal/interop/*
  -> Kafka
  -> pipeline-consumer / Apache Hop
  -> destination du workflow
```

OpenHIM apporte le canal, l'authentification du système émetteur, la transaction et l'audit. Le médiateur IOL applique l'adaptateur, la norme et ses mappings, puis remet une commande ETL normalisée à la plateforme.

## 2. Préparer les variables

Les deux fichiers doivent contenir la même valeur non vide :

```dotenv
# backend/.env
INTEROP_INTERNAL_SECRET=une-valeur-locale-longue-et-aleatoire

# backend/openhim/.env
INTEROP_INTERNAL_SECRET=une-valeur-locale-longue-et-aleatoire
```

Dans `backend/openhim/.env`, renseigner aussi les identifiants administrateur OpenHIM utilisés par le médiateur :

```dotenv
OPENHIM_MEDIATOR_USERNAME=root@openhim.org
OPENHIM_MEDIATOR_PASSWORD=<mot-de-passe-openhim>
IOL_DEFAULT_SOURCE_SYSTEM=hospital_a
IOL_DEFAULT_ADAPTER=generic-json
IOL_INBOUND_AUTH_TYPE=public
```

Le mot de passe initial OpenHIM est `openhim-password`. Il doit être changé à la première connexion, puis la nouvelle valeur doit être placée dans `.env`.

## 3. Démarrer les deux stacks

Depuis `backend/` :

```powershell
docker compose up -d --build
docker compose --env-file openhim/.env -f openhim/docker-compose.openhim.yml up -d --build
docker compose ps
docker compose --env-file openhim/.env -f openhim/docker-compose.openhim.yml ps
```

Points d'accès :

- Plateforme : `http://localhost/`
- Console OpenHIM : `http://localhost/openhim-console/`
- Heartbeat Core : `http://localhost/openhim-api/heartbeat`

`iol-openhim-mongo-init` est un conteneur d'initialisation ponctuel. Son état normal après succès est `Exited (0)` ; il ne doit pas rester actif.

## 4. Vérifier l'enregistrement du médiateur

```powershell
docker logs --tail 200 iol-mediator
```

Dans OpenHIM Console :

1. Ouvrir **Mediators**.
2. Vérifier `IOL Generic Interop Mediator` / `urn:mediator:iol-generic`.
3. Vérifier que le heartbeat est récent.
4. Vérifier que le canal IOL route vers `iol-mediator:3000/`.

Si le médiateur est absent, contrôler le nom d'utilisateur, le mot de passe, le heartbeat Core et les logs du conteneur.

## 5. Créer la norme patient

Dans **Normes**, créer `CUSTOM_PATIENT_V1`, statut `ACTIVE`, puis ajouter les termes suivants.

### Terme `patient_id`

| Champ | Valeur |
|---|---|
| Type | `STRING` |
| Obligatoire | Oui |
| Mappings système | `hospital_a=patientId` puis `hospital_b=patientNumber` |

### Terme `birth_date`

| Champ | Valeur |
|---|---|
| Type | `DATE` |
| Obligatoire | Oui |
| Mappings système | `hospital_a=birthDate` puis `hospital_b=dateOfBirth` |

### Terme `status`

| Champ | Valeur |
|---|---|
| Type | `STRING` |
| Obligatoire | Oui |
| Mappings système | `hospital_a=statusCode` puis `hospital_b=patientStatus` |

### Terme `risk_score`

| Champ | Valeur |
|---|---|
| Type | `DECIMAL` |
| Obligatoire | Oui |
| Mappings système | `hospital_a=riskScore` puis `hospital_b=riskValue` |

Dans l'éditeur, saisir un mapping par ligne sous la forme `système=champ`. Cela produit l'aller-retour attendu :

```text
hospital_a.patientId -> patient_id -> hospital_b.patientNumber
```

`full_name` peut être ajouté comme terme optionnel avec `hospital_a=fullName` et `hospital_b=displayName`, mais il faut alors l'ajouter aussi aux champs du workflow et à ses SQL Silver/Gold.

## 6. Créer le workflow INBOUND

Créer un workflow actif avec :

| Champ | Valeur |
|---|---|
| Nom | `Interop Hospital A Patient` |
| Direction | `INBOUND` |
| Norme | `CUSTOM_PATIENT_V1` |
| Destination | une connexion PostgreSQL valide |
| Source | `PUSH` ou réception externe |
| Table Bronze | `bronze_interop_patient` |
| Mode d'écriture | `append` |

SQL Silver :

```sql
DROP TABLE IF EXISTS silver_interop_patient;
CREATE TABLE silver_interop_patient AS
SELECT
  patient_id,
  birth_date::date AS birth_date,
  UPPER(status) AS status,
  risk_score::numeric AS risk_score,
  extracted_at
FROM bronze_interop_patient;
```

SQL Gold :

```sql
DROP TABLE IF EXISTS gold_interop_patient;
CREATE TABLE gold_interop_patient AS
SELECT patient_id, birth_date, status, risk_score
FROM silver_interop_patient;
```

## 7. Test depuis le nouveau frontend

Dans **Réceptions externes**, section **Test réel OpenHIM** :

| Champ | Valeur |
|---|---|
| Norme | `CUSTOM_PATIENT_V1` |
| Workflow INBOUND | `Interop Hospital A Patient` |
| Système source | `hospital_a` |
| Adaptateur | `JSON générique` |

Données envoyées :

```json
{
  "patientId": "HA-OPENHIM-001",
  "birthDate": "1990-05-12",
  "statusCode": "active",
  "riskScore": 42.5
}
```

Cliquer sur **Envoyer par OpenHIM**. Le frontend génère un identifiant de suivi et affiche la réponse OpenHIM.

Résultat attendu :

1. Réponse HTTP 2xx.
2. Identifiant de suivi non vide.
3. Transaction visible dans OpenHIM Console.
4. Exécution visible dans **Réceptions externes**.
5. Statuts successifs `QUEUED`, `RUNNING`, puis `SUCCESS`.
6. Ligne `HA-OPENHIM-001` présente dans `gold_interop_patient`.

## 8. Contrôler la corrélation

Dans **Réceptions externes**, coller l'identifiant reçu dans **Tracer une transaction**.

Contrôler :

- le workflow sélectionné ;
- la date et la durée ;
- l'étape en erreur si le statut est `FAILED` ;
- le même identifiant dans les logs du médiateur, de l'API et du consumer.

Commandes utiles :

```powershell
docker logs --tail 200 iol-mediator
docker logs --tail 200 iol-api-core
docker logs --tail 200 iol-pipeline-consumer
```

Contrôle PostgreSQL :

```powershell
docker exec iol-postgres psql -U etl_user -d lakehouse `
  -c "SELECT patient_id, birth_date, status, risk_score FROM gold_interop_patient WHERE patient_id='HA-OPENHIM-001';"
```

## 9. Test de rejet

Envoyer volontairement :

```json
{
  "fullName": "Patient sans identifiant"
}
```

Résultat attendu : rejet de validation, entrée dans la DLQ avec `error_context.step`, message explicite dans le monitoring, et aucune nouvelle ligne Gold.

## 10. Test d'un autre système

Pour vérifier l'interopérabilité sémantique, envoyer ensuite une donnée avec `sourceSystem=hospital_b` :

```json
{
  "patientNumber": "HB-002",
  "dateOfBirth": "1985-09-20",
  "patientStatus": "ACTIVE",
  "riskValue": 18.75
}
```

Les deux messages doivent produire les mêmes clés pivot `patient_id`, `birth_date`, `status`, `risk_score`, malgré des noms de champs externes différents.

## 11. Validation réelle effectuée

Le scénario a été exécuté avec succès le 15 juillet 2026 :

| Élément | Valeur |
|---|---|
| Norme | `ca04da80-7cfe-4833-b518-a94423ab02a5` |
| Workflow INBOUND | `6a5707490a32626ea71d31ac` |
| Corrélation | `frontend-doc-20260715095056` |
| Transaction OpenHIM | `6a5749f07f07aec40b0b7381` |
| Exécution IOL | `6a5749f0f45f31278bd91a3b` |
| Statut final | `SUCCESS` |
| Ligne Gold | `HA-OH-095056`, `1990-05-12`, `ACTIVE`, `42.5` |

Le médiateur a renvoyé le pivot normalisé avant remise à Kafka :

```json
{
  "patient_id": "HA-OH-095056",
  "birth_date": "1990-05-12",
  "status": "active",
  "risk_score": 42.5
}
```

## 12. Diagnostic rapide

| Symptôme | Cause probable |
|---|---|
| Médiateur absent | Identifiants OpenHIM invalides ou Core indisponible |
| HTTP 401/403 | Authentification du canal ou secret interne incorrect |
| HTTP 2xx mais pas d'exécution | `standardId` ou `workflowId` invalide/inactif |
| DLQ validation | Champ obligatoire absent ou mapping incorrect |
| Échec `PIPELINE_CONSUMER` | Hop, destination ou SQL Silver/Gold |
| `mongo-init` arrêté | Normal seulement si le code de sortie est `0` |

En production, utiliser TLS, mTLS ou un client OpenHIM authentifié, et stocker tous les secrets hors du dépôt.
