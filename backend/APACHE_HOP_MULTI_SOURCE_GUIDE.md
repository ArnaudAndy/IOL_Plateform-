# Apache Hop dans ce projet

Ce document explique comment Apache Hop est utilisé dans la plateforme actuelle.

## Rôle d’Apache Hop

Apache Hop sert de moteur d’exécution des transformations ETL. Il reçoit les artefacts préparés par le backend et exécute les traitements nécessaires vers la destination finale.

## Où il intervient dans ce dépôt

Hop est utilisé dans la partie d’exécution du pipeline, notamment via :
- la stack backend et le consumer de pipeline ;
- les projets et configurations présents dans le dossier hop-project ;
- les scripts et fichiers de configuration liés à l’orchestration des jobs ETL.

## Flux pratique

1. Le backend prépare une exécution et transmet les métadonnées nécessaires.
2. Le consumer de pipeline lance l’exécution via Hop.
3. Hop traite les données selon la logique du workflow.
4. Les résultats sont écrits vers la destination ciblée.

## Points utiles pour l’intégration

- Hop n’accède pas directement aux secrets source : il reçoit des artefacts et des paramètres déjà préparés.
- Le moteur est utilisé comme couche d’exécution technique, tandis que l’API reste le point de contrôle métier.
- Les projets Hop doivent rester cohérents avec les métadonnées et les chemins d’exécution définis par l’application.

## Bonnes pratiques

- garder les transformations explicites et testables ;
- éviter de coupler directement les jobs à des données sensibles ;
- versionner les projets Hop avec les configurations associées ;
- valider les chemins d’entrée/sortie avant chaque déploiement.

## À retenir

Apache Hop est le moteur d’exécution ETL du système. Il doit être vu comme un composant technique secondaire au regard du contrôle métier, qui reste centralisé dans le backend.
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