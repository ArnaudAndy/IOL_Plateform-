# Guide de deploiement IOL

Ce guide decrit la topologie cible et les commandes de mise en service de la
plateforme. Il ne faut pas lancer une production sans avoir execute la
repetition complete en preproduction et les controles `GO / NO-GO` du
[runbook](PRODUCTION_RUNBOOK.md).

## 1. Un domaine et un seul point d'entree

La plateforme expose un seul domaine public, configure par `IOL_PUBLIC_URL`.
Exemple : `https://iol.example.org`.

Nginx est le **seul** conteneur qui publie des ports sur l'hote :

| Port hote | Usage | Comportement |
| --- | --- | --- |
| `80/TCP` | HTTP | Redirection permanente vers HTTPS. |
| `443/TCP` | HTTPS | Unique port applicatif a ouvrir dans le pare-feu et dans le DNS public. |

Les services ne demandent donc pas deux domaines, un pour le frontend et un
pour le backend. Nginx les route a partir du chemin :

| Adresse publique | Destinataire | Public cible |
| --- | --- | --- |
| `${IOL_PUBLIC_URL}/` | Frontend React | Utilisateurs IOL |
| `${IOL_PUBLIC_URL}/api/...` | `api-core` | Frontend authentifie uniquement |
| `${IOL_PUBLIC_URL}/auth/...` | Keycloak | Connexion, compte utilisateur et administration autorisee |
| `${IOL_PUBLIC_URL}/interop/...` | OpenHIM puis mediateurs IOL | Partenaires d'interoperabilite |
| `${IOL_PUBLIC_URL}/openhim-console/` | Console OpenHIM | Administrateurs OpenHIM |
| `${IOL_PUBLIC_URL}/health/live` et `/health/ready` | Sondes Nginx / API | Supervision autorisee |

`RustFS`, Kafka, MongoDB, PostgreSQL, Spark, Vault et les ports internes des
mediateurs n'ont volontairement **pas** de lien public. RustFS est joignable
uniquement par les conteneurs a `https://rustfs-lb:9000`; son interface
d'administration ne doit pas etre publiee par Nginx. Les operations sur ces
services passent par un acces d'administration prive, audite et limite.

```text
Navigateur ou partenaire externe
              |
              v
    https://iol.example.org:443  (Nginx)
       | /              -> frontend React
       | /auth          -> Keycloak
       | /api           -> api-core
       | /interop       -> OpenHIM -> mediateurs -> api-core
```

Keycloak n'est pas une passerelle HTTP generale. Il authentifie les personnes
et les services, puis signe les jetons. Apres connexion, le navigateur appelle
directement `/api/...` via Nginx avec `Authorization: Bearer <JWT>`.

## 2. Frontend et URL de l'API

Le frontend appelle l'URL relative `/api`, car
`VITE_API_BASE_URL=/api`. Par exemple, depuis
`https://iol.example.org`, un appel vers `/workflows` devient
`https://iol.example.org/api/workflows`; Nginx le transmet ensuite a
`api-core:8084` sur le reseau interne.

Il ne faut pas fournir l'URL Docker ou le port interne du backend au frontend.
`VITE_API_PROXY_TARGET` sert uniquement a `npm run dev`; il n'est pas utilise
en production. Les valeurs `VITE_*` sont compilees dans le JavaScript pendant
le build : elles ne doivent contenir ni secret ni adresse interne.

En production, le conteneur `nginx` est aussi le conteneur web :
[frontend/Dockerfile](../frontend/Dockerfile) compile React puis copie le
repertoire `dist` dans son image Nginx. Il n'existe donc pas de conteneur
separe appele `frontend`.

## 3. Services internes

| Service | Role | Exposition |
| --- | --- | --- |
| Nginx | Frontend statique et reverse proxy | Seul service public : 80/443 |
| Keycloak | Identites, roles et jetons OAuth2/OIDC | Via `/auth/` |
| api-core | API de gestion, orchestration et metadonnees | Via `/api/` |
| OpenHIM | Reception et routage des flux interop | Via `/interop/` et console via `/openhim-console/` |
| Mediateurs IOL | Validation/adaptation FHIR, ISO 20022, Ed-Fi et JSON | Interne a OpenHIM |
| source-gateway | Lecture securisee des sources | Interne |
| pipeline-consumer | Traitements Hop/Spark et ecriture lakehouse | Interne |
| RustFS | Objets volumineux et staging | Interne, S3 TLS |
| Kafka / MongoDB / PostgreSQL | Bus, metadonnees et lakehouse | Interne |
| Vault | Chiffrement et secrets d'exploitation | Reseau d'administration prive |

OpenHIM reste un service distinct de Keycloak : il utilise Keycloak pour le
SSO de sa console, mais c'est OpenHIM qui recoit les messages des partenaires.

## 4. Environnements et secrets

Deux fichiers locaux et ignores par Git sont attendus :

```bash
cd backend
cp .env.preproduction.example .env.preproduction
cp .env.production.example .env.production
```

Ils ont exactement les memes cles. Seules les valeurs changent : domaine,
reseaux Docker, ressources et tag immuable de release. Les deux variables
qui assurent la jonction des stacks sont obligatoires :

```text
IOL_DOCKER_NETWORK=<reseau-interne-propre-a-l-environnement>
IOL_EGRESS_DOCKER_NETWORK=<reseau-sortant-propre-a-l-environnement>
```

Ne pas mettre de mot de passe, cle API, jeton, certificat ou cle privee dans
ces fichiers. Ils doivent etre fournis comme Docker secrets ou par Vault. La
preparation de preproduction peut s'appuyer sur les scripts existants :

```bash
cd backend
bash ops/secrets/generate-runtime-secrets.sh
IOL_PUBLIC_HOSTNAME=preprod.iol.example.org bash ops/pki/generate-preprod-pki.sh
IOL_ENV_FILE="$PWD/.env.preproduction" bash ops/production/prepare-host.sh
```

La PKI generee par ce dernier script est reservee a la preproduction. En
production, utiliser la PKI de l'organisation ou Vault PKI, avec une procedure
de rotation et de sauvegarde qualifiee.

Vault est une dependance de securite distincte. Il doit etre initialise et
disponible avant IOL ; le script de deploiement IOL refuse de continuer si le
reseau Vault ou ses identites attendues sont absents.

## 5. Hop est dans l'image

Apache Hop, Java, les moteurs Python et le dossier versionne `hop-project/`
sont copies dans l'image `pipeline-consumer`. Aucun paquet Hop ni aucun dossier
Hop externe ne doit etre installe ou monte sur le serveur. Le chemin interne
est fixe a `/opt/iol/project` et contient notamment
`Projet ETL/Global_Config/wf_main_ingestion.hwf`.

Les variables non secretes `HOP_PROJECT_NAME`, `HOP_WORKFLOW_FILE`,
`HOP_RUN_CONFIG_LOCAL` et `HOP_RUN_CONFIG_SPARK` permettent de selectionner
la configuration embarquee. Le precontrole refuse le deploiement si le workflow
selectionne n'est pas present dans `hop-project/`.

Une modification des pipelines Hop implique donc une nouvelle release : build,
test, signature et deploiement d'une nouvelle image portant un nouveau
`IOL_RELEASE_TAG`. Cette regle evite qu'un workflow soit modifie directement
sur un serveur sans trace ni possibilite de retour arriere.

## 6. Procedure unique de demarrage

Le script suivant utilise le meme fichier d'environnement pour la stack
principale et OpenHIM. Il demarre d'abord la stack principale, qui cree les
reseaux nommes, puis OpenHIM qui les rejoint.

Avant toute commande, le domaine doit pointer vers le serveur et les ports
`80` et `443` doivent etre ouverts au niveau du pare-feu. Le port `80` ne sert
qu'a rediriger vers HTTPS.

### Premier demarrage en preproduction

```bash
cd backend
bash ops/production/deploy.sh --environment preproduction --bootstrap
```

L'option `--bootstrap` execute une seule fois `keycloak-bootstrap`. Elle cree
les clients techniques, configure SMTP et le premier administrateur IOL, puis
retire le compte administrateur temporaire. Ne jamais la rejouer sans une
procedure explicite de restauration ou de rotation.

### Demarrage ou mise a jour ordinaire

```bash
cd backend
bash ops/production/deploy.sh --environment preproduction

# Apres qualification complete, pour la production :
bash ops/production/deploy.sh --environment production
```

Le script execute automatiquement le precontrole
`ops/production/preflight.sh`. Ce dernier refuse notamment un tag mutable,
une URL non HTTPS, un reseau manquant dans la configuration, un secret ou un
certificat absent, ou un projet Hop incomplet. Il ne lance pas de test de
charge et ne transforme pas une preproduction en production qualifiee.

### Etat et arret planifie

```bash
cd backend

docker compose --env-file .env.preproduction \
  -f docker-compose.yml -f docker-compose.production.yml ps
docker compose --env-file .env.preproduction \
  -f openhim/docker-compose.openhim.yml \
  -f openhim/docker-compose.openhim.production.yml ps
```

Pour un arret planifie, arreter OpenHIM avant la stack principale. Ne pas
ajouter `-v` : cette option supprimerait les volumes de donnees.

```bash
cd backend
docker compose --env-file .env.preproduction \
  -f openhim/docker-compose.openhim.yml \
  -f openhim/docker-compose.openhim.production.yml down
docker compose --env-file .env.preproduction \
  -f docker-compose.yml -f docker-compose.production.yml down
```

## 7. Verification apres deploiement

1. Verifier que `https://<domaine>/` affiche la console IOL.
2. Verifier `https://<domaine>/health/live` et `/health/ready` avec la
   supervision autorisee.
3. Se connecter via `https://<domaine>/auth/` et verifier les roles Keycloak.
4. Ouvrir `https://<domaine>/openhim-console/` avec un compte autorise.
5. Envoyer un message synthetique sur un canal `/interop/...` avec une
   `Idempotency-Key`, sans donnee personnelle reelle.
6. Verifier les statuts d'execution, la DLQ et l'absence de corps metier dans
   les transactions OpenHIM.
7. Executer les tests de restauration, de charge et de panne prevus dans le
   [runbook](PRODUCTION_RUNBOOK.md) avant le `GO` production.

