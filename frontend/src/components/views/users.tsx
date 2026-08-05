
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Trash2, Search, UserCog } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { userService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import { RoleBadge } from '@/components/common/badges'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { useAuthStore } from '@/stores/auth-store'
import { useToast } from '@/hooks/use-toast'
import { roleLabel } from '@/lib/i18n'
import type { UserDto, UserRole } from '@/lib/api/types'

export function UsersView() {
  const { t } = useTranslation()
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const me = useAuthStore((s) => s.user)
  const [search, setSearch] = useState('')
  const [userToDelete, setUserToDelete] = useState<UserDto | null>(null)

  const listQ = useQuery({ queryKey: ['users'], queryFn: userService.list })

  const updateRoleMut = useMutation({
    mutationFn: ({ id, role }: { id: string; role: UserRole }) => userService.updateRole(id, role),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['users'] }); toast({ title: `${t('common.role')} ${t('common.updatedAt').toLowerCase()}` }) },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })
  const deleteMut = useMutation({
    mutationFn: (id: string) => userService.remove(id),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['users'] }); setUserToDelete(null); toast({ title: `${t('common.user')} ${t('common.delete').toLowerCase()}` }) },
    onError: (e) => toast({ title: t('common.failure'), description: describeError(e), variant: 'destructive' }),
  })

  const users = (listQ.data ?? []).filter((u) =>
    !search || u.email?.toLowerCase().includes(search.toLowerCase()) || u.name?.toLowerCase().includes(search.toLowerCase())
  )

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title={t('common.users')}
        description={t('nav.descriptions.users')}
      />

      <div className="mb-4 relative max-w-sm">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
        <Input placeholder={`${t('common.search')} ${t('common.user').toLowerCase()}...`} value={search} onChange={(e) => setSearch(e.target.value)} className="pl-8" />
      </div>

      {listQ.isLoading ? <LoadingState />
      : listQ.isError ? <ErrorState message={describeError(listQ.error)} onRetry={() => listQ.refetch()} />
      : users.length === 0 ? <EmptyState title={`${t('common.none')} ${t('common.user').toLowerCase()}`} icon={UserCog} />
      : (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead className="bg-muted/40">
              <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                <th className="px-3 py-2">{t('common.name')}</th>
                <th className="px-3 py-2">{t('common.email')}</th>
                <th className="px-3 py-2">{t('common.role')}</th>
                <th className="px-3 py-2">{t('common.status')}</th>
                <th className="px-3 py-2 text-right">{t('common.actions')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {users.map((u: UserDto) => (
                <tr key={u.id} className="hover:bg-muted/30">
                  <td className="px-3 py-2 font-medium">{u.name}{me?.id === u.id && <span className="ml-1.5 text-[10px] text-muted-foreground">({t('common.user').toLowerCase()})</span>}</td>
                  <td className="px-3 py-2 text-muted-foreground">{u.email}</td>
                  <td className="px-3 py-2">
                    <Select
                      value={u.role}
                      onValueChange={(v: UserRole) => updateRoleMut.mutate({ id: u.id, role: v })}
                      disabled={me?.id === u.id}
                    >
                      <SelectTrigger className="h-7 w-32 text-xs"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="USER">{roleLabel('USER')}</SelectItem>
                        <SelectItem value="ADMIN">{roleLabel('ADMIN')}</SelectItem>
                      </SelectContent>
                    </Select>
                  </td>
                  <td className="px-3 py-2">
                    {u.active
                      ? <Badge variant="outline" className="text-success border-success/30">{t('common.active')}</Badge>
                      : <Badge variant="outline" className="text-muted-foreground">{t('common.inactive')}</Badge>
                    }
                  </td>
                  <td className="px-3 py-2 text-right">
                    <Button size="sm" variant="ghost" className="text-destructive" disabled={me?.id === u.id} onClick={() => setUserToDelete(u)}>
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <ConfirmDialog
        open={!!userToDelete}
        onOpenChange={(open) => !open && setUserToDelete(null)}
        title={t('confirm.deleteUserTitle', { email: userToDelete?.email || '' })}
        description={t('confirm.deleteUserDescription')}
        confirmLabel={t('common.delete')}
        variant="destructive"
        pending={deleteMut.isPending}
        onConfirm={() => {
          if (userToDelete) deleteMut.mutate(userToDelete.id)
        }}
      />
    </div>
  )
}
