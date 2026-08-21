# Spécifications serveur IOL

## 1. Petit usage : démo, formation, tests ou peu de données

Pour lancer le projet à coût réduit, avec peu d'utilisateurs et de petits volumes de données :

| Ressource | Spécification conseillée |
| --- | --- |
| Processeur | **8 vCPU** (4 vCPU est le minimum) |
| Mémoire vive | **32 Go RAM** (16 Go est le minimum) |
| Disque | **500 Go NVMe SSD** (250 Go est le minimum) |
| Réseau | 1 Gbit/s |
| Système | Ubuntu Server 22.04 ou 24.04 LTS, Docker Engine et Docker Compose v2 |

### Contraintes

- Réduire à une instance les services parallèles : gateway, pipeline consumer et worker Spark.
- Ne pas activer les réplications Kafka, MongoDB, Keycloak ou RustFS.
- Réserver ce format aux démonstrations, à la formation, aux tests et aux petits flux ETL.
- Aucune haute disponibilité : une panne du serveur arrête toute la plateforme.
- Les gros fichiers, les traitements Spark lourds et de nombreux traitements simultanés risquent de saturer le serveur.

## 2. Déploiement complet : préproduction ou production

Pour exécuter la stack complète fournie sur **un seul serveur**, y compris les services répliqués et les traitements Spark :

| Ressource | Spécification conseillée |
| --- | --- |
| Processeur | **32 vCPU** |
| Mémoire vive | **128 Go RAM** |
| Disque | **2 × 2 To NVMe SSD en RAID 1** (2 To utiles) |
| Réseau | 1 Gbit/s minimum ; 10 Gbit/s conseillé pour de gros flux ETL |
| Système | Ubuntu Server 22.04 ou 24.04 LTS, Docker Engine et Docker Compose v2 |

Cette dimension couvre l'API, les gateways et consumers, Kafka (3 nœuds), MongoDB (3 nœuds), PostgreSQL, Keycloak, RustFS, Spark et ClamAV. Les traitements Spark et les transferts de gros fichiers sont les principaux consommateurs de CPU, RAM et disque.

### Pourquoi cette configuration nécessite ces ressources

| Composant | Configuration de préproduction/production | Ressources principalement sollicitées |
| --- | --- | --- |
| Spark | 3 workers, jusqu'à 8 vCPU et 16 Go de limite par worker | CPU et RAM lors des transformations ETL lourdes |
| Pipeline consumer | 3 instances, jusqu'à 4 vCPU et 8 Go par instance | RAM et CPU pour Hop/Spark, contrôles et écritures de données |
| Source gateway | 3 instances, jusqu'à 4 vCPU et 4 Go par instance | CPU/RAM pour lire les sources et transférer les données |
| API | 1 instance, jusqu'à 4 vCPU et 4 Go | Orchestration, API, sécurité et accès aux métadonnées |
| Kafka | 3 brokers, réplication des messages sur 7 jours | Disque rapide, RAM et entrées/sorties réseau |
| MongoDB | 3 nœuds répliqués | Disque, RAM et réseau pour les workflows et journaux |
| Keycloak | 2 instances, 2 Go par instance | RAM pour l'authentification et les sessions |
| RustFS, PostgreSQL et ClamAV | Stockage objet temporaire, base relationnelle et analyse antivirus | Principalement disque, mémoire cache et entrées/sorties |

Les limites indiquées ci-dessus sont les plafonds configurés. Elles ne sont pas toutes atteintes en continu, ce qui permet l'usage d'un serveur à 32 vCPU et 128 Go RAM pour une activité modérée. En cas de traitements Spark lourds et simultanés, augmenter les ressources ou réduire le nombre de workers et de consumers.

### Contraintes

- Cette configuration fait fonctionner tous les composants sur une même machine, mais elle ne fournit pas de vraie haute disponibilité.
- Les répliques protègent contre certaines pannes logicielles, mais pas contre la panne du serveur ou de son stockage.
- Le stockage doit être augmenté si les données temporaires et les journaux dépassent 2 To.

### Haute disponibilité recommandée

Prévoir **au minimum 3 serveurs** plutôt qu'un seul :

| Par serveur | Configuration conseillée |
| --- | --- |
| Processeur | 16 à 24 vCPU |
| Mémoire vive | 64 Go RAM |
| Disque | 1 à 2 To NVMe SSD en RAID 1 |
| Réseau | 10 Gbit/s entre les serveurs si possible |

Répartir Kafka, MongoDB, les workers Spark et les services applicatifs entre ces serveurs. Mettre toutes les répliques sur une seule machine ne protège pas contre une panne matérielle.

## Stockage et sauvegarde

- Le disque doit absorber les données temporaires RustFS, les journaux Kafka (rétention configurée à 7 jours), PostgreSQL, MongoDB et les fichiers envoyés (jusqu'à 5 Go par upload).
- Dimensionner ensuite selon le volume réel : prévoir au moins **3 fois le volume maximal de données temporaires sur 7 jours**, plus l'espace des sauvegardes.
- Prévoir une sauvegarde quotidienne chiffrée sur un stockage séparé du serveur principal.

## Réseau et sécurité

- Publier uniquement les ports web HTTPS **80/443** ; les bases, Kafka, Spark, RustFS et Vault restent sur le réseau interne.
- Utiliser une IP fixe, un nom de domaine et des certificats TLS valides.
- Prévoir une alimentation protégée (onduleur) et une supervision CPU, RAM, disque, sauvegardes et services Docker.

> Point important : le dépôt est prêt pour la préproduction, mais sa topologie de production doit être validée avec vos volumes réels et vos exigences de disponibilité.

## Remarque : minimum technique réel

Les besoins réels définissent le dimensionnement final. Mais e projet impose aussi un seuil technique : API, gateway, Kafka, MongoDB, PostgreSQL, Spark et ClamAV tournent ensemble.

Le vrai minimum pour démarrer toute l'application en mono-serveur, avec très peu de données, est :

| CPU | RAM | Disque |
| --- | --- | --- |
| 4 vCPU | 16 Go | 250 Go NVMe SSD |

Contraintes : un seul traitement ETL léger à la fois, aucun besoin de haute disponibilité, pas de gros fichiers ni plusieurs utilisateurs actifs.

Le minimum recommandé pour un usage fluide reste **8 vCPU, 32 Go RAM et 500 Go NVMe**.

En dessous de 4 vCPU / 16 Go, certains services peuvent démarrer, mais la plateforme complète — surtout Spark, Kafka et ClamAV — risque d'être lente ou instable.
