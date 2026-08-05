
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Search, Filter, ChevronDown, ChevronRight, AlertCircle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { logsService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ExecutionStatusBadge, DirectionBadge } from '@/components/common/badges'
import { useNavStore } from '@/stores/nav-store'
import { formatDateTime, formatDuration, formatRelative } from '@/lib/format'
import { TECH_LABELS } from '@/lib/i18n'
import type { ExecutionLogDto, ExecutionStatus } from '@/lib/api/types'

export function ExecutionsView() {
  const { t } = useTranslation()
  const { executionId } = useNavStore((s) => s.params)
  const navigate = useNavStore((s) => s.navigate)
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(executionId ? [executionId] : []))
  const [selectedExecId, setSelectedExecId] = useState<string | undefined>(executionId)

  const logsQ = useQuery({
    queryKey: ['logs', 'all'],
    queryFn: logsService.all,
    refetchInterval: 3_000,
  })

  const detailsQ = useQuery({
    queryKey: ['logs', 'details', selectedExecId],
    queryFn: () => logsService.details(selectedExecId!),
    enabled: !!selectedExecId,
    refetchInterval: 3_000,
  })
  const sourcesQ = useQuery({
    queryKey: ['logs', 'sources', selectedExecId],
    queryFn: () => logsService.sources(selectedExecId!),
    enabled: !!selectedExecId,
    refetchInterval: 5_000,
  })

  const logs = [...(logsQ.data ?? [])]
    .sort((a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime())
  const filtered = logs.filter((l) => {
    if (statusFilter !== 'ALL' && l.status !== statusFilter) return false
    if (search) {
      const s = search.toLowerCase()
      return l.workflowName?.toLowerCase().includes(s) ||
        l.workflowId?.toLowerCase().includes(s) ||
        l.triggeredBy?.toLowerCase().includes(s) ||
        l.correlationId?.toLowerCase().includes(s)
    }
    return true
  })

  function toggle(id: string) {
    const isOpen = expanded.has(id)
    setSelectedExecId(isOpen ? undefined : id)
    setExpanded(isOpen ? new Set() : new Set([id]))
  }

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title={t('common.executions')}
        description={t('nav.descriptions.executions')}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="relative w-full flex-1 sm:max-w-sm">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input placeholder={`${t('common.search')} (${t('common.workflow')}, ${TECH_LABELS.correlationId.toLowerCase()}, ${t('common.triggeredBy').toLowerCase()})...`} value={search} onChange={(e) => setSearch(e.target.value)} className="pl-8" />
        </div>
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-full sm:w-40"><Filter className="mr-1.5 h-3.5 w-3.5" /><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('common.allStatuses')}</SelectItem>
            <SelectItem value="RUNNING">{t('enums.executionStatus.RUNNING')}</SelectItem>
            <SelectItem value="SUCCESS">{t('enums.executionStatus.SUCCESS')}</SelectItem>
            <SelectItem value="FAILED">{t('enums.executionStatus.FAILED')}</SelectItem>
            <SelectItem value="DELIVERED">{t('enums.executionStatus.DELIVERED')}</SelectItem>
            <SelectItem value="PENDING">{t('enums.executionStatus.PENDING')}</SelectItem>
            <SelectItem value="CANCELLED">{t('enums.executionStatus.CANCELLED')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {logsQ.isLoading ? <LoadingState />
      : logsQ.isError ? <ErrorState message={describeError(logsQ.error)} onRetry={() => logsQ.refetch()} />
      : filtered.length === 0 ? (
        <EmptyState title={t('dashboard.noExecutionTitle')} description={t('workflows.noMatch')} />
      ) : (
        <div className="space-y-2">
          {filtered.map((l) => (
            <Card key={l.id} className="overflow-hidden">
              <button
                onClick={() => toggle(l.id)}
                className="flex w-full flex-wrap items-center gap-2 p-3 text-left hover:bg-muted/30 sm:flex-nowrap sm:gap-3"
              >
                {expanded.has(l.id) ? <ChevronDown className="h-4 w-4 text-muted-foreground" /> : <ChevronRight className="h-4 w-4 text-muted-foreground" />}
                <ExecutionStatusBadge status={l.status} />
                <DirectionBadge direction={l.direction} />
                <div className="min-w-0 flex-1 basis-[180px]">
                  <p className="truncate text-sm font-medium">{l.workflowName || l.workflowId || '—'}</p>
                  <p className="text-xs text-muted-foreground">
                    {formatRelative(l.startTime)} · {formatDuration(l.durationMs)} · {l.triggeredBy || '—'}
                  </p>
                </div>
                {l.correlationId && (
                  <Badge variant="outline" className="hidden font-mono text-[10px] sm:inline-flex">{l.correlationId.slice(0, 8)}</Badge>
                )}
                <span className="hidden text-xs text-muted-foreground lg:inline">{formatDateTime(l.startTime)}</span>
              </button>
              {expanded.has(l.id) && (
                <CardContent className="border-t border-border pt-3">
                  {detailsQ.isLoading ? <LoadingState label={t('common.loading')} />
                  : detailsQ.isError ? <ErrorState message={describeError(detailsQ.error)} onRetry={() => detailsQ.refetch()} />
                  : detailsQ.data ? (
                    <ExecutionDetails exec={detailsQ.data} sources={sourcesQ.data} />
                  ) : null}
                </CardContent>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}

function ExecutionDetails({ exec, sources }: { exec: ExecutionLogDto; sources?: import('@/lib/api/types').SourceMetric[] }) {
  const { t } = useTranslation()
  return (
    <div className="space-y-4">
      {/* Méta */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Detail label={`${t('common.id')} ${t('common.execution').toLowerCase()}`} value={<code className="font-mono text-xs">{exec.id}</code>} />
        <Detail label={t('common.status')} value={<ExecutionStatusBadge status={exec.status} />} />
        <Detail label="Direction" value={<DirectionBadge direction={exec.direction} />} />
        <Detail label={t('common.triggeredBy')} value={exec.triggeredBy || '—'} />
        <Detail label={t('common.start')} value={formatDateTime(exec.startTime)} />
        <Detail label={t('common.end')} value={formatDateTime(exec.endTime)} />
        <Detail label={t('common.duration')} value={formatDuration(exec.durationMs)} />
        <Detail label={TECH_LABELS.correlationId} value={exec.correlationId ? <code className="font-mono text-xs">{exec.correlationId}</code> : '—'} />
        <Detail label="Etape active" value={exec.currentStage || '—'} />
        <Detail label="Dernier signal" value={formatDateTime(exec.lastHeartbeatAt)} />
      </div>

      {/* Watermarks */}
      {exec.lastSuccessfulWatermarks && Object.keys(exec.lastSuccessfulWatermarks).length > 0 && (
        <div>
          <p className="mb-2 text-xs font-medium">{TECH_LABELS.last_watermark} / {t('common.source').toLowerCase()}</p>
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-xs">
              <thead className="bg-muted/40">
                <tr className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
                  <th className="px-2 py-1.5">{t('common.source')}</th>
                  <th className="px-2 py-1.5">{TECH_LABELS.last_watermark}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {Object.entries(exec.lastSuccessfulWatermarks).map(([k, v]) => (
                  <tr key={k}>
                    <td className="px-2 py-1.5 font-mono">{k}</td>
                    <td className="px-2 py-1.5 text-muted-foreground">{String(v)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Source metrics */}
      {sources && sources.length > 0 && (
        <div>
          <p className="mb-2 text-xs font-medium">Metrics / {t('common.source').toLowerCase()}</p>
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full text-xs">
              <thead className="bg-muted/40">
                <tr className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
                  <th className="px-2 py-1.5">{t('common.source')}</th>
                  <th className="px-2 py-1.5">{t('common.status')}</th>
                  <th className="px-2 py-1.5">Lignes lues</th>
                  <th className="px-2 py-1.5">Lignes ecrites</th>
                  <th className="px-2 py-1.5">{TECH_LABELS.last_watermark}</th>
                  <th className="px-2 py-1.5">{t('common.duration')}</th>
                  <th className="px-2 py-1.5">{t('common.error')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {sources.map((s, i) => (
                  <tr key={i}>
                    <td className="px-2 py-1.5 font-mono">{s.sourceName}</td>
                    <td className="px-2 py-1.5">{s.status || '—'}</td>
                    <td className="px-2 py-1.5">{s.rowsRead ?? '—'}</td>
                    <td className="px-2 py-1.5">{s.rowsWritten ?? '—'}</td>
                    <td className="px-2 py-1.5 text-muted-foreground">{s.watermark || s.lastWatermark || '—'}</td>
                    <td className="px-2 py-1.5">{formatDuration(s.durationMs)}</td>
                    <td className="px-2 py-1.5 text-destructive">{s.errorMessage || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Log output */}
      {(exec.detailedLogs || exec.logOutput) && (
        <div>
          <p className="mb-2 text-xs font-medium">Log</p>
          <pre className="sql-block max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/30 p-3">{exec.detailedLogs || exec.logOutput}</pre>
        </div>
      )}

      {/* Erreur */}
      {exec.errorMessage && (
        <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="min-w-0">
            <p className="font-medium">
              {t('common.error')} {t('common.execution').toLowerCase()}
              {exec.failedStage ? ` · étape ${exec.failedStage}` : ''}
            </p>
            <pre className="mt-1 whitespace-pre-wrap break-words font-mono">{exec.errorMessage}</pre>
          </div>
        </div>
      )}
    </div>
  )
}

function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-md border border-border bg-muted/20 p-2.5">
      <p className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-0.5 text-sm">{value}</p>
    </div>
  )
}
