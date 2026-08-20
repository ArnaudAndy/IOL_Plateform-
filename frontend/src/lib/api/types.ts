// ============================================================
// IOL ETL Platform — Types miroir du backend
// Aucun champ inventé : tout vient du contrat d'API fourni.
// ============================================================

// ----- Auth -----
export interface AuthLoginRequest { email: string; password: string }
export interface AuthRegisterRequest { name: string; email: string; password: string }
export interface AuthCreateInitialAdminRequest { name: string; email: string; password: string }
export interface ForgotPasswordRequest { email: string }
export interface ResetPasswordRequest { email: string; code: string; newPassword: string }
export interface UpdateProfileRequest { name: string; email: string }
export interface ChangePasswordRequest { currentPassword: string; newPassword: string }
export interface AuthResponse { token: string; user: UserDto }

export type UserRole = 'ADMIN' | 'USER'

export interface UserDto {
  id: string
  email: string
  name: string
  role: UserRole
  active: boolean
}

// ----- Enveloppe -----
export interface ApiResponse<T> {
  data: T
  success?: boolean
  message?: string
  timestamp?: string
  statusCode?: number
}

// ----- Workflows -----
export type WorkflowDirection = 'INTERNAL' | 'INBOUND' | 'OUTBOUND'
export type WorkflowLoadMode = 'FULL' | 'INCREMENTAL'
export type WorkflowWriteMode = 'append' | 'replace'

export interface WorkflowSchedule {
  enabled: boolean
  cron?: string
  frequency?: string
  time?: string
  loadMode?: WorkflowLoadMode
  incrementalColumn?: string
  [k: string]: unknown
}

export interface StageIndexConfig {
  name?: string
  columns: string[]
  unique?: boolean
}

export interface SilverConfig {
  enabled?: boolean
  target_table_silver?: string
  elt_scripts_silver?: string
  execution_engine?: 'SQL' | 'SPARK'
  spark_sql?: string
  pre_sql?: string
  post_sql?: string
  indexes?: StageIndexConfig[]
}

export interface GoldConfigGlobal {
  enabled?: boolean
  input_layer?: 'SILVER' | 'BRONZE'
  target_table_gold?: string
  elt_scripts_gold?: string
  execution_engine?: 'SQL' | 'SPARK'
  spark_sql?: string
  pre_sql?: string
  post_sql?: string
  indexes?: StageIndexConfig[]
}

export interface WorkflowOutboundConfig {
  targetStandardId?: string
  targetSystem?: string
  targetAdapter?: string
  source?: {
    goldTable?: string
    query?: string
    [k: string]: unknown
  }
  destination?: {
    openhimChannel?: string
    endpointUrl?: string
    auth?: string | OutboundAuthConfig
    [k: string]: unknown
  }
  [k: string]: unknown
}

export interface OutboundAuthConfig {
  type: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY' | 'OAUTH2_CLIENT_CREDENTIALS'
  secretRef?: string
  usernameRef?: string
  passwordRef?: string
  clientIdRef?: string
  clientSecretRef?: string
  tokenUrl?: string
  scope?: string
  header?: string
  prefix?: string
}

export interface UploadedFileDto {
  uploadId: string
  originalName: string
  contentType?: string
  sizeBytes: number
  sha256: string
  storagePath: string
}

export interface SourceField {
  name: string
  originalName?: string
  type?: string
  selected?: boolean
  semanticTerm?: string
  alias?: string
}

export interface FieldMappingWire {
  sourceName?: string
  source_name?: string
  type?: string
  iolTerm?: string
  iol_term?: string
  cleaningRules?: Record<string, unknown>
  cleaning_rules?: Record<string, unknown>
  mappingType?: string
  mapping_type?: string
  sourceFields?: string[]
  source_fields?: string[]
  expression?: string
}

export interface WorkflowSourceConfigWire {
  source_connection_id?: string
  upload_id?: string
  original_file_name?: string
  file_path?: string
  uri?: string
  target_table?: string
  target_connection?: Record<string, unknown>
  source_config?: Record<string, unknown>
  load_mode?: WorkflowLoadMode
  incremental_column?: string
  last_watermark?: string
  jdbc_partitioning_enabled?: boolean
  partition_column?: string
  partition_type?: 'NUMERIC' | 'DATE'
  partition_lower_bound?: string
  partition_upper_bound?: string
  partition_count?: number
  partition_parallelism?: number
  jdbc_fetch_size?: number
  spark_write_partitions?: number
  bulk_load_strategy?: 'AUTO' | 'POSTGRES_COPY' | 'MULTI' | 'INSERT_BATCH'
  transport_mode?: 'AUTO' | 'KAFKA_ROW_BATCH' | 'OBJECT_STORAGE' | 'KAFKA_CHUNKED'
  write_mode?: WorkflowWriteMode
  fields?: string[]
  silver_config?: SilverConfig
  [k: string]: unknown
}

export interface WorkflowSourceWire {
  source_name: string
  config: WorkflowSourceConfigWire
}

export interface WorkflowConfigWire {
  id?: string
  workflowName: string
  description?: string
  protocol: string
  standardDomain?: string
  standardId?: string
  direction?: WorkflowDirection
  outboundConfig?: WorkflowOutboundConfig
  metadataFileName?: string
  metadataJson?: string
  metadataVersion?: string
  schedule?: WorkflowSchedule
  sources?: WorkflowSourceWire[]
  gold_config_global?: GoldConfigGlobal
  destinationConnectionId?: string
  sourceConfig?: Record<string, unknown>
  fields?: FieldMappingWire[]
  aggregationScripts?: string
  executionMode?: 'LOCAL' | 'SPARK'
  priority?: number
  estimatedRows?: number
  isActive?: boolean
  active?: boolean
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface WorkflowSourceUi {
  id?: string
  source_name: string
  file_path?: string
  upload_id?: string
  uri?: string
  target_table?: string
  sourceConnectionId?: string
  connectionId?: string
  connectionName?: string
  connectionDetails?: Record<string, unknown>
  source_config?: {
    delimiter?: string
    encoding?: string
    query?: string
    [k: string]: unknown
  }
  load_mode?: WorkflowLoadMode
  incremental_column?: string
  last_watermark?: string
  jdbc_partitioning_enabled?: boolean
  partition_column?: string
  partition_type?: 'NUMERIC' | 'DATE'
  partition_lower_bound?: string
  partition_upper_bound?: string
  partition_count?: number
  partition_parallelism?: number
  jdbc_fetch_size?: number
  spark_write_partitions?: number
  bulk_load_strategy?: 'AUTO' | 'POSTGRES_COPY' | 'MULTI' | 'INSERT_BATCH'
  transport_mode?: 'AUTO' | 'KAFKA_ROW_BATCH' | 'OBJECT_STORAGE' | 'KAFKA_CHUNKED'
  write_mode?: WorkflowWriteMode
  fields?: SourceField[]
  silver_config?: SilverConfig
  config?: WorkflowSourceConfigWire
}

export interface WorkflowConfigUi {
  id?: string
  name: string
  workflowName?: string
  description?: string
  direction: WorkflowDirection
  outboundConfig?: WorkflowOutboundConfig
  protocol?: string
  standardId?: string
  standardDomain?: string // déprécié
  destinationConnectionId?: string
  priority?: number
  executionMode?: 'LOCAL' | 'SPARK'
  estimatedRows?: number
  schedule: WorkflowSchedule
  sources: WorkflowSourceUi[]
  goldConfigGlobal?: GoldConfigGlobal
  gold_config_global?: GoldConfigGlobal
  aggregationScripts?: string
  metadataFileName?: string
  metadataJson?: string
  metadataVersion?: string
  sourceConfig?: Record<string, unknown>
  fields?: FieldMappingWire[]
  status?: string
  isActive?: boolean
  active?: boolean
  createdAt?: string
  updatedAt?: string
  createdBy?: string
}

export interface WorkflowTemplateDto {
  id: string
  name: string
  description?: string
  category: string
  version: string
  workflow: WorkflowConfigUi
}

// Backward-compatible source alias for existing view code.
export type WorkflowSource = WorkflowSourceUi

// Backend DTO shape. UI code should prefer WorkflowConfigUi at the form boundary.
export type WorkflowConfigDto = WorkflowConfigWire

// ----- Schema discovery -----
export interface DiscoveredColumn {
  name: string
  originalName?: string
  type: string
  nullable?: boolean
  size?: number
}

export interface SchemaDiscoveryResponse {
  columns: DiscoveredColumn[]
  source?: string
}

// ----- Execution logs -----
// Miroir exact de l'enum backend ExecutionStatus. PENDING et CANCELLED ont ete
// retires : aucun code serveur ne les produit, ils n'apparaissaient donc jamais.
export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'DELIVERED'

export interface SourceMetric {
  sourceName: string
  rowsRead?: number
  rowsWritten?: number
  watermark?: string
  lastWatermark?: string
  durationMs?: number
  status?: string
  errorMessage?: string
}

export interface ExecutionLogDto {
  id: string
  workflowId?: string
  execLogId?: string
  status: ExecutionStatus
  startTime: string
  endTime?: string
  currentStage?: string
  lastHeartbeatAt?: string
  durationMs?: number
  direction?: WorkflowDirection
  triggeredBy?: string
  correlationId?: string
  logOutput?: string
  detailedLogs?: string
  errorMessage?: string
  failedStage?: string
  lastSuccessfulWatermarks?: Record<string, string>
  sourceMetrics?: SourceMetric[]
  stageStatuses?: Record<string, 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'NOT_RUN' | string>
  workflowName?: string
}

export interface PerformanceMetrics {
  workflowId?: string
  totalExecutions?: number
  successCount?: number
  failureCount?: number
  averageDurationMs?: number
  lastExecution?: ExecutionLogDto
  successRate?: number
}

export interface InteropSummary {
  totalInbound: number
  running: number
  success: number
  failed: number
  dlqCount?: number
  last24h?: number
}

// ----- Standards -----
export type StandardDomain =
  | 'HEALTH'
  | 'FINANCE'
  | 'EDUCATION'
  | 'RETAIL'
  | 'LOGISTICS'
  | 'COMPLIANCE'
  | 'CUSTOM'

export type StandardStatus = 'DRAFT' | 'ACTIVE' | 'DEPRECATED'

export interface StandardTermDto {
  id: string
  standardId: string
  termName: string
  description?: string
  dataType: string
  required: boolean
  formatRule?: string
  format?: string
  minLength?: number
  maxLength?: number
  enumValues?: string[]
  precision?: number
  scale?: number
  systemMappings?: Record<string, string>
  exampleValue?: string
  notes?: string
  cleaningRules?: string
  createdAt?: string
  updatedAt?: string
}

export interface StandardDto {
  id: string
  domain: StandardDomain
  name: string
  status: StandardStatus
  version?: string
  description?: string
  termCount?: number
  referenceUrl?: string
  createdBy?: string
  terms?: StandardTermDto[]
  createdAt?: string
  updatedAt?: string
}

export interface ValidationResult {
  valid: boolean
  errors?: string[]
  termName?: string
  value?: string
  message?: string
}

// ----- Connections -----
export interface DestinationConnectionDto {
  id?: string
  name: string
  dbType: string
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string // write-only
  schema?: string
  additionalProperties?: Record<string, unknown>
}

export interface ConnectionTestResult {
  success: boolean
  message: string
  latencyMs?: number
}

// ----- AI Assistant -----
export interface ChatContextRequest {
  message: string
  workflowId?: string
  page?: string
}

export interface ChatContextResponse {
  reply: string
  suggestions?: string[]
  sql?: string
}

export interface GenerateSqlRequest {
  prompt: string
  workflowId?: string
  sourceName?: string
  standardId?: string
  targetTable?: string
  columns?: string[]
}

export interface GenerateSqlResponse {
  sql: string
  explanation?: string
  warnings?: string[]
}

export interface AiStatus {
  configured: boolean
  strategy: 'ROUND_ROBIN_FAILOVER'
  privacyMode: 'SCHEMA_ONLY'
  providers: Record<string, { configured: boolean; model: string }>
}

export interface SchemaOnlySqlRequest {
  instruction: string
  columns: string[]
  sourceTable?: string
  sourceTables?: string[]
  targetTable?: string
  workflowId?: string
  destinationConnectionId?: string
  databaseType?: string
  generationType?: 'SELECT' | 'CLEANING' | 'AGGREGATION' | 'MAPPING' | 'CUSTOM'
}

export interface GenerateAggregationSqlRequest {
  targetTableGold?: string
  sourceTables?: string[]
  instructions?: string
  columns?: string[]
}

export interface GenerateCleaningSqlRequest {
  workflowId?: string
  destinationConnectionId?: string
  sourceTable?: string
  targetTable?: string
  standardId?: string
  columns?: string[]
  instructions?: string
}

// ----- SQL Workbench -----
export interface ValidateSqlRequest { sql: string; workflowId?: string }
export interface ValidateSqlResponse {
  valid: boolean
  message?: string
  errors?: string[]
  warnings?: string[]
  operations?: string[]
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH'
}

export interface ExecuteSqlRequest { sql: string; workflowId?: string; connectionId?: string; limit?: number }
export interface ExecuteSqlResponse {
  success: boolean
  columns?: string[]
  rows?: Record<string, unknown>[]
  rowCount?: number
  executionTimeMs?: number
  error?: string
}

// ----- Audit -----
export interface AuditLog {
  id: string
  timestamp: string
  userId?: string
  userRole?: string
  action: string
  resourceType: string
  resourceId?: string
  status: 'SUCCESS' | 'FAILURE' | 'PARTIAL'
  description?: string
  oldValues?: Record<string, unknown>
  newValues?: Record<string, unknown>
  errorMessage?: string
  ipAddress?: string
  userAgent?: string
  durationMs?: number
}

// ----- Generic -----
export type ApiError = {
  status: number
  message: string
  details?: unknown
}
