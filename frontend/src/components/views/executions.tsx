
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Search, Filter, ChevronDown, ChevronRight, AlertCircle, Trash2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { logsService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ExecutionStatusBadge, DirectionBadge } from '@/components/common/badges'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { DataTable, THead, TBody, Th, Tr, Td } from '@/components/common/data-table'
import { useToast } from '@/hooks/use-toast'
import { useNavStore } from '@/stores/nav-store'
import { formatDateTime, formatDuration, formatRelative } from '@/lib/format'
import { TECH_LABELS } from '@/lib/i18n'
import type { ExecutionLogDto, ExecutionStatus } from '@/lib/api/types'

export function ExecutionsView() {
  const { t } = useTranslation()
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const { executionId } = useNavStore((s) => s.params)
  const navigate = useNavStore((s) => s.navigate)
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(executionId ? [executionId] : []))
  const [selectedExecId, setSelectedExecId] = useState<string | undefined>(executionId)
  const [pendingDelete, setPendingDelete] = useState<ExecutionLogDto | null>(null)

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

  const deleteMutation = useMutation({
    mutationFn: (id: string) => logsService.remove(id),
    onSuccess: (_data, id) => {
      // La ligne disparait : on referme le detail s'il pointait dessus.
      if (selectedExecId === id) setSelectedExecId(undefined)
      setExpanded((current) => {
        const next = new Set(current)
        next.delete(id)
        return next
      })
      void queryClient.invalidateQueries({ queryKey: ['logs'] })
      toast({ title: t('executions.deleted') })
    },
    onError: (error) => {
      toast({
        variant: 'destructive',
        title: t('executions.deleteFailed'),
        description: describeError(error),
      })
    },
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
              {/* Le bouton de suppression est un frere du bouton d'expansion,
                  jamais un enfant : imbriquer deux <button> est invalide. */}
              <div className="flex items-center">
                <button
                  onClick={() => toggle(l.id)}
                  className="flex min-w-0 flex-1 flex-wrap items-center gap-2 p-3 text-left hover:bg-muted/30 sm:flex-nowrap sm:gap-3"
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
                <Button
                  variant="ghost"
                  size="icon"
                  className="mr-2 h-8 w-8 shrink-0 text-muted-foreground hover:text-destructive"
                  onClick={() => setPendingDelete(l)}
                  disabled={l.status === 'RUNNING' || deleteMutation.isPending}
                  title={l.status === 'RUNNING' ? t('executions.deleteRunning') : t('executions.delete')}
                  aria-label={t('executions.delete')}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
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

      <ConfirmDialog
        open={!!pendingDelete}
        onOpenChange={(open) => { if (!open) setPendingDelete(null) }}
        variant="destructive"
        pending={deleteMutation.isPending}
        title={t('confirm.deleteExecutionTitle', { date: formatDateTime(pendingDelete?.startTime) })}
        description={t('confirm.deleteExecutionDescription')}
        confirmLabel={t('common.delete')}
        onConfirm={async () => {
          if (!pendingDelete) return
          await deleteMutation.mutateAsync(pendingDelete.id).catch(() => undefined)
          setPendingDelete(null)
        }}
      />
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
          <DataTable>
            <THead>
              <Th>{t('common.source')}</Th>
              <Th align="right">{TECH_LABELS.last_watermark}</Th>
            </THead>
            <TBody>
              {Object.entries(exec.lastSuccessfulWatermarks).map(([k, v]) => (
                <Tr key={k}>
                  <Td strong className="font-mono">{k}</Td>
                  <Td muted numeric>{String(v)}</Td>
                </Tr>
              ))}
            </TBody>
          </DataTable>
        </div>
      )}

      {/* Source metrics */}
      {sources && sources.length > 0 && (
        <div>
          <p className="mb-2 text-xs font-medium">Metrics / {t('common.source').toLowerCase()}</p>
          <DataTable minWidth={760}>
            <THead>
              <Th>{t('common.source')}</Th>
              <Th>{t('common.status')}</Th>
              <Th>{t('common.error')}</Th>
              <Th align="right">Lignes lues</Th>
              <Th align="right">Lignes ecrites</Th>
              <Th align="right">{TECH_LABELS.last_watermark}</Th>
              <Th align="right">{t('common.duration')}</Th>
            </THead>
            <TBody>
              {sources.map((s, i) => (
                <Tr key={i}>
                  <Td strong className="font-mono">{s.sourceName}</Td>
                  <Td>{s.status || '—'}</Td>
                  <Td className="text-destructive">{s.errorMessage || '—'}</Td>
                  <Td numeric>{s.rowsRead ?? '—'}</Td>
                  <Td numeric>{s.rowsWritten ?? '—'}</Td>
                  <Td muted numeric>{s.watermark || s.lastWatermark || '—'}</Td>
                  <Td muted numeric>{formatDuration(s.durationMs)}</Td>
                </Tr>
              ))}
            </TBody>
          </DataTable>
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
