
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Search, Trash2, Download, Play, Pencil } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { workflowService, orchestratorService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { useNavStore } from '@/stores/nav-store'
import { useAuthStore } from '@/stores/auth-store'
import { ExecutionStatusBadge, DirectionBadge } from '@/components/common/badges'
import { useToast } from '@/hooks/use-toast'
import { formatRelative } from '@/lib/format'
import type { WorkflowConfigUi } from '@/lib/api/types'

export function WorkflowsView() {
  const { t } = useTranslation()
  const navigate = useNavStore((s) => s.navigate)
  const isAdmin = useAuthStore((s) => s.isAdmin)
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [workflowToDelete, setWorkflowToDelete] = useState<WorkflowConfigUi | null>(null)

  const workflowsQ = useQuery({
    queryKey: ['workflows'],
    queryFn: workflowService.list,
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => workflowService.remove(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workflows'] })
      setWorkflowToDelete(null)
      toast({ title: t('workflows.toastDeleted') })
    },
    onError: (e) => toast({ title: t('common.error'), description: describeError(e), variant: 'destructive' }),
  })

  const runMut = useMutation({
    mutationFn: (id: string) => orchestratorService.run(id),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['logs'] })
      toast({ title: t('workflows.toastRun'), description: `${t('common.execution')} ${data.id} ${t('common.enabled')}.` })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const exportMut = useMutation({
    mutationFn: (id: string) => workflowService.export(id),
    onSuccess: (data, id) => {
      const blob = new Blob([typeof data === 'string' ? data : JSON.stringify(data, null, 2)], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `workflow-${id}.txt`
      a.click()
      URL.revokeObjectURL(url)
      toast({ title: t('workflows.toastExported') })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const workflows = workflowsQ.data ?? []
  const filtered = workflows.filter((w) =>
    !search || w.name?.toLowerCase().includes(search.toLowerCase()) || w.description?.toLowerCase().includes(search.toLowerCase())
  )

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title={t('common.workflows')}
        description={t('workflows.description')}
        actions={
          <Button onClick={() => navigate('workflow-builder')} disabled={!isAdmin}>
            <Plus className="mr-1.5 h-4 w-4" /> {t('dashboard.newWorkflow')}
          </Button>
        }
      />

      <div className="mb-4 flex items-center gap-2">
        <div className="relative max-w-sm flex-1">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder={t('workflows.searchPlaceholder')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8"
          />
        </div>
        {!isAdmin && (
          <p className="text-xs text-muted-foreground">
            {t('common.adminOnlyRead')}
          </p>
        )}
      </div>

      {workflowsQ.isLoading ? (
        <LoadingState />
      ) : workflowsQ.isError ? (
        <ErrorState message={describeError(workflowsQ.error)} onRetry={() => workflowsQ.refetch()} />
      ) : filtered.length === 0 ? (
        <EmptyState
          title={search ? t('workflows.noMatch') : t('workflows.noConfigured')}
          description={search ? t('common.search') : isAdmin ? t('workflows.createFirst') : t('workflows.readOnlyEmpty')}
          action={isAdmin && !search ? <Button onClick={() => navigate('workflow-builder')}><Plus className="mr-1.5 h-4 w-4" /> {t('workflows.createWorkflow')}</Button> : undefined}
        />
      ) : (
        <div className="grid gap-3">
          {filtered.map((w: WorkflowConfigUi) => (
            <Card key={w.id} className="overflow-hidden">
              <CardContent className="p-4">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0 flex-1 cursor-pointer" onClick={() => w.id && navigate('workflow-detail', { id: w.id })}>
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-medium text-foreground">{w.name}</h3>
                      <DirectionBadge direction={w.direction} />
                      {w.status && (
                        <span className="rounded-md border border-border bg-muted px-2 py-0.5 text-[10px] uppercase tracking-wide text-muted-foreground">{w.status}</span>
                      )}
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground line-clamp-1">
                      {w.description || '—'}
                    </p>
                    <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
                      <span>{t('workflows.sourceCount', { count: w.sources?.length || 0 })}</span>
                      <span>{t('common.priority')} : {w.priority ?? '—'}</span>
                      <span>{t('technical.standard')} : {w.standardId || (w.standardDomain ? `${w.standardDomain} (${t('workflows.standardDeprecated')})` : '—')}</span>
                      <span>{t('workflows.plan')} : {w.schedule?.enabled ? (w.schedule.cron || `${w.schedule.frequency || '—'} @ ${w.schedule.time || '—'}`) : t('common.disabled')}</span>
                      {w.updatedAt && <span>{t('workflows.updated', { date: formatRelative(w.updatedAt) })}</span>}
                    </div>
                  </div>
                  <div className="flex shrink-0 flex-wrap items-center gap-1.5">
                    <Button
                      size="sm" variant="outline"
                      disabled={runMut.isPending || !isAdmin}
                      onClick={() => w.id && runMut.mutate(w.id)}
                    >
                      <Play className="mr-1 h-3.5 w-3.5" /> {t('common.run')}
                    </Button>
                    <Button
                      size="sm" variant="ghost"
                      onClick={() => w.id && navigate('workflow-builder', { id: w.id })}
                      disabled={!isAdmin}
                    >
                      <Pencil className="mr-1 h-3.5 w-3.5" /> {t('common.edit')}
                    </Button>
                    <Button
                      size="sm" variant="ghost"
                      disabled={exportMut.isPending || !isAdmin}
                      onClick={() => w.id && exportMut.mutate(w.id)}
                    >
                      <Download className="mr-1 h-3.5 w-3.5" /> {t('common.export')}
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      className="text-destructive hover:text-destructive"
                      disabled={!isAdmin}
                      onClick={() => setWorkflowToDelete(w)}
                    >
                      <Trash2 className="mr-1 h-3.5 w-3.5" /> {t('common.delete')}
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
      <ConfirmDialog
        open={!!workflowToDelete}
        onOpenChange={(open) => !open && setWorkflowToDelete(null)}
        title={t('confirm.deleteWorkflowTitle', { name: workflowToDelete?.name || '' })}
        description={t('confirm.deleteWorkflowDescription')}
        confirmLabel={t('common.delete')}
        variant="destructive"
        pending={deleteMut.isPending}
        onConfirm={() => {
          if (workflowToDelete?.id) deleteMut.mutate(workflowToDelete.id)
        }}
      />
    </div>
  )
}
