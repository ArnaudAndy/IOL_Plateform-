
import { QueryClient } from '@tanstack/react-query'

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: 1,
        refetchOnWindowFocus: false,
        staleTime: 30_000,
        // Pas de refetch automatique sur mount si on a déjà des données fraîches
      },
      mutations: {
        retry: 0,
      },
    },
  })
}
