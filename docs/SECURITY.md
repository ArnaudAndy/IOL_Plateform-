# Sécurité actuelle

Ce document décrit la posture de sécurité telle qu’elle ressort du dépôt actuel. Il ne remplace pas un audit externe.

## Principes appliqués

- Aucun secret ne doit être committed dans Git.
- Les services utilisent des variables d’environnement et des fichiers de configuration locaux.
- Le backend applique une sécurité Spring avec authentification et contrôle d’accès.
- Les uploads sont analysés par ClamAV avant traitement.
- Les composants sensibles sont séparés dans la stack Docker Compose et ne doivent pas être exposés directement à l’extérieur.

## Composants sensibles présents

- Keycloak : gestion des comptes et des rôles.
- Vault : stockage et circulation de secrets.
- Kafka et RustFS : transport et stockage de données sensibles selon le contexte.
- OpenHIM : interopérabilité avec contrôle de flux et logs.

## Ce qui est déjà prévu dans le dépôt

- authentification backend via Spring Security ;
- séparation des services réseau via la stack Docker Compose ;
- intégration de ClamAV pour les uploads ;
- support de secrets via variables d’environnement et intégrations Vault/Keycloak.

## Ce qu’il faut encore valider en environnement cible

- la qualification complète de la topologie de production ;
- la politique exacte de rotation et de stockage des secrets ;
- la validation des ACL, TLS/mTLS et des procédures de récupération ;
- la conformité opérationnelle et la traçabilité des accès.

## Recommandation pratique

En développement local, garder les secrets hors du dépôt et ne jamais les exposer dans les logs. En production, valider chaque service avant mise en service et conserver une procédure de rollback documentée.
