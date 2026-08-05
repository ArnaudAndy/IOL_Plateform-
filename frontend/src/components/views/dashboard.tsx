
import { useQuery } from '@tanstack/react-query'
import { Link2, Activity, ArrowDownToLine, Workflow as WorkflowIcon, AlertTriangle, CheckCircle2, Clock } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { workflowService, logsService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useNavStore } from '@/stores/nav-store'
import { useAuthStore } from '@/stores/auth-store'
import { ExecutionStatusBadge, DirectionBadge } from '@/components/common/badges'
import { formatRelative, formatDuration } from '@/lib/format'
import { TECH_LABELS } from '@/lib/i18n'

export function DashboardView() {
  const { t } = useTranslation()
  const navigate = useNavStore((s) => s.navigate)
  const isAdmin = useAuthStore((s) => s.isAdmin)

  const workflowsQ = useQuery({
    queryKey: ['workflows'],
    queryFn: workflowService.list,
  })
  const logsQ = useQuery({
    queryKey: ['logs', 'all'],
    queryFn: logsService.all,
    refetchInterval: 10_000, // polling ~temps réel
  })
  const interopSummaryQ = useQuery({
    queryKey: ['logs', 'interop-summary'],
    queryFn: logsService.interopSummary,
    refetchInterval: 10_000,
  })

  const isLoading = workflowsQ.isLoading || logsQ.isLoading
  const isError = workflowsQ.isError || logsQ.isError

  const workflows = workflowsQ.data ?? []
  const logs = logsQ.data ?? []
  const summary = interopSummaryQ.data

  const recentLogs = [...logs]
    .sort((a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime())
    .slice(0, 6)

  const runningCount = logs.filter((l) => l.status === 'RUNNING').length
  const failedCount = logs.filter((l) => l.status === 'FAILED').length
  const successCount = logs.filter((l) => l.status === 'SUCCESS').length

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title={t('nav.items.dashboard')}
        description={t('dashboard.description')}
        actions={
          <Button onClick={() => navigate('workflow-builder')} disabled={!isAdmin}>
            {t('dashboard.newWorkflow')}
          </Button>
        }
      />

      {/* KPIs */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          title={t('dashboard.kpiConfigured')}
          value={workflows.length}
          icon={<WorkflowIcon className="h-4 w-4" />}
          onClick={() => navigate('workflows')}
        />
        <KpiCard
          title={t('dashboard.kpiRunning')}
          value={runningCount}
          icon={<Activity className="h-4 w-4" />}
          tone="info"
          onClick={() => navigate('executions')}
        />
        <KpiCard
          title={t('dashboard.kpiSuccess')}
          value={successCount}
          icon={<CheckCircle2 className="h-4 w-4" />}
          tone="success"
          onClick={() => navigate('executions')}
        />
        <KpiCard
          title={t('dashboard.kpiFailed')}
          value={failedCount}
          icon={<AlertTriangle className="h-4 w-4" />}
          tone="danger"
          onClick={() => navigate('executions')}
        />
      </div>

      {/* Interop summary */}
      <div className="mt-6">
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-sm">
              <ArrowDownToLine className="h-4 w-4 text-accent-foreground" />
              {t('dashboard.summary')}
              <span className="ml-auto text-[10px] font-normal text-muted-foreground">polling 10s</span>
            </CardTitle>
          </CardHeader>
          <CardContent>
            {interopSummaryQ.isLoading ? (
              <LoadingState label={`${t('common.loading')}`} />
            ) : interopSummaryQ.isError ? (
              <ErrorState
                message={describeError(interopSummaryQ.error)}
                onRetry={() => interopSummaryQ.refetch()}
              />
            ) : summary ? (
              <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
                <MiniStat label={t('dashboard.totalInbound')} value={summary.totalInbound ?? 0} />
                <MiniStat label={t('enums.executionStatus.RUNNING')} value={summary.running ?? 0} tone="info" />
                <MiniStat label={t('common.success')} value={summary.success ?? 0} tone="success" />
                <MiniStat label={t('common.failure')} value={summary.failed ?? 0} tone="danger" />
                <MiniStat label={TECH_LABELS.dlq} value={summary.dlqCount ?? 0} tone="warning" />
                <MiniStat label="24h" value={summary.last24h ?? 0} />
              </div>
            ) : (
              <EmptyState title={t('dashboard.noSummary')} icon={Link2} />
            )}
            <div className="mt-3 flex justify-end">
              <Button variant="ghost" size="sm" onClick={() => navigate('interop')}>
                {t('dashboard.viewReceptions')}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Recent executions */}
      <div className="mt-6">
        <h2 className="mb-3 text-sm font-semibold text-foreground">{t('dashboard.recentExecutions')}</h2>
        {isLoading ? (
          <LoadingState />
        ) : isError ? (
          <ErrorState message={describeError(logsQ.error || workflowsQ.error)} onRetry={() => { logsQ.refetch(); workflowsQ.refetch() }} />
        ) : recentLogs.length === 0 ? (
          <EmptyState
            title={t('dashboard.noExecutionTitle')}
            description={t('dashboard.noExecutionDescription')}
            icon={Clock}
          />
        ) : (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead className="bg-muted/40">
                <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <th className="px-3 py-2 font-medium">{t('common.status')}</th>
                  <th className="px-3 py-2 font-medium">{t('common.workflow')}</th>
                  <th className="px-3 py-2 font-medium">Direction</th>
                  <th className="px-3 py-2 font-medium">{t('common.triggeredBy')}</th>
                  <th className="px-3 py-2 font-medium">{t('common.start')}</th>
                  <th className="px-3 py-2 font-medium">{t('common.duration')}</th>
                  <th className="px-3 py-2 font-medium">{TECH_LABELS.correlationId}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {recentLogs.map((log) => (
                  <tr
                    key={log.id}
                    className="cursor-pointer hover:bg-muted/30"
                    onClick={() => log.workflowId && navigate('workflow-detail', { id: log.workflowId, tab: 'executions' })}
                  >
                    <td className="px-3 py-2"><ExecutionStatusBadge status={log.status} /></td>
                    <td className="px-3 py-2 font-medium">{log.workflowName || log.workflowId || '—'}</td>
                    <td className="px-3 py-2"><DirectionBadge direction={log.direction} /></td>
                    <td className="px-3 py-2 text-muted-foreground">{log.triggeredBy || '—'}</td>
                    <td className="px-3 py-2 text-muted-foreground">{formatRelative(log.startTime)}</td>
                    <td className="px-3 py-2 text-muted-foreground">{formatDuration(log.durationMs)}</td>
                    <td className="px-3 py-2 font-mono text-[11px] text-muted-foreground">{log.correlationId?.slice(0, 8) || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Quick actions */}
      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <QuickAction label={t('nav.items.standards')} description={t('dashboard.quickStandards')} onClick={() => navigate('standards')} />
        <QuickAction label={t('nav.items.connections')} description={t('dashboard.quickConnections')} onClick={() => navigate('connections')} />
        <QuickAction label={t('nav.items.sqlWorkbench')} description={t('dashboard.quickSql')} onClick={() => navigate('sql-workbench')} />
        <QuickAction label={t('nav.items.aiAssistant')} description={t('dashboard.quickAi')} onClick={() => navigate('ai-assistant')} />
      </div>
    </div>
  )
}

function KpiCard({
  title, value, icon, tone = 'default', onClick,
}: {
  title: string
  value: number | string
  icon: React.ReactNode
  tone?: 'default' | 'info' | 'success' | 'danger' | 'warning'
  onClick?: () => void
}) {
  const toneCls = {
    default: 'text-foreground',
    info: 'text-info',
    success: 'text-success',
    danger: 'text-destructive',
    warning: 'text-warning',
  }[tone]
  return (
    <Card className="cursor-pointer transition-colors hover:bg-muted/40" onClick={onClick}>
      <CardContent className="p-4">
        <div className="flex items-center justify-between">
          <p className="text-xs text-muted-foreground">{title}</p>
          <span className={toneCls}>{icon}</span>
        </div>
        <p className={`mt-2 text-2xl font-semibold ${toneCls}`}>{value}</p>
      </CardContent>
    </Card>
  )
}

function MiniStat({ label, value, tone = 'default' }: { label: string; value: number; tone?: 'default' | 'info' | 'success' | 'danger' | 'warning' }) {
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
      <p className={`mt-1 text-xl font-semibold ${toneCls}`}>{value}</p>
    </div>
  )
}

function QuickAction({ label, description, onClick }: { label: string; description: string; onClick: () => void }) {
  return (
    <Card className="cursor-pointer transition-colors hover:bg-muted/40" onClick={onClick}>
      <CardContent className="p-4">
        <p className="text-sm font-medium">{label}</p>
        <p className="mt-1 text-xs text-muted-foreground">{description}</p>
      </CardContent>
    </Card>
  )
}
