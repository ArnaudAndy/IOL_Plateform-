// ============================================================
// IOL ETL Platform — Couche service par domaine
// Regroupe les appels API par contrôleur backend.
// `raw: true` pour StandardController et AuditController
// (pas d'enveloppe ApiResponse).
// ============================================================

import { api } from './client'
import { fromBackendWorkflow, toBackendDiscoverySource, toBackendWorkflow } from './mappers'
import type {
  AuthLoginRequest, AuthRegisterRequest, AuthCreateInitialAdminRequest, AuthResponse,
  ForgotPasswordRequest, ResetPasswordRequest, UpdateProfileRequest, ChangePasswordRequest,
  UserDto,
  DiscoveredColumn, WorkflowConfigUi, WorkflowConfigWire, SchemaDiscoveryResponse, WorkflowSourceUi,
  ExecutionLogDto, PerformanceMetrics, SourceMetric, InteropSummary,
  StandardDto, StandardTermDto, ValidationResult,
  DestinationConnectionDto, ConnectionTestResult,
  ChatContextRequest, ChatContextResponse, GenerateSqlRequest, GenerateSqlResponse,
  GenerateAggregationSqlRequest, GenerateCleaningSqlRequest, AiStatus, SchemaOnlySqlRequest,
  ValidateSqlRequest, ValidateSqlResponse, ExecuteSqlRequest, ExecuteSqlResponse,
  AuditLog,
  UploadedFileDto,
  WorkflowTemplateDto,
} from './types'

// ---------- Auth (/api/auth) ----------
// Le backend renvoie une réponse PLATE : { token, userId, name, email, role }.
// Le front attend { token, user: UserDto }. On normalise ici (couche service),
// sans rien inventer : `active` n'est pas fourni au login -> true (session valide).
interface BackendAuthResponse {
  token: string
  userId: string
  name: string
  email: string
  role: UserDto['role']
}

function normalizeAuth(raw: BackendAuthResponse): AuthResponse {
  return {
    token: raw.token,
    user: {
      id: raw.userId,
      email: raw.email,
      name: raw.name,
      role: raw.role,
      active: true,
    },
  }
}

export const authService = {
  login: async (body: AuthLoginRequest) =>
    normalizeAuth(await api.post<BackendAuthResponse>('/auth/login', body)),
  register: async (body: AuthRegisterRequest) =>
    normalizeAuth(await api.post<BackendAuthResponse>('/auth/register', body)),
  createInitialAdmin: async (body: AuthCreateInitialAdminRequest) =>
    normalizeAuth(await api.post<BackendAuthResponse>('/auth/create-initial-admin', body)),
  forgotPassword: (body: ForgotPasswordRequest) => api.post<string>('/auth/password/forgot', body),
  resetPassword: (body: ResetPasswordRequest) => api.post<string>('/auth/password/reset', body),
  updateProfile: async (body: UpdateProfileRequest) =>
    normalizeAuth(await api.put<BackendAuthResponse>('/auth/profile', body)),
  changePassword: async (body: ChangePasswordRequest) =>
    normalizeAuth(await api.put<BackendAuthResponse>('/auth/password', body)),
}

export const fileService = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<UploadedFileDto>('/files/upload', form)
  },
}

// ---------- Workflows (/api/workflows) ----------
export const workflowService = {
  list: async () => (await api.get<WorkflowConfigWire[]>('/workflows')).map(fromBackendWorkflow),
  get: async (id: string) => fromBackendWorkflow(await api.get<WorkflowConfigWire>(`/workflows/${id}`)),
  create: async (body: WorkflowConfigUi) =>
    fromBackendWorkflow(await api.post<WorkflowConfigWire>('/workflows', toBackendWorkflow(body))),
  update: async (id: string, body: WorkflowConfigUi) =>
    fromBackendWorkflow(await api.put<WorkflowConfigWire>(`/workflows/${id}`, toBackendWorkflow(body))),
  remove: (id: string) => api.del<void>(`/workflows/${id}`),
  discoverExisting: (id: string) => api.get<SchemaDiscoveryResponse>(`/workflows/${id}/discover`),
  discoverSource: async (sourceConfig: WorkflowSourceUi) => {
    const columns = await api.post<DiscoveredColumn[]>('/workflows/discover', toBackendDiscoverySource(sourceConfig))
    return { columns }
  },
  saveDraft: (body: WorkflowConfigUi) => api.post<void>('/workflows/draft', toBackendWorkflow(body)),
  execute: (workflowId: string) =>
    api.post<ExecutionLogDto>('/workflows/execute', { workflowId }),
  export: (id: string) => api.getRaw<string>(`/workflows/${id}/export`),
}

interface WorkflowTemplateWire extends Omit<WorkflowTemplateDto, 'workflow'> {
  workflow: WorkflowConfigWire
}

export const workflowTemplateService = {
  list: async () => {
    const templates = await api.get<WorkflowTemplateWire[]>('/workflow-templates')
    return templates.map((template) => ({
      ...template,
      workflow: fromBackendWorkflow(template.workflow),
    }))
  },
}

// ---------- Orchestrator (/api/orchestrator) ----------
export const orchestratorService = {
  run: (id: string) => api.post<ExecutionLogDto>(`/orchestrator/run/${id}`),
}

// ---------- Logs (/api/logs) ----------
export const logsService = {
  all: () => api.get<ExecutionLogDto[]>('/logs'),
  byWorkflow: (workflowId: string) => api.get<ExecutionLogDto[]>(`/logs/${workflowId}`),
  details: (executionId: string) => api.get<ExecutionLogDto>(`/logs/details/${executionId}`),
  performance: (workflowId: string) => api.get<PerformanceMetrics>(`/logs/performance/${workflowId}`),
  sources: (executionId: string) => api.get<SourceMetric[]>(`/logs/sources/${executionId}`),
  interop: () => api.get<ExecutionLogDto[]>('/logs/interop'),
  interopSummary: () => api.get<InteropSummary>('/logs/interop/summary'),
  interopCorrelation: (correlationId: string) =>
    api.get<ExecutionLogDto[]>(`/logs/interop/correlation/${correlationId}`),
  // Suppression d'une execution passee. Le backend refuse celles encore en
  // cours (RUNNING) : l'appelant doit remonter l'erreur telle quelle.
  remove: (executionId: string) => api.del<void>(`/logs/${executionId}`),
}

export const interopTestService = {
  send: async (request: {
    standardId: string
    workflowId: string
    sourceSystem: string
    correlationId: string
    profile: 'fhir' | 'iso20022' | 'edfi' | 'generic'
    format: 'json' | 'xml' | 'ndjson'
    payload: string
  }) => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 120_000)
    try {
      const endpoint = {
        fhir: '/interop/fhir',
        iso20022: '/interop/iso20022',
        edfi: '/interop/edfi/students',
        generic: '/interop/frontend-test',
      }[request.profile]
      const contentType = request.format === 'ndjson'
        ? 'application/x-ndjson'
        : request.format === 'xml'
          ? request.profile === 'fhir' ? 'application/fhir+xml' : 'application/xml'
          : request.profile === 'fhir' ? 'application/fhir+json' : 'application/json'
      const response = await fetch(endpoint, {
        method: 'POST',
        credentials: 'include',
        signal: controller.signal,
        headers: {
          'Content-Type': contentType,
          Accept: 'application/json',
          'X-IOL-Standard-ID': request.standardId,
          'X-IOL-Workflow-ID': request.workflowId,
          'X-IOL-Source-System': request.sourceSystem,
          'X-Correlation-ID': request.correlationId,
          'Idempotency-Key': request.correlationId,
        },
        body: request.payload,
      })
      const text = await response.text()
      let body: unknown = text
      try { body = JSON.parse(text) } catch { /* OpenHIM can return a text body. */ }
      const openHimStatus = typeof body === 'object' && body !== null && 'response' in body
        ? Number((body as { response?: { status?: number } }).response?.status)
        : undefined
      if (!response.ok || (Number.isFinite(openHimStatus) && openHimStatus! >= 400)) {
        const status = Number.isFinite(openHimStatus) ? openHimStatus : response.status
        throw new Error(`OpenHIM ${status}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
      }
      return { status: response.status, correlationId: request.correlationId, body }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new Error('OpenHIM ne répond pas après 120 secondes.')
      }
      throw error
    } finally {
      window.clearTimeout(timeout)
    }
  },
}

// ---------- Standards (/api/v1/standards) — RAW ----------
export const standardService = {
  create: (body: Partial<StandardDto>) => api.postRaw<StandardDto>('/v1/standards', body),
  list: () => api.getRaw<StandardDto[]>('/v1/standards'),
  get: (id: string) => api.getRaw<StandardDto>(`/v1/standards/${id}`),
  byDomain: (domain: string) => api.getRaw<StandardDto[]>(`/v1/standards/domain/${domain}`),
  update: (id: string, body: Partial<StandardDto>) => api.putRaw<StandardDto>(`/v1/standards/${id}`, body),
  activate: (id: string) => api.postRaw<StandardDto>(`/v1/standards/${id}/activate`),
  deprecate: (id: string) => api.postRaw<StandardDto>(`/v1/standards/${id}/deprecate`),
  addTerm: (standardId: string, body: Partial<StandardTermDto>) =>
    api.postRaw<StandardTermDto>(`/v1/standards/${standardId}/terms`, body),
  listTerms: (standardId: string) => api.getRaw<StandardTermDto[]>(`/v1/standards/${standardId}/terms`),
  updateTerm: (termId: string, body: Partial<StandardTermDto>) =>
    api.putRaw<StandardTermDto>(`/v1/standards/terms/${termId}`, body),
  validate: (
    standardId: string,
    body: { fieldName?: string; termName?: string; fieldValue?: unknown; value?: unknown; dataType?: string },
  ) =>
    api.postRaw<ValidationResult>(`/v1/standards/${standardId}/validate`, undefined, {
      params: {
        fieldName: body.fieldName || body.termName,
        fieldValue: String(body.fieldValue ?? body.value ?? ''),
        dataType: body.dataType || 'STRING',
      },
    }),
}

// ---------- Connections (/api/connections) ----------
export const connectionService = {
  create: (body: DestinationConnectionDto) =>
    api.post<DestinationConnectionDto>('/connections', body),
  list: () => api.get<DestinationConnectionDto[]>('/connections'),
  get: (id: string) => api.get<DestinationConnectionDto>(`/connections/${id}`),
  update: (id: string, body: DestinationConnectionDto) =>
    api.put<DestinationConnectionDto>(`/connections/${id}`, body),
  remove: (id: string) => api.del<void>(`/connections/${id}`),
  test: async (id: string) => {
    const startedAt = performance.now()
    const response = await api.post<string | ConnectionTestResult>(`/connections/${id}/test`)
    if (typeof response === 'string') {
      return {
        success: true,
        message: response,
        latencyMs: Math.round(performance.now() - startedAt),
      }
    }
    return response
  },
}

// ---------- AI Assistant (/api/ai) ----------
async function generateSchemaSql(body: SchemaOnlySqlRequest): Promise<GenerateSqlResponse> {
  const response = await api.post<{ generatedSql: string }>('/ai/generate-schema-sql', body)
  return { sql: response.generatedSql }
}

export const aiService = {
  status: () => api.get<AiStatus>('/ai/status'),
  chatContext: (body: ChatContextRequest) => api.post<ChatContextResponse>('/ai/chat-context', body),
  generateSql: (body: GenerateSqlRequest) => api.post<GenerateSqlResponse>('/ai/generate-sql', body),
  generateSchemaSql,
  generateContextualSql: (body: GenerateSqlRequest) => generateSchemaSql({
    instruction: body.prompt,
    columns: body.columns || [],
    targetTable: body.targetTable,
    workflowId: body.workflowId,
    generationType: 'CUSTOM',
  }),
  generateAggregationSql: (workflowId: string, _standardId: string, body: GenerateAggregationSqlRequest) =>
    generateSchemaSql({
      instruction: body.instructions || 'Consolider les colonnes dans le resultat final.',
      columns: body.columns || [],
      sourceTables: body.sourceTables,
      targetTable: body.targetTableGold,
      workflowId,
      generationType: 'AGGREGATION',
    }),
  generateCleaningSql: (body: GenerateCleaningSqlRequest) => generateSchemaSql({
    instruction: body.instructions || 'Nettoyer et normaliser les colonnes fournies.',
    columns: body.columns || [],
    sourceTable: body.sourceTable,
    targetTable: body.targetTable,
    workflowId: body.workflowId,
    destinationConnectionId: body.destinationConnectionId,
    generationType: 'CLEANING',
  }),
}

// ---------- SQL Workbench (/api/sql) ----------
export const sqlService = {
  validate: (body: ValidateSqlRequest) => api.post<ValidateSqlResponse>('/sql/validate', body),
  execute: (body: ExecuteSqlRequest) => api.post<ExecuteSqlResponse>('/sql/execute', body),
}

// ---------- Users (/api/users) — ADMIN ----------
export const userService = {
  list: () => api.get<UserDto[]>('/users'),
  get: (id: string) => api.get<UserDto>(`/users/${id}`),
  getByEmail: (email: string) => api.get<UserDto>(`/users/email/${email}`),
  updateRole: (id: string, role: 'ADMIN' | 'USER') => api.put<UserDto>(`/users/${id}/role`, { role }),
  remove: (id: string) => api.del<void>(`/users/${id}`),
}

// ---------- Audit (/api/v1/audit) — RAW, ADMIN ----------
export const auditService = {
  all: () => api.getRaw<AuditLog[]>('/v1/audit'),
  byResource: (resourceType: string, resourceId: string) =>
    api.getRaw<AuditLog[]>(`/v1/audit/resource/${resourceType}/${resourceId}`),
  byUser: (userId: string) => api.getRaw<AuditLog[]>(`/v1/audit/user/${userId}`),
  failed: () => api.getRaw<AuditLog[]>('/v1/audit/failed'),
}
