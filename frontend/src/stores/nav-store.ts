
import { create } from 'zustand'

export type ViewId =
  | 'dashboard'
  | 'flow-monitor'
  | 'workflows'
  | 'workflow-detail'
  | 'workflow-builder'
  | 'executions'
  | 'interop'
  | 'standards'
  | 'connections'
  | 'sql-workbench'
  | 'ai-assistant'
  | 'users'
  | 'audit'
  | 'settings'

type NavEntry = {
  view: ViewId
  params: Record<string, string | undefined>
}

interface NavState {
  view: ViewId
  params: Record<string, string | undefined>
  navigate: (view: ViewId, params?: Record<string, string | undefined>) => void
  replace: (view: ViewId, params?: Record<string, string | undefined>) => void
  syncFromLocation: () => void
  back: () => void
  history: NavEntry[]
}

const STATIC_PATHS: Partial<Record<ViewId, string>> = {
  dashboard: '/',
  'flow-monitor': '/monitoring',
  workflows: '/workflows',
  executions: '/executions',
  interop: '/interop',
  standards: '/standards',
  connections: '/connections',
  'sql-workbench': '/sql',
  'ai-assistant': '/assistant-sql',
  users: '/utilisateurs',
  audit: '/audit',
  settings: '/parametres',
}

function decodeSegment(value: string) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function routeFromLocation(): NavEntry {
  if (typeof window === 'undefined') return { view: 'dashboard', params: {} }

  const pathname = window.location.pathname.replace(/\/+$/, '') || '/'
  const search = new URLSearchParams(window.location.search)

  if (pathname === '/workflows/new') return { view: 'workflow-builder', params: {} }

  const workflowEdit = pathname.match(/^\/workflows\/([^/]+)\/edit$/)
  if (workflowEdit) {
    return { view: 'workflow-builder', params: { id: decodeSegment(workflowEdit[1]) } }
  }

  const workflowDetail = pathname.match(/^\/workflows\/([^/]+)$/)
  if (workflowDetail) {
    return {
      view: 'workflow-detail',
      params: {
        id: decodeSegment(workflowDetail[1]),
        tab: search.get('tab') || undefined,
      },
    }
  }

  const staticRoute = Object.entries(STATIC_PATHS)
    .find(([, path]) => path === pathname)?.[0] as ViewId | undefined

  if (staticRoute) {
    return {
      view: staticRoute,
      params: staticRoute === 'executions'
        ? { executionId: search.get('execution') || undefined }
        : {},
    }
  }

  return { view: 'dashboard', params: {} }
}

function pathFor(view: ViewId, params: Record<string, string | undefined>) {
  if (view === 'workflow-builder') {
    return params.id ? `/workflows/${encodeURIComponent(params.id)}/edit` : '/workflows/new'
  }
  if (view === 'workflow-detail') {
    if (!params.id) return '/workflows'
    const query = new URLSearchParams()
    if (params.tab) query.set('tab', params.tab)
    const suffix = query.toString()
    return `/workflows/${encodeURIComponent(params.id)}${suffix ? `?${suffix}` : ''}`
  }
  if (view === 'executions' && params.executionId) {
    return `/executions?execution=${encodeURIComponent(params.executionId)}`
  }
  return STATIC_PATHS[view] || '/'
}

function scrollPageTop() {
  if (typeof window === 'undefined') return
  window.requestAnimationFrame(() => {
    document.getElementById('app-main-scroll')?.scrollTo({ top: 0 })
  })
}

const initialRoute = routeFromLocation()

export const useNavStore = create<NavState>((set, get) => ({
  ...initialRoute,
  history: [],
  navigate: (view, params = {}) => {
    const current = get()
    const nextPath = pathFor(view, params)
    const currentPath = typeof window === 'undefined'
      ? ''
      : `${window.location.pathname}${window.location.search}`
    if (
      current.view === view
      && JSON.stringify(current.params) === JSON.stringify(params)
      && currentPath === nextPath
    ) {
      scrollPageTop()
      return
    }
    if (typeof window !== 'undefined' && currentPath !== nextPath) {
      window.history.pushState({ iolView: view }, '', nextPath)
    }
    set({
      view,
      params,
      history: [...current.history, { view: current.view, params: current.params }].slice(-30),
    })
    scrollPageTop()
  },
  replace: (view, params = {}) => {
    if (typeof window !== 'undefined') {
      window.history.replaceState({ iolView: view }, '', pathFor(view, params))
    }
    set({ view, params })
    scrollPageTop()
  },
  syncFromLocation: () => {
    const route = routeFromLocation()
    const current = get()
    if (current.view === route.view && JSON.stringify(current.params) === JSON.stringify(route.params)) return
    set({
      ...route,
      history: current.history.length > 0 ? current.history.slice(0, -1) : [],
    })
    scrollPageTop()
  },
  back: () => {
    const h = get().history
    if (h.length === 0) return
    if (typeof window !== 'undefined') {
      window.history.back()
      return
    }
    const previous = h[h.length - 1]
    set({ view: previous.view, params: previous.params, history: h.slice(0, -1) })
  },
}))
