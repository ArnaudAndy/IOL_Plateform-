package com.iol.etlplatform.sourcegateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Moves source rows before a pipeline command becomes executable.
 *
 * Normal volumes are serialized into ordered Kafka row batches. Big Data, or
 * a single record unsafe for Kafka, is streamed to RustFS and represented in
 * Kafka by a checksum-protected manifest. A command is published only after
 * the transfer is complete; failures emit an abort event for consumer cleanup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceDataTransportService {

    public static final String EVENT_TYPE_SOURCE_DATA_CHUNK = "PIPELINE_SOURCE_DATA_CHUNK";
    private static final List<String> JDBC_PROTOCOLS = List.of(
            "POSTGRES", "MYSQL", "MARIADB", "MSSQL", "ORACLE", "SQLITE", "SNOWFLAKE", "REDSHIFT");

    private final ObjectMapper objectMapper;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final UploadedFileLocator uploadedFileLocator;
    private final ApiSourceClient apiSourceClient;
    private final ObjectStorageService objectStorageService;
    private final SourceConnectionLimiter sourceConnectionLimiter;

    @Value("${app.kafka.data-transport.enabled:true}")
    private boolean enabled;

    @Value("${app.kafka.data-transport.chunk-bytes:524288}")
    private int chunkBytes;

    @Value("${app.kafka.data-transport.row-batch-rows:500}")
    private int rowBatchRows;

    @Value("${app.kafka.data-transport.max-row-batch-event-bytes:8388608}")
    private int maxRowBatchEventBytes;

    /**
     * Nombre de lots Kafka laissés en vol avant d'attendre le plus ancien.
     * Borne à la fois la mémoire du producteur et la latence de détection d'une
     * erreur d'envoi.
     */
    @Value("${app.kafka.data-transport.max-in-flight-batches:64}")
    private int kafkaMaxInFlightBatches;

    /**
     * Nom du magasin d'objets, pour les manifestes et les journaux.
     *
     * Le transport n'utilise que l'API S3 standard: toute implementation
     * compatible convient. Ce libelle sert a la tracabilite, pas au routage.
     */
    @Value("${app.object-storage.provider:S3}")
    private String objectStorageProvider;

    @Value("${app.interop.big-data.row-threshold:${SPARK_ROW_THRESHOLD:10000000}}")
    private long inboundBigDataRowThreshold;

    @Value("${app.interop.big-data.byte-threshold:2147483648}")
    private long inboundBigDataByteThreshold;

    @Value("${app.interop.max-stream-bytes:10737418240}")
    private long maxInboundStreamBytes;

    @Value("${app.interop.max-ndjson-line-bytes:134217728}")
    private int maxInboundNdjsonLineBytes;

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> publishSourceData(
            String topic,
            String kafkaKey,
            String workflowId,
            String execLogId,
            Map<String, Object> command) {
        if (!enabled || "INBOUND".equalsIgnoreCase(String.valueOf(command.get("direction")))) {
            if (!enabled) {
                throw new IllegalStateException(
                        "Le transport Kafka/RustFS des donnees source est desactive.");
            }
            return List.of();
        }
        Object rawSources = command.get("sources");
        if (!(rawSources instanceof List<?> sources)) {
            return List.of();
        }

        List<Map<String, Object>> manifest = new ArrayList<>();
        boolean bigData = isBigDataExecution(command);
        for (int index = 0; index < sources.size(); index++) {
            Object rawSource = sources.get(index);
            if (!(rawSource instanceof Map<?, ?>)) {
                throw new BadRequestException("Source invalide a l'index " + index);
            }
            Map<String, Object> source = (Map<String, Object>) rawSource;
            String protocol = string(source.get("source_name")).toUpperCase(Locale.ROOT);
            if ("PUSH".equals(protocol)) {
                continue;
            }
            Map<String, Object> config = map(source.get("config"));
            if ("API".equals(protocol)) {
                Map<String, Object> item;
                if (bigData) {
                    if (!objectStorageService.isEnabled()) {
                        throw new BadRequestException(
                                "RustFS doit etre disponible pour transporter une source API Big Data.");
                    }
                    item = publishApiObjectReference(workflowId, execLogId, index, config);
                } else {
                    try {
                        item = publishApiRowBatches(
                                topic, kafkaKey, workflowId, execLogId, index, config);
                    } catch (KafkaRowTooLargeException oversized) {
                        if (!objectStorageService.isEnabled()) {
                            throw new BadRequestException(
                                    "Une ligne API depasse la capacite Kafka et RustFS est indisponible.");
                        }
                        item = publishApiObjectReference(workflowId, execLogId, index, config);
                    }
                }
                manifest.add(item);
                rewriteSourceForTransport(source, config, "JSON", string(item.get("fileName")), item);
                continue;
            }
            if (JDBC_PROTOCOLS.contains(protocol)) {
                Map<String, Object> item;
                if (bigData) {
                    if (!objectStorageService.isEnabled()) {
                        throw new BadRequestException(
                                "RustFS doit être disponible pour transporter une source JDBC Big Data.");
                    }
                    item = publishJdbcObjectReference(
                            workflowId, execLogId, index, protocol, config);
                } else {
                    try {
                        item = publishJdbcRowBatches(
                                topic, kafkaKey, workflowId, execLogId, index, protocol, config);
                    } catch (KafkaRowTooLargeException oversized) {
                        if (!objectStorageService.isEnabled()) {
                            throw new BadRequestException(
                                    "Une ligne JDBC dépasse la capacité Kafka et RustFS est indisponible.");
                        }
                        log.info("Bascule RustFS automatique: une ligne JDBC dépasse la taille Kafka "
                                + "(workflowExec={} source={})", execLogId, index);
                        item = publishJdbcObjectReference(
                                workflowId, execLogId, index, protocol, config);
                    }
                }
                manifest.add(item);
                rewriteSourceForTransport(source, config, "JSON",
                        string(item.get("fileName")), item);
                continue;
            }
            PreparedData prepared = prepareSourceData(protocol, config, execLogId, index);
            try {
                Map<String, Object> item = publishPreparedData(
                        topic, kafkaKey, workflowId, execLogId, index, prepared, bigData);
                manifest.add(item);
                rewriteSourceForTransport(source, config, prepared.format(), prepared.fileName(), item);
            } finally {
                deletePreparedData(prepared);
            }
        }
        return manifest;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> publishInboundData(
            String topic,
            String kafkaKey,
            String organizationId,
            String workflowId,
            String execLogId,
            List<Map<String, Object>> records,
            Long estimatedBytes,
            Map<String, Object> command) {
        if (!enabled) return List.of();
        if (records == null || records.isEmpty()) {
            throw new BadRequestException("Le lot INBOUND ne contient aucun enregistrement.");
        }

        Object rawSources = command.get("sources");
        if (!(rawSources instanceof List<?> sources) || sources.isEmpty()
                || !(sources.get(0) instanceof Map<?, ?> rawSource)) {
            throw new BadRequestException("La commande INBOUND ne contient aucune source PUSH.");
        }

        Map<String, Object> source = (Map<String, Object>) rawSource;
        Map<String, Object> config = map(source.get("config"));
        boolean bigData = isBigDataExecution(command)
                || (inboundBigDataRowThreshold > 0 && records.size() >= inboundBigDataRowThreshold)
                || (estimatedBytes != null && inboundBigDataByteThreshold > 0
                    && estimatedBytes >= inboundBigDataByteThreshold);

        Map<String, Object> item;
        if (bigData) {
            if (!objectStorageService.isEnabled()) {
                throw new BadRequestException(
                        "RustFS doit être disponible pour transporter un lot INBOUND Big Data.");
            }
            item = publishInboundObjectReference(
                    organizationId, workflowId, execLogId, records);
        } else {
            item = publishInboundRowBatches(
                    topic, kafkaKey, organizationId, workflowId, execLogId, records);
        }

        rewriteSourceForTransport(source, config, "JSON",
                string(item.get("fileName")), item);
        Map<String, Object> sourceConfig = new LinkedHashMap<>(map(config.get("source_config")));
        sourceConfig.remove("data");
        sourceConfig.remove("records");
        sourceConfig.remove("payload");
        sourceConfig.put("mode", "PUSH");
        sourceConfig.put("format", "JSON");
        sourceConfig.put("already_pivot", true);
        config.put("source_config", sourceConfig);
        source.put("inbound_push", true);

        command.put("rowCount", records.size());
        command.put("dataTransport", item.get("transport"));
        command.put("sourceDataManifest", List.of(item));
        return List.of(item);
    }

    @SuppressWarnings("unchecked")
    public InboundStreamPublication publishInboundStream(
            String topic,
            String kafkaKey,
            String organizationId,
            String workflowId,
            String execLogId,
            InputStream inputStream,
            Long estimatedRows,
            Long estimatedBytes,
            Long estimatedMaxRecordBytes,
            Map<String, Object> command) {
        if (!enabled) {
            throw new IllegalStateException(
                    "Le transport des données Kafka doit être activé pour un flux INBOUND.");
        }
        if (inputStream == null) {
            throw new BadRequestException("Le flux NDJSON INBOUND est absent.");
        }

        Object rawSources = command.get("sources");
        if (!(rawSources instanceof List<?> sources) || sources.isEmpty()
                || !(sources.get(0) instanceof Map<?, ?> rawSource)) {
            throw new BadRequestException(
                    "La commande INBOUND ne contient aucune source PUSH.");
        }

        boolean bigData = isBigDataExecution(command)
                || (estimatedRows != null && inboundBigDataRowThreshold > 0
                    && estimatedRows >= inboundBigDataRowThreshold)
                || (estimatedBytes != null && inboundBigDataByteThreshold > 0
                    && estimatedBytes >= inboundBigDataByteThreshold)
                || (estimatedMaxRecordBytes != null
                    && estimatedMaxRecordBytes >= safeKafkaRecordBytes());

        Map<String, Object> item;
        if (bigData) {
            if (!objectStorageService.isEnabled()) {
                throw new BadRequestException(
                        "RustFS doit être disponible pour transporter un flux INBOUND Big Data.");
            }
            item = publishInboundStreamToObjectStorage(
                    organizationId, workflowId, execLogId, inputStream);
        } else {
            item = publishInboundStreamToKafka(
                    topic,
                    kafkaKey,
                    organizationId,
                    workflowId,
                    execLogId,
                    inputStream);
        }

        Map<String, Object> source = (Map<String, Object>) rawSource;
        Map<String, Object> config = map(source.get("config"));
        rewriteSourceForTransport(
                source, config, "JSON", string(item.get("fileName")), item);
        Map<String, Object> sourceConfig =
                new LinkedHashMap<>(map(config.get("source_config")));
        sourceConfig.remove("data");
        sourceConfig.remove("records");
        sourceConfig.remove("payload");
        sourceConfig.put("mode", "PUSH");
        sourceConfig.put("format", "JSON");
        sourceConfig.put("already_pivot", true);
        sourceConfig.put("streaming", true);
        config.put("source_config", sourceConfig);
        source.put("inbound_push", true);
        if (item.get("headers") instanceof List<?> headers) {
            config.put("fields", new ArrayList<>(headers));
        }

        long recordCount = ((Number) item.get("rowCount")).longValue();
        command.put("rowCount", recordCount);
        command.put("dataTransport", item.get("transport"));
        command.put("sourceDataManifest", List.of(item));
        return new InboundStreamPublication(item, recordCount);
    }

    private long safeKafkaRecordBytes() {
        long configuredEventBytes = Math.max(64 * 1024L, maxRowBatchEventBytes);
        return Math.max(64 * 1024L, configuredEventBytes / 2L);
    }

    private Map<String, Object> publishInboundStreamToKafka(
            String topic,
            String key,
            String organizationId,
            String workflowId,
            String execLogId,
            InputStream inputStream) {
        KafkaTemplate<String, String> kafka = kafkaTemplateProvider.getIfAvailable();
        if (kafka == null) {
            throw new IllegalStateException(
                    "KafkaTemplate indisponible pour le flux INBOUND.");
        }

        String transferId = UUID.randomUUID().toString();
        StreamAccumulator state = new StreamAccumulator();
        SendWindow window = new SendWindow(kafkaMaxInFlightBatches);
        List<List<Object>> batch =
                new ArrayList<>(Math.max(10, Math.min(rowBatchRows, 2_000)));
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }

        try {
            readInboundNdjson(inputStream, record -> {
                if (state.headers == null) {
                    state.headers = List.copyOf(record.keySet());
                } else if (!new LinkedHashSet<>(state.headers).equals(record.keySet())) {
                    throw new BadRequestException(
                            "Tous les pivots NDJSON doivent partager le même schéma.");
                }

                List<Object> values = state.headers.stream().map(record::get).toList();
                byte[] canonical = canonicalJsonRow(state.headers, values);
                state.rowCount++;
                state.canonicalBytes += canonical.length;
                if ((inboundBigDataRowThreshold > 0
                        && state.rowCount >= inboundBigDataRowThreshold)
                        || (inboundBigDataByteThreshold > 0
                        && state.canonicalBytes >= inboundBigDataByteThreshold)) {
                    throw new BadRequestException(
                            "Le flux dépasse le seuil Big Data annoncé. "
                                    + "Renvoyez estimatedRows ou estimatedBytes afin que "
                                    + "la bascule RustFS soit faite avant le transfert.");
                }
                digest.update(canonical);
                batch.add(values);
                int maxRows = Math.max(10, Math.min(rowBatchRows, 2_000));
                if (batch.size() >= maxRows) {
                    state.batchCount += sendRowBatch(
                            kafka,
                            topic,
                            key,
                            workflowId,
                            execLogId,
                            transferId,
                            0,
                            state.batchCount,
                            state.headers,
                            new ArrayList<>(batch),
                            "inbound-pivot.jsonl",
                            organizationId,
                            window);
                    batch.clear();
                }
            });

            if (state.rowCount == 0) {
                throw new BadRequestException(
                        "Le flux NDJSON INBOUND ne contient aucun enregistrement.");
            }
            if (!batch.isEmpty()) {
                state.batchCount += sendRowBatch(
                        kafka,
                        topic,
                        key,
                        workflowId,
                        execLogId,
                        transferId,
                        0,
                        state.batchCount,
                        state.headers,
                        new ArrayList<>(batch),
                        "inbound-pivot.jsonl",
                        organizationId,
                        window);
            }
            // Le manifeste ne doit decrire que des lots reellement acquittes.
            window.awaitAll();
        } catch (Exception error) {
            publishTransferAbort(
                    kafka,
                    topic,
                    key,
                    organizationId,
                    workflowId,
                    execLogId,
                    transferId,
                    error);
            if (error instanceof BadRequestException badRequest) {
                throw badRequest;
            }
            throw new BadRequestException(
                    "Transport streaming INBOUND vers Kafka impossible: "
                            + rootMessage(error));
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("transport", "KAFKA_ROW_BATCH");
        manifest.put("transferId", transferId);
        manifest.put("sourceIndex", 0);
        manifest.put("batchCount", state.batchCount);
        manifest.put("rowCount", state.rowCount);
        manifest.put("sizeBytes", state.canonicalBytes);
        manifest.put("sha256", HexFormat.of().formatHex(digest.digest()));
        manifest.put("format", "JSON");
        manifest.put("fileName", "inbound-pivot.jsonl");
        manifest.put("organizationId", organizationId);
        manifest.put("headers", state.headers);
        return manifest;
    }

    private Map<String, Object> publishInboundStreamToObjectStorage(
            String organizationId,
            String workflowId,
            String execLogId,
            InputStream inputStream) {
        String fileName = "inbound-pivot.jsonl";
        String isolatedWorkflowId =
                safe(organizationId) + "__" + safe(workflowId);
        StreamAccumulator state = new StreamAccumulator();
        ObjectStorageService.StoredObject stored = objectStorageService.storeStreaming(
                isolatedWorkflowId,
                execLogId,
                0,
                fileName,
                contentType("JSON"),
                output -> {
                    readInboundNdjson(inputStream, record -> {
                        if (state.headers == null) {
                            state.headers = List.copyOf(record.keySet());
                        } else if (!new LinkedHashSet<>(state.headers).equals(record.keySet())) {
                            throw new BadRequestException(
                                    "Tous les pivots NDJSON doivent partager le même schéma.");
                        }
                        byte[] canonical = objectMapper.writeValueAsBytes(record);
                        output.write(canonical);
                        output.write('\n');
                        state.rowCount++;
                    });
                    if (state.rowCount == 0) {
                        throw new BadRequestException(
                                "Le flux NDJSON INBOUND ne contient aucun enregistrement.");
                    }
                });
        Map<String, Object> manifest =
                objectManifest(stored, 0, "JSON", fileName);
        manifest.put("rowCount", state.rowCount);
        manifest.put("organizationId", organizationId);
        manifest.put("headers", state.headers);
        return manifest;
    }

    private void readInboundNdjson(
            InputStream inputStream,
            InboundRecordConsumer consumer) throws Exception {
        long maxBytes = Math.max(1L, maxInboundStreamBytes);
        int maxLineBytes = Math.max(1024, maxInboundNdjsonLineBytes);
        byte[] inputBuffer = new byte[64 * 1024];
        ByteArrayOutputStream line = new ByteArrayOutputStream(8 * 1024);
        long receivedBytes = 0;
        long lineNumber = 0;
        int read;

        while ((read = inputStream.read(inputBuffer)) >= 0) {
            receivedBytes += read;
            if (receivedBytes > maxBytes) {
                throw new BadRequestException(
                        "Le flux NDJSON dépasse la limite de " + maxBytes + " octets.");
            }
            for (int index = 0; index < read; index++) {
                byte value = inputBuffer[index];
                if (value == '\n') {
                    lineNumber++;
                    consumeInboundLine(line.toByteArray(), lineNumber, consumer);
                    line.reset();
                    continue;
                }
                if (line.size() >= maxLineBytes) {
                    throw new BadRequestException(
                            "La ligne NDJSON " + (lineNumber + 1)
                                    + " dépasse " + maxLineBytes + " octets.");
                }
                line.write(value);
            }
        }
        if (line.size() > 0) {
            consumeInboundLine(line.toByteArray(), lineNumber + 1, consumer);
        }
    }

    @SuppressWarnings("unchecked")
    private void consumeInboundLine(
            byte[] rawLine,
            long lineNumber,
            InboundRecordConsumer consumer) throws Exception {
        int length = rawLine.length;
        if (length > 0 && rawLine[length - 1] == '\r') length--;
        boolean blank = true;
        for (int index = 0; index < length; index++) {
            if (!Character.isWhitespace((char) rawLine[index])) {
                blank = false;
                break;
            }
        }
        if (blank) return;

        try {
            Map<String, Object> record = objectMapper.readValue(
                    rawLine, 0, length, LinkedHashMap.class);
            if (record == null || record.isEmpty()) {
                throw new BadRequestException(
                        "La ligne NDJSON " + lineNumber
                                + " doit être un objet JSON non vide.");
            }
            consumer.accept(record);
        } catch (BadRequestException error) {
            throw error;
        } catch (Exception error) {
            throw new BadRequestException(
                    "JSON invalide à la ligne NDJSON " + lineNumber + ": "
                            + rootMessage(error));
        }
    }

    private void publishTransferAbort(
            KafkaTemplate<String, String> kafka,
            String topic,
            String key,
            String organizationId,
            String workflowId,
            String execLogId,
            String transferId,
            Exception cause) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "PIPELINE_SOURCE_TRANSFER_ABORTED");
            event.put("workflowId", workflowId);
            event.put("execLogId", execLogId);
            event.put("organizationId", organizationId);
            event.put("transferId", transferId);
            event.put("reason", rootMessage(cause));
            kafka.send(topic, key, objectMapper.writeValueAsString(event))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception abortError) {
            log.error(
                    "Signal d'abandon Kafka impossible pour transferId={}: {}",
                    transferId,
                    rootMessage(abortError));
        }
    }

    private Map<String, Object> publishInboundRowBatches(
            String topic,
            String key,
            String organizationId,
            String workflowId,
            String execLogId,
            List<Map<String, Object>> records) {
        KafkaTemplate<String, String> kafka = kafkaTemplateProvider.getIfAvailable();
        if (kafka == null) throw new IllegalStateException("KafkaTemplate indisponible pour les lots INBOUND.");

        Set<String> headerSet = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            if (record == null || record.isEmpty()) {
                throw new BadRequestException("Chaque enregistrement INBOUND doit être un objet JSON non vide.");
            }
            headerSet.addAll(record.keySet());
        }
        List<String> headers = List.copyOf(headerSet);
        int maxRows = Math.max(10, Math.min(rowBatchRows, 2_000));
        String transferId = UUID.randomUUID().toString();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IllegalStateException(error); }

        List<List<Object>> batch = new ArrayList<>(maxRows);
        SendWindow window = new SendWindow(kafkaMaxInFlightBatches);
        int batchIndex = 0;
        long canonicalBytes = 0;
        try {
            for (Map<String, Object> record : records) {
                List<Object> values = headers.stream().map(record::get).toList();
                byte[] canonical = canonicalJsonRow(headers, values);
                digest.update(canonical);
                canonicalBytes += canonical.length;
                batch.add(values);
                if (batch.size() >= maxRows) {
                    batchIndex += sendRowBatch(
                            kafka, topic, key, workflowId, execLogId, transferId, 0,
                            batchIndex, headers, new ArrayList<>(batch),
                            "inbound-pivot.jsonl", organizationId, window);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                batchIndex += sendRowBatch(
                        kafka, topic, key, workflowId, execLogId, transferId, 0,
                        batchIndex, headers, new ArrayList<>(batch),
                        "inbound-pivot.jsonl", organizationId, window);
            }
            // Le manifeste ne doit decrire que des lots reellement acquittes.
            window.awaitAll();
        } catch (Exception error) {
            if (error instanceof BadRequestException badRequest) throw badRequest;
            throw new BadRequestException("Transport INBOUND vers Kafka impossible: " + rootMessage(error));
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("transport", "KAFKA_ROW_BATCH");
        manifest.put("transferId", transferId);
        manifest.put("sourceIndex", 0);
        manifest.put("batchCount", batchIndex);
        manifest.put("rowCount", records.size());
        manifest.put("sizeBytes", canonicalBytes);
        manifest.put("sha256", HexFormat.of().formatHex(digest.digest()));
        manifest.put("format", "JSON");
        manifest.put("fileName", "inbound-pivot.jsonl");
        manifest.put("organizationId", organizationId);
        return manifest;
    }

    private Map<String, Object> publishInboundObjectReference(
            String organizationId,
            String workflowId,
            String execLogId,
            List<Map<String, Object>> records) {
        String fileName = "inbound-pivot.jsonl";
        String isolatedWorkflowId = safe(organizationId) + "__" + safe(workflowId);
        ObjectStorageService.StoredObject stored = objectStorageService.storeStreaming(
                isolatedWorkflowId,
                execLogId,
                0,
                fileName,
                contentType("JSON"),
                output -> {
                    for (Map<String, Object> record : records) {
                        output.write(objectMapper.writeValueAsBytes(record));
                        output.write('\n');
                    }
                });
        Map<String, Object> manifest = objectManifest(stored, 0, "JSON", fileName);
        manifest.put("rowCount", records.size());
        manifest.put("organizationId", organizationId);
        return manifest;
    }

    private Map<String, Object> publishPreparedData(
            String topic,
            String kafkaKey,
            String workflowId,
            String execLogId,
            int sourceIndex,
            PreparedData prepared,
            boolean bigData) {
        if (bigData) {
            if (!objectStorageService.isEnabled()) {
                throw new BadRequestException("RustFS doit être disponible pour transporter les données Big Data.");
            }
            return publishObjectReference(workflowId, execLogId, sourceIndex, prepared);
        }
        return publishFileChunks(topic, kafkaKey, workflowId, execLogId, sourceIndex, prepared);
    }

    private Map<String, Object> publishJdbcRowBatches(
            String topic,
            String key,
            String workflowId,
            String execLogId,
            int sourceIndex,
            String protocol,
            Map<String, Object> config) {
        KafkaTemplate<String, String> kafka = kafkaTemplateProvider.getIfAvailable();
        if (kafka == null) throw new IllegalStateException("KafkaTemplate indisponible pour les lots de lignes.");
        int maxRows = Math.max(10, Math.min(rowBatchRows, 2_000));
        String transferId = UUID.randomUUID().toString();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }

        int batchIndex = 0;
        long canonicalBytes = 0;
        long rowCount = 0;
        List<List<Object>> batch = new ArrayList<>(maxRows);
        final List<String>[] headersRef = new List[]{List.of()};
        final long[] counters = new long[]{batchIndex, canonicalBytes, rowCount};
        SendWindow window = new SendWindow(kafkaMaxInFlightBatches);
        try {
            streamJdbcRows(protocol, config, (headers, values) -> {
                headersRef[0] = headers;
                byte[] canonical = canonicalJsonRow(headers, values);
                digest.update(canonical);
                counters[1] += canonical.length;
                counters[2]++;
                batch.add(values);
                if (batch.size() >= maxRows) {
                    counters[0] += sendRowBatch(kafka, topic, key, workflowId, execLogId, transferId,
                            sourceIndex, (int) counters[0], headers, new ArrayList<>(batch),
                            "jdbc-source-" + sourceIndex + ".jsonl", window);
                    batch.clear();
                }
            });
            if (!batch.isEmpty() || counters[0] == 0) {
                counters[0] += sendRowBatch(kafka, topic, key, workflowId, execLogId, transferId,
                        sourceIndex, (int) counters[0], headersRef[0], new ArrayList<>(batch),
                        "jdbc-source-" + sourceIndex + ".jsonl", window);
            }
            // Le manifeste ne doit decrire que des lots reellement acquittes.
            window.awaitAll();
        } catch (Exception error) {
            if (error instanceof BadRequestException badRequest) throw badRequest;
            if (error instanceof KafkaRowTooLargeException oversized) throw oversized;
            throw new BadRequestException("Transport JDBC vers Kafka impossible: " + rootMessage(error));
        }
        batchIndex = (int) counters[0];
        canonicalBytes = counters[1];
        rowCount = counters[2];

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("transport", "KAFKA_ROW_BATCH");
        manifest.put("transferId", transferId);
        manifest.put("sourceIndex", sourceIndex);
        manifest.put("batchCount", batchIndex);
        manifest.put("rowCount", rowCount);
        manifest.put("sizeBytes", canonicalBytes);
        manifest.put("sha256", HexFormat.of().formatHex(digest.digest()));
        manifest.put("format", "JSON");
        manifest.put("fileName", "jdbc-source-" + sourceIndex + ".jsonl");
        return manifest;
    }

    /** Transporte les objets d'une API page par page, directement vers Kafka. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> publishApiRowBatches(
            String topic,
            String key,
            String workflowId,
            String execLogId,
            int sourceIndex,
            Map<String, Object> config) {
        KafkaTemplate<String, String> kafka = kafkaTemplateProvider.getIfAvailable();
        if (kafka == null) throw new IllegalStateException("KafkaTemplate indisponible pour les lots API.");
        int maxRows = Math.max(10, Math.min(rowBatchRows, 2_000));
        String transferId = UUID.randomUUID().toString();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }

        List<List<Object>> batch = new ArrayList<>(maxRows);
        final List<String>[] headersRef = new List[]{List.of()};
        final long[] counters = new long[]{0, 0, 0};
        SendWindow window = new SendWindow(kafkaMaxInFlightBatches);
        try {
            apiSourceClient.streamRows(apiConfig(config), row -> {
                List<String> currentHeaders = new ArrayList<>();
                row.fieldNames().forEachRemaining(currentHeaders::add);
                if (headersRef[0].isEmpty()) {
                    headersRef[0] = List.copyOf(currentHeaders);
                } else if (currentHeaders.stream().anyMatch(field -> !headersRef[0].contains(field))) {
                    throw new BadRequestException(
                            "Le schema de la reponse API change pendant la pagination: " + currentHeaders);
                }

                List<Object> values = new ArrayList<>(headersRef[0].size());
                for (String header : headersRef[0]) {
                    com.fasterxml.jackson.databind.JsonNode value = row.get(header);
                    values.add(value == null || value.isNull()
                            ? null : objectMapper.convertValue(value, Object.class));
                }
                byte[] canonical = canonicalJsonRow(headersRef[0], values);
                digest.update(canonical);
                counters[1] += canonical.length;
                counters[2]++;
                batch.add(values);
                if (batch.size() >= maxRows) {
                    counters[0] += sendRowBatch(
                            kafka, topic, key, workflowId, execLogId, transferId,
                            sourceIndex, (int) counters[0], headersRef[0], new ArrayList<>(batch),
                            "api-source-" + sourceIndex + ".jsonl", window);
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) {
                counters[0] += sendRowBatch(
                        kafka, topic, key, workflowId, execLogId, transferId,
                        sourceIndex, (int) counters[0], headersRef[0], new ArrayList<>(batch),
                        "api-source-" + sourceIndex + ".jsonl", window);
            }
            window.awaitAll();
        } catch (Exception error) {
            if (error instanceof BadRequestException badRequest) throw badRequest;
            if (error instanceof KafkaRowTooLargeException oversized) throw oversized;
            throw new BadRequestException("Transport API vers Kafka impossible: " + rootMessage(error));
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("transport", "KAFKA_ROW_BATCH");
        manifest.put("transferId", transferId);
        manifest.put("sourceIndex", sourceIndex);
        manifest.put("batchCount", (int) counters[0]);
        manifest.put("rowCount", counters[2]);
        manifest.put("sizeBytes", counters[1]);
        manifest.put("sha256", HexFormat.of().formatHex(digest.digest()));
        manifest.put("format", "JSON");
        manifest.put("fileName", "api-source-" + sourceIndex + ".jsonl");
        return manifest;
    }

    private int sendRowBatch(
            KafkaTemplate<String, String> kafka,
            String topic,
            String key,
            String workflowId,
            String execLogId,
            String transferId,
            int sourceIndex,
            int batchIndex,
            List<String> headers,
            List<List<Object>> rows,
            String fileName,
            SendWindow window) throws Exception {
        return sendRowBatch(
                kafka, topic, key, workflowId, execLogId, transferId, sourceIndex,
                batchIndex, headers, rows, fileName, null, window);
    }

    private int sendRowBatch(
            KafkaTemplate<String, String> kafka,
            String topic,
            String key,
            String workflowId,
            String execLogId,
            String transferId,
            int sourceIndex,
            int batchIndex,
            List<String> headers,
            List<List<Object>> rows,
            String fileName,
            String organizationId,
            SendWindow window) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "PIPELINE_SOURCE_ROW_BATCH");
        event.put("workflowId", workflowId);
        event.put("execLogId", execLogId);
        if (organizationId != null && !organizationId.isBlank()) {
            event.put("organizationId", organizationId);
        }
        event.put("transferId", transferId);
        event.put("sourceIndex", sourceIndex);
        event.put("batchIndex", batchIndex);
        event.put("headers", headers);
        event.put("rows", rows);
        event.put("format", "JSON");
        event.put("fileName", fileName);
        String json = objectMapper.writeValueAsString(event);
        int limit = Math.max(64 * 1024, Math.min(maxRowBatchEventBytes, 9 * 1024 * 1024));
        if (json.getBytes(StandardCharsets.UTF_8).length > limit) {
            if (rows.size() <= 1) {
                throw new KafkaRowTooLargeException();
            }
            int middle = rows.size() / 2;
            int firstCount = sendRowBatch(kafka, topic, key, workflowId, execLogId, transferId,
                    sourceIndex, batchIndex, headers, rows.subList(0, middle), fileName,
                    organizationId, window);
            int secondCount = sendRowBatch(kafka, topic, key, workflowId, execLogId, transferId,
                    sourceIndex, batchIndex + firstCount, headers, rows.subList(middle, rows.size()),
                    fileName, organizationId, window);
            return firstCount + secondCount;
        }
        window.send(kafka, topic, key, json);
        return 1;
    }

    private byte[] canonicalJsonRow(List<String> headers, List<Object> values) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            row.put(headers.get(index), index < values.size() ? values.get(index) : null);
        }
        byte[] json = objectMapper.writeValueAsBytes(row);
        byte[] canonical = java.util.Arrays.copyOf(json, json.length + 1);
        canonical[canonical.length - 1] = '\n';
        return canonical;
    }

    private Map<String, Object> publishObjectReference(
            String workflowId, String execLogId, int sourceIndex, PreparedData prepared) {
        ObjectStorageService.StoredObject stored = objectStorageService.store(
                prepared.path(), workflowId, execLogId, sourceIndex, prepared.fileName(), contentType(prepared.format()));
        return objectManifest(stored, sourceIndex, prepared.format(), prepared.fileName());
    }

    private Map<String, Object> objectManifest(
            ObjectStorageService.StoredObject stored,
            int sourceIndex,
            String format,
            String fileName) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("transport", "OBJECT_STORAGE");
        // Etiquette informative du magasin d'objets. Le code n'utilise que l'API
        // S3 standard et aucun consommateur ne branche sur cette valeur: le
        // magasin est interchangeable. Annoncer "RUSTFS" en dur donnerait un
        // manifeste faux des qu'une autre implementation S3 est deployee.
        manifest.put("provider", objectStorageProvider);
        manifest.put("bucket", stored.bucket());
        manifest.put("objectKey", stored.objectKey());
        manifest.put("sourceIndex", sourceIndex);
        manifest.put("sizeBytes", stored.sizeBytes());
        manifest.put("sha256", stored.sha256());
        manifest.put("format", format);
        manifest.put("fileName", fileName);
        return manifest;
    }

    private Map<String, Object> publishJdbcObjectReference(
            String workflowId,
            String execLogId,
            int sourceIndex,
            String protocol,
            Map<String, Object> config) {
        String fileName = "jdbc-source-" + sourceIndex + ".jsonl";
        ObjectStorageService.StoredObject stored = objectStorageService.storeStreaming(
                workflowId,
                execLogId,
                sourceIndex,
                fileName,
                contentType("JSON"),
                output -> streamJdbcRows(protocol, config,
                        (headers, values) -> output.write(canonicalJsonRow(headers, values))));
        return objectManifest(stored, sourceIndex, "JSON", fileName);
    }

    /** Les API Big Data sont streamees vers S3/RustFS sans fichier intermediaire. */
    private Map<String, Object> publishApiObjectReference(
            String workflowId,
            String execLogId,
            int sourceIndex,
            Map<String, Object> config) {
        String fileName = "api-source-" + sourceIndex + ".jsonl";
        ObjectStorageService.StoredObject stored = objectStorageService.storeStreaming(
                workflowId,
                execLogId,
                sourceIndex,
                fileName,
                contentType("JSON"),
                output -> apiSourceClient.streamRows(apiConfig(config), row -> {
                    byte[] json = objectMapper.writeValueAsBytes(row);
                    output.write(json);
                    output.write('\n');
                }));
        return objectManifest(stored, sourceIndex, "JSON", fileName);
    }

    private Map<String, Object> apiConfig(Map<String, Object> config) {
        Map<String, Object> nested = map(config.get("source_config"));
        return nested.isEmpty() ? config : nested;
    }

    private void streamJdbcRows(
            String protocol, Map<String, Object> config, JdbcRowConsumer consumer) throws Exception {
        // Un transport tient sa connexion source pendant toute l'extraction: le
        // permis borne le nombre de bases clientes sollicitees simultanement.
        sourceConnectionLimiter.withPermit("transport JDBC", () -> {
            streamJdbcRowsWithConnection(protocol, config, consumer);
            return null;
        });
    }

    private void streamJdbcRowsWithConnection(
            String protocol, Map<String, Object> config, JdbcRowConsumer consumer) throws Exception {
        String query = validatedJdbcQuery(config);
        JdbcConnectionInfo jdbc = jdbcInfo(protocol, config);
        registerDriver(protocol);
        try (Connection connection = DriverManager.getConnection(jdbc.url(), jdbc.username(), jdbc.password())) {
            try {
                connection.setReadOnly(true);
            } catch (Exception ignored) {
                // Certains pilotes ne permettent pas de modifier ce drapeau.
            }
            try (Statement statement = connection.createStatement(
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(jdbcFetchRows(config));
                statement.setQueryTimeout(jdbcQueryTimeoutSeconds(config));
                try (ResultSet resultSet = statement.executeQuery(query)) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    List<String> headers = uniqueHeaders(metadata);
                    while (resultSet.next()) {
                        List<Object> values = new ArrayList<>(headers.size());
                        for (int index = 1; index <= headers.size(); index++) {
                            values.add(jsonValue(resultSet.getObject(index)));
                        }
                        consumer.accept(headers, values);
                    }
                }
            }
        }
    }

    private String validatedJdbcQuery(Map<String, Object> config) {
        Map<String, Object> sourceConfig = map(config.get("source_config"));
        String query = string(sourceConfig.get("query"));
        if (query.isBlank()) query = string(config.get("query"));
        String normalized = query.replaceFirst(";\\s*$", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ((!lower.startsWith("select ") && !lower.startsWith("with "))
                || normalized.contains(";")) {
            throw new BadRequestException("La source JDBC doit contenir une seule requête SELECT/WITH.");
        }
        String incrementalColumn = string(config.get("incremental_column"));
        String watermark = string(config.get("last_watermark"));
        if (incrementalColumn.isBlank() || watermark.isBlank()) return normalized;
        if (!incrementalColumn.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new BadRequestException("Colonne incrémentale invalide: " + incrementalColumn);
        }
        if (!watermark.matches("[0-9TtZz+:. \\-]+")) {
            throw new BadRequestException("Watermark invalide.");
        }
        return "SELECT * FROM (" + normalized + ") _iol_incremental WHERE "
                + incrementalColumn + " > '" + watermark + "'";
    }

    private JdbcConnectionInfo jdbcInfo(String protocol, Map<String, Object> config) {
        Map<String, Object> sourceConfig = map(config.get("source_config"));
        Map<String, Object> connection = map(sourceConfig.get("source_connection"));
        String host = firstPresent(config, connection, "host");
        int port = parsePort(firstPresentValue(config, connection, "port"));
        String database = firstPresent(config, connection, "database");
        String username = firstPresent(config, connection, "username");
        String password = firstPresent(config, connection, "password");
        String rawUri = string(config.get("uri"));
        if (rawUri.startsWith("jdbc:")) {
            return new JdbcConnectionInfo(rawUri, username, password);
        }
        int effectivePort = port > 0 ? port : defaultPort(protocol);
        String url = switch (protocol) {
            case "POSTGRES" -> "jdbc:postgresql://" + host + ":" + effectivePort + "/" + database;
            case "MYSQL" -> "jdbc:mysql://" + host + ":" + effectivePort + "/" + database;
            case "MARIADB" -> "jdbc:mariadb://" + host + ":" + effectivePort + "/" + database;
            case "MSSQL" -> "jdbc:sqlserver://" + host + ":" + effectivePort
                    + ";databaseName=" + database + ";encrypt=false";
            case "ORACLE" -> "jdbc:oracle:thin:@//" + host + ":" + effectivePort + "/" + database;
            case "SQLITE" -> "jdbc:sqlite:" + database;
            case "SNOWFLAKE" -> "jdbc:snowflake://" + host + "/?db=" + database;
            case "REDSHIFT" -> "jdbc:redshift://" + host + ":" + effectivePort + "/" + database;
            default -> throw new BadRequestException("Protocole JDBC non supporté: " + protocol);
        };
        return new JdbcConnectionInfo(url, username, password);
    }

    private void registerDriver(String protocol) throws ClassNotFoundException {
        String className = switch (protocol) {
            case "POSTGRES" -> "org.postgresql.Driver";
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "MARIADB" -> "org.mariadb.jdbc.Driver";
            case "MSSQL" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "SQLITE" -> "org.sqlite.JDBC";
            case "SNOWFLAKE" -> "net.snowflake.client.jdbc.SnowflakeDriver";
            case "REDSHIFT" -> "com.amazon.redshift.jdbc42.Driver";
            default -> "";
        };
        if (!className.isBlank()) Class.forName(className);
    }

    private List<String> uniqueHeaders(ResultSetMetaData metadata) throws Exception {
        List<String> headers = new ArrayList<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String base = string(metadata.getColumnLabel(index));
            if (base.isBlank()) base = "column_" + index;
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            headers.add(occurrence == 1 ? base : base + "_" + occurrence);
        }
        return List.copyOf(headers);
    }

    private Object jsonValue(Object value) throws Exception {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Blob blob) {
            return Base64.getEncoder().encodeToString(blob.getBytes(1, Math.toIntExact(blob.length())));
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return value.toString();
    }

    private int jdbcFetchRows(Map<String, Object> config) {
        Map<String, Object> sourceConfig = map(config.get("source_config"));
        Object value = sourceConfig.containsKey("jdbc_chunk_rows")
                ? sourceConfig.get("jdbc_chunk_rows") : config.get("jdbc_chunk_rows");
        return Math.max(100, Math.min(parsePositiveInt(value, 5_000), 100_000));
    }

    private int jdbcQueryTimeoutSeconds(Map<String, Object> config) {
        Map<String, Object> sourceConfig = map(config.get("source_config"));
        Object value = sourceConfig.containsKey("query_timeout_seconds")
                ? sourceConfig.get("query_timeout_seconds") : config.get("query_timeout_seconds");
        return Math.max(1, Math.min(parsePositiveInt(value, 900), 3_600));
    }

    private PreparedData prepareSourceData(String protocol, Map<String, Object> config, String execLogId, int index) {
        String uploadId = string(config.get("upload_id"));
        Path path = !uploadId.isBlank()
                ? uploadedFileLocator.resolve(uploadId)
                : Path.of(firstNonBlank(config, "file_path", "uri")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new BadRequestException("Fichier source introuvable: " + path);
        }
        String format = inferFileFormat(protocol, path);
        return new PreparedData(path, format, path.getFileName().toString(), false);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private Map<String, Object> publishFileChunks(
            String topic, String key, String workflowId, String execLogId, int sourceIndex, PreparedData prepared) {
        KafkaTemplate<String, String> kafka = kafkaTemplateProvider.getIfAvailable();
        if (kafka == null) {
            throw new IllegalStateException("KafkaTemplate indisponible pour le transport des donnees.");
        }
        int size = Math.max(64 * 1024, Math.min(chunkBytes, 4 * 1024 * 1024));
        long totalBytes;
        try {
            totalBytes = Files.size(prepared.path());
        } catch (Exception e) {
            throw new BadRequestException("Impossible de lire la taille de la source: " + e.getMessage());
        }
        int chunkCount = Math.max(1, (int) Math.ceil((double) totalBytes / size));
        String transferId = UUID.randomUUID().toString();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        SendWindow window = new SendWindow(kafkaMaxInFlightBatches);
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(prepared.path()))) {
            byte[] buffer = new byte[size];
            int chunkIndex = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                byte[] payload = read == buffer.length ? buffer.clone() : java.util.Arrays.copyOf(buffer, read);
                digest.update(payload);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventType", EVENT_TYPE_SOURCE_DATA_CHUNK);
                event.put("workflowId", workflowId);
                event.put("execLogId", execLogId);
                event.put("transferId", transferId);
                event.put("sourceIndex", sourceIndex);
                event.put("chunkIndex", chunkIndex);
                event.put("chunkCount", chunkCount);
                event.put("format", prepared.format());
                event.put("fileName", prepared.fileName());
                event.put("payloadBase64", Base64.getEncoder().encodeToString(payload));
                window.send(kafka, topic, key, objectMapper.writeValueAsString(event));
                chunkIndex++;
            }
            if (totalBytes == 0) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventType", EVENT_TYPE_SOURCE_DATA_CHUNK);
                event.put("workflowId", workflowId);
                event.put("execLogId", execLogId);
                event.put("transferId", transferId);
                event.put("sourceIndex", sourceIndex);
                event.put("chunkIndex", 0);
                event.put("chunkCount", 1);
                event.put("format", prepared.format());
                event.put("fileName", prepared.fileName());
                event.put("payloadBase64", "");
                window.send(kafka, topic, key, objectMapper.writeValueAsString(event));
            }
            // Le manifeste ne doit decrire que des morceaux reellement acquittes.
            window.awaitAll();
        } catch (Exception e) {
            throw new BadRequestException("Publication des donnees Kafka impossible: " + e.getMessage());
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("transport", "KAFKA_CHUNKED");
        manifest.put("transferId", transferId);
        manifest.put("sourceIndex", sourceIndex);
        manifest.put("chunkCount", chunkCount);
        manifest.put("sizeBytes", totalBytes);
        manifest.put("sha256", HexFormat.of().formatHex(digest.digest()));
        manifest.put("format", prepared.format());
        manifest.put("fileName", prepared.fileName());
        return manifest;
    }

    private void rewriteSourceForTransport(
            Map<String, Object> source,
            Map<String, Object> config,
            String format,
            String fileName,
            Map<String, Object> manifest) {
        String original = string(source.get("source_name"));
        source.put("original_source_name", original);
        source.put("source_name", format);
        source.put("type", format);
        String transport = string(manifest.get("transport")).toUpperCase(Locale.ROOT);
        boolean objectStorage = "OBJECT_STORAGE".equals(transport);
        String transportUri = objectStorage
                ? "s3://" + manifest.get("bucket") + "/" + manifest.get("objectKey")
                : "kafka://" + manifest.get("transferId");
        config.put("file_path", transportUri);
        config.put("uri", transportUri);
        config.put("data_transport", transport.isBlank() ? "KAFKA_CHUNKED" : transport);
        config.put("encoding", "utf-8");
        config.put("transport_file_name", fileName);
        config.remove("upload_id");
        config.remove("transport_mode");
        config.remove("source_connection_id");
        config.remove("sourceConnectionId");
        config.remove("host");
        config.remove("port");
        config.remove("database");
        config.remove("username");
        config.remove("password");
        config.remove("source_db_type");
        config.remove("query");
        sanitizeSourceConfig(config);
        source.put("config", config);
    }

    private void sanitizeSourceConfig(Map<String, Object> config) {
        for (String key : List.of("source_config", "sourceConfig")) {
            Map<String, Object> sourceConfig = new LinkedHashMap<>(map(config.get(key)));
            if (sourceConfig.isEmpty()) {
                config.remove(key);
                continue;
            }
            for (String sensitive : List.of(
                    "source_connection", "sourceConnection", "source_connection_id", "sourceConnectionId",
                    "host", "port", "database", "username", "password", "query", "url", "uri",
                    "headers", "authorization", "token", "api_key", "apiKey", "secret")) {
                sourceConfig.remove(sensitive);
            }
            if (sourceConfig.isEmpty()) config.remove(key);
            else config.put(key, sourceConfig);
        }
    }

    private boolean isBigDataExecution(Map<String, Object> command) {
        if ("SPARK".equalsIgnoreCase(string(command.get("executionMode")))) return true;
        Map<String, Object> assessment = map(command.get("loadAssessment"));
        return Boolean.parseBoolean(string(assessment.get("distributedRecommended")));
    }

    private void deletePreparedData(PreparedData prepared) {
        if (prepared == null || !prepared.deleteAfterPublish()) return;
        try {
            Files.deleteIfExists(prepared.path());
        } catch (Exception error) {
            log.warn("Impossible de supprimer le fichier temporaire {}: {}",
                    prepared.path(), error.getMessage());
        }
    }

    private String contentType(String format) {
        return switch (format.toUpperCase(Locale.ROOT)) {
            case "CSV" -> "text/csv";
            case "JSON" -> "application/json";
            case "PARQUET" -> "application/vnd.apache.parquet";
            case "AVRO" -> "application/avro";
            default -> "application/octet-stream";
        };
    }

    private String inferFileFormat(String protocol, Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt")) return "CSV";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "EXCEL";
        if (lower.endsWith(".parquet")) return "PARQUET";
        if (lower.endsWith(".avro")) return "AVRO";
        if (lower.endsWith(".orc")) return "ORC";
        if (lower.endsWith(".json")) return "JSON";
        return protocol.isBlank() ? "CSV" : protocol;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : new LinkedHashMap<>();
    }

    private String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = string(map.get(key));
            if (!value.isBlank()) return value;
        }
        throw new BadRequestException("Chemin du fichier source manquant.");
    }

    private String firstPresent(
            Map<String, Object> primary, Map<String, Object> fallback, String key) {
        return string(firstPresentValue(primary, fallback, key));
    }

    private Object firstPresentValue(
            Map<String, Object> primary, Map<String, Object> fallback, String key) {
        Object value = primary.get(key);
        if (value != null && !string(value).isBlank()) return value;
        return fallback.get(key);
    }

    private int parsePort(Object value) {
        try {
            return Integer.parseInt(string(value));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int parsePositiveInt(Object value, int fallback) {
        try {
            int parsed = Integer.parseInt(string(value));
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int defaultPort(String protocol) {
        return switch (protocol) {
            case "POSTGRES" -> 5432;
            case "MYSQL", "MARIADB" -> 3306;
            case "MSSQL" -> 1433;
            case "ORACLE" -> 1521;
            case "REDSHIFT" -> 5439;
            default -> -1;
        };
    }

    private String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @FunctionalInterface
    private interface JdbcRowConsumer {
        void accept(List<String> headers, List<Object> values) throws Exception;
    }

    @FunctionalInterface
    private interface InboundRecordConsumer {
        void accept(Map<String, Object> record) throws Exception;
    }

    public record InboundStreamPublication(
            Map<String, Object> manifest,
            long recordCount) {
    }

    private static final class StreamAccumulator {
        private List<String> headers;
        private long rowCount;
        private long canonicalBytes;
        private int batchCount;
    }

    private record JdbcConnectionInfo(String url, String username, String password) { }
    private record PreparedData(Path path, String format, String fileName, boolean deleteAfterPublish) { }

    private static final class KafkaRowTooLargeException extends RuntimeException { }

    /**
     * Fenêtre d'envois Kafka en vol.
     *
     * Auparavant chaque lot était suivi d'un {@code get()} bloquant : un million
     * de lignes produisait deux mille allers-retours réseau strictement
     * sérialisés, sur le thread de transport. Ici les envois sont empilés et
     * seuls les plus anciens sont attendus quand la fenêtre est pleine, ce qui
     * laisse le producteur grouper et compresser les lots.
     *
     * L'ordre reste garanti : tous les lots d'un transfert partagent la clé
     * d'exécution donc la même partition, et le producteur est idempotent — un
     * renvoi après erreur réseau ne peut ni dupliquer ni réordonner.
     *
     * La fenêtre borne aussi la latence de détection d'erreur : un échec est
     * remonté après quelques dizaines de lots, pas à la fin du transfert.
     */
    private static final class SendWindow {
        private final int maxInFlight;
        private final java.util.ArrayDeque<java.util.concurrent.Future<?>> inFlight;

        SendWindow(int maxInFlight) {
            this.maxInFlight = Math.max(1, maxInFlight);
            this.inFlight = new java.util.ArrayDeque<>(this.maxInFlight);
        }

        void send(KafkaTemplate<String, String> kafka, String topic, String key, String payload)
                throws Exception {
            inFlight.addLast(kafka.send(topic, key, payload));
            while (inFlight.size() > maxInFlight) {
                inFlight.pollFirst().get(60, TimeUnit.SECONDS);
            }
        }

        /** Draine la fenêtre. Toute erreur d'un envoi est remontée ici. */
        void awaitAll() throws Exception {
            java.util.concurrent.Future<?> pending;
            while ((pending = inFlight.pollFirst()) != null) {
                pending.get(60, TimeUnit.SECONDS);
            }
        }
    }
}
