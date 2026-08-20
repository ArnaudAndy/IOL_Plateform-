
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Search, Link2, AlertCircle, ArrowDownToLine, Activity, Send, Loader2, Eye } from 'lucide-react'
import { interopTestService, logsService, standardService, workflowService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { DataTable, THead, TBody, Th, Tr, Td } from '@/components/common/data-table'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { ExecutionStatusBadge } from '@/components/common/badges'
import { formatDateTime, formatDuration, formatRelative } from '@/lib/format'
import { TECH_LABELS } from '@/lib/i18n'
import { useToast } from '@/hooks/use-toast'
import { useNavStore } from '@/stores/nav-store'
import type { StandardDto } from '@/lib/api/types'

type InteropProfile = 'fhir' | 'iso20022' | 'edfi' | 'generic'
type PayloadFormat = 'json' | 'xml' | 'ndjson'

function interopProfile(standard?: StandardDto): InteropProfile {
  const identity = `${standard?.id ?? ''} ${standard?.name ?? ''}`.toLowerCase()
  if (identity.includes('fhir')) return 'fhir'
  if (identity.includes('iso20022') || identity.includes('iso 20022')) return 'iso20022'
  if (identity.includes('edfi') || identity.includes('ed-fi')) return 'edfi'
  return 'generic'
}

function formatsFor(profile: InteropProfile): Array<{ value: PayloadFormat; label: string }> {
  if (profile === 'fhir') return [{ value: 'json', label: 'FHIR JSON' }, { value: 'xml', label: 'FHIR XML' }]
  if (profile === 'iso20022') return [{ value: 'xml', label: 'ISO 20022 XML' }, { value: 'json', label: 'Lot JSON' }]
  if (profile === 'edfi') return [{ value: 'json', label: 'Ed-Fi JSON' }, { value: 'ndjson', label: 'Ed-Fi NDJSON' }]
  return [{ value: 'json', label: 'JSON' }, { value: 'ndjson', label: 'NDJSON' }]
}

function samplePayload(profile: InteropProfile, format: PayloadFormat): string {
  if (profile === 'fhir') {
    if (format === 'xml') {
      return '<Patient xmlns="http://hl7.org/fhir">\n  <id value="patient-1001"/>\n  <active value="true"/>\n</Patient>'
    }
    return '{\n  "resourceType": "Patient",\n  "id": "patient-1001",\n  "active": true\n}'
  }
  if (profile === 'iso20022') {
    const xml = '<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.11"><CstmrCdtTrfInitn><GrpHdr><MsgId>MSG-001</MsgId><CreDtTm>2026-07-30T12:00:00Z</CreDtTm><NbOfTxs>0</NbOfTxs><InitgPty><Nm>IOL</Nm></InitgPty></GrpHdr></CstmrCdtTrfInitn></Document>'
    return format === 'json' ? JSON.stringify({ xml }, null, 2) : xml
  }
  if (profile === 'edfi') {
    const first = JSON.stringify({ studentUniqueId: 'S001', firstName: 'Ada', lastSurname: 'Lovelace' }, null, format === 'json' ? 2 : 0)
    const second = JSON.stringify({ studentUniqueId: 'S002', firstName: 'Grace', lastSurname: 'Hopper' })
    return format === 'ndjson' ? `${first}\n${second}` : first
  }
  if (format === 'ndjson') {
    return '{"externalId":"EXT-1001","status":"ACTIVE"}\n{"externalId":"EXT-1002","status":"ACTIVE"}'
  }
  return '{\n  "externalId": "EXT-1001",\n  "status": "ACTIVE"\n}'
}

export function InteropView() {
  const { toast } = useToast()
  const navigate = useNavStore((state) => state.navigate)
  const queryClient = useQueryClient()
  const [correlationId, setCorrelationId] = useState('')
  const [searchedCorrelation, setSearchedCorrelation] = useState('')
  const [standardId, setStandardId] = useState('')
  const [workflowId, setWorkflowId] = useState('')
  const [sourceSystem, setSourceSystem] = useState('hospital_a')
  const [payloadFormat, setPayloadFormat] = useState<PayloadFormat>('json')
  const [testPayload, setTestPayload] = useState(samplePayload('generic', 'json'))
  const [testResult, setTestResult] = useState<unknown>(null)

  const standardsQ = useQuery({ queryKey: ['standards'], queryFn: standardService.list })
  const workflowsQ = useQuery({ queryKey: ['workflows'], queryFn: workflowService.list })
  const inboundWorkflows = useMemo(
    () => (workflowsQ.data ?? []).filter((workflow) => workflow.direction === 'INBOUND'),
    [workflowsQ.data],
  )
  const selectedStandard = useMemo(
    () => (standardsQ.data ?? []).find((standard) => standard.id === standardId),
    [standardId, standardsQ.data],
  )
  const profile = useMemo(() => interopProfile(selectedStandard), [selectedStandard])
  const matchingWorkflows = useMemo(
    () => inboundWorkflows.filter((workflow) => workflow.standardId === standardId),
    [inboundWorkflows, standardId],
  )
  const availableFormats = useMemo(() => formatsFor(profile), [profile])

  useEffect(() => {
    if (!standardId && standardsQ.data?.[0]?.id) setStandardId(standardsQ.data[0].id)
    if (!matchingWorkflows.some((workflow) => workflow.id === workflowId)) {
      setWorkflowId(matchingWorkflows[0]?.id ?? '')
    }
  }, [standardId, workflowId, standardsQ.data, matchingWorkflows])

  useEffect(() => {
    setPayloadFormat(formatsFor(profile)[0].value)
  }, [profile])

  useEffect(() => {
    setTestPayload(samplePayload(profile, payloadFormat))
  }, [profile, payloadFormat])

  const testMut = useMutation({
    mutationFn: async () => {
      const generatedCorrelation = crypto.randomUUID()
      return interopTestService.send({
        standardId,
        workflowId,
        sourceSystem,
        correlationId: generatedCorrelation,
        profile,
        format: payloadFormat,
        payload: testPayload,
      })
    },
    onSuccess: (result) => {
      setTestResult(result)
      setCorrelationId(result.correlationId)
      setSearchedCorrelation(result.correlationId)
      queryClient.invalidateQueries({ queryKey: ['logs'] })
      toast({ title: 'Message accepté par OpenHIM', description: `Suivi ${result.correlationId}` })
    },
    onError: (error) => toast({ title: 'Échec du test', description: describeError(error), variant: 'destructive' }),
  })

  // Summary
  const summaryQ = useQuery({
    queryKey: ['logs', 'interop-summary'],
    queryFn: logsService.interopSummary,
    refetchInterval: 10_000,
  })

  // External receptions
  const interopQ = useQuery({
    queryKey: ['logs', 'interop'],
    queryFn: logsService.interop,
    refetchInterval: 15_000,
  })

  // Correlation trace (on-demand)
  const correlationQ = useQuery({
    queryKey: ['logs', 'interop', 'correlation', searchedCorrelation],
    queryFn: () => logsService.interopCorrelation(searchedCorrelation),
    enabled: !!searchedCorrelation,
  })

  return (
    <div className="mx-auto w-full min-w-0 max-w-7xl">
      <PageHeader
        title="Réceptions externes"
        description="Exécutions des données envoyées par des systèmes externes, avec synthèse et suivi par identifiant."
      />

      <Card className="mb-4">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-sm"><Send className="h-4 w-4" /> Test réel OpenHIM</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 md:grid-cols-2">
            <div className="space-y-1.5">
              <Label className="text-xs">Norme</Label>
              <Select value={standardId} onValueChange={setStandardId} disabled={standardsQ.isLoading}>
                <SelectTrigger><SelectValue placeholder="Choisir une norme" /></SelectTrigger>
                <SelectContent>{(standardsQ.data ?? []).map((standard) => <SelectItem key={standard.id} value={standard.id}>{standard.name}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">Workflow INBOUND</Label>
              <Select value={workflowId} onValueChange={setWorkflowId} disabled={workflowsQ.isLoading}>
                <SelectTrigger><SelectValue placeholder="Choisir un workflow" /></SelectTrigger>
                <SelectContent>{matchingWorkflows.map((workflow) => <SelectItem key={workflow.id} value={workflow.id!}>{workflow.name}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5"><Label className="text-xs">Système source</Label><Input value={sourceSystem} onChange={(event) => setSourceSystem(event.target.value)} /></div>
            <div className="space-y-1.5">
              <Label className="text-xs">Format du message</Label>
              <Select value={payloadFormat} onValueChange={(value) => setPayloadFormat(value as PayloadFormat)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>{availableFormats.map((format) => <SelectItem key={format.value} value={format.value}>{format.label}</SelectItem>)}</SelectContent>
              </Select>
            </div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Données envoyées</Label><Textarea value={testPayload} onChange={(event) => setTestPayload(event.target.value)} rows={7} className="font-mono text-xs" /></div>
          <Button onClick={() => testMut.mutate()} disabled={testMut.isPending || !standardId || !workflowId || !sourceSystem}>
            {testMut.isPending ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <Send className="mr-1.5 h-4 w-4" />}
            Envoyer par OpenHIM
          </Button>
          {testResult !== null && <pre className="max-h-64 max-w-full overflow-auto whitespace-pre-wrap break-all rounded-md border border-border bg-muted/20 p-3 text-xs">{JSON.stringify(testResult, null, 2)}</pre>}
        </CardContent>
      </Card>

      {/* Summary */}
      <Card className="mb-4">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Activity className="h-4 w-4 text-accent-foreground" />
            Synthèse temps réel
            <span className="ml-auto text-[10px] font-normal text-muted-foreground">polling 10s</span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {summaryQ.isLoading ? <LoadingState label="Chargement…" />
          : summaryQ.isError ? <ErrorState message={describeError(summaryQ.error)} onRetry={() => summaryQ.refetch()} />
          : summaryQ.data ? (
            <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
              <Stat label="Total réception" value={summaryQ.data.totalInbound ?? 0} />
              <Stat label="En cours" value={summaryQ.data.running ?? 0} tone="info" />
              <Stat label="Succès" value={summaryQ.data.success ?? 0} tone="success" />
              <Stat label="Échecs" value={summaryQ.data.failed ?? 0} tone="danger" />
              <Stat label={TECH_LABELS.dlq} value={summaryQ.data.dlqCount ?? 0} tone="warning" />
              <Stat label="24h" value={summaryQ.data.last24h ?? 0} />
            </div>
          ) : <EmptyState title="Aucune donnée" />}
        </CardContent>
      </Card>

      {/* Correlation search */}
      <Card className="mb-4">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Link2 className="h-4 w-4" /> Tracer une transaction par {TECH_LABELS.correlationId.toLowerCase()}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              placeholder={`Saisir un ${TECH_LABELS.correlationId.toLowerCase()}…`}
              value={correlationId}
              onChange={(e) => setCorrelationId(e.target.value)}
              className="font-mono"
              onKeyDown={(e) => { if (e.key === 'Enter' && correlationId) setSearchedCorrelation(correlationId) }}
            />
            <Button onClick={() => setSearchedCorrelation(correlationId)} disabled={!correlationId}>
              <Search className="mr-1.5 h-3.5 w-3.5" /> Tracer
            </Button>
          </div>

          {correlationQ.isError && (
            <div className="mt-3"><ErrorState message={describeError(correlationQ.error)} onRetry={() => correlationQ.refetch()} /></div>
          )}
          {correlationQ.data && (
            <div className="mt-3">
              {correlationQ.data.length === 0 ? (
                <EmptyState title="Aucune exécution trouvée" description={`Aucune exécution pour cet identifiant : "${searchedCorrelation}".`} icon={AlertCircle} />
              ) : (
                <div className="space-y-2">
                  {correlationQ.data.map((l) => (
                    <div key={l.id} className="rounded-md border border-border bg-muted/20 p-3 text-xs">
                       <div className="flex flex-wrap items-center gap-2">
                        <ExecutionStatusBadge status={l.status} />
                        <span className="font-medium">{l.workflowName || l.workflowId}</span>
                         <span className="ml-auto text-muted-foreground">{formatDateTime(l.startTime)}</span>
                         {l.workflowId && (
                           <Button
                             size="icon"
                             variant="ghost"
                             title="Ouvrir le traitement"
                             aria-label="Ouvrir le traitement"
                             onClick={() => navigate('workflow-detail', { id: l.workflowId })}
                           >
                             <Eye className="h-4 w-4" />
                           </Button>
                         )}
                      </div>
                      <pre className="mt-2 whitespace-pre-wrap text-[10px] text-muted-foreground">{l.logOutput || l.errorMessage || '—'}</pre>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Interop executions list */}
      <div>
        <h2 className="mb-3 text-sm font-semibold">Toutes les réceptions externes</h2>
        {interopQ.isLoading ? <LoadingState />
        : interopQ.isError ? <ErrorState message={describeError(interopQ.error)} onRetry={() => interopQ.refetch()} />
        : (interopQ.data ?? []).length === 0 ? (
          <EmptyState title="Aucune réception externe" description="Les données envoyées par des systèmes externes apparaîtront ici." icon={ArrowDownToLine} />
        ) : (
          <DataTable minWidth={760}>
            <THead>
              <Th>Traitement</Th>
              <Th>Statut</Th>
              <Th>Par</Th>
              <Th align="right">{TECH_LABELS.correlationId}</Th>
              <Th align="right">Début</Th>
              <Th align="right">Durée</Th>
              <Th align="right"></Th>
            </THead>
            <TBody>
              {(interopQ.data ?? []).map((l) => (
                <Tr key={l.id}>
                  <Td strong>{l.workflowName || l.workflowId}</Td>
                  <Td><ExecutionStatusBadge status={l.status} /></Td>
                  <Td muted>{l.triggeredBy || '—'}</Td>
                  <Td numeric className="font-mono text-xs">{l.correlationId?.slice(0, 12) || '—'}</Td>
                  <Td muted numeric>{formatRelative(l.startTime)}</Td>
                  <Td muted numeric>{formatDuration(l.durationMs)}</Td>
                  <Td align="right" className="py-2">
                    {l.workflowId && (
                      <Button
                        size="icon"
                        variant="ghost"
                        title="Ouvrir le traitement"
                        aria-label="Ouvrir le traitement"
                        onClick={() => navigate('workflow-detail', { id: l.workflowId })}
                      >
                        <Eye className="h-4 w-4" />
                      </Button>
                    )}
                  </Td>
                </Tr>
              ))}
            </TBody>
          </DataTable>
        )}
      </div>

    </div>
  )
}

function Stat({ label, value, tone = 'default' }: { label: string; value: number; tone?: 'default' | 'info' | 'success' | 'danger' | 'warning' }) {
  const toneCls = {
    default: 'text-foreground',
    info: 'text-info',
    success: 'text-success',
    danger: 'text-destructive',
    warning: 'text-warning',
  }[tone]
  return (
    <div className="rounded-md border border-border bg-muted/20 p-3">
      <p className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${toneCls}`}>{value}</p>
    </div>
  )
}
