# Kafka dans ce projet

Ce document décrit l’usage pratique de Kafka dans la stack backend actuelle.

## Rôle de Kafka

Kafka sert de bus de messages entre les services du backend et le consumer de pipeline. Il transporte les commandes d’exécution, les états de progression et les événements liés au traitement.

## Où Kafka est utilisé

Dans ce dépôt, Kafka est intégré à :
- api-core : publication des commandes et événements de pipeline ;
- pipeline-consumer : consommation des commandes et suivi de l’exécution ;
- la stack Docker Compose : broker Kafka et dépendances associées.

## Flux principal

1. api-core prépare une exécution.
2. Un message est publié vers un topic Kafka.
3. pipeline-consumer consomme ce message.
4. Le consumer déclenche l’exécution technique via Hop ou Spark selon le contexte.

## Points techniques utiles

- Les topics sont définis dans la configuration Docker Compose et dans les variables d’environnement du backend.
- Les messages transportent des métadonnées et des commandes, pas les secrets de connexion en clair.
- Kafka est utilisé comme couche de coordination asynchrone, pas comme stockage de référence unique.

## Bonnes pratiques

- garder les messages simples et structurés ;
- éviter d’y mettre des secrets sensibles ;
- surveiller les topics, les offsets et les erreurs de consommation ;
- traiter les messages idempotemment quand c’est possible.

## À retenir

Kafka est un composant de transport et d’orchestration, pas un substitut au stockage métier durable.
            String pipelineName = meta.path("pipeline").path("name").asText();
            
            // 2. Extraire la configuration source
            JsonNode sources = meta.path("sources");
            for (JsonNode source : sources) {
                processSource(source, meta);
            }
            
            // 3. Exécuter les scripts gold (agrégation finale)
            String goldScript = meta.path("gold_config")
                .path("elt_scripts_gold").asText();
            if (!goldScript.isBlank()) {
                executeGoldAggregation(goldScript, meta);
            }
            
            log.info("Pipeline {} exécuté avec succès", pipelineName);
            
        } catch (Exception e) {
            log.error("Erreur exécution pipeline", e);
            // Publication vers Dead Letter Queue
            publishToDLQ(metadataJson, e.getMessage());
        }
    }
    
    private void processSource(JsonNode source, JsonNode metadata) {
        String sourceType = source.path("type").asText();
        JsonNode config = source.path("config");
        
        switch (sourceType) {
            case "CSV":
                processCsvSource(config, metadata);
                break;
            case "POSTGRES":
            case "MYSQL":
                processDatabaseSource(config, metadata);
                break;
            // Autres types de sources...
        }
    }
    
    private void processCsvSource(JsonNode config, JsonNode metadata) {
        String filePath = config.path("file_path").asText();
        JsonNode connection = config.path("target_connection");
        JsonNode sourceConfig = config.path("source_config");
        
        // 1. Lire le fichier CSV
        // 2. Appliquer les transformations silver
        String silverScript = config.path("silver_config")
            .path("elt_scripts_silver").asText();
        
        // 3. Exécuter via Apache Hop ou JDBC
        executeHopTransformation(filePath, silverScript, connection);
    }
    
    private void executeHopTransformation(String input, 
                                         String script, 
                                         JsonNode connection) {
        // Construction de la commande Apache Hop
        String hopCommand = buildHopCommand(input, script, connection);
        
        // Exécution du pipeline Hop
        ProcessBuilder pb = new ProcessBuilder(hopCommand);
        // ... exécution ...
    }
}
```

## Configuration Kafka

### Docker Compose

**Fichier:** `backend/docker-compose.yml`

```yaml
services:
  kafka:
    image: bitnami/kafka:3.7
    container_name: etl-kafka
    environment:
      KAFKA_ENABLE_KRAFT: "yes"
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: broker,controller
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    ports:
      - "9092:9092"
    volumes:
      - kafka_data:/bitnami/kafka

  pipeline-consumer:
    build:
      context: ./pipeline-consumer
    container_name: pipeline-consumer
    depends_on:
      - kafka
    environment:
      KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
      APP_KAFKA_PIPELINE_TOPIC: "iol.pipeline.events"
```

### Configuration Application

**Fichier:** `backend/src/main/resources/application.yml`

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

app:
  kafka:
    enabled: ${APP_KAFKA_ENABLED:true}
    topics:
      pipeline-events: ${APP_KAFKA_PIPELINE_TOPIC:iol.pipeline.events}
```

## Avantages de cette Architecture

### 1. **Découplage**
- Le backend principal n'attend pas la fin de l'exécution ETL
- Le consommateur peut être scale indépendamment

### 2. **Fiabilité**
- Les messages Kafka sont persistés
- En cas d'échec, le pipeline peut être rejoué
- Dead Letter Queue pour les erreurs non récupérables

### 3. **Asynchronisme**
- L'utilisateur reçoit une confirmation immédiate
- L'exécution ETL se fait en arrière-plan

### 4. **Extensibilité**
- Facile d'ajouter de nouveaux consommateurs
- Supporte le traitement parallèle de multiples pipelines

## Flux Complet avec Credentials

### Étape 1: Configuration Utilisateur
1. L'utilisateur remplit le formulaire dans `WorkflowWizardPage.jsx`
2. Spécifie les credentials de la source (host, port, database, username, password)
3. Configure les transformations SQL (silver et gold)

### Étape 2: Construction Métadonnées
```javascript
// Dans buildMetadataObject()
const metadata = {
  sources: [{
    config: {
      target_connection: {
        host: "localhost",
        port: 5432,
        database: "source_db",
        username: "user",
        password: "pass" // ⚠️ Devrait être encrypté
      }
    }
  }]
};
```

### Étape 3: Envoi vers Backend
```javascript
await workflowService.createWorkflow({
  metadata_json: JSON.stringify(metadata)
});
```

### Étape 4: Publication Kafka
```java
// Dans WorkflowService.java
String metadataJson = payload.getMetadataJson();
kafkaTemplate.send("iol.pipeline.events", metadataJson);
```

### Étape 5: Récupération et Exécution
```java
// Dans PipelineOrchestrator.java
@KafkaListener(topics = "iol.pipeline.events")
public void executeFromEvent(String metadataJson) {
    JsonNode meta = objectMapper.readTree(metadataJson);
    
    // Récupérer les credentials
    String host = meta.path("sources").get(0)
        .path("config").path("target_connection")
        .path("host").asText();
    String username = meta.path("sources").get(0)
        .path("config").path("target_connection")
        .path("username").asText();
    String password = meta.path("sources").get(0)
        .path("config").path("target_connection")
        .path("password").asText();
    
    // Se connecter à la source
    Connection conn = DriverManager.getConnection(
        "jdbc:postgresql://" + host + "/source_db",
        username,
        password
    );
    
    // Exécuter les transformations
    Statement stmt = conn.createStatement();
    stmt.execute(silverScript);
    stmt.execute(goldScript);
}
```

## Sécurité des Credentials

⚠️ **IMPORTANT:** Les credentials ne devraient jamais être stockés en clair.

### Solutions Recommandées:

1. **Encryption dans MongoDB**
```java
@Service
public class CredentialEncryptionService {
    
    @Value("${encryption.key}")
    private String encryptionKey;
    
    public String encrypt(String password) {
        // Utiliser AES-256 ou similaire
        return encryptedPassword;
    }
    
    public String decrypt(String encryptedPassword) {
        return decryptedPassword;
    }
}
```

2. **Utiliser un Vault (HashiCorp Vault, AWS Secrets Manager)**
```java
@Autowired
private VaultTemplate vaultTemplate;

public String getCredential(String path) {
    return vaultTemplate.read(path).getData().get("password");
}
```

3. **Variables d'environnement dans le pipeline-consumer**
```yaml
# docker-compose.yml
pipeline-consumer:
  environment:
    DB_PASSWORD: ${DB_PASSWORD}
```

## Gestion des Erreurs

### Dead Letter Queue (DLQ)

**Fichier:** `backend/pipeline-consumer/src/main/java/com/iol/etlplatform/pipelineconsumer/service/PipelineOrchestrator.java`

```java
@Value("${app.kafka.topics.dlq:iol.pipeline.events.dlq}")
private String dlqTopic;

private void publishToDLQ(String metadataJson, String error) {
    JsonNode meta = objectMapper.readTree(metadataJson);
    String pipelineName = meta.path("pipeline").path("name").asText();
    
    JsonNode dlqMessage = objectMapper.createObjectNode()
        .put("pipeline_name", pipelineName)
        .put("error", error)
        .put("timestamp", Instant.now().toString())
        .put("metadata", metadataJson);
    
    kafkaTemplate.send(dlqTopic, dlqMessage.toString());
}
```

## Monitoring

### Health Check
```java
@RestController
@RequestMapping("/actuator")
public class HealthController {
    
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("kafka", kafkaConnected ? "UP" : "DOWN");
        status.put("database", dbConnected ? "UP" : "DOWN");
        return status;
    }
}
```

## Conclusion

Cette architecture Kafka permet:
- ✅ Une exécution asynchrone des pipelines
- ✅ Un découplage entre configuration et exécution
- ✅ Une scalabilité horizontale
- ✅ Une gestion robuste des erreurs
- ✅ Un support natif pour multiples sources

Le flux complet est:
1. Configuration → 2. Métadonnées JSON → 3. Kafka → 4. Consumer → 5. Apache Hop → 6. Base de données
