import Keycloak, { type KeycloakTokenParsed } from 'keycloak-js'
import type { UserDto, UserRole } from '@/lib/api/types'

export const keycloakEnabled = String(import.meta.env.VITE_AUTH_MODE || 'local').toLowerCase() === 'keycloak'

const keycloak = keycloakEnabled
  ? new Keycloak({
      url: import.meta.env.VITE_KEYCLOAK_URL || '/auth',
      realm: import.meta.env.VITE_KEYCLOAK_REALM || 'iol',
      clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'iol-web',
    })
  : null

let initialization: Promise<boolean> | null = null

export function initializeIdentity(): Promise<boolean> {
  if (!keycloak) return Promise.resolve(false)
  if (!initialization) {
    initialization = keycloak.init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      checkLoginIframe: false,
      enableLogging: false,
    })
  }
  return initialization
}

export function identityToken(): string | null {
  return keycloak?.token || null
}

export function identityUser(): UserDto | null {
  const claims = keycloak?.tokenParsed
  if (!claims?.sub) return null
  const roles = tokenRoles(claims)
  const role: UserRole = roles.includes('ADMIN') ? 'ADMIN' : 'USER'
  return {
    id: claims.sub,
    name: String(claims.name || claims.preferred_username || claims.email || ''),
    email: String(claims.email || claims.preferred_username || ''),
    role,
    active: true,
  }
}

export async function refreshIdentity(minValiditySeconds = 30): Promise<boolean> {
  if (!keycloak?.authenticated) return false
  try {
    await keycloak.updateToken(minValiditySeconds)
    return true
  } catch {
    return false
  }
}

export async function loginWithIdentity(): Promise<void> {
  if (!keycloak) return
  await keycloak.login({ redirectUri: window.location.href })
}

export async function logoutFromIdentity(): Promise<void> {
  if (!keycloak) return
  await keycloak.logout({ redirectUri: `${window.location.origin}/` })
}

export function openIdentityAccount(): void {
  if (!keycloak) return
  void keycloak.accountManagement()
}

function tokenRoles(claims: KeycloakTokenParsed): string[] {
  const realmRoles = claims.realm_access?.roles || []
  const resourceRoles = Object.values(claims.resource_access || {})
    .flatMap((entry) => entry.roles || [])
  return [...realmRoles, ...resourceRoles].map((role) => role.toUpperCase())
}
