# Pipeline Consumer

Ce microservice reçoit les commandes d’exécution issues du backend, contrôle leur intégrité et lance les traitements ETL via Apache Hop ou Spark selon le contexte.

## Rôle du service

Le pipeline consumer est le point d’exécution technique du système. Il ne décide pas du métier, mais il garantit que :
- une commande est reçue depuis Kafka ;
- les métadonnées d’exécution sont préparées ;
- les données nécessaires sont matérialisées dans un format exploitable ;
- l’exécution est lancée correctement ;
- les statuts de progression et d’erreur sont remontés.

## Flux principal

1. Le backend publie une commande d’exécution sur Kafka.
2. Le consumer récupère la commande.
3. Il construit le fichier de métadonnées utilisé par Hop.
4. Il lance l’exécution technique.
5. Il publie les statuts et, si besoin, les messages de DLQ.

## Exécution locale

```bash
cd backend/pipeline-consumer
mvn -DskipTests package
java -jar target/pipeline-consumer-0.0.1-SNAPSHOT.jar
```

## Configuration importante

Variables à prévoir selon l’environnement :
- `KAFKA_BOOTSTRAP_SERVERS` : adresse du broker Kafka ;
- `APP_KAFKA_COMMANDS_HIGH_TOPIC`, `APP_KAFKA_COMMANDS_TOPIC`, `APP_KAFKA_COMMANDS_LOW_TOPIC` : topics de commandes ;
- `APP_KAFKA_STATUS_TOPIC` : topic de statut ;
- `APP_KAFKA_DLQ_TOPIC` : topic pour les erreurs non récupérables.

## Cas INBOUND PUSH

Pour les commandes interop de type INBOUND, le consumer accepte un envelope similaire à celui du backend. Si la source est de type `PUSH`, il matérialise les données déjà normalisées en CSV temporaire pour permettre l’exécution via Hop, tout en conservant les métadonnées de corrélation et de contexte métier.

Le chemin classique `INTERNAL` ou `PULL` reste inchangé.

## Gestion des erreurs

En cas d’échec non récupérable, le consumer publie un message dans la DLQ avec un contexte d’erreur exploitable par l’ops et l’API.

## Profil Hop : Windows vs Linux

Le service détecte automatiquement le système d’exploitation au démarrage :
- Windows → profil `windows` avec `hop-run.bat` et chemins Windows ;
- Linux / Docker → profil `linux` avec `hop-run.sh` et chemins Linux.

Pour forcer un profil :

```bash
SPRING_PROFILES_ACTIVE=windows java -jar target/pipeline-consumer-0.0.1-SNAPSHOT.jar
```

Les valeurs Hop peuvent être surchargées par variables d’environnement `HOP_*` :
- `HOP_HOME`
- `HOP_RUN_SCRIPT`
- `HOP_WORKFLOW_FILE`
- `HOP_PIPELINES_DIR`
- `HOP_TEMP_DIR`

## Points utiles

- ce service est un composant d’orchestration technique ;
- il ne remplace pas la logique métier de l’API ;
- il doit rester robuste face aux erreurs de traitement et aux messages dupliqués.

