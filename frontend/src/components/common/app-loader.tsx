import { Workflow } from 'lucide-react'
import { cn } from '@/lib/utils'

export function AppLoader({ compact = false }: { compact?: boolean }) {
  return (
    <div
      className={cn(
        'flex items-center justify-center bg-background',
        compact ? 'min-h-[18rem] w-full' : 'min-h-screen w-full',
      )}
      role="status"
      aria-label="Chargement de IOL ETL Platform"
    >
      <div className="flex flex-col items-center gap-4">
        <div className="relative flex h-16 w-16 items-center justify-center">
          <span className="absolute inset-0 animate-spin rounded-full border-2 border-border border-t-primary" />
          <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary text-primary-foreground shadow-sm">
            <Workflow className="h-6 w-6" aria-hidden="true" />
          </span>
        </div>
        <div className="text-center">
          <p className="text-sm font-semibold">IOL ETL Platform</p>
          <p className="mt-1 text-xs text-muted-foreground">Chargement de votre espace</p>
        </div>
      </div>
    </div>
  )
}
