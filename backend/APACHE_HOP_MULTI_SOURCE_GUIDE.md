# Guide: Adaptation Apache Hop pour Support Multi-Sources

## Contexte Actuel

Votre pipeline Apache Hop actuel gère une seule source. Voici comment l'adapter pour gérer **plusieurs sources** simultanément, comme spécifié dans la nouvelle structure de métadonnées.

## Nouvelle Structure de Métadonnées

```json
{
  "pipeline": { "name": "multi_source_pipeline", ... },
  "sources": [
    {
      "source_name": "CSV_Ventes",
      "type": "CSV",
      "config": {
        "file_path": "C:\\ventes.csv",
        "target_connection": { ... },
        "source_config": { ... },
        "fields": [...],
        "silver_config": { ... }
      }
    },
    {
      "source_name": "PostgreSQL_Commandes",
      "type": "POSTGRES",
      "config": {
        "target_connection": { ... },
        "fields": [...],
        "silver_config": { ... }
      }
    }
  ],
  "gold_config": {
    "target_table_gold": "fact_ventes_mensuelles",
    "elt_scripts_gold": "SQL d'agrégation finale"
  }
}
```

## Architecture du Pipeline Multi-Sources

### 1. Pipeline Principal d'Orchestration

**Nom:** `main_orchestration.hpl`

```
┌─────────────────────────────────────────────────────────────┐
│                     MAIN ORCHESTRATION                       │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐    │
│  │   START      │──▶│  Parse JSON  │──▶│  Loop Sources│    │
│  │  Pipeline    │   │  Metadata    │   │  (For Each)  │    │
│  └──────────────┘   └──────────────┘   └──────────────┘    │
│                                              │               │
│                                              ▼               │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐    │
│  │   END        │◀──│  Gold        │◀──│ Source       │    │
│  │  Pipeline    │   │  Aggregation │   │ Sub-Pipeline │    │
│  └──────────────┘   └──────────────┘   └──────────────┘    │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### 2. Sub-Pipeline par Type de Source

#### A. Sub-Pipeline pour Sources CSV

**Nom:** `csv_source_processor.hpl`

**Paramètres d'entrée:**
- `${SOURCE_CONFIG}` - Configuration JSON de la source
- `${METADATA_JSON}` - Métadonnées complètes

**Étapes:**

1. **Lire le fichier CSV**
```hop
Transform: CSV File Input
  - Filename: ${SOURCE_CONFIG.file_path}
  - Delimiter: ${SOURCE_CONFIG.source_config.delimiter}
  - Encoding: ${SOURCE_CONFIG.source_config.encoding}
  - Fields: ${SOURCE_CONFIG.fields}
```

2. **Écrire dans la table de staging**
```hop
Transform: Table Output
  - Connection: ${SOURCE_CONFIG.target_connection}
  - Target table: ${SOURCE_CONFIG.target_connection.target_table}
  - Commit size: 1000
```

3. **Exécuter le script Silver**
```hop
Transform: Execute SQL Script
  - SQL: ${SOURCE_CONFIG.silver_config.elt_scripts_silver}
  - Connection: ${SOURCE_CONFIG.target_connection}
```

#### B. Sub-Pipeline pour Sources Database (PostgreSQL, MySQL, etc.)

**Nom:** `database_source_processor.hpl`

**Étapes:**

1. **Lire depuis la source**
```hop
Transform: Table Input
  - Connection: ${SOURCE_CONFIG.target_connection}
  - SQL: SELECT * FROM ${SOURCE_CONFIG.source_config.table}
```

2. **Écrire dans staging**
```hop
Transform: Table Output
  - Connection: ${SOURCE_CONFIG.target_connection}
  - Target table: ${SOURCE_CONFIG.target_connection.target_table}
```

3. **Transformation Silver**
```hop
Transform: Execute SQL Script
  - SQL: ${SOURCE_CONFIG.silver_config.elt_scripts_silver}
```

### 3. Pipeline d'Agrégation Gold (Unique)

**Nom:** `gold_aggregation.hpl`

**Paramètres:**
- `${GOLD_CONFIG}` - Configuration d'agrégation
- `${SILVER_TABLES}` - Liste des tables silver à agréger

**Étapes:**

1. **Récupérer toutes les tables silver**
```hop
Transform: Get tables from metadata
  - Query: SELECT table_name FROM information_schema.tables 
           WHERE table_name LIKE 'cln_%'
```

2. **Exécuter l'agrégation finale**
```hop
Transform: Execute SQL Script
  - SQL: ${GOLD_CONFIG.elt_scripts_gold}
  - Connection: ${GOLD_CONFIG.target_connection}
```

## Implémentation Détaillée

### Étape 1: Pipeline Principal (`main_orchestration.hpl`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<pipeline>
  <info>
    <name>main_orchestration</name>
    <description>Orchestration multi-sources avec Apache Hop</description>
  </info>
  
  <transform>
    <name>Parse Metadata</name>
    <type>JsonInput</type>
    <config>
      <filename>${METADATA_JSON_PATH}</filename>
      <fields>
        <field>
          <name>sources</name>
          <path>$.sources</path>
          <type>Json</type>
        </field>
        <field>
          <name>gold_config</name>
          <path>$.gold_config</path>
          <type>Json</type>
        </field>
      </fields>
    </config>
  </transform>
  
  <transform>
    <name>Process Each Source</name>
    <type>Executor</type>
    <config>
      <pipeline>source_processor.hpl</pipeline>
      <parameters>
        <parameter>
          <name>SOURCE_CONFIG</name>
          <value>${sources}</value>
        </parameter>
        <parameter>
          <name>METADATA_JSON</name>
          <value>${METADATA_JSON}</value>
        </parameter>
      </parameters>
    </config>
  </transform>
  
  <transform>
    <name>Gold Aggregation</name>
    <type>Executor</type>
    <config>
      <pipeline>gold_aggregation.hpl</pipeline>
      <parameters>
        <parameter>
          <name>GOLD_CONFIG</name>
          <value>${gold_config}</value>
        </parameter>
      </parameters>
    </config>
  </transform>
</pipeline>
```

### Étape 2: Sub-Pipeline Source (`source_processor.hpl`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<pipeline>
  <parameters>
    <parameter>
      <name>SOURCE_CONFIG</name>
      <description>Configuration JSON de la source</description>
    </parameter>
  </parameters>
  
  <transform>
    <name>Detect Source Type</name>
    <type>SwitchCase</type>
    <config>
      <field_name>source_type</field_name>
      <cases>
        <case>
          <value>CSV</value>
          <target>CSV_Processor</target>
        </case>
        <case>
          <value>POSTGRES</value>
          <target>Database_Processor</target>
        </case>
        <case>
          <value>MYSQL</value>
          <target>Database_Processor</target>
        </case>
      </cases>
    </config>
  </transform>
  
  <transform>
    <name>CSV_Processor</name>
    <type>Executor</type>
    <config>
      <pipeline>csv_processor.hpl</pipeline>
      <parameters>
        <parameter>
          <name>FILE_PATH</name>
          <value>${SOURCE_CONFIG.config.file_path}</value>
        </parameter>
        <parameter>
          <name>CONNECTION</name>
          <value>${SOURCE_CONFIG.config.target_connection}</value>
        </parameter>
      </parameters>
    </config>
  </transform>
  
  <transform>
    <name>Database_Processor</name>
    <type>Executor</type>
    <config>
      <pipeline>database_processor.hpl</pipeline>
      <parameters>
        <parameter>
          <name>CONNECTION</name>
          <value>${SOURCE_CONFIG.config.target_connection}</value>
        </parameter>
      </parameters>
    </config>
  </transform>
</pipeline>
```

### Étape 3: Pipeline Gold (`gold_aggregation.hpl`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<pipeline>
  <parameters>
    <parameter>
      <name>GOLD_CONFIG</name>
      <description>Configuration d'agrégation gold</description>
    </parameter>
  </parameters>
  
  <transform>
    <name>Execute Gold Aggregation</name>
    <type>ExecuteSQLScript</type>
    <config>
      <connection>${GOLD_CONFIG.target_connection}</connection>
      <sql>${GOLD_CONFIG.elt_scripts_gold}</sql>
      <single_statement>false</single_statement>
    </config>
  </transform>
</pipeline>
```

## Exemple de Script SQL Multi-Sources

### Script Gold d'Agrégation

```sql
-- Nettoyage préalable
DROP TABLE IF EXISTS gold.fact_ventes_mensuelles;

-- Agrégation depuis toutes les sources silver
CREATE TABLE gold.fact_ventes_mensuelles AS
SELECT 
    -- Dimensions communes
    COALESCE(v.id_client, c.id_client) AS id_client,
    DATE_TRUNC('month', COALESCE(v.date_transaction, c.date_commande)) AS mois,
    
    -- Métriques depuis CSV (ventes)
    SUM(COALESCE(v.montant_ht, 0)) AS ca_ventes_csv,
    COUNT(DISTINCT v.id_transaction) AS nb_transactions_csv,
    
    -- Métriques depuis PostgreSQL (commandes)
    SUM(COALESCE(c.montant_commande, 0)) AS ca_commandes_pg,
    COUNT(DISTINCT c.id_commande) AS nb_commandes_pg,
    
    -- Totaux
    SUM(COALESCE(v.montant_ht, 0) + COALESCE(c.montant_commande, 0)) AS ca_total,
    COUNT(DISTINCT v.id_transaction) + COUNT(DISTINCT c.id_commande) AS nb_transactions_total,
    
    CURRENT_TIMESTAMP AS date_chargement

FROM cln_ventes v
FULL OUTER JOIN cln_commandes c 
    ON v.id_client = c.id_client 
    AND DATE_TRUNC('month', v.date_transaction) = DATE_TRUNC('month', c.date_commande)

GROUP BY 
    COALESCE(v.id_client, c.id_client),
    DATE_TRUNC('month', COALESCE(v.date_transaction, c.date_commande));

-- Index pour performances
CREATE INDEX idx_gold_client_mois ON gold.fact_ventes_mensuelles(id_client, mois);
```

## Configuration des Connections

### Fichier de Connections (`connections.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<connections>
  <connection>
    <name>PostgreSQL_Lakehouse</name>
    <type>POSTGRES</type>
    <hostname>localhost</hostname>
    <port>5432</port>
    <database>lakehouse</database>
    <username>${DB_USERNAME}</username>
    <password>${DB_PASSWORD}</password>
  </connection>
  
  <connection>
    <name>Source_PostgreSQL</name>
    <type>POSTGRES</type>
    <hostname>${SOURCE_HOST}</hostname>
    <port>${SOURCE_PORT}</port>
    <database>${SOURCE_DATABASE}</database>
    <username>${SOURCE_USERNAME}</username>
    <password>${SOURCE_PASSWORD}</password>
  </connection>
</connections>
```

## Exécution depuis le Pipeline Consumer

### Fichier: `backend/pipeline-consumer/src/main/java/com/iol/etlplatform/pipelineconsumer/service/PipelineOrchestrator.java`

```java
@Service
public class PipelineOrchestrator {
    
    @Value("${hop.installation.path:/opt/hop}")
    private String hopPath;
    
    public void executeFromEvent(String metadataJson) {
        try {
            JsonNode meta = objectMapper.readTree(metadataJson);
            String pipelineName = meta.path("pipeline").path("name").asText();
            
            // 1. Sauvegarder les métadonnées dans un fichier temporaire
            Path tempFile = Files.createTempFile("hop_metadata_", ".json");
            Files.write(tempFile, metadataJson.getBytes());
            
            // 2. Construire la commande Hop
            List<String> command = new ArrayList<>();
            command.add(hopPath + "/hop-run.sh");
            command.add("--file=" + hopPath + "/pipelines/main_orchestration.hpl");
            command.add("--parameter:METADATA_JSON_PATH=" + tempFile.toString());
            
            // 3. Exécuter le pipeline
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Pipeline {} exécuté avec succès", pipelineName);
            } else {
                throw new RuntimeException("Pipeline failed with exit code: " + exitCode);
            }
            
            // 4. Nettoyer le fichier temporaire
            Files.deleteIfExists(tempFile);
            
        } catch (Exception e) {
            log.error("Erreur exécution pipeline", e);
            throw new RuntimeException(e);
        }
    }
}
```

## Variables d'Environnement Requises

```bash
# .env file for pipeline-consumer
HOP_INSTALLATION_PATH=/opt/hop
DB_USERNAME=postgres
DB_PASSWORD=your_password
SOURCE_HOST=localhost
SOURCE_PORT=5432
SOURCE_DATABASE=source_db
SOURCE_USERNAME=user
SOURCE_PASSWORD=pass
```

## Tests et Validation

### 1. Tester avec une seule source
```bash
# Métadonnées test
{
  "sources": [{
    "source_name": "test_csv",
    "type": "CSV",
    "config": { ... }
  }]
}
```

### 2. Tester avec plusieurs sources
```bash
{
  "sources": [
    { "source_name": "csv_ventes", "type": "CSV", ... },
    { "source_name": "pg_commandes", "type": "POSTGRES", ... }
  ]
}
```

### 3. Vérifier les tables créées
```sql
-- Tables de staging
SELECT * FROM information_schema.tables 
WHERE table_name LIKE 'stg_%';

-- Tables silver
SELECT * FROM information_schema.tables 
WHERE table_name LIKE 'cln_%';

-- Table gold finale
SELECT * FROM gold.fact_ventes_mensuelles;
```

## Bonnes Pratiques

1. **Nommage cohérent:**
   - Staging: `stg_{source_name}_{table}`
   - Silver: `cln_{source_name}_{table}`
   - Gold: `fact_{business_process}`

2. **Gestion d'erreurs:**
   - Chaque sub-pipeline doit gérer ses erreurs
   - Logging détaillé pour chaque source
   - Rollback en cas d'échec partiel

3. **Performance:**
   - Paralleliser le traitement des sources
   - Utiliser le batch processing
   - Indexer les tables intermédiaires

4. **Monitoring:**
   - Logs par source
   - Métriques de performance
   - Alertes en cas d'échec

## Conclusion

Cette architecture permet de:
- ✅ Gérer N sources différentes
- ✅ Traiter chaque source selon son type
- ✅ Agréger les résultats dans une table gold unique
- ✅ Réutiliser les sub-pipelines pour chaque source
- ✅ Scalabilité horizontale

Le pipeline principal orchestre, les sub-pipelines traitent, et le pipeline gold agrège.