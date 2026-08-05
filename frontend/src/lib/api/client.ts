// ============================================================
// IOL ETL Platform — Client API
// - Base URL configurable via VITE_API_BASE_URL (default: /api)
// - Intercepteur JWT (Authorization: Bearer <token>)
// - Gestion 401 (logout auto) + 403 (RBAC)
// - Deux modes de réponse :
//   * ENVELOPPE  : ApiResponse<T>  -> on retourne payload.data
//   * RAW        : objet direct    -> on retourne payload tel quel
//   Le mode est décidé par l'appelant via `raw: true`.
// - Aucune donnée inventée : si le backend est injoignable, on lève
//   une erreur honnête.
// ============================================================

import type { ApiResponse, ApiError } from './types'
import {
  identityToken,
  identityUser,
  keycloakEnabled,
  refreshIdentity,
  logoutFromIdentity,
} from '@/lib/auth/keycloak'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
const TOKEN_KEY = 'iol.etl.token'
const USER_KEY = 'iol.etl.user'

// ----- Token storage (localStorage, jamais en dur nulle part) -----
export function getToken(): string | null {
  if (typeof window === 'undefined') return null
  if (keycloakEnabled) return identityToken()
  return window.localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (typeof window === 'undefined') return
  if (keycloakEnabled) return
  if (token) window.localStorage.setItem(TOKEN_KEY, token)
  else window.localStorage.removeItem(TOKEN_KEY)
}

export function getStoredUser<T = unknown>(): T | null {
  if (typeof window === 'undefined') return null
  if (keycloakEnabled) return identityUser() as T | null
  const raw = window.localStorage.getItem(USER_KEY)
  if (!raw) return null
  try { return JSON.parse(raw) as T } catch { return null }
}

export function setStoredUser(user: unknown | null) {
  if (typeof window === 'undefined') return
  if (keycloakEnabled) return
  if (user) window.localStorage.setItem(USER_KEY, JSON.stringify(user))
  else window.localStorage.removeItem(USER_KEY)
}

// ----- Erreurs -----
export class ApiRequestError extends Error {
  status: number
  details?: unknown
  constructor(status: number, message: string, details?: unknown) {
    super(message)
    this.status = status
    this.details = details
    Object.setPrototypeOf(this, ApiRequestError.prototype)
  }
}

function makeError(status: number, message: string, details?: unknown): ApiRequestError {
  return new ApiRequestError(status, message, details)
}

// ----- Logout callback (branché par le store auth) -----
let onUnauthorized: (() => void) | null = null
let onSessionRefreshed: ((token: string, user: unknown) => void) | null = null
let refreshInFlight: Promise<boolean> | null = null
export function registerUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

export function registerSessionRefreshedHandler(fn: (token: string, user: unknown) => void) {
  onSessionRefreshed = fn
}

// ----- Constructeur d'URL (supporte query params) -----
function buildUrl(path: string, params?: Record<string, string | number | boolean | undefined>): string {
  const base = API_BASE_URL.endsWith('/') ? API_BASE_URL.slice(0, -1) : API_BASE_URL
  const cleanPath = path.startsWith('/') ? path : `/${path}`
  const url = `${base}${cleanPath}`
  if (!params) return url
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') search.append(k, String(v))
  })
  const qs = search.toString()
  return qs ? `${url}?${qs}` : url
}

// ----- Fetch wrapper -----
type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  body?: unknown
  params?: Record<string, string | number | boolean | undefined>
  raw?: boolean // true = réponse brute (sans enveloppe)
  signal?: AbortSignal
  headers?: Record<string, string>
}

async function request<T>(path: string, opts: RequestOptions = {}, allowRefresh = true): Promise<T> {
  const { method = 'GET', body, params, raw = false, signal, headers = {} } = opts
  const url = buildUrl(path, params)
  const token = getToken()

  const finalHeaders: Record<string, string> = {
    'Accept': 'application/json',
    ...headers,
  }
  if (token) finalHeaders['Authorization'] = `Bearer ${token}`
  if (body !== undefined && !(body instanceof FormData)) {
    finalHeaders['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers: finalHeaders,
      body: body instanceof FormData ? body : (body !== undefined ? JSON.stringify(body) : undefined),
      signal,
      credentials: 'include',
    })
  } catch (e) {
    // Erreur réseau (backend injoignable, DNS, timeout...)
    const err = e as Error
    throw makeError(0, `Backend injoignable : ${err.message || 'network error'}. Vérifiez que l'API est démarrée et que VITE_API_BASE_URL est correct.`, { originalError: err.message })
  }

  // 401 -> rotation du refresh token puis rejeu unique de la requete.
  if (response.status === 401) {
    if (allowRefresh && !isPublicAuthPath(path) && await renewSession()) {
      return request<T>(path, opts, false)
    }
    if (onUnauthorized) onUnauthorized()
    throw makeError(401, 'Session expirée ou non authentifié. Veuillez vous reconnecter.')
  }

  // Pas de contenu
  if (response.status === 204) {
    return undefined as T
  }

  // Pour les réponses export (fichier texte), on renvoie le texte brut
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    const text = await response.text()
    if (!response.ok) {
      throw makeError(response.status, text || `Erreur ${response.status}`)
    }
    return text as unknown as T
  }

  let payload: unknown
  try {
    payload = await response.json()
  } catch (e) {
    const err = e as Error
    throw makeError(response.status, `Réponse non-JSON illisible : ${err.message}`)
  }

  if (!response.ok) {
    // Extraction message d'erreur
    const p = payload as { message?: string; error?: string; details?: unknown }
    const message = p?.message || p?.error || `Erreur ${response.status}`
    throw makeError(response.status, message, p?.details)
  }

  // Décision enveloppe vs raw
  if (raw) {
    return payload as T
  }

  // Enveloppe ApiResponse<T>
  const env = payload as ApiResponse<T>
  // Heuristique : si l'objet a un champ `data`, on suppose que c'est l'enveloppe
  // (sinon on retourne tel quel — l'appelant peut forcer raw:true pour être sûr)
  if (env && typeof env === 'object' && 'data' in env && (Object.keys(env).length <= 5)) {
    return env.data
  }
  return payload as T
}

function isPublicAuthPath(path: string) {
  return ['/auth/login', '/auth/register', '/auth/refresh', '/auth/password/forgot', '/auth/password/reset']
    .some((publicPath) => path.startsWith(publicPath))
}

async function renewSession(): Promise<boolean> {
  if (keycloakEnabled) {
    const renewed = await refreshIdentity()
    const token = identityToken()
    const user = identityUser()
    if (renewed && token && user) onSessionRefreshed?.(token, user)
    return renewed
  }
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await fetch(buildUrl('/auth/refresh'), {
          method: 'POST',
          headers: { Accept: 'application/json' },
          credentials: 'include',
        })
        if (!response.ok) return false
        const payload = await response.json() as ApiResponse<{
          token: string
          userId: string
          name: string
          email: string
          role: string
        }>
        const session = payload.data
        if (!session?.token || !session?.userId) return false
        const user = {
          id: session.userId,
          name: session.name,
          email: session.email,
          role: session.role,
          active: true,
        }
        setToken(session.token)
        setStoredUser(user)
        onSessionRefreshed?.(session.token, user)
        return true
      } catch {
        return false
      } finally {
        refreshInFlight = null
      }
    })()
  }
  return refreshInFlight
}

export async function revokeSession() {
  if (keycloakEnabled) {
    await logoutFromIdentity()
    return
  }
  try {
    await fetch(buildUrl('/auth/logout'), {
      method: 'POST',
      headers: getToken() ? { Authorization: `Bearer ${getToken()}` } : undefined,
      credentials: 'include',
    })
  } finally {
    setToken(null)
    setStoredUser(null)
  }
}

// ----- API publique -----
export const api = {
  get: <T>(path: string, opts?: Omit<RequestOptions, 'method' | 'body'>) => request<T>(path, { ...opts, method: 'GET' }),
  post: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>) => request<T>(path, { ...opts, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>) => request<T>(path, { ...opts, method: 'PUT', body }),
  del: <T>(path: string, opts?: Omit<RequestOptions, 'method' | 'body'>) => request<T>(path, { ...opts, method: 'DELETE' }),
  // Variante raw (sans enveloppe) — pour StandardController et AuditController
  getRaw: <T>(path: string, opts?: Omit<RequestOptions, 'method' | 'body' | 'raw'>) => request<T>(path, { ...opts, method: 'GET', raw: true }),
  postRaw: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body' | 'raw'>) => request<T>(path, { ...opts, method: 'POST', body, raw: true }),
  putRaw: <T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body' | 'raw'>) => request<T>(path, { ...opts, method: 'PUT', body, raw: true }),
}

// ----- Helper export (texte/fichier) -----
export async function downloadExport(path: string, filename: string) {
  const text = await request<string>(path, { raw: true })
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ----- Helper erreur lisible -----
export function describeError(e: unknown): string {
  if (e instanceof ApiRequestError) {
    if (e.status === 0) return e.message
    if (e.status === 403) return 'Action interdite : permissions insuffisantes (rôle requis non satisfait).'
    if (e.status === 404) return 'Ressource introuvable.'
    return e.message
  }
  const err = e as Error
  return err?.message || 'Erreur inattendue.'
}
