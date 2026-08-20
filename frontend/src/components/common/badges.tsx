
import { cn } from '@/lib/utils'
import type { ExecutionStatus, StandardStatus, WorkflowDirection, UserRole } from '@/lib/api/types'
import { directionLabel, executionStatusLabel, roleLabel, standardStatusLabel } from '@/lib/i18n'

// ---------- Status execution ----------
export function ExecutionStatusBadge({ status }: { status: ExecutionStatus }) {
  const map: Record<ExecutionStatus, { label: string; cls: string; dot?: boolean }> = {
    RUNNING: { label: executionStatusLabel('RUNNING'), cls: 'bg-info/10 text-info border-info/30', dot: true },
    SUCCESS: { label: executionStatusLabel('SUCCESS'), cls: 'bg-success/10 text-success border-success/30' },
    FAILED: { label: executionStatusLabel('FAILED'), cls: 'bg-destructive/10 text-destructive border-destructive/30' },
    DELIVERED: { label: executionStatusLabel('DELIVERED'), cls: 'bg-success/10 text-success border-success/30' },
  }
  // Repli neutre si le serveur renvoie un jour un statut inconnu du client.
  const s = map[status] || { label: String(status), cls: 'bg-muted text-muted-foreground border-border' }
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium', s.cls)}>
      {s.dot && <span className="h-1.5 w-1.5 rounded-full bg-current pulse-dot" />}
      {s.label}
    </span>
  )
}

// ---------- Direction ----------
export function DirectionBadge({ direction }: { direction?: WorkflowDirection }) {
  if (!direction) return null
  const isInternal = direction === 'INTERNAL'
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium',
        isInternal
          ? 'bg-secondary text-secondary-foreground border-border'
          : 'bg-accent text-accent-foreground border-accent/40'
      )}
    >
      {directionLabel(direction)}
    </span>
  )
}

// ---------- Standard status ----------
export function StandardStatusBadge({ status }: { status: StandardStatus }) {
  const active = status === 'ACTIVE'
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium',
        active
          ? 'bg-success/10 text-success border-success/30'
          : 'bg-muted text-muted-foreground border-border'
      )}
    >
      {standardStatusLabel(status)}
    </span>
  )
}

// ---------- Role badge ----------
export function RoleBadge({ role }: { role: UserRole }) {
  const admin = role === 'ADMIN'
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-[10px] font-semibold',
        admin
          ? 'bg-warning/10 text-warning border-warning/30'
          : 'bg-muted text-muted-foreground border-border'
      )}
    >
      {roleLabel(role)}
    </span>
  )
}
