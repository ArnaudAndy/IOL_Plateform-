
import { lazy, Suspense, useEffect, useState } from 'react'
import { useAuthStore } from '@/stores/auth-store'
import { useNavStore } from '@/stores/nav-store'
import { AppSidebar, AppTopbar } from '@/components/layout/sidebar'
import { AuthScreen } from '@/components/views/auth-screen'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import { AppLoader } from '@/components/common/app-loader'
import { ScrollToTop } from '@/components/common/scroll-to-top'

const DashboardView = lazy(() => import('@/components/views/dashboard').then((module) => ({ default: module.DashboardView })))
const FlowMonitorView = lazy(() => import('@/components/views/flow-monitor').then((module) => ({ default: module.FlowMonitorView })))
const WorkflowsView = lazy(() => import('@/components/views/workflows').then((module) => ({ default: module.WorkflowsView })))
const WorkflowDetailView = lazy(() => import('@/components/views/workflow-detail').then((module) => ({ default: module.WorkflowDetailView })))
const WorkflowBuilderView = lazy(() => import('@/components/views/workflow-builder').then((module) => ({ default: module.WorkflowBuilderView })))
const ExecutionsView = lazy(() => import('@/components/views/executions').then((module) => ({ default: module.ExecutionsView })))
const InteropView = lazy(() => import('@/components/views/interop').then((module) => ({ default: module.InteropView })))
const StandardsView = lazy(() => import('@/components/views/standards').then((module) => ({ default: module.StandardsView })))
const ConnectionsView = lazy(() => import('@/components/views/connections').then((module) => ({ default: module.ConnectionsView })))
const SqlWorkbenchView = lazy(() => import('@/components/views/sql-workbench').then((module) => ({ default: module.SqlWorkbenchView })))
const AiAssistantView = lazy(() => import('@/components/views/ai-assistant').then((module) => ({ default: module.AiAssistantView })))
const UsersView = lazy(() => import('@/components/views/users').then((module) => ({ default: module.UsersView })))
const AuditView = lazy(() => import('@/components/views/audit').then((module) => ({ default: module.AuditView })))
const SettingsView = lazy(() => import('@/components/views/settings').then((module) => ({ default: module.SettingsView })))

export function AppShell() {
  const hydrated = useAuthStore((s) => s.hydrated)
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  const isAdmin = useAuthStore((s) => s.isAdmin)
  const view = useNavStore((s) => s.view)
  const replace = useNavStore((s) => s.replace)
  const syncFromLocation = useNavStore((s) => s.syncFromLocation)
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  useEffect(() => {
    const adminOnlyViews = ['users', 'audit']
    if (isAuthenticated && !isAdmin && adminOnlyViews.includes(view)) {
      replace('dashboard')
    }
  }, [isAuthenticated, isAdmin, view, replace])

  useEffect(() => {
    window.addEventListener('popstate', syncFromLocation)
    return () => window.removeEventListener('popstate', syncFromLocation)
  }, [syncFromLocation])

  if (!hydrated) {
    return <AppLoader />
  }

  if (!isAuthenticated) {
    return <AuthScreen />
  }

  return (
    <div className="flex h-screen w-full overflow-hidden bg-background">
      <AppSidebar />
      <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
        <SheetContent side="left" className="w-[min(20rem,90vw)] gap-0 p-0 md:hidden">
          <SheetTitle className="sr-only">Navigation</SheetTitle>
          <AppSidebar mobile onNavigate={() => setMobileNavOpen(false)} />
        </SheetContent>
      </Sheet>
      <div className="flex min-w-0 flex-1 flex-col">
        <AppTopbar onOpenNavigation={() => setMobileNavOpen(true)} />
        <main id="app-main-scroll" className="min-w-0 flex-1 overflow-y-auto overflow-x-hidden px-3 py-4 sm:px-4 md:px-6 md:py-6">
          <Suspense fallback={<AppLoader compact />}>
            {renderView(view)}
          </Suspense>
          {['workflows', 'workflow-detail', 'workflow-builder', 'executions'].includes(view) && (
            <ScrollToTop targetId="app-main-scroll" />
          )}
        </main>
      </div>
    </div>
  )
}

function renderView(view: string) {
  switch (view) {
    case 'dashboard': return <DashboardView />
    case 'flow-monitor': return <FlowMonitorView />
    case 'workflows': return <WorkflowsView />
    case 'workflow-detail': return <WorkflowDetailView />
    case 'workflow-builder': return <WorkflowBuilderView />
    case 'executions': return <ExecutionsView />
    case 'interop': return <InteropView />
    case 'standards': return <StandardsView />
    case 'connections': return <ConnectionsView />
    case 'sql-workbench': return <SqlWorkbenchView />
    case 'ai-assistant': return <AiAssistantView />
    case 'users': return <UsersView />
    case 'audit': return <AuditView />
    case 'settings': return <SettingsView />
    default: return <DashboardView />
  }
}
