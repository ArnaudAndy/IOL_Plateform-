import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  FileText,
  ListFilter,
  RefreshCw,
  Search,
  ShieldAlert,
  User,
  XCircle,
} from 'lucide-react'
import { auditService, userService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Badge } from '@/components/ui/badge'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatDateTime } from '@/lib/format'
import type { AuditLog, UserDto } from '@/lib/api/types'

const RESOURCE_TYPES = ['WORKFLOW', 'CONNECTION', 'STANDARD', 'USER', 'SQL_ASSISTANT', 'SQL_QUERY', 'FILE', 'API']
const ACTIONS = ['CREATE', 'UPDATE', 'DELETE', 'EXECUTE', 'ACTIVATE', 'DEPRECATE', 'EXPORT', 'IMPORT']

export function AuditView() {
  const [tab, setTab] = useState('all')
  const usersQ = useQuery({ queryKey: ['users'], queryFn: userService.list })

  return (
    <div className="mx-auto w-full min-w-0 max-w-7xl">
      <PageHeader
        title="Audit"
        description="Traçabilité des modifications et des exécutions de la plateforme."
      />

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="grid h-auto w-full grid-cols-2 sm:inline-grid sm:w-auto sm:grid-cols-4">
          <TabsTrigger value="all"><ListFilter className="mr-1.5 h-3.5 w-3.5" /> Toutes</TabsTrigger>
          <TabsTrigger value="failed"><XCircle className="mr-1.5 h-3.5 w-3.5" /> Échecs</TabsTrigger>
          <TabsTrigger value="resource"><FileText className="mr-1.5 h-3.5 w-3.5" /> Ressource</TabsTrigger>
          <TabsTrigger value="user"><User className="mr-1.5 h-3.5 w-3.5" /> Utilisateur</TabsTrigger>
        </TabsList>

        <TabsContent value="all"><AllAuditPanel users={usersQ.data ?? []} /></TabsContent>
        <TabsContent value="failed"><FailedAuditPanel users={usersQ.data ?? []} /></TabsContent>
        <TabsContent value="resource"><ResourceAuditPanel users={usersQ.data ?? []} /></TabsContent>
        <TabsContent value="user">
          <UserAuditPanel
            users={usersQ.data ?? []}
            usersLoading={usersQ.isLoading}
            usersError={usersQ.isError ? describeError(usersQ.error) : undefined}
          />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function AllAuditPanel({ users }: { users: UserDto[] }) {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('ALL')
  const [action, setAction] = useState('ALL')
  const query = useQuery({
    queryKey: ['audit', 'all'],
    queryFn: auditService.all,
    refetchInterval: 10_000,
  })

  const logs = useMemo(() => {
    const term = search.trim().toLowerCase()
    return (query.data ?? []).filter((log) => {
      if (status !== 'ALL' && log.status !== status) return false
      if (action !== 'ALL' && log.action !== action) return false
      if (!term) return true
      return [
        log.action,
        log.resourceType,
        log.resourceId,
        log.description,
        log.errorMessage,
        log.ipAddress,
        userLabel(log.userId, users),
      ].some((value) => String(value || '').toLowerCase().includes(term))
    })
  }, [action, query.data, search, status, users])

  if (query.isLoading) return <LoadingState />
  if (query.isError) return <ErrorState message={describeError(query.error)} onRetry={() => query.refetch()} />

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <CardTitle className="text-sm">Journal complet ({logs.length})</CardTitle>
          <Button variant="outline" size="icon" onClick={() => query.refetch()} title="Actualiser" aria-label="Actualiser">
            <RefreshCw className={`h-4 w-4 ${query.isFetching ? 'animate-spin' : ''}`} />
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid gap-2 md:grid-cols-[minmax(0,1fr)_180px_180px]">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Rechercher dans le journal..." className="pl-8" />
          </div>
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">Tous les statuts</SelectItem>
              <SelectItem value="SUCCESS">Réussies</SelectItem>
              <SelectItem value="FAILURE">Échouées</SelectItem>
              <SelectItem value="PARTIAL">Partielles</SelectItem>
            </SelectContent>
          </Select>
          <Select value={action} onValueChange={setAction}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">Toutes les actions</SelectItem>
              {ACTIONS.map((value) => <SelectItem key={value} value={value}>{actionLabel(value)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <AuditTable logs={logs} users={users} />
      </CardContent>
    </Card>
  )
}

function AuditTable({ logs, users }: { logs: AuditLog[]; users: UserDto[] }) {
  if (logs.length === 0) return <EmptyState title="Aucune entrée" icon={ShieldAlert} />
  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table className="min-w-[980px] w-full text-xs">
        <thead className="bg-muted/40">
          <tr className="text-left text-[10px] uppercase text-muted-foreground">
            <th className="px-3 py-2">Horodatage</th>
            <th className="px-3 py-2">Utilisateur</th>
            <th className="px-3 py-2">Action</th>
            <th className="px-3 py-2">Ressource</th>
            <th className="px-3 py-2">Statut</th>
            <th className="px-3 py-2">IP</th>
            <th className="px-3 py-2">Détail</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {logs.map((log) => (
            <tr key={log.id} className="hover:bg-muted/30">
              <td className="whitespace-nowrap px-3 py-2 text-muted-foreground">{formatDateTime(log.timestamp)}</td>
              <td className="px-3 py-2">
                <p>{userLabel(log.userId, users)}</p>
                {log.userRole && <p className="text-[10px] text-muted-foreground">{log.userRole}</p>}
              </td>
              <td className="px-3 py-2 font-medium">{actionLabel(log.action)}</td>
              <td className="px-3 py-2">
                <span className="font-mono text-[10px]">{log.resourceType}</span>
                {log.resourceId && <span className="ml-1 text-muted-foreground">{log.resourceId.slice(0, 12)}</span>}
              </td>
              <td className="px-3 py-2"><AuditStatus status={log.status} /></td>
              <td className="px-3 py-2 font-mono text-[10px]">{log.ipAddress || '—'}</td>
              <td className="max-w-[340px] px-3 py-2">
                <span className={log.errorMessage ? 'text-destructive' : 'text-muted-foreground'}>
                  {log.errorMessage || log.description || '—'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FailedAuditPanel({ users }: { users: UserDto[] }) {
  const query = useQuery({
    queryKey: ['audit', 'failed'],
    queryFn: auditService.failed,
    refetchInterval: 10_000,
  })
  if (query.isLoading) return <LoadingState />
  if (query.isError) return <ErrorState message={describeError(query.error)} onRetry={() => query.refetch()} />
  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-sm">Actions échouées</CardTitle></CardHeader>
      <CardContent><AuditTable logs={query.data ?? []} users={users} /></CardContent>
    </Card>
  )
}

function ResourceAuditPanel({ users }: { users: UserDto[] }) {
  const [resourceType, setResourceType] = useState('WORKFLOW')
  const [resourceId, setResourceId] = useState('')
  const [submitted, setSubmitted] = useState<{ type: string; id: string } | null>(null)
  const query = useQuery({
    queryKey: ['audit', 'resource', submitted?.type, submitted?.id],
    queryFn: () => auditService.byResource(submitted!.type, submitted!.id),
    enabled: !!submitted?.id,
  })

  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-sm">Audit par ressource</CardTitle></CardHeader>
      <CardContent>
        <div className="mb-4 grid items-end gap-2 sm:grid-cols-[200px_minmax(0,1fr)_auto]">
          <div className="space-y-1.5">
            <Label className="text-xs">Type de ressource</Label>
            <Select value={resourceType} onValueChange={setResourceType}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>{RESOURCE_TYPES.map((value) => <SelectItem key={value} value={value}>{value}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label className="text-xs">Identifiant de la ressource</Label>
            <Input value={resourceId} onChange={(event) => setResourceId(event.target.value)} placeholder="Identifiant complet" />
          </div>
          <Button onClick={() => setSubmitted({ type: resourceType, id: resourceId.trim() })} disabled={!resourceId.trim()}>
            <Search className="mr-1.5 h-3.5 w-3.5" /> Rechercher
          </Button>
        </div>
        {!submitted ? <EmptyState title="Choisissez une ressource" description="La recherche utilise son identifiant exact." />
          : query.isLoading ? <LoadingState />
            : query.isError ? <ErrorState message={describeError(query.error)} onRetry={() => query.refetch()} />
              : <AuditTable logs={query.data ?? []} users={users} />}
      </CardContent>
    </Card>
  )
}

function UserAuditPanel({
  users,
  usersLoading,
  usersError,
}: {
  users: UserDto[]
  usersLoading: boolean
  usersError?: string
}) {
  const [userId, setUserId] = useState('')
  const [submitted, setSubmitted] = useState<string | null>(null)
  const query = useQuery({
    queryKey: ['audit', 'user', submitted],
    queryFn: () => auditService.byUser(submitted!),
    enabled: !!submitted,
  })

  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-sm">Audit par utilisateur</CardTitle></CardHeader>
      <CardContent>
        <div className="mb-4 grid items-end gap-2 sm:grid-cols-[minmax(0,360px)_auto]">
          <div className="space-y-1.5">
            <Label className="text-xs">Utilisateur</Label>
            <Select value={userId} onValueChange={setUserId} disabled={usersLoading || users.length === 0}>
              <SelectTrigger><SelectValue placeholder={usersLoading ? 'Chargement...' : 'Choisir un utilisateur'} /></SelectTrigger>
              <SelectContent>
                {users.map((item) => <SelectItem key={item.id} value={item.id}>{item.name || item.email} · {item.email}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <Button onClick={() => setSubmitted(userId)} disabled={!userId}>
            <Search className="mr-1.5 h-3.5 w-3.5" /> Rechercher
          </Button>
        </div>
        {usersError ? <ErrorState message={usersError} />
          : !submitted ? <EmptyState title="Choisissez un utilisateur" />
            : query.isLoading ? <LoadingState />
              : query.isError ? <ErrorState message={describeError(query.error)} onRetry={() => query.refetch()} />
                : <AuditTable logs={query.data ?? []} users={users} />}
      </CardContent>
    </Card>
  )
}

function AuditStatus({ status }: { status: AuditLog['status'] }) {
  if (status === 'SUCCESS') {
    return <Badge variant="outline" className="border-success/30 text-success">Réussie</Badge>
  }
  if (status === 'PARTIAL') {
    return <Badge variant="outline" className="border-warning/30 text-warning">Partielle</Badge>
  }
  return <Badge variant="outline" className="border-destructive/30 text-destructive">Échec</Badge>
}

function userLabel(userId: string | undefined, users: UserDto[]) {
  if (!userId) return 'Système'
  const user = users.find((item) => String(item.id) === String(userId))
  return user ? (user.name || user.email) : userId
}

function actionLabel(action: string) {
  const labels: Record<string, string> = {
    CREATE: 'Création',
    READ: 'Lecture',
    UPDATE: 'Modification',
    DELETE: 'Suppression',
    EXECUTE: 'Exécution',
    ACTIVATE: 'Activation',
    DEPRECATE: 'Dépublication',
    EXPORT: 'Export',
    IMPORT: 'Import',
  }
  return labels[action] || action
}
