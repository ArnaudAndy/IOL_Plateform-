# Runbook de mise en service

Ce document décrit la procédure pratique à suivre pour mettre en service le dépôt dans un environnement cible. Il est volontairement conservé à un niveau simple, car la topologie complète de production n’a pas encore été entièrement qualifiée ici.

## 1. État actuel

Le dépôt contient une stack fonctionnelle de base avec :
- frontend React/Vite ;
- backend Spring Boot ;
- services d’infrastructure via Docker Compose ;
- composants de sécurité et d’intégration comme Keycloak, Vault, Kafka, RustFS, OpenHIM et ClamAV.

La plateforme est donc opérationnelle pour des usages de préproduction et d’intégration, mais la mise en production complète exige encore des validations spécifiques à l’environnement.

## 2. Pré-requis

Avant toute mise en service :
1. préparer un environnement Linux/Windows avec Docker Compose fonctionnel ;
2. générer les secrets de fichiers avec `scripts/generate-production-secrets.sh`,
   puis provisionner les identités Vault avec
   `scripts/bootstrap-production-vault.sh` ;
3. vérifier que le frontend est construit avant d’exposer l’interface ;
4. générer la PKI avec `scripts/generate-production-pki.sh` et vérifier la
   connectivité mTLS entre les services internes ;
5. exécuter le preflight avant toute création de conteneur :
   `scripts/preflight-production.sh`.

### Premier bootstrap Keycloak

Le service `keycloak-bootstrap` est volontairement place dans le profil
`bootstrap`. Il configure les clients et roles, teste SMTP, cree le premier
administrateur avec un mot de passe temporaire, puis supprime l'administrateur
master de bootstrap. Il ne doit donc pas etre rejoue automatiquement a chaque
release.

Pour la premiere installation uniquement :

```bash
cd backend
docker compose --env-file .env.production \
  -f docker-compose.yml -f docker-compose.production.yml \
  --profile bootstrap up --abort-on-container-exit keycloak-bootstrap
docker compose --env-file .env.production \
  -f docker-compose.yml -f docker-compose.production.yml up -d
```

Lors des releases suivantes, executer uniquement la seconde commande. Les
applications dependent de la readiness Keycloak et leurs propres readiness
verifient ensuite l'obtention d'un jeton avec leurs identites de service.

## 3. Démarrage de base

```bash
cd backend
cp .env.example .env
cd ../frontend
npm ci
npm run build
cd ../backend
docker compose up -d
```

## 4. Vérification minimale

Après démarrage, vérifier :
- l’interface sur http://localhost ;
- l’API via l’endpoint de base ou le proxy Nginx ;
- les services Docker avec `docker compose ps` ;
- la santé du backend si l’endpoint est disponible.

## 5. Sécurité minimale

Avant toute utilisation sensible :
- ne jamais committer les secrets ;
- garder les clés hors du dépôt ;
- vérifier que ClamAV est bien actif pour les uploads ;
- contrôler les accès à Keycloak et Vault ;
- éviter l’exposition directe des services internes au réseau public.

## 6. Rollback simple

Si un déploiement ne se passe pas correctement :
1. stopper la pile avec `docker compose down` ;
2. vérifier les logs des services concernés ;
3. corriger la configuration ou les secrets ;
4. redémarrer la pile proprement.

## 7. À garder en tête

Le dépôt contient la structure de production attendue, mais la qualification réelle reste à faire dans l’environnement cible. La documentation doit donc rester pragmatique et s’appuyer sur les preuves observées, pas sur des hypothèses de niveau production.

### Points de defaillance unique restants

Kafka, MongoDB, RustFS et Keycloak sont replliques. **PostgreSQL et Nginx ne le
sont pas**, et aucun fichier Compose ne peut honnetement corriger cela sur un
hote unique.

| Composant | Effet de la perte | Pourquoi Compose ne suffit pas |
| --- | --- | --- |
| PostgreSQL | Le verrou d'execution distribue disparait : les consumers ne peuvent plus reclamer de travail, meme si Kafka, Mongo et RustFS sont sains. Les comptes locaux et les jetons de rafraichissement deviennent inaccessibles. | Ajouter un second conteneur sans orchestrateur de replication produit une divergence silencieuse et un risque de split-brain. C'est **pire** qu'un noeud unique, parce que cela cree une fausse confiance. |
| Nginx | Plus aucun acces exterieur, meme si tous les services internes sont sains. C'est le seul composant qui publie des ports. | Des replicas se disputeraient les ports 80 et 443 du meme hote. La repartition exige une adresse flottante ou un repartiteur en amont. |

Traitements reels, hors Compose :

- **PostgreSQL** : Patroni ou repmgr avec bascule automatique, ou une instance
  geree par l'hebergeur. Router les clients via un point d'entree unique
  (PgBouncer, HAProxy ou le point de terminaison du service gere) afin que la
  bascule reste transparente pour `api-core` et le `pipeline-consumer`.
- **Nginx** : deux hotes avec `keepalived` et une adresse IP virtuelle, ou un
  repartiteur en amont, ou une entree d'orchestrateur. Le certificat et la
  politique CORS doivent etre identiques sur chaque instance.

Tant que ces deux points ne sont pas traites, la plateforme reste une topologie
**mono-hote a redondance partielle** : elle survit a la perte d'un broker, d'un
membre Mongo ou d'un noeud RustFS, mais pas a la perte de sa base de verrous ni
de sa porte d'entree.

### Charge

```bash
IOL_BASE_URL=https://preprod.iol.example.org \
IOL_ACCESS_TOKEN_FILE=/run/secrets/iol-load-token \
IOL_LOAD_VUS=50 IOL_LOAD_DURATION=15m \
bash backend/ops/tests/run-load-test.sh
```

Executer au minimum : volume sous le seuil, volume juste au-dessus, un record
trop grand pour Kafka, upload multipart, requete SQL Silver/Gold longue,
rebalance consumer et plusieurs organisations simulees sans activer le mode
multi-organisation. Fixer les objectifs p95/p99 et debit avant le test.

### Panne

Dans une fenetre de maintenance de preproduction :

```bash
export CONFIRM_CHAOS=IOL-HA-FAILOVER
export IOL_PRODUCTION_ENV_FILE="$PWD/backend/.env.production"
bash backend/ops/tests/ha-failover.sh
```

Le script injecte successivement la perte d'un broker Kafka, d'un membre Mongo
et d'un noeud RustFS, puis remet chaque service en etat. Completer manuellement
par la perte d'un hote entier, d'une zone, du DNS, du KMS, de Vault, de
PostgreSQL, du Spark master, de ClamAV et du depot de sauvegarde.

### Rollback

Conserver deux fichiers d'environnement differents, tous deux avec des tags
immuables. Le dry-run ne modifie rien :

```bash
CURRENT_RELEASE_ENV=backend/releases/current.env \
PREVIOUS_RELEASE_ENV=backend/releases/previous.env \
ROLLBACK_DRY_RUN=true \
bash backend/ops/tests/rollback-rehearsal.sh
```

Pour la repetition reelle, fournir une sauvegarde verifiee et confirmer
explicitement. Le script remet la version precedente, attend sa readiness puis
restaure la version courante. Une migration de schema destructive doit avoir
son propre plan de retour ; aucun rollback binaire ne peut la rendre reversible.

## 10. Cas d'utilisation reels

### Hopital, Oracle vers lakehouse

L'administrateur cree une connexion Oracle. Le mot de passe est chiffre par
Vault et seul le ciphertext va dans MongoDB. Au lancement, `api-core` publie un
ordre minimal. Le `source-gateway` relit le workflow, verifie sa revision, lit
Oracle par fenetres et transporte les lignes dans Kafka. Le consumer lance Hop
sur le staging JSONL. Une panne du gateway ou du consumer provoque une reprise
avec claim persistant, heartbeat et fencing ; Hop ne se reconnecte jamais a
Oracle.

### Assurance, 2,4 To

L'estimation depasse les seuils. Sans choix affiche a l'utilisateur, le
`source-gateway` envoie la source en multipart vers RustFS, publie le manifeste
dans Kafka et le consumer selectionne Spark. Apres succes, l'objet technique
est supprime. En cas d'echec, il reste 72 heures par defaut pour diagnostic
avant purge.

### Demarrage securise de RustFS

`rustfs-init` est une etape bloquante du Compose de production. Elle cree le
bucket s'il est absent, installe une politique limitee au prefixe
`source-data/*`, provisionne l'identite applicative et verifie son acces. Les
services `api-core`, `source-gateway` et `pipeline-consumer` ne demarrent
qu'apres son succes. En profil `prod`, aucun service applicatif n'a le droit de
creer lui-meme le bucket.

Les mots de passe MongoDB de runtime sont separes :
`mongodb-gateway-password` ne permet d'ecrire que dans `transport_claims`, et
`mongodb-pipeline-password` uniquement dans `pipeline_execution_claims` et
`pipeline_data_chunks`. Les deux services ont egalement leurs propres
certificats, ACL Kafka et identites Vault.

### Echange ISO 20022

La banque envoie un message signe au canal OpenHIM. Le mediateur Java parse et
valide le XML sans resolution d'entites externes, produit le pivot JSONL et
transmet une `Idempotency-Key`. Un rejeu du meme message retrouve le ledger
MongoDB et ne double pas la livraison.

### Restauration apres perte d'une zone

L'astreinte isole la zone, retablit les quorum restants, restaure les donnees
manquantes depuis Restic dans un environnement vierge, inspecte Vault, compare
les comptes et checksums, puis ouvre le trafic. La decision revient au
responsable incident et au proprietaire de donnees, jamais au script seul.

## 11. Incidents prioritaires

| Incident | Premiere action | Interdit |
| --- | --- | --- |
| Secret expose | revoquer, tourner, rechercher l'empreinte | remettre le secret historique |
| Vault sealed | geler les nouvelles executions, restaurer quorum/seal | basculer en plaintext |
| Certificat compromis | retirer la confiance, reemettre, redemarrer par vagues | desactiver mTLS globalement |
| Poison pill Kafka | verifier DLQ et acquittement | rejouer toute la partition sans filtre |
| Objet infecte | isoler la quarantaine, alerter securite | charger quand ClamAV est indisponible |
| Pipeline bloque | laisser le watchdog cibler l'etape, examiner l'historique | marquer arbitrairement `SUCCESS` |
| Double livraison | bloquer l'endpoint, verifier ledger et destination | supprimer le ledger |

## 12. Decision GO / NO-GO

Le comite ne peut prononcer `GO` que si toutes les cases suivantes possedent
une preuve datee :

- [ ] CI GitHub entierement verte sur le commit signe de release.
- [ ] Images sans vulnerabilite bloquante, signees et verifiees.
- [ ] Vault/KMS HA, rotation et restauration de snapshot reussis.
- [ ] Keycloak HA, MFA, SMTP, roles et recuperation admin testes.
- [ ] TLS/mTLS et ACL verifies par tests positifs et negatifs.
- [ ] Kafka, MongoDB et RustFS repartis sur des domaines de panne distincts.
- [ ] Version RustFS cible qualifiee avec KMS, multipart et reconstruction.
- [ ] PostgreSQL et plan de controle Spark hautement disponibles.
- [ ] Sauvegarde hors site restauree dans le RTO et le RPO contractuels.
- [ ] Tests de charge aux volumes reels et tests de panne reussis.
- [ ] Rollback de deux vraies releases immuables reussi.
- [ ] Monitoring, alertes et astreinte testes.
- [ ] Audit de securite independant et traitement des constats termines.
- [ ] Contrats FHIR/ISO 20022/Ed-Fi signes avec chaque partenaire.
- [x] Mono-organisation verrouille dans le code : aucun commutateur de mode
      n'existe, le multi-organisation ne peut pas etre active par configuration.

Un seul echec sur un controle de secret, d'integrite, de restauration,
d'authentification ou d'isolation impose un `NO-GO`.
