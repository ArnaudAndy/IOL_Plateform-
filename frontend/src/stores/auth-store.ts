
import { create } from 'zustand'
import type { UserDto, UserRole } from '@/lib/api/types'
import {
  getToken, setToken, getStoredUser, setStoredUser,
  registerUnauthorizedHandler,
  registerSessionRefreshedHandler, revokeSession,
} from '@/lib/api/client'
import { initializeIdentity, identityToken, identityUser, keycloakEnabled } from '@/lib/auth/keycloak'

interface AuthState {
  user: UserDto | null
  token: string | null
  isAuthenticated: boolean
  isAdmin: boolean
  hydrated: boolean
  hydrate: () => Promise<void>
  setSession: (token: string, user: UserDto) => void
  logout: () => Promise<void>
  clearSession: () => void
  hasRole: (...roles: UserRole[]) => boolean
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  token: null,
  isAuthenticated: false,
  isAdmin: false,
  hydrated: false,

  hydrate: async () => {
    if (keycloakEnabled) {
      const authenticated = await initializeIdentity()
      const token = identityToken()
      const user = identityUser()
      if (authenticated && token && user) {
        set({ token, user, isAuthenticated: true, isAdmin: user.role === 'ADMIN', hydrated: true })
      } else {
        set({ token: null, user: null, isAuthenticated: false, isAdmin: false, hydrated: true })
      }
      return
    }
    const token = getToken()
    const user = getStoredUser<UserDto>()
    if (token && user) {
      set({ token, user, isAuthenticated: true, isAdmin: user.role === 'ADMIN', hydrated: true })
    } else {
      set({ token: null, user: null, isAuthenticated: false, isAdmin: false, hydrated: true })
    }
  },

  setSession: (token, user) => {
    setToken(token)
    setStoredUser(user)
    set({ token, user, isAuthenticated: true, isAdmin: user.role === 'ADMIN', hydrated: true })
  },

  clearSession: () => {
    setToken(null)
    setStoredUser(null)
    set({ token: null, user: null, isAuthenticated: false, isAdmin: false, hydrated: true })
  },

  logout: async () => {
    await revokeSession()
    set({ token: null, user: null, isAuthenticated: false, isAdmin: false, hydrated: true })
  },

  hasRole: (...roles) => {
    const u = get().user
    if (!u) return false
    return roles.includes(u.role)
  },
}))

// Brancher le handler 401 du client API -> logout automatique
if (typeof window !== 'undefined') {
  registerUnauthorizedHandler(() => {
    useAuthStore.getState().clearSession()
  })
  registerSessionRefreshedHandler((token, user) => {
    useAuthStore.getState().setSession(token, user as UserDto)
  })
}
