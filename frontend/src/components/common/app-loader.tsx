import { cn } from '@/lib/utils'
import { IolLogo } from '@/components/common/iol-logo'

type AppLoaderProps = {
  /**
   * Superposition : le loader se place au-dessus de toute l'application
   * (sidebar et topbar comprises) au lieu de n'occuper que son conteneur.
   */
  overlay?: boolean
}

export function AppLoader({ overlay = false }: AppLoaderProps) {
  return (
    <div
      className={cn(
        'flex items-center justify-center bg-background',
        overlay
          ? 'fixed inset-0 z-50 h-screen w-screen'
          : 'min-h-screen w-full',
      )}
      role="status"
      aria-live="polite"
      aria-label="Chargement de IOL ETL Platform"
    >
      <div className="flex flex-col items-center gap-4">
        <div className="relative flex h-20 w-20 items-center justify-center">
          <span className="absolute inset-0 animate-spin rounded-full border-2 border-border border-t-primary" />
          <IolLogo size={48} className="rounded-xl shadow-sm" />
        </div>
        <div className="text-center">
          <p className="text-sm font-semibold">IOL ETL Platform</p>
          <p className="mt-1 text-xs text-muted-foreground">Chargement de votre espace</p>
        </div>
      </div>
    </div>
  )
}
