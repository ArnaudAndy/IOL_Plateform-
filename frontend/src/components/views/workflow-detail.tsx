
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Play, Pencil, Download, RefreshCw, Code2, Database, Calendar, ListTree } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { workflowService, orchestratorService, logsService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useNavStore } from '@/stores/nav-store'
import { useAuthStore } from '@/stores/auth-store'
import { DirectionBadge, ExecutionStatusBadge } from '@/components/common/badges'
import { useToast } from '@/hooks/use-toast'
import { formatDateTime, formatDuration, formatRelative } from '@/lib/format'
import { loadModeLabel, writeModeLabel, TECH_LABELS } from '@/lib/i18n'

export function WorkflowDetailView() {
  const { t } = useTranslation()
  const { id, tab } = useNavStore((s) => s.params)
  const navigate = useNavStore((s) => s.navigate)
  const isAdmin = useAuthStore((s) => s.isAdmin)
  const { toast } = useToast()
  const queryClient = useQueryClient()

  const wfQ = useQuery({
    queryKey: ['workflow', id],
    queryFn: () => workflowService.get(id!),
    enabled: !!id,
  })

  const logsQ = useQuery({
    queryKey: ['logs', 'workflow', id],
    queryFn: () => logsService.byWorkflow(id!),
    enabled: !!id,
    refetchInterval: 10_000,
  })

  const perfQ = useQuery({
    queryKey: ['logs', 'performance', id],
    queryFn: () => logsService.performance(id!),
    enabled: !!id,
  })

  const discoverQ = useQuery({
    queryKey: ['workflow', id, 'discover'],
    queryFn: () => workflowService.discoverExisting(id!),
    enabled: false, // on-demand
  })

  const runMut = useMutation({
    mutationFn: () => orchestratorService.run(id!),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['logs'] })
      toast({ title: t('workflows.toastRun'), description: `${t('common.execution')} ${data.id}` })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const exportMut = useMutation({
    mutationFn: () => workflowService.export(id!),
    onSuccess: (data) => {
      const blob = new Blob([typeof data === 'string' ? data : JSON.stringify(data, null, 2)], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = `workflow-${id}.txt`; a.click()
      URL.revokeObjectURL(url)
      toast({ title: t('workflows.toastExported') })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  if (!id) {
    return <EmptyState title={`${t('common.none')} ${t('common.workflow').toLowerCase()}`} description={t('workflows.readOnlyEmpty')} />
  }
  if (wfQ.isLoading) return <LoadingState />
  if (wfQ.isError) return <ErrorState message={describeError(wfQ.error)} onRetry={() => wfQ.refetch()} />

  const wf = wfQ.data
  const logs = logsQ.data ?? []
  const perf = perfQ.data

  if (!wf) {
    return <EmptyState title={`${t('common.workflow')} introuvable`} description={t('common.error')} />
  }

  return (
    <div className="mx-auto w-full min-w-0 max-w-7xl">
      <PageHeader
        title={wf.name}
        description={wf.description}
        actions={
          <>
            <Button variant="outline" onClick={() => discoverQ.refetch()}>
              <RefreshCw className="mr-1.5 h-3.5 w-3.5" /> {t('builder.discoveryTitle')}
            </Button>
            <Button variant="outline" disabled={!isAdmin} onClick={() => exportMut.mutate()}>
              <Download className="mr-1.5 h-3.5 w-3.5" /> {t('common.export')}
            </Button>
            <Button variant="outline" disabled={!isAdmin} onClick={() => navigate('workflow-builder', { id })}>
              <Pencil className="mr-1.5 h-3.5 w-3.5" /> {t('common.edit')}
            </Button>
            <Button disabled={!isAdmin || runMut.isPending} onClick={() => runMut.mutate()}>
              <Play className="mr-1.5 h-3.5 w-3.5" /> {t('common.run')}
            </Button>
          </>
        }
      />

      <div className="mb-4 flex min-w-0 flex-wrap items-center gap-2 text-xs">
        <DirectionBadge direction={wf.direction} />
        <span className="rounded-md border border-border bg-muted px-2 py-0.5">{wf.status || '—'}</span>
        <span className="text-muted-foreground">{t('common.priority')} : {wf.priority ?? '—'}</span>
        <span className="text-muted-foreground">{TECH_LABELS.standard} : {wf.standardId || (wf.standardDomain ? `${wf.standardDomain} (${t('workflows.standardDeprecated')})` : '—')}</span>
        <span className="min-w-0 text-muted-foreground">ID : <code className="break-all font-mono">{wf.id}</code></span>
      </div>

      <Tabs
        value={tab || 'overview'}
        onValueChange={(value) => navigate('workflow-detail', { id, tab: value })}
        className="w-full"
      >
        <TabsList className="w-full justify-start">
          <TabsTrigger className="flex-none" value="overview">{t('nav.descriptions.dashboard')}</TabsTrigger>
          <TabsTrigger className="flex-none" value="sources">{t('common.sources')} ({wf.sources?.length || 0})</TabsTrigger>
          <TabsTrigger className="flex-none" value="schedule">{t('fields.schedule')}</TabsTrigger>
          <TabsTrigger className="flex-none" value="gold">{TECH_LABELS.gold}</TabsTrigger>
          <TabsTrigger className="flex-none" value="executions">{t('common.executions')} ({logs.length})</TabsTrigger>
          <TabsTrigger className="flex-none" value="performance">Performance</TabsTrigger>
          <TabsTrigger className="flex-none" value="discover">{t('builder.discoveryTitle')}</TabsTrigger>
        </TabsList>

        {/* Overview */}
        <TabsContent value="overview">
          <Card>
            <CardHeader><CardTitle className="text-sm">{t('builder.generalConfiguration')}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              <Row label={t('common.name')} value={wf.name} />
              <Row label="Direction" value={<DirectionBadge direction={wf.direction} />} />
              <Row label={TECH_LABELS.standard} value={wf.standardId || '—'} />
              <Row label="Domaine de norme (ancienne valeur)" value={wf.standardDomain || '—'} />
              <Row label={t('common.priority')} value={String(wf.priority ?? '—')} />
              <Row label={t('common.status')} value={wf.status || '—'} />
              <Row label={t('common.createdAt')} value={formatDateTime(wf.createdAt)} />
              <Row label={t('common.updatedAt')} value={formatDateTime(wf.updatedAt)} />
              <Row label={t('common.createdBy')} value={wf.createdBy || '—'} />
            </CardContent>
          </Card>
        </TabsContent>

        {/* Sources */}
        <TabsContent value="sources">
          <div className="grid gap-3">
            {(wf.sources ?? []).map((s, i) => (
              <Card key={i}>
                <CardHeader className="pb-2">
                  <CardTitle className="flex min-w-0 items-center gap-2 break-words text-sm">
                    <Database className="h-4 w-4" />
                    {s.source_name}
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-2 text-xs">
                  <Row label={`${t('common.target')} ${TECH_LABELS.bronze.toLowerCase()}`} value={s.target_table || '—'} />
                  <Row label={`${t('common.target')} ${TECH_LABELS.silver.toLowerCase()}`} value={s.silver_config?.target_table_silver || '—'} />
                  <Row label={t('fields.filePath')} value={s.file_path || s.uri || '—'} />
                  <Row label={t('common.connection')} value={s.connectionName || s.connectionId || '—'} />
                  <Row label={t('fields.loadMode')} value={loadModeLabel(s.load_mode)} />
                  <Row label={t('fields.incrementalColumn')} value={s.incremental_column || '—'} />
                  <Row label={TECH_LABELS.last_watermark} value={s.last_watermark || '—'} />
                  <Row label={t('fields.writeMode')} value={writeModeLabel(s.write_mode)} />
                  <Row label="Parametres source" value={<code className="block max-h-32 overflow-auto rounded bg-muted p-2 font-mono text-[10px]">{JSON.stringify(s.source_config || {}, null, 2)}</code>} />
                  <div>
                    <p className="mb-1 text-[10px] uppercase tracking-wide text-muted-foreground">{t('common.columns')} ({s.fields?.length || 0})</p>
                    <div className="overflow-x-auto rounded-md border border-border">
                      <table className="w-full min-w-[680px] text-xs">
                        <thead className="bg-muted/40">
                          <tr className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
                            <th className="px-2 py-1">{t('common.name')}</th>
                            <th className="px-2 py-1">{t('fields.originalName')}</th>
                            <th className="px-2 py-1">{t('common.type')}</th>
                            <th className="px-2 py-1">{t('fields.selected')}</th>
                            <th className="px-2 py-1">{t('fields.semanticTerm')}</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                          {(s.fields ?? []).map((f, j) => (
                            <tr key={j}>
                              <td className="px-2 py-1 font-mono">{f.name}</td>
                              <td className="px-2 py-1 text-muted-foreground">{f.originalName || '—'}</td>
                              <td className="px-2 py-1 text-muted-foreground">{f.type || '—'}</td>
                              <td className="px-2 py-1">{f.selected ? '✓' : '—'}</td>
                              <td className="px-2 py-1 text-accent-foreground">{f.semanticTerm || '—'}</td>
                            </tr>
                          ))}
                          {(!s.fields || s.fields.length === 0) && (
                            <tr><td colSpan={5} className="px-2 py-3 text-center text-muted-foreground">{t('builder.noColumns')}</td></tr>
                          )}
                        </tbody>
                      </table>
                    </div>
                  </div>
                  {s.silver_config?.elt_scripts_silver && (
                    <div>
                      <p className="mb-1 text-[10px] uppercase tracking-wide text-muted-foreground">SQL {TECH_LABELS.silver.toLowerCase()}</p>
                      <pre className="sql-block max-h-64 max-w-full overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/30 p-3 text-foreground">{s.silver_config.elt_scripts_silver}</pre>
                    </div>
                  )}
                </CardContent>
              </Card>
            ))}
            {(!wf.sources || wf.sources.length === 0) && (
              <EmptyState title={`${t('common.none')} ${t('common.source').toLowerCase()}`} description={t('builder.emptyNoSourceDescription')} />
            )}
          </div>
        </TabsContent>

        {/* Schedule */}
        <TabsContent value="schedule">
          <Card>
            <CardHeader><CardTitle className="flex items-center gap-2 text-sm"><Calendar className="h-4 w-4" /> {t('fields.schedule')}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              <Row label={t('fields.schedule')} value={wf.schedule?.enabled ? t('common.yes') : t('common.no')} />
              <Row label="Cron" value={wf.schedule?.cron || '—'} />
              <Row label={t('fields.frequency')} value={wf.schedule?.frequency || '—'} />
              <Row label={t('fields.time')} value={wf.schedule?.time || '—'} />
              <Row label={t('fields.loadMode')} value={loadModeLabel(wf.schedule?.loadMode)} />
              <Row label={t('fields.incrementalColumn')} value={wf.schedule?.incrementalColumn || '—'} />
            </CardContent>
          </Card>
        </TabsContent>

        {/* Final data */}
        <TabsContent value="gold">
          <Card>
            <CardHeader><CardTitle className="flex items-center gap-2 text-sm"><Code2 className="h-4 w-4" /> {TECH_LABELS.gold}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              <Row label={t('builder.tableTargetRaw', { layer: TECH_LABELS.gold.toLowerCase() })} value={wf.goldConfigGlobal?.target_table_gold || '—'} />
              {wf.goldConfigGlobal?.elt_scripts_gold && (
                <div>
                  <p className="mb-1 text-[10px] uppercase tracking-wide text-muted-foreground">SQL {TECH_LABELS.gold.toLowerCase()}</p>
                  <pre className="sql-block max-h-96 max-w-full overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/30 p-3">{wf.goldConfigGlobal.elt_scripts_gold}</pre>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* Executions */}
        <TabsContent value="executions">
          {logsQ.isLoading ? <LoadingState /> : logsQ.isError ? <ErrorState message={describeError(logsQ.error)} onRetry={() => logsQ.refetch()} /> : logs.length === 0 ? (
            <EmptyState title={t('dashboard.noExecutionTitle')} description={t('dashboard.noExecutionDescription')} />
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full min-w-[760px] text-sm">
                <thead className="bg-muted/40">
                  <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="px-3 py-2">{t('common.status')}</th>
                    <th className="px-3 py-2">{t('common.start')}</th>
                    <th className="px-3 py-2">{t('common.end')}</th>
                    <th className="px-3 py-2">{t('common.duration')}</th>
                    <th className="px-3 py-2">{t('common.triggeredBy')}</th>
                    <th className="px-3 py-2">{TECH_LABELS.correlationId}</th>
                    <th className="px-3 py-2"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {logs.map((l) => (
                    <tr key={l.id} className="hover:bg-muted/30">
                      <td className="px-3 py-2"><ExecutionStatusBadge status={l.status} /></td>
                      <td className="px-3 py-2 text-muted-foreground">{formatRelative(l.startTime)}</td>
                      <td className="px-3 py-2 text-muted-foreground">{formatRelative(l.endTime)}</td>
                      <td className="px-3 py-2">{formatDuration(l.durationMs)}</td>
                      <td className="px-3 py-2 text-muted-foreground">{l.triggeredBy || '—'}</td>
                      <td className="px-3 py-2 font-mono text-[11px]">{l.correlationId?.slice(0, 8) || '—'}</td>
                      <td className="px-3 py-2 text-right">
                        <Button size="sm" variant="ghost" onClick={() => navigate('executions', { executionId: l.id })}>
                          {t('common.details')}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </TabsContent>

        {/* Performance */}
        <TabsContent value="performance">
          {perfQ.isLoading ? <LoadingState /> : perfQ.isError ? <ErrorState message={describeError(perfQ.error)} onRetry={() => perfQ.refetch()} /> : perf ? (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <PerfCard label={`Total ${t('common.executions').toLowerCase()}`} value={perf.totalExecutions ?? 0} />
              <PerfCard label={t('common.success')} value={perf.successCount ?? 0} tone="success" />
              <PerfCard label={t('common.failure')} value={perf.failureCount ?? 0} tone="danger" />
              <PerfCard label={`${t('common.success')} %`} value={`${((perf.successRate ?? 0) * 100).toFixed(1)}%`} />
              <PerfCard label={`${t('common.duration')} moyenne`} value={formatDuration(perf.averageDurationMs)} />
              {perf.lastExecution && (
                <Card className="sm:col-span-2 lg:col-span-2">
                  <CardHeader><CardTitle className="text-sm">Derniere {t('common.execution').toLowerCase()}</CardTitle></CardHeader>
                  <CardContent className="text-xs">
                    <Row label={t('common.status')} value={<ExecutionStatusBadge status={perf.lastExecution.status} />} />
                    <Row label={t('common.start')} value={formatDateTime(perf.lastExecution.startTime)} />
                    <Row label={t('common.duration')} value={formatDuration(perf.lastExecution.durationMs)} />
                    <Row label={t('common.triggeredBy')} value={perf.lastExecution.triggeredBy || '—'} />
                  </CardContent>
                </Card>
              )}
            </div>
          ) : <EmptyState title="Aucune metrique" />}
        </TabsContent>

        {/* Schema discovery (on-demand) */}
        <TabsContent value="discover">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <ListTree className="h-4 w-4" /> {t('builder.discoveryTitle')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              {discoverQ.fetchStatus === 'idle' ? (
                <EmptyState
                  title="Decouverte non lancee"
                  description={t('builder.discoverDescription')}
                  action={<Button onClick={() => discoverQ.refetch()}>{t('builder.discover')}</Button>}
                />
              ) : discoverQ.isLoading ? <LoadingState label={t('common.loading')} />
              : discoverQ.isError ? <ErrorState message={describeError(discoverQ.error)} onRetry={() => discoverQ.refetch()} />
              : (
                <div className="overflow-x-auto rounded-md border border-border">
                  <table className="w-full min-w-[620px] text-xs">
                    <thead className="bg-muted/40">
                      <tr className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
                        <th className="px-2 py-1">{t('common.name')}</th>
                        <th className="px-2 py-1">{t('fields.originalName')}</th>
                        <th className="px-2 py-1">{t('common.type')}</th>
                        <th className="px-2 py-1">Null</th>
                        <th className="px-2 py-1">Taille</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                      {(discoverQ.data?.columns ?? []).map((c, i) => (
                        <tr key={i}>
                          <td className="px-2 py-1 font-mono">{c.name}</td>
                          <td className="px-2 py-1 text-muted-foreground">{c.originalName || '—'}</td>
                          <td className="px-2 py-1 text-muted-foreground">{c.type}</td>
                          <td className="px-2 py-1">{c.nullable ? '✓' : '—'}</td>
                          <td className="px-2 py-1">{c.size || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 border-b border-border/50 py-1.5 last:border-0 sm:grid-cols-[200px_1fr]">
      <span className="text-xs uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className="min-w-0 break-all text-sm">{value}</span>
    </div>
  )
}

function PerfCard({ label, value, tone = 'default' }: { label: string; value: number | string; tone?: 'default' | 'success' | 'danger' }) {
  const toneCls = {
    default: 'text-foreground',
    success: 'text-success',
    danger: 'text-destructive',
  }[tone]
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className={`mt-1 text-2xl font-semibold ${toneCls}`}>{value}</p>
      </CardContent>
    </Card>
  )
}
