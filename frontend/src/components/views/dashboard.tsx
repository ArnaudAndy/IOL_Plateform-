
import { useQuery } from '@tanstack/react-query'
import {
  Link2,
  ArrowDownToLine,
  Network,
  PlayCircle,
  CircleCheck,
  CircleAlert,
  Clock,
  BookMarked,
  Plug,
  Terminal,
  Bot,
  ChevronRight,
  type LucideIcon,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { workflowService, logsService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { DataTable, THead, TBody, Th, Tr, Td } from '@/components/common/data-table'
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
          icon={Network}
          onClick={() => navigate('workflows')}
        />
        <KpiCard
          title={t('dashboard.kpiRunning')}
          value={runningCount}
          icon={PlayCircle}
          tone="info"
          onClick={() => navigate('executions')}
        />
        <KpiCard
          title={t('dashboard.kpiSuccess')}
          value={successCount}
          icon={CircleCheck}
          tone="success"
          onClick={() => navigate('executions')}
        />
        <KpiCard
          title={t('dashboard.kpiFailed')}
          value={failedCount}
          icon={CircleAlert}
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
          <DataTable minWidth={880}>
            <THead>
              <Th>{t('common.workflow')}</Th>
              <Th>{t('common.status')}</Th>
              <Th>Direction</Th>
              <Th>{t('common.triggeredBy')}</Th>
              <Th align="right">{t('common.start')}</Th>
              <Th align="right">{t('common.duration')}</Th>
              <Th align="right">{TECH_LABELS.correlationId}</Th>
            </THead>
            <TBody>
              {recentLogs.map((log) => (
                <Tr
                  key={log.id}
                  onClick={() => log.workflowId && navigate('workflow-detail', { id: log.workflowId, tab: 'executions' })}
                >
                  <Td strong>{log.workflowName || log.workflowId || '—'}</Td>
                  <Td><ExecutionStatusBadge status={log.status} /></Td>
                  <Td><DirectionBadge direction={log.direction} /></Td>
                  <Td muted>{log.triggeredBy || '—'}</Td>
                  <Td muted numeric>{formatRelative(log.startTime)}</Td>
                  <Td muted numeric>{formatDuration(log.durationMs)}</Td>
                  <Td muted numeric className="font-mono text-xs">{log.correlationId?.slice(0, 8) || '—'}</Td>
                </Tr>
              ))}
            </TBody>
          </DataTable>
        )}
      </div>

      {/* Quick actions */}
      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <QuickAction icon={BookMarked} label={t('nav.items.standards')} description={t('dashboard.quickStandards')} onClick={() => navigate('standards')} />
        <QuickAction icon={Plug} label={t('nav.items.connections')} description={t('dashboard.quickConnections')} onClick={() => navigate('connections')} />
        <QuickAction icon={Terminal} label={t('nav.items.sqlWorkbench')} description={t('dashboard.quickSql')} onClick={() => navigate('sql-workbench')} />
        <QuickAction icon={Bot} label={t('nav.items.aiAssistant')} description={t('dashboard.quickAi')} onClick={() => navigate('ai-assistant')} />
      </div>
    </div>
  )
}

// Palette par tonalite : la valeur reste en couleur du texte principal (plus
// sobre), seule la pastille d'icone porte la couleur semantique.
const KPI_TONES = {
  default: { icon: 'bg-muted text-foreground', accent: 'bg-border' },
  info: { icon: 'bg-info/12 text-info', accent: 'bg-info' },
  success: { icon: 'bg-success/12 text-success', accent: 'bg-success' },
  danger: { icon: 'bg-destructive/12 text-destructive', accent: 'bg-destructive' },
  warning: { icon: 'bg-warning/15 text-warning', accent: 'bg-warning' },
} as const

function KpiCard({
  title, value, icon: Icon, tone = 'default', hint, onClick,
}: {
  title: string
  value: number | string
  icon: LucideIcon
  tone?: keyof typeof KPI_TONES
  hint?: string
  onClick?: () => void
}) {
  const palette = KPI_TONES[tone]
  return (
    <Card
      className="group relative cursor-pointer overflow-hidden transition-shadow hover:shadow-md"
      onClick={onClick}
    >
      {/* Filet de couleur : identifie le KPI sans surcharger la carte. */}
      <span className={`absolute inset-x-0 top-0 h-0.5 ${palette.accent}`} aria-hidden="true" />
      <CardContent className="flex items-center gap-4 p-5">
        <span
          className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-lg ${palette.icon}`}
          aria-hidden="true"
        >
          <Icon className="h-6 w-6" strokeWidth={1.75} />
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-medium text-muted-foreground">{title}</p>
          <p className="mt-0.5 text-3xl font-semibold leading-none tracking-tight text-foreground">{value}</p>
          {hint && <p className="mt-1.5 truncate text-xs text-muted-foreground">{hint}</p>}
        </div>
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

function QuickAction({
  label, description, icon: Icon, onClick,
}: {
  label: string
  description: string
  icon: LucideIcon
  onClick: () => void
}) {
  return (
    <Card className="group cursor-pointer transition-shadow hover:shadow-md" onClick={onClick}>
      <CardContent className="flex items-start gap-3 p-4">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground" aria-hidden="true">
          <Icon className="h-5 w-5" strokeWidth={1.75} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-1 text-sm font-medium">
            {label}
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">{description}</p>
        </div>
      </CardContent>
    </Card>
  )
}
