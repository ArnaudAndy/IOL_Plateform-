package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrateur Hop/Spark du pipeline-consumer.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  MODE D'EXÉCUTION : LOCAL vs SPARK
 * ═══════════════════════════════════════════════════════════════════
 *
 *  LOCAL (mode par défaut) :
 *    → Hop tourne sur la JVM du pipeline-consumer
 *    → Run config : "local" dans Hop
 *    → Commande : hop-run.sh --runconfig=local
 *    → Adapté : petits/moyens volumes (< quelques millions de lignes)
 *
 *  SPARK (mode big data) :
 *    → Le consumer soumet spark_etl.py à un cluster Spark standalone
 *    → Hop reste le pilote métadonné du mode local
 *    → Commande : spark-submit --master spark://... spark_etl.py
 *    → Adapté : gros volumes (> 10M lignes, jointures massives, ML)
 *    → Requiert : master/worker Spark disponibles sur le réseau Docker
 *
 *  Comment choisir automatiquement :
 *    1. Champ "executionMode" dans la config workflow : "LOCAL" ou "SPARK"
 *    2. Sinon : seuil de lignes estimées (rowCountThreshold dans application.yml)
 *    3. Sinon : mode LOCAL par défaut
 *
 * ═══════════════════════════════════════════════════════════════════
 *  CE QUE HOP REÇOIT (paramètres injectés) :
 * ═══════════════════════════════════════════════════════════════════
 *   IOL_METADATA_FILE   : chemin absolu du fichier JSON de configuration
 *   IOL_WORKFLOW_ID     : identifiant du workflow
 *   IOL_EXEC_LOG_ID     : identifiant du log d'exécution (pour callback)
 *   IOL_EXEC_MODE       : LOCAL ou SPARK (pour que le .hwf Hop adapte son comportement)
 *
 *  Le fichier JSON contient uniquement ce dont le moteur a besoin :
 *    - artefact source   : chemin local vérifié, reconstruit depuis Kafka ou RustFS
 *    - fieldsJson        : mappings colonne→terme IOL
 *    - aggregationScripts: SQL Gold
 *    - sources[]         : sources multiples si présentes
 *
 *  Les connexions et requêtes source restent dans api-core et ne sont jamais
 *  transmises à Hop ou Spark. Les identifiants de destination restent présents
 *  car le moteur doit écrire le résultat dans la base cible.
 */
@Service
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaTemplate<String, String> kafkaTemplate;
    @Autowired(required = false)
    private KafkaDataChunkStore dataChunkStore;
    @Autowired(required = false)
    private ObjectStorageClient objectStorageClient;
    @Autowired(required = false)
    private RuntimeCredentialClient runtimeCredentialClient;
    private final ConcurrentHashMap<String, Instant> executedHashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletionSnapshot> completedExecutions = new ConcurrentHashMap<>();

    @Value("${orchestrator.idempotence.ttl.seconds:3600}")
    private long idempotenceTtlSeconds;

    // ── Hop ──────────────────────────────────────────────────────────────────
    @Value("${app.hop.home:/opt/hop}")
    private String hopHome;

    /** Nom du script hop-run selon l'OS : hop-run.sh (Linux/Mac) ou hop-run.bat (Windows) */
    @Value("${app.hop.run.script:hop-run.sh}")
    private String hopRunScript;

    /** Projet Hop à activer pour charger les métadonnées (connexions, run configs, etc.). */
    @Value("${app.hop.project.name:}")
    private String hopProjectName;

    /** Environnement Hop optionnel. Vide = aucun environnement forcé. */
    @Value("${app.hop.environment.name:}")
    private String hopEnvironmentName;

    /** Nom du fichier workflow principal (.hwf) à exécuter */
    @Value("${app.hop.workflow.file:main_orchestration.hwf}")
    private String hopWorkflowFile;

    /** Run config Hop pour le mode LOCAL (petits volumes) */
    @Value("${app.hop.run.config.local:local}")
    private String hopRunConfigLocal;

    @Value("${app.hop.pipelines.dir:/opt/hop/pipelines/iol}")
    private String hopPipelinesDir;

    @Value("${app.hop.temp.dir:/tmp/iol}")
    private String hopTempDir;

    @Value("${app.hop.engine.path:}")
    private String hopEnginePath;

    @Value("${app.hop.python.executable:python}")
    private String hopPythonExecutable;

    @Value("${app.hop.config.dir:}")
    private String hopConfigDir;

    @Value("${app.hop.project.home:}")
    private String hopProjectHome;

    /** Timeout max d'exécution Hop en secondes (0 ou négatif = pas de timeout). */
    @Value("${app.hop.execution.timeout.seconds:600}")
    private long hopTimeoutSeconds;

    /**
     * Mémoire JVM allouée à Hop en mode local (ex: 2g, 4g).
     * En mode Spark, c'est Spark qui gère la mémoire des executors.
     */
    @Value("${app.hop.local.jvm.memory:2g}")
    private String hopLocalJvmMemory;

    // ── Spark ─────────────────────────────────────────────────────────────────
    /**
     * Seuil en nombre de lignes estimées au-delà duquel on bascule sur Spark.
     * -1 = ne jamais basculer automatiquement (mode explicite seulement).
     */
    @Value("${app.spark.row.threshold:-1}")
    private long sparkRowThreshold;

    @Value("${app.spark.distributed.ready:false}")
    private boolean sparkDistributedReady;

    @Value("${app.spark.submit.path:/opt/spark/bin/spark-submit}")
    private String sparkSubmitPath;

    @Value("${app.spark.master-url:spark://spark-master:7077}")
    private String sparkMasterUrl;

    @Value("${app.spark.job-file:/opt/iol/engine/spark_etl.py}")
    private String sparkJobFile;

    @Value("${app.spark.jdbc-jars-dir:/opt/iol/jdbc}")
    private String sparkJdbcJarsDir;

    @Value("${app.spark.driver-memory:1g}")
    private String sparkDriverMemory;

    @Value("${app.spark.executor-memory:2g}")
    private String sparkExecutorMemory;

    @Value("${app.spark.executor-cores:2}")
    private int sparkExecutorCores;

    @Value("${app.spark.cores-max:4}")
    private int sparkCoresMax;

    @Value("${app.spark.execution.timeout.seconds:3600}")
    private long sparkTimeoutSeconds;

    // ── Kafka ─────────────────────────────────────────────────────────────────
    @Value("${app.kafka.topics.status:iol.pipeline.status}")
    private String statusTopic;

    @Value("${app.kafka.topics.dlq:iol.pipeline.commands.dlq}")
    private String dlqTopic;

    @Value("${app.kafka.status.heartbeat.seconds:5}")
    private long statusHeartbeatSeconds;

    public PipelineOrchestrator(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Log au démarrage les valeurs Hop RÉELLEMENT chargées (permet de voir tout de
     * suite si ce sont les valeurs Linux ou Windows, et si le chemin est bon) et
     * vérifie l'existence du script Hop (fail-soft : WARN clair sans bloquer).
     */
    @PostConstruct
    void logAndValidateHopConfig() {
        normalizeHopConfigForCurrentOs();
        log.info("Hop config → home={} script={} workflow={} pipelinesDir={} projectHome={} engine={} tempDir={}",
                 hopHome, hopRunScript, hopWorkflowFile, hopPipelinesDir, hopProjectHome, hopEnginePath, hopTempDir);
        java.io.File runScript = new java.io.File(hopHome, hopRunScript);
        if (!runScript.exists()) {
            log.warn("ATTENTION: le script Hop est introuvable: {} — vérifier app.hop.home / app.hop.run.script",
                     runScript.getAbsolutePath());
        }
    }

    private void normalizeHopConfigForCurrentOs() {
        if (!isWindows()) {
            return;
        }

        if (isBlankOrLinuxDefault(hopHome, "/opt/hop")) {
            hopHome = "C:/Users/ANDY/Desktop/ING5/Stage/hop";
        }
        if (isBlankOrLinuxDefault(hopRunScript, "hop-run.sh")) {
            hopRunScript = "hop-run.bat";
        }
        if (isBlankOrLinuxDefault(hopPipelinesDir, "/opt/iol/Global_Config")
                || isBlankOrLinuxDefault(hopPipelinesDir, "/opt/hop/pipelines/iol")) {
            hopPipelinesDir = "C:/Users/ANDY/Desktop/ING5/Stage/Projet ETL/Global_Config";
        }
        if (isBlankOrLinuxDefault(hopTempDir, "/tmp/iol")) {
            hopTempDir = "C:/Users/ANDY/AppData/Local/Temp/iol";
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isBlankOrLinuxDefault(String value, String linuxDefault) {
        return value == null || value.isBlank() || linuxDefault.equals(value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Point d'entrée
    // ─────────────────────────────────────────────────────────────────────────

    public boolean execute(JsonNode command, String workflowId, String execLogId) {
        // Idempotence
        String hash = sha256(command.toString());
        if (isDuplicate(hash)) {
            log.info("Exécution dupliquée ignorée (idempotence) — workflowId={}", workflowId);
            CompletionSnapshot completed = completedExecutions.get(hash);
            if (completed != null) {
                publishStatus(command, interopContext(command), execLogId, workflowId,
                        completed.success(), completed.logOutput(), completed.errorMessage(), completed.durationMs());
            }
            return completed == null || completed.success();
        }
        executedHashes.put(hash, Instant.now());

        // Déterminer le mode d'exécution
        ExecutionMode mode = resolveExecutionMode(command);
        String pipelineName = command.path("workflowName").asText("unnamed");
        InteropContext interopContext = interopContext(command);
        ProgressReporter progress = new ProgressReporter(
                command, interopContext, execLogId, workflowId, pipelineName);

        log.info("Pipeline '{}' démarré en mode {} (workflowId={})", pipelineName, mode, workflowId);

        Path tempFile = null;
        PreparedCommand preparedCommand = null;
        Instant start = Instant.now();
        boolean successful = false;
        try {
            progress.start();
            progress.stage("PREPARATION");
            // 0. INBOUND/PUSH uniquement : exposer le pivot au moteur dans son format JSON natif.
            preparedCommand = prepareCommandForHop(command, workflowId, execLogId);

            // 1. Écrire le JSON de config dans un fichier temporaire
            tempFile = writeTempMetadata(preparedCommand.command(), workflowId);

            // 2. Hop local ou soumission réelle au cluster Spark.
            progress.stage("BRONZE");
            HopResult result = mode == ExecutionMode.SPARK
                    ? runSpark(tempFile, pipelineName, preparedCommand.command(), progress)
                    : runHop(tempFile, workflowId, execLogId, pipelineName, mode, preparedCommand.command(), progress);

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            progress.finish();
            completedExecutions.put(hash, new CompletionSnapshot(
                    result.success(), result.logOutput(), result.errorMessage(), durationMs));
            successful = result.success();
            publishStatus(preparedCommand.command(), interopContext, execLogId, workflowId, result.success(), result.logOutput(),
                    result.errorMessage(), durationMs);

        } catch (Exception e) {
            progress.finish();
            log.error("Erreur pipeline '{}': {}", pipelineName, e.getMessage(), e);
            completedExecutions.put(hash, new CompletionSnapshot(false, "", e.getMessage(),
                    Duration.between(start, Instant.now()).toMillis()));
            publishFailure(command, execLogId, workflowId, e.getMessage());
        } finally {
            progress.close();
            deleteTempFile(tempFile);
            if (preparedCommand != null) {
                preparedCommand.tempFiles().forEach(this::deleteTempFile);
            }
        }
        return successful;
    }

    public void cleanupTransferredObjects(JsonNode command) {
        if (objectStorageClient == null) return;
        objectStorageClient.deleteTransferredObjects(command);
    }

    PreparedCommand prepareCommandForHop(JsonNode command, String workflowId, String execLogId) throws Exception {
        ObjectNode executable = command.deepCopy();
        List<Path> tempFiles = new ArrayList<>();
        materializeKafkaSources(executable, execLogId, tempFiles);

        if (!isInbound(executable)) {
            assertSourcesAreTransported(executable);
            return new PreparedCommand(executable, tempFiles);
        }

        JsonNode sources = executable.path("sources");
        if (!sources.isArray()) {
            return new PreparedCommand(executable, tempFiles);
        }

        int index = 0;
        for (JsonNode source : sources) {
            if (source instanceof ObjectNode sourceObject && isPushSource(sourceObject)) {
                Path materialized = materializePushSource(sourceObject, workflowId, execLogId, index);
                tempFiles.add(materialized);
            }
            index++;
        }

        return new PreparedCommand(executable, tempFiles);
    }

    private void materializeKafkaSources(ObjectNode command, String execLogId, List<Path> tempFiles) throws Exception {
        JsonNode manifest = command.path("sourceDataManifest");
        if (!manifest.isArray() || manifest.isEmpty()) return;
        JsonNode sources = command.path("sources");
        if (!(sources instanceof ArrayNode sourceArray)) {
            throw new IllegalArgumentException("Commande Kafka avec manifeste mais sans sources.");
        }

        for (JsonNode item : manifest) {
            int sourceIndex = item.path("sourceIndex").asInt(-1);
            if (sourceIndex < 0 || sourceIndex >= sourceArray.size()) {
                throw new IllegalArgumentException("Index source invalide dans le manifeste Kafka: " + sourceIndex);
            }
            String transport = item.path("transport").asText("KAFKA_CHUNKED");
            Path materialized;
            if ("OBJECT_STORAGE".equalsIgnoreCase(transport)) {
                if (objectStorageClient == null) {
                    throw new IllegalStateException("ObjectStorageClient indisponible pour télécharger la source.");
                }
                materialized = objectStorageClient.materialize(item, execLogId);
            } else {
                if (dataChunkStore == null) {
                    throw new IllegalStateException("KafkaDataChunkStore indisponible pour reconstruire les données.");
                }
                materialized = dataChunkStore.materialize(item, execLogId);
            }
            tempFiles.add(materialized);
            ObjectNode source = (ObjectNode) sourceArray.get(sourceIndex);
            ObjectNode config = objectChild(source, "config");
            String format = item.path("format").asText("CSV").toUpperCase();
            source.put("source_name", format);
            source.put("type", format);
            source.put("transport_materialized", true);
            config.put("file_path", materialized.toAbsolutePath().toString());
            config.put("uri", materialized.toAbsolutePath().toString());
            config.put("data_transport", transport.toUpperCase());
            if ("CSV".equals(format)) {
                config.put("delimiter", ",");
                config.put("encoding", "utf-8");
                ObjectNode sourceConfig = objectChild(config, "source_config");
                sourceConfig.put("delimiter", ",");
                sourceConfig.put("encoding", "utf-8");
            }
        }
    }

    private void assertSourcesAreTransported(ObjectNode command) {
        JsonNode sources = command.path("sources");
        if (!sources.isArray()) {
            throw new IllegalArgumentException("Commande d'exécution sans sources transportées.");
        }
        for (int index = 0; index < sources.size(); index++) {
            JsonNode source = sources.get(index);
            if (!source.path("transport_materialized").asBoolean(false)) {
                throw new IllegalStateException(
                        "Source " + index + " non transportée: Hop et Spark ne peuvent pas se connecter directement à la source.");
            }
            String protocol = source.path("source_name").asText("").toUpperCase();
            if (Set.of("POSTGRES", "MYSQL", "MARIADB", "MSSQL", "ORACLE",
                    "SQLITE", "SNOWFLAKE", "REDSHIFT").contains(protocol)) {
                throw new IllegalStateException(
                        "Accès JDBC source direct interdit pour la source " + index + ".");
            }
            JsonNode config = source.path("config");
            JsonNode sourceConfig = config.path("source_config");
            if (config.has("source_connection_id") || config.has("sourceConnectionId")
                    || sourceConfig.has("source_connection") || sourceConfig.has("sourceConnection")
                    || sourceConfig.has("password") || sourceConfig.has("username")
                    || sourceConfig.has("query")) {
                throw new IllegalStateException(
                        "Identifiants ou requête source interdits dans la commande d'exécution.");
            }
        }
    }

    private boolean isInbound(JsonNode command) {
        return "INBOUND".equalsIgnoreCase(command.path("direction").asText(""));
    }

    private boolean isPushSource(JsonNode source) {
        return "PUSH".equalsIgnoreCase(source.path("type").asText(""))
                || "PUSH".equalsIgnoreCase(source.path("source_name").asText(""));
    }

    private Path materializePushSource(ObjectNode source, String workflowId, String execLogId, int index) throws Exception {
        ObjectNode config = objectChild(source, "config");
        ObjectNode sourceConfig = objectChild(config, "source_config");
        ArrayNode records = extractPushRecords(config, sourceConfig);
        Path jsonLinesFile = writePushJsonLines(records, workflowId, execLogId, index);
        String jsonPath = jsonLinesFile.toAbsolutePath().toString();

        String originalName = source.path("source_name").asText("PUSH");
        source.put("original_source_name", originalName);
        source.put("source_name", "JSON");
        source.put("type", "JSON");
        source.put("inbound_push", true);
        source.put("transport_materialized", true);

        config.put("file_path", jsonPath);
        config.put("uri", jsonPath);
        config.put("encoding", "utf-8");
        config.put("multi_line", false);
        config.put("data_transport", "KAFKA_NATIVE_JSON");
        config.put("write_mode", "append");

        sourceConfig.put("mode", "PUSH");
        sourceConfig.put("format", "JSON");
        sourceConfig.put("materialized_format", "JSON");
        sourceConfig.put("materialized_file_path", jsonPath);
        sourceConfig.put("encoding", "utf-8");
        sourceConfig.put("already_pivot", true);

        log.info("Source INBOUND/PUSH exposée au moteur en JSON Lines natif: {}", jsonPath);
        return jsonLinesFile;
    }

    private ObjectNode objectChild(ObjectNode parent, String fieldName) {
        JsonNode child = parent.path(fieldName);
        if (child instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode created = objectMapper.createObjectNode();
        parent.set(fieldName, created);
        return created;
    }

    private ArrayNode extractPushRecords(ObjectNode config, ObjectNode sourceConfig) {
        for (JsonNode candidate : List.of(
                sourceConfig.path("records"),
                sourceConfig.path("data"),
                sourceConfig.path("payload"),
                config.path("records"),
                config.path("data"),
                config.path("push_payload"))) {
            ArrayNode records = toRecordArray(candidate);
            if (records != null && !records.isEmpty()) {
                return records;
            }
        }
        throw new IllegalArgumentException("Source PUSH INBOUND sans donnees pivot.");
    }

    private ArrayNode toRecordArray(JsonNode candidate) {
        if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
            return null;
        }

        ArrayNode records = objectMapper.createArrayNode();
        if (candidate.isArray()) {
            for (JsonNode item : candidate) {
                if (!item.isObject()) {
                    throw new IllegalArgumentException("Chaque record PUSH doit etre un objet JSON.");
                }
                records.add(item.deepCopy());
            }
            return records;
        }

        if (candidate.isObject()) {
            records.add(candidate.deepCopy());
            return records;
        }

        throw new IllegalArgumentException("Les donnees PUSH doivent etre un objet JSON ou un tableau d'objets.");
    }

    private Path writePushJsonLines(
            ArrayNode records,
            String workflowId,
            String execLogId,
            int index) throws Exception {
        Files.createDirectories(Paths.get(hopTempDir));
        Path file = Paths.get(hopTempDir,
                "iol_push_" + safeFileToken(workflowId) + "_" + safeFileToken(execLogId) + "_" + index + "_"
                        + System.currentTimeMillis() + ".jsonl");

        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (JsonNode record : records) {
                writer.write(objectMapper.writeValueAsString(record));
                writer.newLine();
            }
        }
        return file;
    }

    private String safeFileToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Résolution du mode d'exécution
    // ─────────────────────────────────────────────────────────────────────────

    private ExecutionMode resolveExecutionMode(JsonNode command) {
        // Priorité 1 : champ explicite dans la config workflow
        String explicit = command.path("executionMode").asText("").toUpperCase().trim();
        if ("SPARK".equals(explicit)) {
            requireSparkDistributedReady();
            return ExecutionMode.SPARK;
        }
        if ("LOCAL".equals(explicit)) return ExecutionMode.LOCAL;

        // Priorité 2 : seuil de lignes estimées
        if (sparkRowThreshold > 0) {
            long estimatedRows = command.path("estimatedRows").asLong(0);
            if (estimatedRows > sparkRowThreshold) {
                requireSparkDistributedReady();
                log.info("Bascule automatique SPARK : {} lignes estimées > seuil {}",
                        estimatedRows, sparkRowThreshold);
                return ExecutionMode.SPARK;
            }
        }

        // Défaut
        return ExecutionMode.LOCAL;
    }

    private void requireSparkDistributedReady() {
        if (!sparkDistributedReady) {
            throw new IllegalStateException(
                    "Mode SPARK indisponible: le cluster distribué n'est pas activé. "
                            + "Démarrez spark-master/spark-worker puis activez SPARK_DISTRIBUTED_READY=true.");
        }
        requireRegularFile(Paths.get(sparkSubmitPath), "spark-submit",
                "SPARK_SUBMIT_PATH doit pointer vers le binaire spark-submit.");
        requireRegularFile(Paths.get(sparkJobFile), "Job PySpark IOL",
                "SPARK_JOB_FILE doit pointer vers spark_etl.py.");
        if (sparkMasterUrl == null || sparkMasterUrl.isBlank()) {
            throw new IllegalStateException("SPARK_MASTER_URL est obligatoire.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lancement Apache Hop
    // ─────────────────────────────────────────────────────────────────────────

    private HopResult runSpark(
            Path metadataFile,
            String pipelineName,
            JsonNode command,
            ProgressReporter progress) throws Exception {
        requireSparkDistributedReady();
        List<String> cmd = new ArrayList<>();
        cmd.add(sparkSubmitPath);
        cmd.add("--master");
        cmd.add(sparkMasterUrl);
        cmd.add("--deploy-mode");
        cmd.add("client");
        cmd.add("--driver-memory");
        cmd.add(sparkDriverMemory);
        cmd.add("--executor-memory");
        cmd.add(sparkExecutorMemory);
        cmd.add("--executor-cores");
        cmd.add(Integer.toString(Math.max(1, sparkExecutorCores)));
        cmd.add("--conf");
        cmd.add("spark.cores.max=" + Math.max(1, sparkCoresMax));
        cmd.add("--conf");
        cmd.add("spark.driver.bindAddress=0.0.0.0");
        cmd.add("--conf");
        cmd.add("spark.driver.host=" + InetAddress.getLocalHost().getHostAddress());
        cmd.add("--conf");
        cmd.add("spark.sql.adaptive.enabled=true");
        String jdbcJars = sparkJdbcJars();
        if (!jdbcJars.isBlank()) {
            cmd.add("--jars");
            cmd.add(jdbcJars);
        }
        cmd.add(sparkJobFile);
        cmd.add("--metadata");
        cmd.add(metadataFile.toAbsolutePath().toString());

        log.info("Soumission Spark: master={} job={} jdbcJars={}",
                sparkMasterUrl, sparkJobFile, jdbcJars.isBlank() ? 0 : jdbcJars.split(",").length);
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("PYTHONUTF8", "1");
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.environment().putAll(targetEnvironment(command));

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append("\n");
                    }
                    progress.acceptLine(line);
                    log.debug("[SPARK][{}] {}", pipelineName, line);
                }
            } catch (Exception e) {
                log.warn("Lecture du flux Spark interrompue: {}", e.getMessage());
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished;
        if (sparkTimeoutSeconds > 0) {
            finished = process.waitFor(sparkTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            process.waitFor();
            finished = true;
        }
        if (!finished) {
            process.destroyForcibly();
            readerThread.join(2000);
            throw new RuntimeException("Timeout Spark dépassé (" + sparkTimeoutSeconds + "s).");
        }

        readerThread.join(5000);
        int exitCode = process.exitValue();
        String logOutput;
        synchronized (output) {
            logOutput = output.toString();
        }
        boolean success = exitCode == 0;
        log.info("Spark terminé '{}' — exitCode={}", pipelineName, exitCode);
        return new HopResult(
                success,
                logOutput,
                success ? null : "spark-submit a terminé avec le code " + exitCode);
    }

    private String sparkJdbcJars() throws Exception {
        Path directory = Paths.get(sparkJdbcJarsDir);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException(
                    "Répertoire des pilotes JDBC Spark introuvable: " + directory.toAbsolutePath());
        }
        try (var paths = Files.list(directory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .map(path -> path.toAbsolutePath().toString())
                    .collect(java.util.stream.Collectors.joining(","));
        }
    }

    private HopResult runHop(Path metadataFile, String workflowId, String execLogId,
                              String pipelineName, ExecutionMode mode, JsonNode command,
                              ProgressReporter progress) throws Exception {

        String runConfig = hopRunConfigLocal;

        // Paths.get() produit des chemins cohérents avec l'OS courant (évite les
        // mélanges / et \ lors de la concaténation manuelle).
        Path runScript = Paths.get(hopHome, hopRunScript);
        Path workflow = Paths.get(hopPipelinesDir, hopWorkflowFile);
        requireRegularFile(runScript, "Script Hop",
                "En Docker Windows, HOP_HOST_HOME doit pointer vers le dossier Hop de l'hôte, pas vers /opt/hop.");
        requireRegularFile(workflow, "Workflow Hop",
                "HOP_HOST_PIPELINES_DIR doit pointer vers le dossier qui contient " + hopWorkflowFile + ".");

        String runScriptPath = runScript.toString();
        String workflowPath  = workflow.toString();

        List<String> cmd = new ArrayList<>();
        String lowerScript = hopRunScript != null ? hopRunScript.toLowerCase() : "";
        if (lowerScript.endsWith(".bat") || lowerScript.endsWith(".cmd")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        } else if (lowerScript.endsWith(".sh")) {
            cmd.add("sh");
        }
        cmd.add(runScriptPath);
        if (hopProjectName != null && !hopProjectName.isBlank()) {
            cmd.add("--project=" + hopProjectName);
        }
        if (hopEnvironmentName != null && !hopEnvironmentName.isBlank()) {
            cmd.add("--environment=" + hopEnvironmentName);
        }
        cmd.add("--file=" + workflowPath);
        cmd.add("--runconfig=" + runConfig);
        // Paramètres Hop via -p CLE=VALEUR (un -p par paire). Cette version de Hop
        // n'accepte PAS --parameter:CLE=VALEUR. Chaque paire est passée en deux
        // éléments distincts de la liste : robuste face aux chemins Windows (: et \).
        cmd.add("-p");
        cmd.add("IOL_METADATA_FILE=" + metadataFile.toAbsolutePath());
        cmd.add("-p");
        cmd.add("IOL_WORKFLOW_ID=" + workflowId);
        cmd.add("-p");
        cmd.add("IOL_EXEC_LOG_ID=" + execLogId);
        cmd.add("-p");
        cmd.add("IOL_EXEC_MODE=" + mode.name());
        if (hopEnginePath != null && !hopEnginePath.isBlank()) {
            requireRegularFile(Paths.get(hopEnginePath), "Moteur ETL Python",
                    "HOP_ENGINE_PATH doit pointer vers moteur_universel.py.");
            cmd.add("-p");
            cmd.add("MOTEUR_PATH=" + hopEnginePath);
        }
        cmd.add("-p");
        cmd.add("PYTHON_EXE=" + hopPythonExecutable);

        log.info("Commande Hop [{}]: {}", mode, String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        // Variables d'environnement pour Hop.
        // JAVA_HOME : ne forcer que s'il existe déjà (le défaut Linux /usr/lib/jvm/...
        // serait faux sur Windows). Sinon, hop-run.bat/.sh résout Java tout seul.
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            pb.environment().put("JAVA_HOME", javaHome);
        }
        pb.environment().put("HOP_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xms512m -Xmx" + hopLocalJvmMemory);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        if (hopConfigDir != null && !hopConfigDir.isBlank()) {
            pb.environment().put("HOP_CONFIG_FOLDER", hopConfigDir);
        }
        Path auditFolder = Paths.get(hopTempDir, "audit");
        Files.createDirectories(auditFolder.resolve("executions"));
        pb.environment().put("HOP_AUDIT_FOLDER", auditFolder.toString());
        Map<String, String> targetEnvironment = targetEnvironment(command);
        pb.environment().putAll(targetEnvironment);
        if (!targetEnvironment.isEmpty()) {
            log.info("Destination Hop dynamique: {}:{}/{}",
                    targetEnvironment.get("TARGET_HOST"),
                    targetEnvironment.get("TARGET_PORT"),
                    targetEnvironment.get("TARGET_DATABASE"));
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        // Lire le flux (stdout+stderr fusionnés) dans un thread séparé pour ne jamais
        // bloquer l'attente : un buffer plein ne doit pas geler waitFor().
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) { output.append(line).append("\n"); }
                    progress.acceptLine(line);
                    // Log chaque ligne Hop en DEBUG pour ne pas polluer les logs normaux
                    log.debug("[HOP][{}][{}] {}", mode, pipelineName, line);
                }
            } catch (Exception e) {
                log.warn("Lecture du flux Hop interrompue: {}", e.getMessage());
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        // Attente bornée : évite un thread consumer gelé si Hop/Python se bloque.
        boolean finished;
        if (hopTimeoutSeconds > 0) {
            finished = process.waitFor(hopTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            process.waitFor();
            finished = true;
        }

        if (!finished) {
            log.error("Hop a dépassé le timeout de {}s — arrêt forcé du processus (workflow bloqué)", hopTimeoutSeconds);
            process.destroyForcibly();
            readerThread.join(2000);
            throw new RuntimeException("Timeout Hop dépassé (" + hopTimeoutSeconds
                    + "s) — processus tué, pipeline considéré en échec");
        }

        // Laisser le reader finir de vider le flux avant de lire la sortie.
        readerThread.join(5000);
        int exitCode = process.exitValue();
        boolean success = (exitCode == 0);
        log.info("Hop terminé [{}] '{}' — exitCode={}", mode, pipelineName, exitCode);

        String logOutput;
        synchronized (output) { logOutput = output.toString(); }
        return new HopResult(
                success,
                logOutput,
                success ? null : "Hop a terminé avec le code " + exitCode + " [mode=" + mode + "]"
        );
    }

    Map<String, String> targetEnvironment(JsonNode command) {
        JsonNode sources = command != null ? command.path("sources") : null;
        if (sources == null || !sources.isArray() || sources.isEmpty()) {
            return Map.of();
        }

        JsonNode target = sources.get(0).path("config").path("target_connection");
        if (!target.isObject() || target.isEmpty()) {
            return Map.of();
        }

        String dbType = normalizeTargetDbType(target.path("db_type").asText("POSTGRES"));

        Map<String, String> environment = new LinkedHashMap<>();
        putRequiredTarget(environment, "TARGET_DATABASE", target, "database");
        if (!"SQLITE".equals(dbType)) {
            putRequiredTarget(environment, "TARGET_HOST", target, "host");
            putRequiredTarget(environment, "TARGET_USER", target, "username");
            String password = target.path("password").asText("");
            if (password.isBlank()) {
                String connectionId = target.path("connection_id").asText("");
                String executionId = command.path("execLogId").asText("");
                String workflowId = command.path("workflowId").asText("");
                if (runtimeCredentialClient == null || connectionId.isBlank()
                        || executionId.isBlank() || workflowId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Référence de credential destination incomplète ou client runtime indisponible.");
                }
                password = runtimeCredentialClient.resolve(connectionId, executionId, workflowId);
            }
            environment.put("TARGET_PASSWORD", password);
            putOptionalTarget(environment, "TARGET_PORT", target, "port");
        }
        putOptionalTarget(environment, "TARGET_SCHEMA", target, "schema");
        JsonNode extra = target.path("additional_properties");
        putOptionalTarget(environment, "TARGET_WAREHOUSE", extra, "warehouse");
        putOptionalTarget(environment, "TARGET_ROLE", extra, "role");
        putOptionalTarget(environment, "TARGET_ACCOUNT", extra, "account");
        environment.put("TARGET_DB_TYPE", dbType);
        environment.put(
                "TARGET_HOP_CONNECTION",
                target.path("hop_connection_name").asText(defaultHopConnection(dbType)));
        return environment;
    }

    private String normalizeTargetDbType(String dbType) {
        String normalized = dbType == null ? "POSTGRES" : dbType.trim().toUpperCase();
        return switch (normalized) {
            case "POSTGRES", "POSTGRESQL", "PG" -> "POSTGRES";
            case "MYSQL" -> "MYSQL";
            case "MARIADB", "MARIA_DB" -> "MARIADB";
            case "MSSQL", "SQLSERVER", "SQL_SERVER" -> "MSSQL";
            case "ORACLE" -> "ORACLE";
            case "SQLITE", "SQLITE3" -> "SQLITE";
            case "SNOWFLAKE" -> "SNOWFLAKE";
            case "REDSHIFT", "AWS_REDSHIFT" -> "REDSHIFT";
            default -> throw new IllegalArgumentException(
                    "Destination ETL non supportee: " + dbType
                            + ". Destinations supportees: POSTGRES, MYSQL, MARIADB, MSSQL, ORACLE, SQLITE, SNOWFLAKE, REDSHIFT.");
        };
    }

    private String defaultHopConnection(String dbType) {
        return switch (dbType) {
            case "MYSQL" -> "IOL_DESTINATION_MYSQL";
            case "MARIADB" -> "IOL_DESTINATION_MARIADB";
            case "MSSQL" -> "IOL_DESTINATION_MSSQL";
            case "ORACLE" -> "IOL_DESTINATION_ORACLE";
            case "SQLITE" -> "IOL_DESTINATION_SQLITE";
            case "SNOWFLAKE" -> "IOL_DESTINATION_SNOWFLAKE";
            case "REDSHIFT" -> "IOL_DESTINATION_REDSHIFT";
            default -> "IOL_DESTINATION_POSTGRES";
        };
    }

    private void putRequiredTarget(
            Map<String, String> environment, String environmentName, JsonNode target, String jsonField) {
        String value = target.path(jsonField).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "Destination ETL incomplète: target_connection." + jsonField + " est obligatoire.");
        }
        environment.put(environmentName, value);
    }

    private void putOptionalTarget(
            Map<String, String> environment, String environmentName, JsonNode target, String jsonField) {
        if (target == null || target.isMissingNode() || target.isNull()) return;
        String value = target.path(jsonField).asText("");
        if (!value.isBlank()) environment.put(environmentName, value);
    }

    private void requireRegularFile(Path path, String label, String hint) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(label + " introuvable: " + path.toAbsolutePath() + ". " + hint);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fichier JSON temporaire
    // ─────────────────────────────────────────────────────────────────────────

    private Path writeTempMetadata(JsonNode command, String workflowId) throws Exception {
        Files.createDirectories(Paths.get(hopTempDir));
        Path file = Paths.get(hopTempDir,
                "iol_metadata_" + workflowId + "_" + System.currentTimeMillis() + ".json");
        Files.writeString(file,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(command),
                StandardCharsets.UTF_8);
        return file;
    }

    private void deleteTempFile(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Publications Kafka
    // ─────────────────────────────────────────────────────────────────────────

    private void publishStatus(JsonNode command, InteropContext context, String execLogId, String workflowId, boolean success,
                                 String logOutput, String errorMessage, long durationMs) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("execLogId",    execLogId);
            payload.put("workflowId",   workflowId);
            payload.put("status",       success ? "SUCCESS" : "FAILED");
            payload.put("durationMs",   durationMs);
            payload.put("logOutput",    logOutput != null ? logOutput : "");
            payload.put("heartbeatAt", Instant.now().toString());
            putInteropContext(payload, context);
            if (errorMessage != null) payload.put("errorMessage", errorMessage);
            String failedStage = success ? "" : detectFailedStage(logOutput, errorMessage);
            payload.put("currentStage", success ? "COMPLETED" : failedStage);
            if (!success) payload.put("failedStage", failedStage);
            payload.set("stageStatuses", objectMapper.valueToTree(stageStatuses(command, success, failedStage)));

            // Watermarks incrémentaux émis par moteur_universel.py sur stdout, capturés
            // dans la sortie Hop sous la forme : IOL_WATERMARK::<target_table>::<valeur>.
            // On les remonte par source (clé = target_table) pour fermer la boucle du watermark.
            Map<String, String> watermarks = parseWatermarks(logOutput);
            if (!watermarks.isEmpty()) {
                ObjectNode wmNode = payload.putObject("watermarks");
                watermarks.forEach(wmNode::put);
                log.info("Watermarks détectés dans la sortie Hop : {}", watermarks);
            }

            kafkaTemplate.send(statusTopic, workflowId, payload.toString()).get(30, TimeUnit.SECONDS);
            log.info("Statut publié → topic={} status={}", statusTopic, success ? "SUCCESS" : "FAILED");
        } catch (Exception e) {
            log.error("Impossible de publier le statut: {}", e.getMessage(), e);
        }
    }

    private void publishProgress(
            JsonNode command,
            InteropContext context,
            String execLogId,
            String workflowId,
            String currentStage,
            String logChunk) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("execLogId", execLogId);
            payload.put("workflowId", workflowId);
            payload.put("status", "RUNNING");
            payload.put("currentStage", currentStage);
            payload.put("heartbeatAt", Instant.now().toString());
            payload.put("logOutput", logChunk != null ? logChunk : "");
            payload.set("stageStatuses", objectMapper.valueToTree(progressStageStatuses(command, currentStage)));
            putInteropContext(payload, context);
            kafkaTemplate.send(statusTopic, workflowId, payload.toString()).get(15, TimeUnit.SECONDS);
        } catch (Exception error) {
            log.warn("Battement de progression non publie pour execLogId={}: {}", execLogId, error.getMessage());
        }
    }

    Map<String, String> progressStageStatuses(JsonNode command, String currentStage) {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("PREPARATION", "NOT_RUN");
        statuses.putAll(stageStatuses(command, false, ""));

        List<String> ordered = List.of("PREPARATION", "BRONZE", "SILVER", "GOLD", "DESTINATION");
        String normalized = normalizeProgressStage(currentStage);
        int activeIndex = ordered.indexOf(normalized);
        if (activeIndex < 0) activeIndex = 0;

        for (int index = 0; index < ordered.size(); index++) {
            String stage = ordered.get(index);
            if ("SKIPPED".equals(statuses.get(stage))) continue;
            statuses.put(stage, index < activeIndex ? "SUCCESS" : index == activeIndex ? "RUNNING" : "NOT_RUN");
        }
        return statuses;
    }

    private String normalizeProgressStage(String stage) {
        String value = stage == null ? "PREPARATION" : stage.toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "EXTRACTION", "HOP" -> "BRONZE";
            case "SILVER", "GOLD", "DESTINATION", "BRONZE" -> value;
            default -> "PREPARATION";
        };
    }

    private String detectProgressStage(String line, String fallback) {
        String text = line == null ? "" : line.toUpperCase(java.util.Locale.ROOT);
        if (text.contains("IOL_STAGE::DESTINATION") || text.contains("DESTINATION TERMINEE")) return "DESTINATION";
        if (text.contains("IOL_STAGE::GOLD") || text.contains("GOLD SQL") || text.contains("GOLD_TERMINE")) return "GOLD";
        if (text.contains("IOL_STAGE::SILVER") || text.contains("SILVER SQL") || text.contains("SILVER_TERMINE")) return "SILVER";
        if (text.contains("IOL_STAGE::BRONZE") || text.contains("BRONZE") || text.contains("JDBC STREAMING")) return "BRONZE";
        if (text.contains("EXTRACTION") || text.contains("LECTURE DIRECTE")) return "EXTRACTION";
        return fallback;
    }

    public void publishFailure(String execLogId, String workflowId, String errorMessage) {
        publishFailure(null, execLogId, workflowId, errorMessage);
    }

    void publishFailure(JsonNode command, String execLogId, String workflowId, String errorMessage) {
        InteropContext context = interopContext(command);
        publishStatus(command, context, execLogId, workflowId, false, "", errorMessage, 0);
        try {
            ObjectNode dlq = context.inbound()
                    ? buildInboundDlq(command, context, execLogId, workflowId, errorMessage)
                    : buildLegacyDlq(execLogId, workflowId, errorMessage);
            kafkaTemplate.send(dlqTopic, workflowId, dlq.toString());
            log.info("Erreur publiée → DLQ={}", dlqTopic);
        } catch (Exception e) {
            log.error("Impossible de publier en DLQ: {}", e.getMessage(), e);
        }    }

    String detectFailedStage(String logOutput, String errorMessage) {
        String text = ((logOutput == null ? "" : logOutput) + "\n"
                + (errorMessage == null ? "" : errorMessage)).toUpperCase(java.util.Locale.ROOT);
        if (text.contains("TARGET_CONNECTION") || text.contains("TARGET_HOP_CONNECTION")
                || text.contains("DESTINATION ETL") || text.contains("CONNEXION CIBLE")) {
            return "DESTINATION";
        }
        int gold = Math.max(text.lastIndexOf("GOLD"), text.lastIndexOf("GOLD_ELT"));
        int silver = Math.max(text.lastIndexOf("SILVER"), text.lastIndexOf("SILVER_LOOP"));
        int bronze = Math.max(text.lastIndexOf("BRONZE"), text.lastIndexOf("ECRITURE DANS LA DESTINATION"));
        int extraction = Math.max(text.lastIndexOf("EXTRACTION"), text.lastIndexOf("JDBC"));
        int latest = Math.max(Math.max(gold, silver), Math.max(bronze, extraction));
        if (latest == gold && gold >= 0) return "GOLD";
        if (latest == silver && silver >= 0) return "SILVER";
        if (latest == bronze && bronze >= 0) return "BRONZE";
        if (latest == extraction && extraction >= 0) return "EXTRACTION";
        if (text.contains("HOP") || text.contains("HOP-RUN")) return "HOP";
        return "PREPARATION";
    }

    Map<String, String> stageStatuses(JsonNode command, boolean success, String failedStage) {
        Map<String, String> statuses = new LinkedHashMap<>();
        String failed = failedStage == null ? "" : failedStage.toUpperCase(java.util.Locale.ROOT);
        statuses.put("BRONZE", stageStatus("BRONZE", success, failed));

        JsonNode sources = command != null ? command.path("sources") : null;
        boolean anySilverEnabled = false;
        if (sources != null && sources.isArray()) {
            for (int index = 0; index < sources.size(); index++) {
                JsonNode config = sources.get(index).path("config");
                JsonNode silver = config.path("silver_config");
                boolean enabled = silver.path("enabled").asBoolean(
                        config.path("silver_enabled").asBoolean(!silver.isMissingNode() && !silver.isEmpty()));
                statuses.put("SILVER:" + index, enabled ? stageStatus("SILVER", success, failed) : "SKIPPED");
                anySilverEnabled |= enabled;
            }
        }
        statuses.put("SILVER", anySilverEnabled ? stageStatus("SILVER", success, failed) : "SKIPPED");

        JsonNode gold = command != null ? command.path("gold_config_global") : null;
        boolean goldEnabled = gold != null && !gold.isMissingNode() && !gold.isEmpty()
                && gold.path("enabled").asBoolean(true);
        statuses.put("GOLD", goldEnabled ? stageStatus("GOLD", success, failed) : "SKIPPED");
        statuses.put("DESTINATION", stageStatus("DESTINATION", success, failed));
        return statuses;
    }

    private String stageStatus(String stage, boolean success, String failedStage) {
        if (success) {
            return "SUCCESS";
        }
        return stage.equals(failedStage) ? "FAILED" : "NOT_RUN";
    }

    private ObjectNode buildLegacyDlq(String execLogId, String workflowId, String errorMessage) {
        ObjectNode dlq = objectMapper.createObjectNode();
        dlq.put("execLogId",    execLogId);
        dlq.put("workflowId",   workflowId);
        dlq.put("errorMessage", errorMessage != null ? errorMessage : "");
        dlq.put("timestamp",    Instant.now().toString());
        return dlq;
    }

    private ObjectNode buildInboundDlq(
            JsonNode command,
            InteropContext context,
            String execLogId,
            String workflowId,
            String errorMessage) {
        ObjectNode dlq = objectMapper.createObjectNode();
        dlq.put("log_id", execLogId != null && !execLogId.isBlank() ? execLogId : "unknown");
        dlq.put("source_id", context.sourceSystem() != null && !context.sourceSystem().isBlank()
                ? context.sourceSystem()
                : "openhim-inbound");
        dlq.put("workflow_id", workflowId);
        putIfText(dlq, "standard_id", context.standardId());
        putIfText(dlq, "correlation_id", context.correlationId());
        putIfText(dlq, "openhim_transaction_id", context.openhimTransactionId());

        ObjectNode errorContext = dlq.putObject("error_context");
        errorContext.put("step", "PIPELINE_CONSUMER");
        errorContext.put("message", errorMessage != null ? errorMessage : "");
        errorContext.put("severity", "ERROR");

        ObjectNode originalData = dlq.putObject("original_data");
        if (command != null && !command.isMissingNode() && !command.isNull()) {
            originalData.set("command", command.deepCopy());
        } else {
            originalData.put("workflowId", workflowId);
            originalData.put("execLogId", execLogId);
        }
        dlq.put("timestamp", Instant.now().toString());
        return dlq;
    }

    private void putInteropContext(ObjectNode payload, InteropContext context) {
        putIfText(payload, "direction", context.direction());
        putIfText(payload, "standardId", context.standardId());
        putIfText(payload, "sourceSystem", context.sourceSystem());
        putIfText(payload, "correlationId", context.correlationId());
        putIfText(payload, "openhimTransactionId", context.openhimTransactionId());
    }

    private void putIfText(ObjectNode payload, String fieldName, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(fieldName, value);
        }
    }

    private InteropContext interopContext(JsonNode command) {
        if (command == null || command.isMissingNode() || command.isNull()) {
            return InteropContext.empty();
        }
        return new InteropContext(
                text(command, "direction"),
                text(command, "standardId"),
                text(command, "sourceSystem"),
                text(command, "correlationId"),
                text(command, "openhimTransactionId")
        );
    }

    private String text(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("");
        return value != null ? value.trim() : "";
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extrait les watermarks émis par moteur_universel.py dans la sortie Hop.
     * Format d'une ligne (tolère un préfixe de log Hop avant le marqueur) :
     *   ...IOL_WATERMARK::&lt;target_table&gt;::&lt;valeur&gt;
     * La valeur peut contenir des ':' simples (timestamps ISO-8601) ; seul le
     * séparateur '::' est significatif. Clé du résultat = target_table.
     */
    Map<String, String> parseWatermarks(String logOutput) {
        Map<String, String> result = new LinkedHashMap<>();
        if (logOutput == null || logOutput.isBlank()) return result;
        final String marker = "IOL_WATERMARK::";
        for (String rawLine : logOutput.split("\\R")) {
            int at = rawLine.indexOf(marker);
            if (at < 0) continue;
            String payload = rawLine.substring(at + marker.length());
            int sep = payload.indexOf("::");
            if (sep <= 0) continue;
            String targetTable = payload.substring(0, sep).trim();
            String value = payload.substring(sep + 2).trim();
            if (!targetTable.isEmpty() && !value.isEmpty()) {
                result.put(targetTable, value);
            }
        }
        return result;
    }

    private boolean isDuplicate(String hash) {
        Instant last = executedHashes.get(hash);
        if (last == null) return false;
        boolean duplicate = Duration.between(last, Instant.now()).getSeconds() < idempotenceTtlSeconds;
        if (!duplicate) {
            executedHashes.remove(hash, last);
            completedExecutions.remove(hash);
        }
        return duplicate;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toString(input.hashCode());
        }
    }

    private final class ProgressReporter implements AutoCloseable {
        private final JsonNode command;
        private final InteropContext context;
        private final String execLogId;
        private final String workflowId;
        private final String pipelineName;
        private final AtomicReference<String> currentStage = new AtomicReference<>("PREPARATION");
        private final StringBuilder pendingOutput = new StringBuilder();
        private ScheduledExecutorService scheduler;
        private volatile boolean finished;

        private ProgressReporter(
                JsonNode command,
                InteropContext context,
                String execLogId,
                String workflowId,
                String pipelineName) {
            this.command = command;
            this.context = context;
            this.execLogId = execLogId;
            this.workflowId = workflowId;
            this.pipelineName = pipelineName;
        }

        private void start() {
            publishProgress(command, context, execLogId, workflowId, currentStage.get(), "Worker connecte.\n");
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("iol-status-heartbeat-" + execLogId);
                thread.setDaemon(true);
                return thread;
            });
            long interval = Math.max(2L, statusHeartbeatSeconds);
            scheduler.scheduleAtFixedRate(this::heartbeat, interval, interval, TimeUnit.SECONDS);
        }

        private void stage(String stage) {
            if (stage == null || stage.isBlank()) return;
            String normalized = stage.toUpperCase(java.util.Locale.ROOT);
            if (!normalized.equals(currentStage.getAndSet(normalized))) {
                append("Etape active: " + normalized);
                heartbeat();
            }
        }

        private void acceptLine(String line) {
            if (line == null) return;
            String detected = detectProgressStage(line, currentStage.get());
            currentStage.set(detected);
            append(line);
        }

        private synchronized void append(String line) {
            pendingOutput.append(line).append("\n");
            if (pendingOutput.length() > 64_000) {
                pendingOutput.delete(0, pendingOutput.length() - 64_000);
            }
        }

        private void heartbeat() {
            if (finished) return;
            String chunk = drainOutput();
            publishProgress(command, context, execLogId, workflowId, currentStage.get(), chunk);
        }

        private synchronized String drainOutput() {
            String value = pendingOutput.toString();
            pendingOutput.setLength(0);
            return value;
        }

        private void finish() {
            finished = true;
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }

        @Override
        public void close() {
            finish();
            log.debug("Suivi de progression ferme pour '{}'", pipelineName);
        }
    }

    private enum ExecutionMode { LOCAL, SPARK }

    private record HopResult(boolean success, String logOutput, String errorMessage) {}
    private record CompletionSnapshot(boolean success, String logOutput, String errorMessage, long durationMs) {}
    record PreparedCommand(JsonNode command, List<Path> tempFiles) {}
    private record InteropContext(
            String direction,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId) {
        static InteropContext empty() {
            return new InteropContext("", "", "", "", "");
        }

        boolean inbound() {
            return "INBOUND".equalsIgnoreCase(direction);
        }
    }
}
