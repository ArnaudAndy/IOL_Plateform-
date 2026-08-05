import { QueryClientProvider } from '@tanstack/react-query'
import { useState, useEffect, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { createQueryClient } from '@/lib/query-client'
import { useAuthStore } from '@/stores/auth-store'
import { ThemeProvider } from '@/lib/theme'
import { Toaster } from '@/components/ui/toaster'

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => createQueryClient())
  const hydrate = useAuthStore((s) => s.hydrate)
  const { i18n } = useTranslation()

  // Hydrater l'auth au montage (côté client)
  useEffect(() => {
    hydrate()
  }, [hydrate])

  return (
    <ThemeProvider defaultTheme="dark">
      <QueryClientProvider client={queryClient}>
        <div key={i18n.resolvedLanguage || i18n.language}>
          {children}
        </div>
        <Toaster />
      </QueryClientProvider>
    </ThemeProvider>
  )
}
