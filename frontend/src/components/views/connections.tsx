
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight, Plus, Trash2, Pencil, Plug, Zap, CheckCircle2 } from 'lucide-react'
import { connectionService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogClose } from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { DataTable, THead, TBody, Th, Tr, Td } from '@/components/common/data-table'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { useToast } from '@/hooks/use-toast'
import type { DestinationConnectionDto } from '@/lib/api/types'

const DB_TYPES = ['POSTGRES', 'MYSQL', 'MARIADB', 'MSSQL', 'ORACLE', 'SQLITE', 'SNOWFLAKE', 'REDSHIFT']
const DEFAULT_PORT_BY_TYPE: Record<string, number | undefined> = {
  POSTGRES: 5432,
  MYSQL: 3306,
  MARIADB: 3306,
  MSSQL: 1433,
  ORACLE: 1521,
  REDSHIFT: 5439,
}
const CONNECTION_PAGE_SIZES = [5, 10, 20]
const DB_TYPE_STYLES: Record<string, string> = {
  POSTGRES: 'border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-300',
  MYSQL: 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  MARIADB: 'border-teal-500/30 bg-teal-500/10 text-teal-700 dark:text-teal-300',
  MSSQL: 'border-rose-500/30 bg-rose-500/10 text-rose-700 dark:text-rose-300',
  ORACLE: 'border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-300',
  SQLITE: 'border-zinc-500/30 bg-zinc-500/10 text-zinc-700 dark:text-zinc-300',
  SNOWFLAKE: 'border-cyan-500/30 bg-cyan-500/10 text-cyan-700 dark:text-cyan-300',
  REDSHIFT: 'border-violet-500/30 bg-violet-500/10 text-violet-700 dark:text-violet-300',
}

function normalizeDbType(type?: string) {
  const raw = (type || 'POSTGRES').trim().toUpperCase().replace('-', '_')
  if (raw === 'POSTGRESQL' || raw === 'PG') return 'POSTGRES'
  if (raw === 'SQLSERVER' || raw === 'SQL_SERVER') return 'MSSQL'
  if (raw === 'MARIA_DB') return 'MARIADB'
  if (raw === 'SQLITE3') return 'SQLITE'
  if (raw === 'AWS_REDSHIFT') return 'REDSHIFT'
  return raw
}

export function ConnectionsView() {
  const { t } = useTranslation()
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<DestinationConnectionDto | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [connectionToDelete, setConnectionToDelete] = useState<DestinationConnectionDto | null>(null)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  const listQ = useQuery({ queryKey: ['connections'], queryFn: connectionService.list })
  const connections = useMemo(() => listQ.data ?? [], [listQ.data])
  const totalPages = Math.max(1, Math.ceil(connections.length / pageSize))
  const activePage = Math.min(page, totalPages)
  const pageConnections = useMemo(
    () => connections.slice((activePage - 1) * pageSize, activePage * pageSize),
    [activePage, connections, pageSize],
  )

  const createMut = useMutation({
    mutationFn: (body: DestinationConnectionDto) => connectionService.create(body),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['connections'] }); toast({ title: t('common.connection') + ' ' + t('common.createdAt').toLowerCase() }) },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })
  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: string; body: DestinationConnectionDto }) => connectionService.update(id, body),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['connections'] }); toast({ title: t('common.connection') + ' ' + t('common.updatedAt').toLowerCase() }) },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })
  const deleteMut = useMutation({
    mutationFn: (id: string) => connectionService.remove(id),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['connections'] }); setConnectionToDelete(null); toast({ title: t('common.connection') + ' ' + t('common.delete').toLowerCase() }) },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })
  const testMut = useMutation({
    mutationFn: (id: string) => connectionService.test(id),
    onSuccess: (data) => {
      toast({
        title: data.success ? `${t('common.connection')} OK` : `${t('common.connection')} KO`,
        description: `${data.message}${data.latencyMs ? ` (${data.latencyMs}ms)` : ''}`,
        variant: data.success ? 'default' : 'destructive',
      })
    },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  function openCreate() {
    setEditing({ name: '', dbType: 'POSTGRES', host: '', port: 5432, database: '', username: '', password: '' })
    setDialogOpen(true)
  }
  function openEdit(c: DestinationConnectionDto) {
    const dbType = normalizeDbType(c.dbType)
    setEditing({ ...c, dbType, port: c.port || DEFAULT_PORT_BY_TYPE[dbType], password: '' })
    setDialogOpen(true)
  }

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title={t('common.connections')}
        description={t('nav.descriptions.connections')}
        actions={<Button onClick={openCreate}><Plus className="mr-1.5 h-4 w-4" /> {t('common.new')} {t('common.connection').toLowerCase()}</Button>}
      />

      {listQ.isLoading ? <LoadingState />
      : listQ.isError ? <ErrorState message={describeError(listQ.error)} onRetry={() => listQ.refetch()} />
      : connections.length === 0 ? (
        <EmptyState
          title={`${t('common.none')} ${t('common.connection').toLowerCase()}`}
          description={`${t('common.create')} ${t('common.connection').toLowerCase()}.`}
          icon={Plug}
          action={<Button onClick={openCreate}><Plus className="mr-1.5 h-4 w-4" /> {t('common.create')}</Button>}
        />
      ) : (
        <div className="space-y-2">
          <DataTable minWidth={760}>
              <THead>
                  <Th>{t('common.name')}</Th>
                  <Th>{t('common.type')}</Th>
                  <Th>{t('common.host')}</Th>
                  <Th>{t('common.database')}</Th>
                  <Th>{t('common.username')}</Th>
                  <Th align="right" className="w-[132px]">Actions</Th>
              </THead>
              <TBody>
                {pageConnections.map((c) => {
                  const dbType = normalizeDbType(c.dbType)
                  return (
                    <Tr key={c.id}>
                      <Td strong>{c.name}</Td>
                      <Td>
                        <Badge variant="outline" className={`text-[10px] ${DB_TYPE_STYLES[dbType] || ''}`}>
                          {dbType}
                        </Badge>
                      </Td>
                      <Td muted className="font-mono text-xs">
                        {dbType === 'SQLITE' ? 'Local' : `${c.host || '—'}${c.port ? `:${c.port}` : ''}`}
                      </Td>
                      <Td muted className="font-mono text-xs">{c.database || '—'}</Td>
                      <Td muted className="font-mono text-xs">{c.username || '—'}</Td>
                      <Td className="py-2">
                        <div className="flex justify-end gap-1">
                          <Button
                            size="icon"
                            variant="outline"
                            className="h-8 w-8"
                            onClick={() => c.id && testMut.mutate(c.id)}
                            disabled={testMut.isPending}
                            title={t('common.test')}
                            aria-label={`${t('common.test')} ${c.name}`}
                          >
                            <Zap className={`h-3.5 w-3.5 ${testMut.isPending && testMut.variables === c.id ? 'animate-pulse' : ''}`} />
                          </Button>
                          <Button size="icon" variant="ghost" className="h-8 w-8" onClick={() => openEdit(c)} title={t('common.edit')} aria-label={`${t('common.edit')} ${c.name}`}>
                            <Pencil className="h-3.5 w-3.5" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            className="h-8 w-8 text-destructive hover:text-destructive"
                            onClick={() => setConnectionToDelete(c)}
                            title={t('common.delete')}
                            aria-label={`${t('common.delete')} ${c.name}`}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        </div>
                      </Td>
                    </Tr>
                  )
                })}
              </TBody>
          </DataTable>
          <div className="flex flex-col gap-3 pt-1 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-xs text-muted-foreground">
              {Math.min((activePage - 1) * pageSize + 1, connections.length)}–{Math.min(activePage * pageSize, connections.length)} sur {connections.length}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <Select
                value={String(pageSize)}
                onValueChange={(value) => {
                  setPageSize(Number(value))
                  setPage(1)
                }}
              >
                <SelectTrigger className="h-8 w-[108px]" aria-label="Connexions par page">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CONNECTION_PAGE_SIZES.map((size) => <SelectItem key={size} value={String(size)}>{size} / page</SelectItem>)}
                </SelectContent>
              </Select>
              <Button
                size="icon"
                variant="outline"
                className="h-8 w-8"
                onClick={() => setPage(Math.max(1, activePage - 1))}
                disabled={activePage === 1}
                aria-label="Page précédente"
              >
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <span className="min-w-[72px] text-center text-xs">Page {activePage} / {totalPages}</span>
              <Button
                size="icon"
                variant="outline"
                className="h-8 w-8"
                onClick={() => setPage(Math.min(totalPages, activePage + 1))}
                disabled={activePage === totalPages}
                aria-label="Page suivante"
              >
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Edit/Create dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{editing?.id ? `${t('common.edit')} ${t('common.connection').toLowerCase()}` : `${t('common.new')} ${t('common.connection').toLowerCase()}`}</DialogTitle>
          </DialogHeader>
          {editing && (
            <ConnectionForm
              conn={editing}
              onSubmit={(body) => {
                if (editing.id) updateMut.mutate({ id: editing.id, body })
                else createMut.mutate(body)
                setDialogOpen(false)
              }}
            />
          )}
        </DialogContent>
      </Dialog>
      <ConfirmDialog
        open={!!connectionToDelete}
        onOpenChange={(open) => !open && setConnectionToDelete(null)}
        title={t('confirm.deleteConnectionTitle', { name: connectionToDelete?.name || '' })}
        description={t('confirm.deleteConnectionDescription')}
        confirmLabel={t('common.delete')}
        variant="destructive"
        pending={deleteMut.isPending}
        onConfirm={() => {
          if (connectionToDelete?.id) deleteMut.mutate(connectionToDelete.id)
        }}
      />
    </div>
  )
}

function ConnectionForm({ conn, onSubmit }: { conn: DestinationConnectionDto; onSubmit: (b: DestinationConnectionDto) => void }) {
  const { t } = useTranslation()
  const [form, setForm] = useState<DestinationConnectionDto>(() => ({
    ...conn,
    dbType: normalizeDbType(conn.dbType),
  }))
  function update(patch: Partial<DestinationConnectionDto>) { setForm((p) => ({ ...p, ...patch })) }
  const isSqlite = normalizeDbType(form.dbType) === 'SQLITE'
  const isSnowflake = normalizeDbType(form.dbType) === 'SNOWFLAKE'
  function updateAdditional(name: string, value: string) {
    update({ additionalProperties: { ...(form.additionalProperties || {}), [name]: value } })
  }
  return (
    <div className="space-y-3">
      <div className="space-y-1.5"><Label className="text-xs">{t('common.name')} *</Label><Input value={form.name} onChange={(e) => update({ name: e.target.value })} /></div>
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <Label className="text-xs">{t('common.type')}</Label>
          <Select value={normalizeDbType(form.dbType)} onValueChange={(v) => update({ dbType: v, port: DEFAULT_PORT_BY_TYPE[v] })}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {DB_TYPES.map((t) => <SelectItem key={t} value={t}>{t}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1.5"><Label className="text-xs">{t('common.port')}</Label><Input type="number" value={form.port ?? ''} onChange={(e) => update({ port: Number(e.target.value) })} /></div>
      </div>
      <div className="space-y-1.5"><Label className="text-xs">{t('common.host')}</Label><Input value={form.host || ''} onChange={(e) => update({ host: e.target.value })} placeholder={isSqlite ? 'Non requis pour SQLite' : 'postgres ou host.docker.internal'} disabled={isSqlite} /></div>
      <div className="space-y-1.5"><Label className="text-xs">{isSqlite ? 'Fichier SQLite' : t('common.database')}</Label><Input value={form.database || ''} onChange={(e) => update({ database: e.target.value })} placeholder={isSqlite ? 'C:\\data\\source.db ou /data/source.db' : undefined} /></div>
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5"><Label className="text-xs">{t('common.username')}</Label><Input value={form.username || ''} onChange={(e) => update({ username: e.target.value })} disabled={isSqlite} /></div>
        <div className="space-y-1.5"><Label className="text-xs">{t('common.password')} {conn.id && '(laisser vide = inchange)'}</Label><Input type="password" value={form.password || ''} onChange={(e) => update({ password: e.target.value })} disabled={isSqlite} /></div>
      </div>
      <div className="space-y-1.5"><Label className="text-xs">Schema ({t('common.optional')})</Label><Input value={form.schema || ''} onChange={(e) => update({ schema: e.target.value })} /></div>
      {isSnowflake && (
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="space-y-1.5"><Label className="text-xs">Compte Snowflake</Label><Input value={String(form.additionalProperties?.account || '')} onChange={(e) => updateAdditional('account', e.target.value)} /></div>
          <div className="space-y-1.5"><Label className="text-xs">Warehouse</Label><Input value={String(form.additionalProperties?.warehouse || '')} onChange={(e) => updateAdditional('warehouse', e.target.value)} /></div>
          <div className="space-y-1.5"><Label className="text-xs">Role</Label><Input value={String(form.additionalProperties?.role || '')} onChange={(e) => updateAdditional('role', e.target.value)} /></div>
        </div>
      )}
      <DialogFooter>
        <DialogClose asChild><Button variant="outline">{t('common.cancel')}</Button></DialogClose>
        <Button onClick={() => onSubmit(form)} disabled={!form.name}><CheckCircle2 className="mr-1.5 h-3.5 w-3.5" /> {t('common.save')}</Button>
      </DialogFooter>
    </div>
  )
}
