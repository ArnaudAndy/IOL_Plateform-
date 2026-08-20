
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import {
  ChevronRight, ChevronLeft, ChevronDown, Plus, Trash2, RefreshCw, Search,
  Database, Sparkles, Wand2, CheckCircle2, AlertCircle, Save, Play, Bot, Loader2, Copy, Download, Upload,
  FileSpreadsheet, Server, ArrowRight, Globe2, Settings2, LayoutTemplate,
} from 'lucide-react'
import {
  workflowService, workflowTemplateService, standardService, connectionService,
  aiService, sqlService, fileService,
} from '@/lib/api/services'
import { describeError, ApiRequestError } from '@/lib/api/client'
import { toBackendWorkflow } from '@/lib/api/mappers'
import { PageHeader, LoadingState, ErrorState, EmptyState, InlineError } from '@/components/common/states'
import { DataTable, THead, TBody, Th, Tr, Td } from '@/components/common/data-table'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Checkbox } from '@/components/ui/checkbox'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Badge } from '@/components/ui/badge'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useNavStore } from '@/stores/nav-store'
import { useAuthStore } from '@/stores/auth-store'
import { useToast } from '@/hooks/use-toast'
import { directionLabel, loadModeLabel, writeModeLabel, TECH_LABELS } from '@/lib/i18n'
import type {
  WorkflowConfigUi, WorkflowSourceUi, SourceField, WorkflowDirection, WorkflowOutboundConfig,
  OutboundAuthConfig, DestinationConnectionDto, StageIndexConfig,
  WorkflowTemplateDto, StandardDto,
} from '@/lib/api/types'

type BuilderStep = 'general' | 'sources' | 'fields' | 'silver' | 'gold' | 'review'

const STEPS: { id: BuilderStep; labelKey: string }[] = [
  { id: 'general', labelKey: 'builder.steps.general' },
  { id: 'sources', labelKey: 'builder.steps.sources' },
  { id: 'fields', labelKey: 'builder.steps.fields' },
  { id: 'silver', labelKey: 'builder.steps.silver' },
  { id: 'gold', labelKey: 'builder.steps.gold' },
  { id: 'review', labelKey: 'builder.steps.review' },
]

const FILE_PROTOCOLS = ['CSV', 'EXCEL', 'PARQUET', 'AVRO', 'ORC'] as const
const DATABASE_PROTOCOLS = ['POSTGRES', 'MYSQL', 'MARIADB', 'MSSQL', 'ORACLE', 'SQLITE', 'SNOWFLAKE', 'REDSHIFT'] as const
const DESTINATION_PROTOCOLS = DATABASE_PROTOCOLS

function isFileProtocol(protocol?: string) {
  return FILE_PROTOCOLS.includes((protocol || '').toUpperCase() as typeof FILE_PROTOCOLS[number])
}

function isApiProtocol(protocol?: string) {
  return (protocol || '').toUpperCase() === 'API'
}

function recordValue(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function normalizeConnectionType(type?: string) {
  const normalized = (type || '').trim().toUpperCase().replace('-', '_')
  if (normalized === 'POSTGRESQL' || normalized === 'PG') return 'POSTGRES'
  if (normalized === 'SQLSERVER' || normalized === 'SQL_SERVER') return 'MSSQL'
  if (normalized === 'MARIA_DB') return 'MARIADB'
  return normalized
}

function outboundAdapterFor(standard?: StandardDto) {
  const identity = `${standard?.id ?? ''} ${standard?.name ?? ''}`.toLowerCase()
  return identity.includes('fhir') ? 'fhir' : 'generic-json'
}

function protocolFromFileName(name: string) {
  const lower = name.toLowerCase()
  if (/\.xlsx?$/.test(lower)) return 'EXCEL'
  if (lower.endsWith('.parquet')) return 'PARQUET'
  if (lower.endsWith('.avro')) return 'AVRO'
  if (lower.endsWith('.orc')) return 'ORC'
  return 'CSV'
}

function emptyWorkflow(): WorkflowConfigUi {
  return {
    name: '',
    description: '',
    direction: 'INTERNAL',
    protocol: 'CSV',
    standardId: undefined,
    priority: 5,
    schedule: { enabled: false, cron: '', frequency: 'DAILY', time: '00:00' },
    sources: [emptySource()],
    goldConfigGlobal: {
      enabled: true,
      input_layer: 'SILVER',
      execution_engine: 'SQL',
      target_table_gold: '',
      elt_scripts_gold: '',
    },
  }
}

function emptySource(): WorkflowSourceUi {
  return {
    source_name: 'CSV',
    file_path: '',
    target_table: '',
    load_mode: 'FULL',
    incremental_column: '',
    jdbc_partitioning_enabled: false,
    partition_type: 'NUMERIC',
    partition_count: 4,
    partition_parallelism: 4,
    jdbc_fetch_size: 1000,
    spark_write_partitions: 4,
    bulk_load_strategy: 'AUTO',
    transport_mode: 'AUTO',
    write_mode: 'append',
    fields: [],
    silver_config: {
      enabled: true,
      execution_engine: 'SQL',
      target_table_silver: '',
      elt_scripts_silver: '',
    },
    source_config: { delimiter: ',', encoding: 'UTF-8' },
  }
}

function validateWorkflowForExecution(
  workflow: WorkflowConfigUi,
  connections: DestinationConnectionDto[],
) {
  if (!workflow.name.trim()) return 'Le nom du workflow est obligatoire.'
  if (!workflow.destinationConnectionId) return 'Choisissez une connexion de destination.'

  const destination = connections.find((connection) => connection.id === workflow.destinationConnectionId)
  if (!destination) return 'La connexion de destination sélectionnée est introuvable.'
  if (!DESTINATION_PROTOCOLS.includes(normalizeConnectionType(destination.dbType) as typeof DESTINATION_PROTOCOLS[number])) {
    return 'Le type de destination sélectionné n’est pas supporté.'
  }

  if (!workflow.sources.length) return 'Ajoutez au moins une source.'
  for (let index = 0; index < workflow.sources.length; index += 1) {
    const source = workflow.sources[index]
    const label = `Source ${index + 1}`
    if (!source.target_table?.trim()) return `${label} : renseignez la table Bronze de destination.`
    if (source.silver_config?.enabled !== false) {
      if (!source.silver_config?.target_table_silver?.trim()) return `${label} : renseignez la table cible Silver.`
      if (source.silver_config?.execution_engine === 'SPARK') {
        if (!source.silver_config?.spark_sql?.trim()) return `${label} : renseignez la requête distribuée Silver.`
      } else if (!source.silver_config?.elt_scripts_silver?.trim()) {
        return `${label} : renseignez le SQL Silver.`
      }
    }
    if (isFileProtocol(source.source_name)) {
      if (!source.upload_id && !source.file_path) return `${label} : chargez un fichier.`
      continue
    }
    if (isApiProtocol(source.source_name)) {
      if (!String(source.source_config?.url || '').match(/^https?:\/\//i)) return `${label} : renseignez une URL API HTTP/HTTPS.`
      continue
    }
    if (!source.sourceConnectionId && !source.uri) return `${label} : choisissez une connexion source.`
    if (!String(source.source_config?.query || '').trim()) return `${label} : renseignez la requête SELECT à extraire.`
    if (source.jdbc_partitioning_enabled) {
      if (!source.partition_column?.trim()) return `${label} : choisissez la colonne de partition JDBC.`
      if (!source.partition_lower_bound?.trim() || !source.partition_upper_bound?.trim()) {
        return `${label} : renseignez les deux bornes de partition JDBC.`
      }
      if ((source.partition_parallelism || 0) < 1 || (source.partition_parallelism || 0) > 32) {
        return `${label} : le parallélisme JDBC doit être compris entre 1 et 32.`
      }
    }
  }
  const gold = workflow.goldConfigGlobal
  if (gold?.enabled !== false) {
    if (!gold?.target_table_gold?.trim()) return 'Renseignez la table cible Gold.'
    if (gold?.execution_engine === 'SPARK') {
      if (!gold?.spark_sql?.trim()) return 'Renseignez la requête distribuée Gold.'
    } else if (!gold?.elt_scripts_gold?.trim()) {
      return 'Renseignez le SQL Gold.'
    }
    const allSilverDisabled = workflow.sources.every((source) => source.silver_config?.enabled === false)
    if (allSilverDisabled && gold.input_layer !== 'BRONZE') {
      return 'Toutes les étapes Silver sont désactivées : choisissez Bronze comme entrée Gold.'
    }
  }
  return undefined
}

export function WorkflowBuilderView() {
  const { t } = useTranslation()
  const { id } = useNavStore((s) => s.params)
  const isEdit = !!id

  // Charger le workflow existant si edit mode
  const wfQ = useQuery({
    queryKey: ['workflow', id],
    queryFn: () => workflowService.get(id!),
    enabled: isEdit,
  })

  if (isEdit && wfQ.isLoading) return <LoadingState label={t('common.loading')} />
  if (isEdit && wfQ.isError) return <ErrorState message={describeError(wfQ.error)} onRetry={() => wfQ.refetch()} />

  // Une fois la query résolue (ou en mode création), on monte le formulaire
  // avec une `key` pour le remonter proprement si l'id change.
  return <WorkflowBuilderForm key={id || 'new'} id={id} initialData={wfQ.data} />
}

function WorkflowBuilderForm({ id, initialData }: { id?: string; initialData?: WorkflowConfigUi }) {
  const { t } = useTranslation()
  const navigate = useNavStore((s) => s.navigate)
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const isAdmin = useAuthStore((s) => s.isAdmin)
  const isEdit = !!id

  const [step, setStep] = useState<BuilderStep>('general')
  // Initial state dérivé de initialData (pas de setState dans useEffect)
  const [wf, setWf] = useState<WorkflowConfigUi>(() =>
    initialData ? { ...emptyWorkflow(), ...initialData } : emptyWorkflow()
  )
  const [activeSourceIdx, setActiveSourceIdx] = useState(0)
  const [sourceOptionsOpen, setSourceOptionsOpen] = useState(false)
  const [uploadingSourceIdx, setUploadingSourceIdx] = useState<number | null>(null)
  const [templatesOpen, setTemplatesOpen] = useState(false)

  // Normes + connections pour les dropdowns
  const standardsQ = useQuery({ queryKey: ['standards'], queryFn: standardService.list })
  const connectionsQ = useQuery({ queryKey: ['connections'], queryFn: connectionService.list })
  const templatesQ = useQuery({
    queryKey: ['workflow-templates'],
    queryFn: workflowTemplateService.list,
    enabled: !isEdit,
  })

  const applyTemplate = (template: WorkflowTemplateDto) => {
    const templateWorkflow = structuredClone(template.workflow)
    setWf({
      ...emptyWorkflow(),
      ...templateWorkflow,
      id: undefined,
      name: '',
      workflowName: undefined,
      destinationConnectionId: undefined,
      isActive: false,
      active: false,
      createdBy: undefined,
      createdAt: undefined,
      updatedAt: undefined,
    })
    setActiveSourceIdx(0)
    setStep('general')
    setTemplatesOpen(false)
    toast({ title: t('builder.templateApplied'), description: template.name })
  }

  const uploadSourceFile = async (index: number, file: File) => {
    setUploadingSourceIdx(index)
    try {
      const uploaded = await fileService.upload(file)
      setWf((current) => {
        const sources = [...current.sources]
        const source = sources[index]
        if (!source) return current
        sources[index] = {
          ...source,
          source_name: protocolFromFileName(file.name),
          upload_id: uploaded.uploadId,
          file_path: uploaded.storagePath,
          uri: uploaded.storagePath,
          config: {
            ...(source.config || {}),
            upload_id: uploaded.uploadId,
            original_file_name: uploaded.originalName,
            upload_sha256: uploaded.sha256,
          },
        }
        return { ...current, sources }
      })
      toast({ title: 'Fichier charge', description: uploaded.originalName })
    } catch (error) {
      toast({ title: t('common.failure'), description: describeError(error), variant: 'destructive' })
    } finally {
      setUploadingSourceIdx(null)
    }
  }

  // ----- Mutations -----
  const createMut = useMutation({
    mutationFn: (body: WorkflowConfigUi) => workflowService.create(body),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] })
      toast({ title: t('workflows.toastCreated'), description: `${t('common.id')} : ${created.id}` })
      navigate('workflow-detail', { id: created.id })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const updateMut = useMutation({
    mutationFn: (body: WorkflowConfigUi) => workflowService.update(id!, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] })
      queryClient.invalidateQueries({ queryKey: ['workflow', id] })
      toast({ title: t('workflows.toastUpdated') })
      navigate('workflow-detail', { id })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const draftMut = useMutation({
    mutationFn: (body: WorkflowConfigUi) => workflowService.saveDraft(body),
    onSuccess: () => {
      toast({ title: t('workflows.toastDraftSaved') })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const executeMut = useMutation({
    mutationFn: async (body: WorkflowConfigUi) => {
      const workflowId = body.id || id
      if (workflowId) return workflowService.execute(workflowId)

      const created = await workflowService.create(body)
      if (!created.id) {
        throw new ApiRequestError(0, 'Workflow created without an identifier returned by the server.')
      }
      queryClient.invalidateQueries({ queryKey: ['workflows'] })
      return workflowService.execute(created.id)
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['logs'] })
      toast({ title: t('workflows.toastRun'), description: `${t('common.execution')} ${data.id}` })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  // ----- Helpers -----
  function updateWf(patch: Partial<WorkflowConfigUi>) {
    setWf((p) => ({ ...p, ...patch }))
  }
  function updateOutboundConfig(patch: Partial<WorkflowOutboundConfig>) {
    setWf((p) => ({
      ...p,
      outboundConfig: {
        targetAdapter: 'generic-json',
        ...(p.outboundConfig || {}),
        ...patch,
      },
    }))
  }
  function updateOutboundSource(patch: NonNullable<WorkflowOutboundConfig['source']>) {
    setWf((p) => ({
      ...p,
      outboundConfig: {
        targetAdapter: 'generic-json',
        ...(p.outboundConfig || {}),
        source: { ...(p.outboundConfig?.source || {}), ...patch },
      },
    }))
  }
  function updateOutboundDestination(patch: NonNullable<WorkflowOutboundConfig['destination']>) {
    setWf((p) => ({
      ...p,
      outboundConfig: {
        targetAdapter: 'generic-json',
        ...(p.outboundConfig || {}),
        destination: { ...(p.outboundConfig?.destination || {}), ...patch },
      },
    }))
  }
  function updateOutboundAuth(patch: Partial<OutboundAuthConfig>) {
    const current = typeof wf.outboundConfig?.destination?.auth === 'object'
      ? wf.outboundConfig.destination.auth
      : { type: 'NONE' as const }
    updateOutboundDestination({ auth: { ...current, ...patch } as OutboundAuthConfig })
  }
  function updateSource(idx: number, patch: Partial<WorkflowSourceUi>) {
    setWf((p) => {
      const sources = [...(p.sources || [])]
      sources[idx] = { ...sources[idx], ...patch }
      return { ...p, sources }
    })
  }
  function updateSourceProtocol(idx: number, protocol: string) {
    setWf((current) => {
      const sources = [...current.sources]
      const source = sources[idx]
      if (!source) return current
      const fileProtocol = isFileProtocol(protocol)
      const apiProtocol = isApiProtocol(protocol)
      sources[idx] = {
        ...source,
        source_name: protocol,
        sourceConnectionId: fileProtocol || apiProtocol ? undefined : source.sourceConnectionId,
        upload_id: fileProtocol ? source.upload_id : undefined,
        file_path: fileProtocol ? source.file_path : undefined,
        uri: fileProtocol ? source.file_path : undefined,
        config: fileProtocol
          ? { ...(source.config || {}), source_connection_id: undefined }
          : {
              ...(source.config || {}),
              upload_id: undefined,
              original_file_name: undefined,
              upload_sha256: undefined,
            },
        source_config: fileProtocol
          ? { delimiter: ',', encoding: 'UTF-8' }
          : apiProtocol
          ? {
              url: '', method: 'GET', records_path: '',
              pagination: { type: 'NONE', page_size: 100, max_pages: 100 },
              auth: { type: 'NONE' },
            }
          : { query: source.source_config?.query || '' },
      }
      return { ...current, protocol: sources[0]?.source_name, sources }
    })
  }
  function addSource() {
    setWf((p) => ({ ...p, sources: [...(p.sources || []), emptySource()] }))
    setActiveSourceIdx(wf.sources?.length ?? 0)
  }
  function removeSource(idx: number) {
    setWf((p) => ({ ...p, sources: (p.sources || []).filter((_, i) => i !== idx) }))
    setActiveSourceIdx(0)
  }

  const currentSource = wf.sources?.[activeSourceIdx]
  const destinationConnections = (connectionsQ.data ?? []).filter((connection) =>
    DESTINATION_PROTOCOLS.includes(normalizeConnectionType(connection.dbType) as typeof DESTINATION_PROTOCOLS[number]),
  )
  const sourceConnections = (connectionsQ.data ?? []).filter(
    (connection) => normalizeConnectionType(connection.dbType) === normalizeConnectionType(currentSource?.source_name),
  )
  const stepIdx = STEPS.findIndex((s) => s.id === step)
  const backendWorkflowJson = JSON.stringify(toBackendWorkflow(wf), null, 2)
  const outboundConfig = wf.outboundConfig || {}
  const outboundSource = outboundConfig.source || {}
  const outboundDestination = outboundConfig.destination || {}
  const outboundAuth: OutboundAuthConfig = typeof outboundDestination.auth === 'object'
    ? outboundDestination.auth
    : outboundDestination.auth
      ? { type: 'BEARER', secretRef: outboundDestination.auth.replace(/^env:/, '') }
      : { type: 'NONE' }

  function next() { if (stepIdx < STEPS.length - 1) setStep(STEPS[stepIdx + 1].id) }
  function prev() { if (stepIdx > 0) setStep(STEPS[stepIdx - 1].id) }

  function save() {
    const validationError = validateWorkflowForExecution(wf, connectionsQ.data ?? [])
    if (validationError) {
      toast({ title: t('common.failure'), description: validationError, variant: 'destructive' })
      return
    }
    if (isEdit) updateMut.mutate(wf)
    else createMut.mutate(wf)
  }

  function run() {
    const validationError = validateWorkflowForExecution(wf, connectionsQ.data ?? [])
    if (validationError) {
      toast({ title: t('common.failure'), description: validationError, variant: 'destructive' })
      return
    }
    executeMut.mutate(wf)
  }

  async function copyReviewJson() {
    await navigator.clipboard.writeText(backendWorkflowJson)
    toast({ title: t('builder.configurationCopied') })
  }

  function downloadReviewJson() {
    const blob = new Blob([backendWorkflowJson], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${wf.name || 'workflow'}-configuration.json`
    a.click()
    URL.revokeObjectURL(url)
    toast({ title: t('builder.configurationDownloaded') })
  }

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title={isEdit ? t('builder.editTitle', { name: wf.name }) : t('builder.newTitle')}
        description={t('builder.description')}
        actions={
          <>
            {!isEdit && (
              <Button variant="outline" onClick={() => setTemplatesOpen(true)}>
                <LayoutTemplate className="mr-1.5 h-3.5 w-3.5" /> {t('builder.templates')}
              </Button>
            )}
            <Button variant="outline" onClick={() => draftMut.mutate(wf)} disabled={draftMut.isPending}>
              <Save className="mr-1.5 h-3.5 w-3.5" /> {t('builder.saveDraft')}
            </Button>
            <Button variant="outline" onClick={save} disabled={createMut.isPending || updateMut.isPending}>
              <CheckCircle2 className="mr-1.5 h-3.5 w-3.5" /> {isEdit ? t('common.save') : t('common.create')}
            </Button>
            <Button onClick={run} disabled={executeMut.isPending}>
              <Play className="mr-1.5 h-3.5 w-3.5" /> {t('common.create')} & {t('common.run')}
            </Button>
          </>
        }
      />

      <Dialog open={templatesOpen} onOpenChange={setTemplatesOpen}>
        <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('builder.chooseTemplate')}</DialogTitle>
          </DialogHeader>
          {templatesQ.isLoading && <LoadingState label={t('common.loading')} />}
          {templatesQ.isError && (
            <ErrorState message={describeError(templatesQ.error)} onRetry={() => templatesQ.refetch()} />
          )}
          {templatesQ.data && (
            <div className="grid gap-3 sm:grid-cols-2">
              {templatesQ.data.map((template) => (
                <div key={template.id} className="flex min-h-40 flex-col border border-border p-4">
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <h3 className="text-sm font-semibold">{template.name}</h3>
                      <p className="mt-1 text-xs text-muted-foreground">{template.description}</p>
                    </div>
                    <Badge variant="outline" className="shrink-0">{template.category}</Badge>
                  </div>
                  <div className="mt-auto flex items-center justify-between gap-3">
                    <span className="text-[11px] text-muted-foreground">v{template.version}</span>
                    <Button size="sm" onClick={() => applyTemplate(template)}>
                      <CheckCircle2 className="mr-1.5 h-3.5 w-3.5" />
                      {t('builder.useTemplate')}
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* Steps */}
      <div className="mb-6 flex flex-wrap items-center gap-1">
        {STEPS.map((s, i) => (
          <div key={s.id} className="flex items-center">
            <button
              onClick={() => setStep(s.id)}
              className={`flex items-center gap-2 rounded-md px-3 py-1.5 text-xs transition-colors ${
                step === s.id
                  ? 'bg-primary text-primary-foreground'
                  : i < stepIdx
                  ? 'bg-success/10 text-success'
                  : 'bg-muted text-muted-foreground hover:bg-muted/70'
              }`}
            >
              <span className="flex h-4 w-4 items-center justify-center rounded-full border border-current text-[10px]">{i + 1}</span>
              {t(s.labelKey)}
            </button>
            {i < STEPS.length - 1 && <ChevronRight className="mx-0.5 h-3 w-3 text-muted-foreground" />}
          </div>
        ))}
      </div>

      {/* ----- Step: General ----- */}
      {step === 'general' && (
        <Card>
          <CardHeader><CardTitle className="text-sm">{t('builder.generalConfiguration')}</CardTitle></CardHeader>
          <CardContent className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="name" className="text-xs">{t('common.name')} *</Label>
              <Input id="name" value={wf.name} onChange={(e) => updateWf({ name: e.target.value })} placeholder="ex: ingestion-clients" />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">Direction *</Label>
              <Select
                value={wf.direction}
                onValueChange={(v: WorkflowDirection) => updateWf({
                  direction: v,
                  outboundConfig: v === 'OUTBOUND'
                    ? (wf.outboundConfig || { targetAdapter: 'generic-json', source: {}, destination: {} })
                    : undefined,
                })}
              >
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="INTERNAL">{directionLabel('INTERNAL')}</SelectItem>
                  <SelectItem value="INBOUND">{directionLabel('INBOUND')}</SelectItem>
                  <SelectItem value="OUTBOUND">{directionLabel('OUTBOUND')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="sm:col-span-2 space-y-1.5">
              <Label htmlFor="desc" className="text-xs">{t('common.description')}</Label>
              <Textarea id="desc" value={wf.description || ''} onChange={(e) => updateWf({ description: e.target.value })} rows={2} />
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">{TECH_LABELS.standard}</Label>
              <Select
                value={wf.standardId || ''}
                onValueChange={(v) => updateWf({ standardId: v })}
                disabled={standardsQ.isLoading}
              >
                <SelectTrigger><SelectValue placeholder={`${t('common.search')} ${TECH_LABELS.standard.toLowerCase()}...`} /></SelectTrigger>
                <SelectContent>
                  {(standardsQ.data ?? []).map((s) => (
                    <SelectItem key={s.id} value={s.id}>{s.name} ({s.domain})</SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-[10px] text-muted-foreground">
                {standardsQ.isError ? <InlineError message={describeError(standardsQ.error)} /> : t('builder.sourceConfigHelp')}
              </p>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="priority" className="text-xs">{t('common.priority')}</Label>
              <Input id="priority" type="number" min={1} max={10} value={wf.priority ?? 5} onChange={(e) => updateWf({ priority: Number(e.target.value) })} />
            </div>
            {isAdmin && (
              <div className="space-y-1.5">
                <Label className="text-xs">{t('builder.estimatedRows')}</Label>
                <Input type="number" min={0} value={wf.estimatedRows ?? ''} onChange={(event) => updateWf({
                  estimatedRows: event.target.value ? Number(event.target.value) : undefined,
                })} />
                <p className="text-[10px] text-muted-foreground">{t('builder.executionAutoHelp')}</p>
              </div>
            )}
            <div className="sm:col-span-2 space-y-1.5 border-t border-border pt-4">
              <Label className="text-xs">{t('builder.destinationConnection')} *</Label>
              <Select
                value={wf.destinationConnectionId || ''}
                onValueChange={(value) => updateWf({ destinationConnectionId: value })}
                disabled={connectionsQ.isLoading}
              >
                <SelectTrigger><SelectValue placeholder={t('builder.destinationConnectionPlaceholder')} /></SelectTrigger>
                <SelectContent>
                  {destinationConnections.map((connection) => (
                    <SelectItem key={connection.id} value={connection.id!}>
                      {connection.name} ({connection.host}:{connection.port}/{connection.database})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <div className="flex items-center justify-between gap-3">
                <p className="text-[10px] text-muted-foreground">{t('builder.destinationConnectionHelp')}</p>
                <Button type="button" variant="ghost" size="sm" onClick={() => navigate('connections')}>
                  {t('builder.manageConnections')} <ArrowRight className="ml-1 h-3.5 w-3.5" />
                </Button>
              </div>
              {connectionsQ.isError && <InlineError message={describeError(connectionsQ.error)} />}
            </div>

            {/* Schedule */}
            <div className="sm:col-span-2 rounded-md border border-border bg-muted/20 p-3">
              <div className="mb-3 flex items-center justify-between">
                <Label className="text-xs">{t('fields.schedule')}</Label>
                <Switch
                  checked={wf.schedule?.enabled}
                  onCheckedChange={(v) => updateWf({ schedule: { ...wf.schedule!, enabled: v } })}
                />
              </div>
              {wf.schedule?.enabled && (
                <Tabs defaultValue="cron">
                  <TabsList className="mb-3">
                    <TabsTrigger value="cron">Cron</TabsTrigger>
                    <TabsTrigger value="freq">{t('fields.frequency')} + {t('fields.time')}</TabsTrigger>
                  </TabsList>
                  <TabsContent value="cron" className="space-y-1.5">
                    <Label className="text-xs">Expression cron</Label>
                    <Input
                      value={wf.schedule?.cron || ''}
                      onChange={(e) => updateWf({ schedule: { ...wf.schedule!, cron: e.target.value } })}
                      placeholder="0 0 * * * ?"
                    />
                    <p className="text-[10px] text-muted-foreground">{t('builder.scheduleQuartzHelp')}</p>
                  </TabsContent>
                  <TabsContent value="freq" className="grid gap-3 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <Label className="text-xs">{t('fields.frequency')}</Label>
                      <Select
                        value={wf.schedule?.frequency || 'DAILY'}
                        onValueChange={(v) => updateWf({ schedule: { ...wf.schedule!, frequency: v } })}
                      >
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="HOURLY">Hourly</SelectItem>
                          <SelectItem value="DAILY">Daily</SelectItem>
                          <SelectItem value="WEEKLY">Weekly</SelectItem>
                          <SelectItem value="MONTHLY">Monthly</SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-xs">{t('fields.time')}</Label>
                      <Input
                        value={wf.schedule?.time || ''}
                        onChange={(e) => updateWf({ schedule: { ...wf.schedule!, time: e.target.value } })}
                        placeholder="00:00"
                      />
                    </div>
                  </TabsContent>
                </Tabs>
              )}
            </div>

            {wf.direction === 'OUTBOUND' && (
              <div className="sm:col-span-2 rounded-md border border-border bg-muted/20 p-3">
                <div className="mb-3">
                  <h3 className="text-sm font-medium">{t('builder.outboundTitle')}</h3>
                  <p className="mt-1 text-[10px] text-muted-foreground">{t('builder.outboundHelp')}</p>
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.outboundTargetStandard')} *</Label>
                    <Select
                      value={outboundConfig.targetStandardId || ''}
                      onValueChange={(v) => updateOutboundConfig({
                        targetStandardId: v,
                        targetAdapter: outboundAdapterFor((standardsQ.data ?? []).find((standard) => standard.id === v)),
                      })}
                      disabled={standardsQ.isLoading}
                    >
                      <SelectTrigger><SelectValue placeholder={`${t('common.search')} ${TECH_LABELS.standard.toLowerCase()}...`} /></SelectTrigger>
                      <SelectContent>
                        {(standardsQ.data ?? []).map((s) => (
                          <SelectItem key={s.id} value={s.id}>{s.name} ({s.domain})</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {standardsQ.isError && <InlineError message={describeError(standardsQ.error)} />}
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.outboundTargetSystem')} *</Label>
                    <Input
                      value={outboundConfig.targetSystem || ''}
                      onChange={(e) => updateOutboundConfig({ targetSystem: e.target.value })}
                      placeholder="hospital_b"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.outboundGoldTable')} *</Label>
                    <Input
                      value={outboundSource.goldTable || ''}
                      onChange={(e) => updateOutboundSource({ goldTable: e.target.value })}
                      placeholder="gold.client_orders"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.outboundOpenhimChannel')}</Label>
                    <Input
                      value={outboundDestination.openhimChannel || ''}
                      onChange={(e) => updateOutboundDestination({ openhimChannel: e.target.value })}
                      placeholder="outbound-client"
                    />
                  </div>
                  <div className="space-y-1.5 sm:col-span-2">
                    <Label className="text-xs">{t('builder.outboundGoldQuery')}</Label>
                    <Textarea
                      value={outboundSource.query || ''}
                      onChange={(e) => updateOutboundSource({ query: e.target.value })}
                      rows={3}
                      placeholder="select * from gold.client_orders"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.outboundEndpointUrl')}</Label>
                    <Input
                      value={outboundDestination.endpointUrl || ''}
                      onChange={(e) => updateOutboundDestination({ endpointUrl: e.target.value })}
                      placeholder="https://partner.example/api"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.outboundAuthType')}</Label>
                    <Select value={outboundAuth.type} onValueChange={(value: OutboundAuthConfig['type']) => updateOutboundAuth({ type: value })}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="NONE">Aucune</SelectItem>
                        <SelectItem value="BEARER">Bearer</SelectItem>
                        <SelectItem value="BASIC">Basic</SelectItem>
                        <SelectItem value="API_KEY">API key</SelectItem>
                        <SelectItem value="OAUTH2_CLIENT_CREDENTIALS">OAuth2 client credentials</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  {['BEARER', 'API_KEY'].includes(outboundAuth.type) && (
                    <div className="space-y-1.5">
                      <Label className="text-xs">{t('builder.outboundSecretReference')}</Label>
                      <Input value={outboundAuth.secretRef || ''} onChange={(e) => updateOutboundAuth({ secretRef: e.target.value })} placeholder="HOSPITAL_B_TOKEN" />
                    </div>
                  )}
                  {outboundAuth.type === 'API_KEY' && (
                    <div className="space-y-1.5">
                      <Label className="text-xs">En-tete API key</Label>
                      <Input value={outboundAuth.header || ''} onChange={(e) => updateOutboundAuth({ header: e.target.value })} placeholder="X-API-Key" />
                    </div>
                  )}
                  {outboundAuth.type === 'BASIC' && (
                    <>
                      <div className="space-y-1.5"><Label className="text-xs">Reference utilisateur</Label><Input value={outboundAuth.usernameRef || ''} onChange={(e) => updateOutboundAuth({ usernameRef: e.target.value })} placeholder="HOSPITAL_B_USER" /></div>
                      <div className="space-y-1.5"><Label className="text-xs">Reference mot de passe</Label><Input value={outboundAuth.passwordRef || ''} onChange={(e) => updateOutboundAuth({ passwordRef: e.target.value })} placeholder="HOSPITAL_B_PASSWORD" /></div>
                    </>
                  )}
                  {outboundAuth.type === 'OAUTH2_CLIENT_CREDENTIALS' && (
                    <>
                      <div className="space-y-1.5 sm:col-span-2"><Label className="text-xs">URL du jeton OAuth2</Label><Input value={outboundAuth.tokenUrl || ''} onChange={(e) => updateOutboundAuth({ tokenUrl: e.target.value })} placeholder="https://partner.example/oauth/token" /></div>
                      <div className="space-y-1.5"><Label className="text-xs">Reference client ID</Label><Input value={outboundAuth.clientIdRef || ''} onChange={(e) => updateOutboundAuth({ clientIdRef: e.target.value })} placeholder="HOSPITAL_B_CLIENT_ID" /></div>
                      <div className="space-y-1.5"><Label className="text-xs">Reference client secret</Label><Input value={outboundAuth.clientSecretRef || ''} onChange={(e) => updateOutboundAuth({ clientSecretRef: e.target.value })} placeholder="HOSPITAL_B_CLIENT_SECRET" /></div>
                    </>
                  )}
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* ----- Step: Sources ----- */}
      {step === 'sources' && (
        <div className="space-y-4">
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="flex items-center justify-between text-sm">
                <span>{t('common.sources')} ({wf.sources?.length || 0})</span>
                <Button size="sm" onClick={addSource}><Plus className="mr-1 h-3.5 w-3.5" /> {t('common.add')}</Button>
              </CardTitle>
            </CardHeader>
            <CardContent>
              {(wf.sources?.length ?? 0) === 0 ? (
                <EmptyState title={`${t('common.none')} ${t('common.source').toLowerCase()}`} description={t('builder.emptyNoSourceDescription')} icon={Database} />
              ) : (
                <div className="flex flex-wrap gap-1.5">
                  {(wf.sources || []).map((s, i) => (
                    <div key={i} className="flex items-center gap-1">
                      <button
                        onClick={() => setActiveSourceIdx(i)}
                        className={`rounded-md px-3 py-1.5 text-xs ${
                          i === activeSourceIdx ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-muted/70'
                        }`}
                      >
                        {s.source_name || `Source ${i + 1}`}
                      </button>
                      <Button size="icon" variant="ghost" className="h-6 w-6 text-destructive" onClick={() => removeSource(i)}>
                        <Trash2 className="h-3 w-3" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>

          {currentSource && (
            <Card>
              <CardHeader><CardTitle className="text-sm">{t('builder.sourceTitle', { name: currentSource.source_name || `#${activeSourceIdx + 1}` })}</CardTitle></CardHeader>
              <CardContent className="space-y-4">
                <Tabs
                  value={isFileProtocol(currentSource.source_name) ? 'file' : isApiProtocol(currentSource.source_name) ? 'api' : 'database'}
                  onValueChange={(value) => updateSourceProtocol(activeSourceIdx, value === 'file' ? 'CSV' : value === 'api' ? 'API' : 'POSTGRES')}
                >
                  <TabsList className="grid h-auto w-full grid-cols-1 sm:grid-cols-3">
                    <TabsTrigger value="database"><Server className="mr-1.5 h-4 w-4" />{t('builder.databaseSource')}</TabsTrigger>
                    <TabsTrigger value="file"><FileSpreadsheet className="mr-1.5 h-4 w-4" />{t('builder.fileSource')}</TabsTrigger>
                    <TabsTrigger value="api"><Globe2 className="mr-1.5 h-4 w-4" />API</TabsTrigger>
                  </TabsList>
                </Tabs>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.sourceProtocol')} *</Label>
                    <Select value={currentSource.source_name || 'CSV'} onValueChange={(value) => updateSourceProtocol(activeSourceIdx, value)}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        {(isFileProtocol(currentSource.source_name) ? FILE_PROTOCOLS : isApiProtocol(currentSource.source_name) ? ['API'] : DATABASE_PROTOCOLS).map((protocol) => (
                          <SelectItem key={protocol} value={protocol}>
                            {protocol === 'CSV' ? t('builder.csvFile') : protocol === 'EXCEL' ? t('builder.excelFile') : protocol}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">{t('builder.tableTargetRaw', { layer: TECH_LABELS.bronze.toLowerCase() })} *</Label>
                    <Input value={currentSource.target_table || ''} onChange={(e) => updateSource(activeSourceIdx, { target_table: e.target.value })} placeholder="bronze.clients" />
                  </div>
                </div>

                {isFileProtocol(currentSource.source_name) ? (
                  <div className="grid gap-4 border-t border-border pt-4 sm:grid-cols-2">
                    <div className="space-y-1.5 sm:col-span-2">
                      <Label className="text-xs">{t('builder.fileToProcess')} *</Label>
                      <div className="flex flex-col gap-2 sm:flex-row">
                        <Input
                          value={String(currentSource.config?.original_file_name || currentSource.file_path || '')}
                          readOnly
                          placeholder={t('builder.noFileUploaded')}
                        />
                        <Button asChild variant="outline" disabled={uploadingSourceIdx === activeSourceIdx}>
                          <Label className="h-9 cursor-pointer justify-center px-3">
                            {uploadingSourceIdx === activeSourceIdx
                              ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                              : <Upload className="mr-1.5 h-4 w-4" />}
                            {t('builder.uploadFile')}
                            <Input
                              type="file"
                              className="sr-only"
                              accept=".csv,.tsv,.txt,.xls,.xlsx,.parquet,.avro,.orc"
                              disabled={uploadingSourceIdx === activeSourceIdx}
                              onChange={(event) => {
                                const file = event.target.files?.[0]
                                if (file) void uploadSourceFile(activeSourceIdx, file)
                                event.target.value = ''
                              }}
                            />
                          </Label>
                        </Button>
                      </div>
                    </div>
                    {currentSource.source_name === 'CSV' && (
                      <>
                        <div className="space-y-1.5">
                          <Label className="text-xs">{t('fields.delimiter')}</Label>
                          <Input value={currentSource.source_config?.delimiter || ','} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, delimiter: e.target.value } })} />
                        </div>
                        <div className="space-y-1.5">
                          <Label className="text-xs">{t('fields.encoding')}</Label>
                          <Input value={currentSource.source_config?.encoding || 'UTF-8'} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, encoding: e.target.value } })} />
                        </div>
                      </>
                    )}
                  </div>
                ) : isApiProtocol(currentSource.source_name) ? (
                  <div className="grid gap-4 border-t border-border pt-4 sm:grid-cols-2">
                    <div className="space-y-1.5 sm:col-span-2">
                      <Label className="text-xs">URL API *</Label>
                      <Input
                        value={String(currentSource.source_config?.url || '')}
                        onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, url: e.target.value } })}
                        placeholder="https://api.hospital.local/v1/patients"
                      />
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-xs">Méthode HTTP</Label>
                      <Select
                        value={String(currentSource.source_config?.method || 'GET')}
                        onValueChange={(method) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, method } })}
                      >
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent><SelectItem value="GET">GET</SelectItem><SelectItem value="POST">POST</SelectItem></SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-xs">Chemin des enregistrements</Label>
                      <Input
                        value={String(currentSource.source_config?.records_path || '')}
                        onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, records_path: e.target.value } })}
                        placeholder="data.items"
                      />
                    </div>
                    {String(currentSource.source_config?.method || 'GET') === 'POST' && (
                      <div className="space-y-1.5 sm:col-span-2">
                        <Label className="text-xs">Corps JSON</Label>
                        <Textarea
                          value={String(currentSource.source_config?.body || '')}
                          onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, body: e.target.value } })}
                          rows={4}
                          className="font-mono text-xs"
                          placeholder={'{"status":"active"}'}
                        />
                      </div>
                    )}
                    <div className="space-y-1.5">
                      <Label className="text-xs">Pagination</Label>
                      <Select
                        value={String(recordValue(currentSource.source_config?.pagination).type || 'NONE')}
                        onValueChange={(type) => updateSource(activeSourceIdx, { source_config: {
                          ...currentSource.source_config!, pagination: { ...recordValue(currentSource.source_config?.pagination), type },
                        } })}
                      >
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="NONE">Aucune</SelectItem>
                          <SelectItem value="PAGE">Numéro de page</SelectItem>
                          <SelectItem value="OFFSET">Offset</SelectItem>
                          <SelectItem value="CURSOR">Curseur</SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <div className="space-y-1.5">
                        <Label className="text-xs">Taille du lot</Label>
                        <Input type="number" min={1} max={10000} value={Number(recordValue(currentSource.source_config?.pagination).page_size || 100)} onChange={(e) => updateSource(activeSourceIdx, { source_config: {
                          ...currentSource.source_config!, pagination: { ...recordValue(currentSource.source_config?.pagination), page_size: Number(e.target.value) },
                        } })} />
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-xs">Pages max.</Label>
                        <Input type="number" min={1} max={10000} value={Number(recordValue(currentSource.source_config?.pagination).max_pages || 100)} onChange={(e) => updateSource(activeSourceIdx, { source_config: {
                          ...currentSource.source_config!, pagination: { ...recordValue(currentSource.source_config?.pagination), max_pages: Number(e.target.value) },
                        } })} />
                      </div>
                    </div>
                    {String(recordValue(currentSource.source_config?.pagination).type || 'NONE') === 'CURSOR' && (
                      <>
                        <div className="space-y-1.5"><Label className="text-xs">Paramètre curseur</Label><Input value={String(recordValue(currentSource.source_config?.pagination).cursor_param || 'cursor')} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, pagination: { ...recordValue(currentSource.source_config?.pagination), cursor_param: e.target.value } } })} /></div>
                        <div className="space-y-1.5"><Label className="text-xs">Chemin du prochain curseur</Label><Input value={String(recordValue(currentSource.source_config?.pagination).next_cursor_path || 'next_cursor')} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, pagination: { ...recordValue(currentSource.source_config?.pagination), next_cursor_path: e.target.value } } })} /></div>
                      </>
                    )}
                    <div className="space-y-1.5">
                      <Label className="text-xs">Authentification</Label>
                      <Select value={String(recordValue(currentSource.source_config?.auth).type || 'NONE')} onValueChange={(type) => updateSource(activeSourceIdx, { source_config: {
                        ...currentSource.source_config!, auth: { ...recordValue(currentSource.source_config?.auth), type },
                      } })}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent><SelectItem value="NONE">Aucune</SelectItem><SelectItem value="BEARER">Bearer</SelectItem><SelectItem value="BASIC">Basic</SelectItem><SelectItem value="API_KEY">Clé API</SelectItem></SelectContent>
                      </Select>
                    </div>
                    {['BEARER', 'API_KEY'].includes(String(recordValue(currentSource.source_config?.auth).type || 'NONE')) && (
                      <div className="space-y-1.5">
                        <Label className="text-xs">Variable d'environnement du secret</Label>
                        <Input value={String(recordValue(currentSource.source_config?.auth).secret_ref || '')} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, auth: { ...recordValue(currentSource.source_config?.auth), secret_ref: e.target.value } } })} placeholder="HOSPITAL_API_TOKEN" />
                      </div>
                    )}
                    {String(recordValue(currentSource.source_config?.auth).type || 'NONE') === 'BASIC' && (
                      <div className="grid grid-cols-2 gap-2">
                        <div className="space-y-1.5"><Label className="text-xs">Variable utilisateur</Label><Input value={String(recordValue(currentSource.source_config?.auth).username_ref || '')} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, auth: { ...recordValue(currentSource.source_config?.auth), username_ref: e.target.value } } })} /></div>
                        <div className="space-y-1.5"><Label className="text-xs">Variable mot de passe</Label><Input value={String(recordValue(currentSource.source_config?.auth).password_ref || '')} onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, auth: { ...recordValue(currentSource.source_config?.auth), password_ref: e.target.value } } })} /></div>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="grid gap-4 border-t border-border pt-4 sm:grid-cols-2">
                    <div className="space-y-1.5 sm:col-span-2">
                      <Label className="text-xs">{t('builder.sourceConnection')} *</Label>
                      <Select
                        value={currentSource.sourceConnectionId || ''}
                        onValueChange={(value) => {
                          const connection = sourceConnections.find((item) => item.id === value)
                          updateSource(activeSourceIdx, { sourceConnectionId: value, connectionName: connection?.name, uri: undefined })
                        }}
                        disabled={connectionsQ.isLoading}
                      >
                        <SelectTrigger><SelectValue placeholder={t('builder.sourceConnectionPlaceholder')} /></SelectTrigger>
                        <SelectContent>
                          {sourceConnections.map((connection) => (
                            <SelectItem key={connection.id} value={connection.id!}>
                              {connection.name} ({connection.host}:{connection.port}/{connection.database})
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <p className="text-[10px] text-muted-foreground">{t('builder.sourceConnectionHelp')}</p>
                      {connectionsQ.isError && <InlineError message={describeError(connectionsQ.error)} />}
                    </div>
                    <div className="space-y-1.5 sm:col-span-2">
                      <Label className="text-xs">{t('builder.sourceQuery')} *</Label>
                      <Textarea
                        value={(currentSource.source_config?.query as string) || ''}
                        onChange={(e) => updateSource(activeSourceIdx, { source_config: { ...currentSource.source_config!, query: e.target.value } })}
                        rows={4}
                        placeholder="SELECT patient_id, name, updated_at FROM public.patients LIMIT 1000"
                        className="sql-block"
                      />
                    </div>
                  </div>
                )}

                <Collapsible open={sourceOptionsOpen} onOpenChange={setSourceOptionsOpen} className="border-t border-border pt-3">
                  <CollapsibleTrigger asChild>
                    <Button type="button" variant="ghost" size="sm" className="w-full justify-start px-2">
                      <Settings2 className="mr-2 h-4 w-4" />
                      Options de synchronisation
                      <ChevronDown className={`ml-auto h-4 w-4 transition-transform ${sourceOptionsOpen ? 'rotate-180' : ''}`} />
                    </Button>
                  </CollapsibleTrigger>
                  <CollapsibleContent className="pt-3">
                    <div className="grid gap-4 rounded-md border border-border bg-muted/20 p-3 sm:grid-cols-2">
                      <div className="space-y-1.5">
                        <Label className="text-xs">{t('fields.loadMode')}</Label>
                        <Select
                          value={currentSource.load_mode || 'FULL'}
                          onValueChange={(value: 'FULL' | 'INCREMENTAL') => updateSource(activeSourceIdx, {
                            load_mode: value,
                            incremental_column: value === 'INCREMENTAL' ? currentSource.incremental_column : '',
                          })}
                        >
                          <SelectTrigger><SelectValue /></SelectTrigger>
                          <SelectContent>
                            <SelectItem value="FULL">{loadModeLabel('FULL')}</SelectItem>
                            <SelectItem value="INCREMENTAL">{loadModeLabel('INCREMENTAL')}</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-xs">{t('fields.writeMode')}</Label>
                        <Select
                          value={currentSource.write_mode || 'append'}
                          onValueChange={(value: 'append' | 'replace') => updateSource(activeSourceIdx, { write_mode: value })}
                        >
                          <SelectTrigger><SelectValue /></SelectTrigger>
                          <SelectContent>
                            <SelectItem value="append">{writeModeLabel('append')}</SelectItem>
                            <SelectItem value="replace">{writeModeLabel('replace')}</SelectItem>
                          </SelectContent>
                        </Select>
                      </div>
                      {currentSource.load_mode === 'INCREMENTAL' && (
                        <div className="space-y-1.5 sm:col-span-2">
                          <Label className="text-xs">{t('fields.incrementalColumn')}</Label>
                          <Select
                            value={currentSource.incremental_column || ''}
                            onValueChange={(value) => updateSource(activeSourceIdx, { incremental_column: value })}
                          >
                            <SelectTrigger><SelectValue placeholder="Choisir la colonne qui repère les nouvelles lignes" /></SelectTrigger>
                            <SelectContent>
                              {(currentSource.fields || []).map((field) => (
                                <SelectItem key={field.name} value={field.name}>{field.name}</SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>
                      )}
                      <p className="text-[10px] text-muted-foreground sm:col-span-2">
                        Le chargement complet reste la valeur par défaut. Le mode incrémental ne relit que les lignes ajoutées ou modifiées depuis la dernière exécution réussie.
                      </p>
                    </div>
                  </CollapsibleContent>
                </Collapsible>

                <div className="rounded-md border border-accent/40 bg-accent/5 p-3">
                  <div className="mb-2 flex items-center justify-between">
                    <p className="text-xs font-medium text-accent-foreground">{t('builder.discoveryTitle')}</p>
                    <DiscoveryButton source={currentSource} onResult={(cols) => {
                      const fields: SourceField[] = cols.map((c) => ({
                        name: c.name, originalName: c.originalName || c.name, type: c.type, selected: true,
                      }))
                      updateSource(activeSourceIdx, { fields })
                      toast({ title: `${cols.length} ${t('common.columns')}` })
                    }} />
                  </div>
                  <p className="text-[10px] text-muted-foreground">{t('builder.discoverDescription')}</p>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      )}

      {/* ----- Step: Fields & mapping ----- */}
      {step === 'fields' && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">{t('builder.fieldMappingTitle')}</CardTitle>
          </CardHeader>
          <CardContent>
            {!currentSource ? (
              <EmptyState title={t('builder.emptyNoSourceSelected')} />
            ) : (currentSource.fields?.length ?? 0) === 0 ? (
              <EmptyState
                title={t('builder.noColumns')}
                description={t('builder.noColumnsDescription')}
                action={<Button onClick={() => setStep('sources')}>{t('builder.goToSources')}</Button>}
              />
            ) : (
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">
                  {t('builder.fieldsInstruction')}
                </p>
                <DataTable minWidth={860}>
                  <THead>
                    <Th>{t('fields.extract')}</Th>
                    <Th>{t('fields.targetName')}</Th>
                    <Th>{t('fields.originalName')}</Th>
                    <Th>{t('common.type')}</Th>
                    <Th>{t('fields.alias')}</Th>
                    <Th>{t('fields.semanticTerm')}</Th>
                  </THead>
                  <TBody>
                      {(currentSource.fields || []).map((f, i) => {
                        const standard = standardsQ.data?.find((s) => s.id === wf.standardId)
                        const terms = standard?.terms || []
                        return (
                          <Tr key={i}>
                            <Td className="py-2">
                              <Checkbox
                                checked={!!f.selected}
                                onCheckedChange={(v) => {
                                  const fields = [...(currentSource.fields!)]
                                  fields[i] = { ...f, selected: v === true }
                                  updateSource(activeSourceIdx, { fields })
                                }}
                              />
                            </Td>
                            <Td className="py-2">
                              <Input
                                value={f.name}
                                onChange={(e) => {
                                  const fields = [...(currentSource.fields!)]
                                  fields[i] = { ...f, name: e.target.value }
                                  updateSource(activeSourceIdx, { fields })
                                }}
                                className="h-7 font-mono text-xs"
                              />
                            </Td>
                            <Td muted>{f.originalName || '—'}</Td>
                            <Td muted>{f.type || '—'}</Td>
                            <Td className="py-2">
                              <Input
                                value={f.alias || ''}
                                onChange={(e) => {
                                  const fields = [...(currentSource.fields!)]
                                  fields[i] = { ...f, alias: e.target.value }
                                  updateSource(activeSourceIdx, { fields })
                                }}
                                className="h-7 text-xs"
                                placeholder="—"
                              />
                            </Td>
                            <Td className="py-2">
                              <Select
                                value={f.semanticTerm || ''}
                                onValueChange={(v) => {
                                  const fields = [...(currentSource.fields!)]
                                  fields[i] = { ...f, semanticTerm: v }
                                  updateSource(activeSourceIdx, { fields })
                                }}
                              >
                                <SelectTrigger className="h-7 text-xs"><SelectValue placeholder="—" /></SelectTrigger>
                                <SelectContent>
                                  {terms.map((t) => (
                                    <SelectItem key={t.id} value={t.termName} className="text-xs">{t.termName} ({t.dataType})</SelectItem>
                                  ))}
                                  {terms.length === 0 && <SelectItem value="_none_" disabled className="text-xs">{t('builder.noTerm')}</SelectItem>}
                                </SelectContent>
                              </Select>
                            </Td>
                          </Tr>
                        )
                      })}
                  </TBody>
                </DataTable>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* ----- Step: Clean data SQL ----- */}
      {step === 'silver' && currentSource && (
        <SqlEditorCard
          title={t('builder.cleaningSqlTitle', { layer: TECH_LABELS.silver })}
          description={t('builder.cleaningSqlDescription', { from: TECH_LABELS.bronze.toLowerCase(), to: TECH_LABELS.silver.toLowerCase() })}
          targetTable={currentSource.silver_config?.target_table_silver || ''}
          onTargetTableChange={(v) => updateSource(activeSourceIdx, { silver_config: { ...currentSource.silver_config!, target_table_silver: v } })}
          executionEngine={currentSource.silver_config?.execution_engine || 'SQL'}
          showAdvancedEngine={false}
          onExecutionEngineChange={(execution_engine) => updateSource(activeSourceIdx, { silver_config: { ...currentSource.silver_config!, execution_engine } })}
          sql={currentSource.silver_config?.execution_engine === 'SPARK'
            ? currentSource.silver_config?.spark_sql || ''
            : currentSource.silver_config?.elt_scripts_silver || ''}
          onSqlChange={(v) => updateSource(activeSourceIdx, {
            silver_config: currentSource.silver_config?.execution_engine === 'SPARK'
              ? { ...currentSource.silver_config!, spark_sql: v }
              : { ...currentSource.silver_config!, elt_scripts_silver: v },
          })}
          preSql={currentSource.silver_config?.pre_sql || ''}
          onPreSqlChange={(pre_sql) => updateSource(activeSourceIdx, { silver_config: { ...currentSource.silver_config!, pre_sql } })}
          postSql={currentSource.silver_config?.post_sql || ''}
          onPostSqlChange={(post_sql) => updateSource(activeSourceIdx, { silver_config: { ...currentSource.silver_config!, post_sql } })}
          indexes={currentSource.silver_config?.indexes || []}
          onIndexesChange={(indexes) => updateSource(activeSourceIdx, { silver_config: { ...currentSource.silver_config!, indexes } })}
          workflowId={id}
          destinationConnectionId={wf.destinationConnectionId}
          sourceName={currentSource.source_name}
          standardId={wf.standardId}
          columns={(currentSource.fields || []).filter((f) => f.selected).map((f) => f.name)}
          sourceTable={currentSource.target_table || ''}
          mode="cleaning"
          enabled={currentSource.silver_config?.enabled !== false}
          onEnabledChange={(enabled) => updateSource(activeSourceIdx, {
            silver_config: { ...currentSource.silver_config!, enabled },
          })}
        />
      )}

      {/* ----- Step: Final data SQL ----- */}
      {step === 'gold' && (
        <SqlEditorCard
          title={t('builder.finalSqlTitle', { layer: TECH_LABELS.gold })}
          description={t('builder.finalSqlDescription', { from: TECH_LABELS.silver.toLowerCase(), to: TECH_LABELS.gold.toLowerCase() })}
          targetTable={wf.goldConfigGlobal?.target_table_gold || ''}
          onTargetTableChange={(v) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, target_table_gold: v } })}
          executionEngine={wf.goldConfigGlobal?.execution_engine || 'SQL'}
          showAdvancedEngine={false}
          onExecutionEngineChange={(execution_engine) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, execution_engine } })}
          sql={wf.goldConfigGlobal?.execution_engine === 'SPARK'
            ? wf.goldConfigGlobal?.spark_sql || ''
            : wf.goldConfigGlobal?.elt_scripts_gold || ''}
          onSqlChange={(v) => updateWf({
            goldConfigGlobal: wf.goldConfigGlobal?.execution_engine === 'SPARK'
              ? { ...wf.goldConfigGlobal!, spark_sql: v }
              : { ...wf.goldConfigGlobal!, elt_scripts_gold: v },
          })}
          preSql={wf.goldConfigGlobal?.pre_sql || ''}
          onPreSqlChange={(pre_sql) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, pre_sql } })}
          postSql={wf.goldConfigGlobal?.post_sql || ''}
          onPostSqlChange={(post_sql) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, post_sql } })}
          indexes={wf.goldConfigGlobal?.indexes || []}
          onIndexesChange={(indexes) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, indexes } })}
          workflowId={id}
          destinationConnectionId={wf.destinationConnectionId}
          standardId={wf.standardId}
          columns={(wf.sources || []).flatMap((source) =>
            (source.fields || []).filter((field) => field.selected).map((field) => field.name))}
          sourceTables={(wf.sources || []).map((source) => {
            if (wf.goldConfigGlobal?.input_layer === 'BRONZE') return source.target_table
            return source.silver_config?.enabled !== false
              ? source.silver_config?.target_table_silver
              : source.target_table
          }).filter(Boolean) as string[]}
          mode="aggregation"
          enabled={wf.goldConfigGlobal?.enabled !== false}
          onEnabledChange={(enabled) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, enabled } })}
          inputLayer={wf.goldConfigGlobal?.input_layer || 'SILVER'}
          onInputLayerChange={(input_layer) => updateWf({ goldConfigGlobal: { ...wf.goldConfigGlobal!, input_layer } })}
        />
      )}

      {/* ----- Step: Review ----- */}
      {step === 'review' && (
        <Card>
          <CardHeader><CardTitle className="text-sm">{t('builder.reviewTitle')}</CardTitle></CardHeader>
          <CardContent className="space-y-3 text-sm">
            {isAdmin && <div className="rounded-md border border-border bg-muted/20 p-3">
              <div className="mb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-sm font-medium">{t('builder.reviewJsonTitle')}</p>
                <div className="flex flex-wrap gap-1.5">
                  <Button size="sm" variant="outline" onClick={copyReviewJson}>
                    <Copy className="mr-1 h-3.5 w-3.5" /> {t('common.copy')}
                  </Button>
                  <Button size="sm" variant="outline" onClick={downloadReviewJson}>
                    <Download className="mr-1 h-3.5 w-3.5" /> {t('common.download')}
                  </Button>
                </div>
              </div>
              <pre className="sql-block max-h-[520px] overflow-auto rounded-md border border-border bg-background p-3 text-[11px]">{backendWorkflowJson}</pre>
            </div>}
            <ReviewRow label={t('common.name')} value={wf.name} />
            <ReviewRow label={t('common.description')} value={wf.description || '—'} />
            <ReviewRow label="Direction" value={directionLabel(wf.direction)} />
            <ReviewRow label={TECH_LABELS.standard} value={wf.standardId || '—'} />
            <ReviewRow label={t('common.priority')} value={String(wf.priority ?? '—')} />
            <ReviewRow label={t('fields.schedule')} value={wf.schedule?.enabled ? (wf.schedule.cron || `${wf.schedule.frequency} @ ${wf.schedule.time}`) : t('common.disabled')} />
            <ReviewRow label={t('common.sources')} value={t('workflows.sourceCount', { count: wf.sources?.length || 0 })} />
            {(wf.sources || []).map((s, i) => (
              <div key={i} className="rounded-md border border-border bg-muted/20 p-3 text-xs">
                <p className="font-medium">{s.source_name}</p>
                <p className="text-muted-foreground">{s.fields?.length || 0} {t('common.columns')} · {loadModeLabel(s.load_mode)} · {t('common.target').toLowerCase()}={s.target_table}</p>
              </div>
            ))}
            <ReviewRow label={t('builder.tableTargetRaw', { layer: TECH_LABELS.gold.toLowerCase() })} value={wf.goldConfigGlobal?.target_table_gold || '—'} />
          </CardContent>
        </Card>
      )}

      {/* Navigation */}
      <div className="mt-6 flex items-center justify-between">
        <Button variant="outline" onClick={prev} disabled={stepIdx === 0}>
          <ChevronLeft className="mr-1.5 h-4 w-4" /> {t('common.previous')}
        </Button>
        <p className="text-xs text-muted-foreground">{t('common.step')} {stepIdx + 1} / {STEPS.length}</p>
        {stepIdx < STEPS.length - 1 ? (
          <Button onClick={next}>{t('common.next')} <ChevronRight className="ml-1.5 h-4 w-4" /></Button>
        ) : (
          <Button onClick={save} disabled={createMut.isPending || updateMut.isPending}>
            <CheckCircle2 className="mr-1.5 h-4 w-4" /> {isEdit ? t('common.save') : t('workflows.createWorkflow')}
          </Button>
        )}
      </div>
    </div>
  )
}

// ============================================================
// Sous-composant : bouton découverte de schéma
// ============================================================
function DiscoveryButton({
  source, onResult,
}: {
  source: WorkflowSourceUi
  onResult: (cols: { name: string; originalName?: string; type: string }[]) => void
}) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const mut = useMutation({
    mutationFn: () => workflowService.discoverSource(source),
    onSuccess: (data) => {
      const cols = (data?.columns || []).map((c) => ({ name: c.name, originalName: c.originalName, type: c.type }))
      onResult(cols)
    },
    onError: (e) => toast({ title: t('builder.discoveryFailed'), description: describeError(e), variant: 'destructive' }),
  })
  return (
    <Button size="sm" variant="outline" onClick={() => mut.mutate()} disabled={mut.isPending || !source.source_name}>
      {mut.isPending ? <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" /> : <Search className="mr-1 h-3.5 w-3.5" />}
      {t('builder.discover')}
    </Button>
  )
}

// ============================================================
// Sous-composant : éditeur SQL avec AI + Workbench
// ============================================================
function SqlEditorCard({
  title, description, targetTable, onTargetTableChange, sql, onSqlChange,
  workflowId, destinationConnectionId, sourceName, standardId, columns, sourceTable, sourceTables, mode,
  enabled, onEnabledChange, inputLayer, onInputLayerChange,
  executionEngine, onExecutionEngineChange, showAdvancedEngine, preSql, onPreSqlChange,
  postSql, onPostSqlChange, indexes, onIndexesChange,
}: {
  title: string
  description: string
  targetTable: string
  onTargetTableChange: (v: string) => void
  sql: string
  onSqlChange: (v: string) => void
  workflowId?: string
  destinationConnectionId?: string
  sourceName?: string
  standardId?: string
  columns?: string[]
  sourceTable?: string
  sourceTables?: string[]
  mode: 'cleaning' | 'aggregation'
  enabled: boolean
  onEnabledChange: (enabled: boolean) => void
  inputLayer?: 'SILVER' | 'BRONZE'
  onInputLayerChange?: (layer: 'SILVER' | 'BRONZE') => void
  executionEngine: 'SQL' | 'SPARK'
  onExecutionEngineChange: (engine: 'SQL' | 'SPARK') => void
  showAdvancedEngine: boolean
  preSql: string
  onPreSqlChange: (sql: string) => void
  postSql: string
  onPostSqlChange: (sql: string) => void
  indexes: StageIndexConfig[]
  onIndexesChange: (indexes: StageIndexConfig[]) => void
}) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const [instructions, setInstructions] = useState('')

  // AI: generate SQL
  const aiGenMut = useMutation({
    mutationFn: async () => {
      if (mode === 'cleaning') {
        return aiService.generateCleaningSql({
          workflowId, destinationConnectionId, sourceTable, targetTable, standardId, columns, instructions,
        })
      }
      // aggregation
      if (workflowId && standardId) {
        return aiService.generateAggregationSql(workflowId, standardId, {
          targetTableGold: targetTable,
          sourceTables: sourceTables || [sourceTable].filter(Boolean) as string[],
          instructions,
          columns,
        })
      }
      // fallback contextual
      return aiService.generateContextualSql({
        prompt: instructions || t('builder.finalSqlTitle', { layer: TECH_LABELS.gold }),
        workflowId, sourceName, standardId, targetTable, columns,
      })
    },
    onSuccess: (data) => {
      if (data.sql) {
        onSqlChange(data.sql)
        toast({ title: `${t('common.sql')} ${t('common.generate').toLowerCase()}`, description: data.explanation })
      } else {
        toast({ title: `IA ${t('common.none')} ${t('common.sql')}`, description: data.explanation || '—' })
      }
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  // Workbench: validate
  const validateMut = useMutation({
    mutationFn: () => sqlService.validate({ sql, workflowId }),
    onSuccess: (data) => {
      if (data.valid) {
        toast({ title: `${t('common.sql')} valide`, description: data.warnings?.length ? `${data.warnings.length} ${t('common.warnings').toLowerCase()}` : `${t('common.none')} ${t('common.warnings').toLowerCase()}` })
      } else {
        toast({ title: `${t('common.sql')} invalide`, description: data.errors?.join('; ') || t('common.errors'), variant: 'destructive' })
      }
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  // Workbench: execute (test)
  const executeMut = useMutation({
    mutationFn: () => sqlService.execute({ sql, workflowId, limit: 100 }),
    onSuccess: (data) => {
      if (data.success) {
        toast({ title: `${t('common.execution')} OK - ${data.rowCount ?? 0} ligne(s)`, description: `${data.executionTimeMs ?? 0}ms` })
      } else {
        toast({ title: t('common.failure'), description: data.error || '—', variant: 'destructive' })
      }
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Sparkles className="h-4 w-4 text-accent-foreground" /> {title}
          </CardTitle>
          <div className="flex items-center gap-2">
            <Label className="text-xs" htmlFor={`${mode}-enabled`}>{enabled ? 'Activée' : 'Désactivée'}</Label>
            <Switch id={`${mode}-enabled`} checked={enabled} onCheckedChange={onEnabledChange} />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">{description}</p>
      </CardHeader>
      <CardContent className="space-y-3">
        {mode === 'aggregation' && onInputLayerChange && (
          <div className="space-y-1.5">
            <Label className="text-xs">Couche d'entrée</Label>
            <Select value={inputLayer || 'SILVER'} onValueChange={(value: 'SILVER' | 'BRONZE') => onInputLayerChange(value)} disabled={!enabled}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="SILVER">Silver vers Gold</SelectItem>
                <SelectItem value="BRONZE">Bronze vers Gold</SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}
        {showAdvancedEngine && (
          <div className="space-y-1.5">
            <Label className="text-xs">{t('builder.stageEngine')}</Label>
            <Select value={executionEngine} onValueChange={(value: 'SQL' | 'SPARK') => onExecutionEngineChange(value)} disabled={!enabled}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="SQL">{t('builder.stageEngineSql')}</SelectItem>
                <SelectItem value="SPARK">{t('builder.stageEngineSpark')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        )}
        <div className="space-y-1.5">
          <Label className="text-xs">{t('builder.targetTable')}</Label>
          <Input value={targetTable} onChange={(e) => onTargetTableChange(e.target.value)} disabled={!enabled} placeholder={mode === 'aggregation' ? 'gold.clients' : 'silver.clients'} />
        </div>

        {/* AI block */}
        <div className="rounded-md border border-accent/40 bg-accent/5 p-3">
          <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-accent-foreground">
            <Bot className="h-3.5 w-3.5" /> {t('builder.aiAssisted')}
          </p>
          <Textarea
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
            rows={2}
            placeholder="Instructions..."
            className="mb-2 text-xs"
          />
          <Button
            size="sm" variant="outline"
            onClick={() => aiGenMut.mutate()}
            disabled={!enabled || aiGenMut.isPending || !columns?.length}
          >
            {aiGenMut.isPending ? <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" /> : <Wand2 className="mr-1 h-3.5 w-3.5" />}
            {t('common.generate')} {t('common.sql')}
          </Button>
          {mode === 'aggregation' && !workflowId && (
            <p className="mt-1 text-[10px] text-muted-foreground">
              {t('builder.aggregationNeedsSavedWorkflow')}
            </p>
          )}
        </div>

        {/* SQL editor */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <Label className="text-xs">{showAdvancedEngine && executionEngine === 'SPARK' ? t('builder.distributedSql') : t('builder.transformationSql')}</Label>
            <div className="flex gap-1.5">
              <Button size="sm" variant="ghost" onClick={() => validateMut.mutate()} disabled={!enabled || validateMut.isPending || !sql}>
                {validateMut.isPending ? <Loader2 className="mr-1 h-3 w-3 animate-spin" /> : <CheckCircle2 className="mr-1 h-3 w-3" />}
                {t('common.validate')}
              </Button>
              {executionEngine === 'SQL' && (
                <Button size="sm" variant="ghost" onClick={() => executeMut.mutate()} disabled={!enabled || executeMut.isPending || !sql}>
                  {executeMut.isPending ? <Loader2 className="mr-1 h-3 w-3 animate-spin" /> : <Play className="mr-1 h-3 w-3" />}
                  {t('common.test')}
                </Button>
              )}
            </div>
          </div>
          <Textarea
            value={sql}
            onChange={(e) => onSqlChange(e.target.value)}
            disabled={!enabled}
            rows={10}
            placeholder="-- SQL de transformation --"
            className="sql-block"
          />
        </div>
        <div className="grid gap-3 border-t pt-3 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label className="text-xs">{t('builder.preSql')}</Label>
            <Textarea value={preSql} onChange={(event) => onPreSqlChange(event.target.value)} disabled={!enabled} rows={4} className="sql-block" />
          </div>
          <div className="space-y-1.5">
            <Label className="text-xs">{t('builder.postSql')}</Label>
            <Textarea value={postSql} onChange={(event) => onPostSqlChange(event.target.value)} disabled={!enabled} rows={4} className="sql-block" />
          </div>
        </div>
        <div className="space-y-2 border-t pt-3">
          <div className="flex items-center justify-between gap-2">
            <Label className="text-xs">{t('builder.indexes')}</Label>
            <Button type="button" size="sm" variant="outline" disabled={!enabled} onClick={() => onIndexesChange([
              ...indexes,
              { name: '', columns: [], unique: false },
            ])}>
              <Plus className="mr-1 h-3.5 w-3.5" /> {t('common.add')}
            </Button>
          </div>
          {indexes.map((indexConfig, index) => (
            <div key={`${indexConfig.name || 'index'}-${index}`} className="grid items-end gap-2 rounded-md border p-2 sm:grid-cols-[1fr_1.5fr_auto_auto]">
              <div className="space-y-1">
                <Label className="text-[10px]">{t('builder.indexName')}</Label>
                <Input value={indexConfig.name || ''} disabled={!enabled} onChange={(event) => {
                  const next = [...indexes]
                  next[index] = { ...indexConfig, name: event.target.value }
                  onIndexesChange(next)
                }} />
              </div>
              <div className="space-y-1">
                <Label className="text-[10px]">{t('builder.indexColumns')}</Label>
                <Input value={indexConfig.columns.join(', ')} disabled={!enabled} onChange={(event) => {
                  const next = [...indexes]
                  next[index] = {
                    ...indexConfig,
                    columns: event.target.value.split(',').map((column) => column.trim()).filter(Boolean),
                  }
                  onIndexesChange(next)
                }} />
              </div>
              <label className="flex h-9 items-center gap-2 text-xs">
                <Checkbox checked={indexConfig.unique === true} disabled={!enabled} onCheckedChange={(checked) => {
                  const next = [...indexes]
                  next[index] = { ...indexConfig, unique: checked === true }
                  onIndexesChange(next)
                }} />
                {t('builder.uniqueIndex')}
              </label>
              <Button type="button" size="icon" variant="ghost" title={t('common.delete')} disabled={!enabled} onClick={() => onIndexesChange(indexes.filter((_, itemIndex) => itemIndex !== index))}>
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function ReviewRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 border-b border-border/50 pb-2 sm:grid-cols-[200px_1fr]">
      <span className="text-xs uppercase tracking-wide text-muted-foreground">{label}</span>
      <span>{value}</span>
    </div>
  )
}
