# Architecture Kafka de la Plateforme ETL

## Vue d'ensemble

La plateforme utilise Apache Kafka comme système de messagerie asynchrone pour orchestrer l'exécution des pipelines ETL entre le backend principal et le service consommateur de pipelines.

## Architecture Globale

```
┌─────────────────┐      ┌──────────────────┐      ┌─────────────────────┐
│   Backend       │      │     Kafka        │      │  Pipeline Consumer  │
│   Principal     │─────▶│   Broker         │─────▶│  (Kafka Listener)   │
│   (Port 8084)   │      │  (Port 9092)     │      │   (Port 8085)       │
└─────────────────┘      └──────────────────┘      └─────────────────────┘
         │                                                 │
         │                                                 │
         ▼                                                 ▼
┌─────────────────┐                              ┌─────────────────────┐
│   MongoDB       │                              │   Apache Hop        │
│ (Metadata)      │                              │  (ETL Execution)    │
└─────────────────┘                              └─────────────────────┘
```

## Flux de Données Détaillé

### Contrat actuel des commandes pipeline

Les commandes consommées par `pipeline-consumer` doivent porter :

```json
{
  "eventType": "PIPELINE_EXECUTION_REQUESTED",
  "workflowId": "wf_123",
  "execLogId": "log_456",
  "workflowName": "Workflow",
  "executionMode": "LOCAL",
  "priority": 3,
  "direction": "INTERNAL",
  "standardId": null,
  "sources": [
    {
      "source_name": "CSV",
      "config": {
        "target_table": "stg_table",
        "target_connection": {},
        "source_config": {},
        "silver_config": {}
      }
    }
  ],
  "gold_config_global": {}
}
```

Pour l'interop INBOUND, le médiateur OpenHIM publie le même contrat avec
`direction=INBOUND`, `standardId`, et une source `PUSH` dont les données sont
déjà normalisées au pivot IOL :

```json
{
  "eventType": "PIPELINE_EXECUTION_REQUESTED",
  "direction": "INBOUND",
  "standardId": "std_custom",
  "correlationId": "corr-123",
  "openhimTransactionId": "openhim-tx-123",
  "sources": [
    {
      "source_name": "PUSH",
      "type": "PUSH",
      "config": {
        "source_config": {
          "mode": "PUSH",
          "already_pivot": true,
          "data": { "patient_id": "P001" },
          "records": [{ "patient_id": "P001" }]
        }
      }
    }
  ]
}
```

Le consumer accepte cette source `PUSH` uniquement sur `direction=INBOUND` et la
matérialise en CSV temporaire pour réutiliser le pipeline Hop existant.

Les messages `iol.pipeline.status` publiés par `pipeline-consumer` recopient
`direction`, `standardId`, `sourceSystem`, `correlationId` et
`openhimTransactionId`. api-core les fusionne dans `ExecutionLog.executionParams`,
ce qui permet de consulter les transactions interop via :

- `GET /api/logs/interop`
- `GET /api/logs/interop/correlation/{correlationId}`
- `GET /api/logs/interop/summary`

### 1. Configuration du Pipeline (Backend Principal)

**Fichier:** `backend/src/main/java/com/iol/etlplatform/service/WorkflowService.java`

```java
// 1. L'utilisateur configure le pipeline via l'interface web
// 2. Les métadonnées sont construites dans WorkflowWizardPage.jsx
// 3. Le payload est envoyé au backend

@PostMapping("/api/workflows")
public WorkflowConfigDto createWorkflow(@RequestBody WorkflowPayload payload) {
    // Construction des métadonnées JSON
    String metadataJson = buildMetadataJson(payload);
    
    // Sauvegarde dans MongoDB
    WorkflowConfig workflow = saveToDatabase(payload, metadataJson);
    
    // Publication vers Kafka
    if (kafkaEnabled) {
        kafkaTemplate.send("iol.pipeline.events", metadataJson);
    }
    
    return workflow;
}
```

### 2. Structure des Métadonnées (Format Apache Hop)

**Fichier:** `frontend/src/pages/WorkflowWizardPage.jsx` - `buildMetadataObject()`

```json
{
  "pipeline": {
    "name": "pipeline_ventes",
    "description": "Pipeline ETL pour les ventes",
    "version": "v1"
  },
  "standard": "Finance",
  "schedule": {
    "enabled": true,
    "frequency": "DAILY",
    "time": "05:00"
  },
  "sources": [
    {
      "source_name": "CSV_Ventes",
      "type": "CSV",
      "config": {
        "file_path": "C:\\ventes.csv",
        "target_connection": {
          "host": "localhost",
          "port": 5432,
          "database": "lakehouse",
          "username": "postgres",
          "password": "password",
          "target_table": "stg_ventes"
        },
        "source_config": {
          "delimiter": ";",
          "enclosure": "\"",
          "encoding": "UTF-8"
        },
        "fields": [
          {
            "name": "id_client",
            "type": "String",
            "trim": "both"
          }
        ],
        "silver_config": {
          "target_table_silver": "cln_ventes",
          "elt_scripts_silver": "SQL de nettoyage"
        }
      }
    }
  ],
  "fields": [...],
  "gold_config": {
    "target_table_gold": "fact_ventes_mensuelles",
    "elt_scripts_gold": "SQL d'agrégation"
  },
  "createdAt": "2026-06-05T09:25:44.194Z"
}
```

### 3. Publication Kafka

**Fichier:** `backend/src/main/java/com/iol/etlplatform/kafka/KafkaPipelineEventService.java`

```java
@Service
public class KafkaPipelineEventService {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    
    @Value("${app.kafka.topics.pipeline-events:iol.pipeline.events}")
    private String pipelineTopic;
    
    public void publishPipelineEvent(String metadataJson) {
        // Publication asynchrone vers Kafka
        kafkaTemplate.send(pipelineTopic, metadataJson)
            .whenComplete((result, exception) -> {
                if (exception == null) {
                    log.info("Pipeline publié avec succès, offset: {}", 
                        result.getRecordMetadata().offset());
                } else {
                    log.error("Erreur publication Kafka", exception);
                }
            });
    }
}
```

### 4. Consommation et Orchestration

**Fichier:** `backend/pipeline-consumer/src/main/java/com/iol/etlplatform/pipelineconsumer/service/PipelineOrchestrator.java`

```java
@Service
public class PipelineOrchestrator {
    
    @KafkaListener(topics = "${app.kafka.topics.pipeline-events:iol.pipeline.events}")
    public void executeFromEvent(String metadataJson) {
        try {
            // 1. Parser les métadonnées JSON
            JsonNode meta = objectMapper.readTree(metadataJson);
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
