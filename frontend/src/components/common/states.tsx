
import { AlertCircle, Loader2, Inbox, RotateCcw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

// ---------- Loading ----------
export function LoadingState({ label, className }: { label?: string; className?: string }) {
  const { t } = useTranslation()
  return (
    <div className={cn('flex items-center justify-center gap-3 py-16 text-muted-foreground', className)}>
      <Loader2 className="h-4 w-4 animate-spin" />
      <span className="text-sm">{label || t('common.loading')}</span>
    </div>
  )
}

// ---------- Empty (honnête) ----------
export function EmptyState({
  title,
  description,
  icon: Icon = Inbox,
  action,
  className,
}: {
  title: string
  description?: string
  icon?: typeof Inbox
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed border-border py-16 text-center', className)}>
      <div className="rounded-full bg-muted p-3">
        <Icon className="h-5 w-5 text-muted-foreground" />
      </div>
      <div>
        <p className="text-sm font-medium text-foreground">{title}</p>
        {description && (
          <p className="mx-auto mt-1 max-w-md text-xs text-muted-foreground">{description}</p>
        )}
      </div>
      {action}
    </div>
  )
}

// ---------- Error (avec retry) ----------
export function ErrorState({
  message,
  onRetry,
  className,
}: {
  message: string
  onRetry?: () => void
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <div className={cn('flex flex-col items-center justify-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 py-12 text-center', className)}>
      <div className="rounded-full bg-destructive/10 p-3">
        <AlertCircle className="h-5 w-5 text-destructive" />
      </div>
      <p className="mx-auto max-w-md text-sm text-foreground">{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RotateCcw className="mr-1.5 h-3.5 w-3.5" />
          {t('common.retry')}
        </Button>
      )}
    </div>
  )
}

// ---------- Inline error (smaller, for forms) ----------
export function InlineError({ message }: { message?: string }) {
  if (!message) return null
  return (
    <span className="flex items-center gap-1.5 text-xs text-destructive">
      <AlertCircle className="h-3 w-3" />
      {message}
    </span>
  )
}

// ---------- Page header ----------
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: string
  actions?: React.ReactNode
}) {
  return (
    <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-foreground">{title}</h1>
        {description && (
          <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}
