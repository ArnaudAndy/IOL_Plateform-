
import { useState } from 'react'
import {
  LayoutDashboard,
  Workflow,
  Activity,
  ArrowDownToLine,
  BookMarked,
  Plug,
  Terminal,
  Bot,
  Users,
  ShieldAlert,
  Moon,
  Sun,
  LogOut,
  ChevronLeft,
  Radio,
  Languages,
  Settings,
  Menu,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useTheme } from '@/lib/theme'
import { getCurrentLanguage, setAppLanguage, type AppLanguage } from '@/lib/i18n'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { useNavStore, type ViewId } from '@/stores/nav-store'
import { useAuthStore } from '@/stores/auth-store'
import { RoleBadge } from '@/components/common/badges'
import { IolLogo } from '@/components/common/iol-logo'
import { ConfirmDialog } from '@/components/common/confirm-dialog'
import { useMounted } from '@/lib/format'

type NavItem = {
  id: ViewId
  labelKey: string
  icon: typeof Workflow
  adminOnly?: boolean
  descriptionKey?: string
}

type NavGroup = {
  titleKey: string
  items: NavItem[]
}

const NAV_GROUPS: NavGroup[] = [
  {
    titleKey: 'nav.groups.operations',
    items: [
      { id: 'dashboard', labelKey: 'nav.items.dashboard', icon: LayoutDashboard, descriptionKey: 'nav.descriptions.dashboard' },
      { id: 'flow-monitor', labelKey: 'nav.items.flowMonitor', icon: Radio, descriptionKey: 'nav.descriptions.flowMonitor' },
      { id: 'workflows', labelKey: 'nav.items.workflows', icon: Workflow, descriptionKey: 'nav.descriptions.workflows' },
      { id: 'executions', labelKey: 'nav.items.executions', icon: Activity, descriptionKey: 'nav.descriptions.executions' },
      { id: 'interop', labelKey: 'nav.items.interop', icon: ArrowDownToLine, descriptionKey: 'nav.descriptions.interop' },
    ],
  },
  {
    titleKey: 'nav.groups.configuration',
    items: [
      { id: 'standards', labelKey: 'nav.items.standards', icon: BookMarked, descriptionKey: 'nav.descriptions.standards' },
      { id: 'connections', labelKey: 'nav.items.connections', icon: Plug, descriptionKey: 'nav.descriptions.connections' },
      { id: 'sql-workbench', labelKey: 'nav.items.sqlWorkbench', icon: Terminal, descriptionKey: 'nav.descriptions.sqlWorkbench' },
      { id: 'ai-assistant', labelKey: 'nav.items.aiAssistant', icon: Bot, descriptionKey: 'nav.descriptions.aiAssistant' },
      { id: 'settings', labelKey: 'nav.items.settings', icon: Settings, descriptionKey: 'nav.descriptions.settings' },
    ],
  },
  {
    titleKey: 'nav.groups.administration',
    items: [
      { id: 'users', labelKey: 'nav.items.users', icon: Users, adminOnly: true, descriptionKey: 'nav.descriptions.users' },
      { id: 'audit', labelKey: 'nav.items.audit', icon: ShieldAlert, adminOnly: true, descriptionKey: 'nav.descriptions.audit' },
    ],
  },
]

export function AppSidebar({ mobile = false, onNavigate }: { mobile?: boolean; onNavigate?: () => void }) {
  const { t } = useTranslation()
  const view = useNavStore((s) => s.view)
  const navigate = useNavStore((s) => s.navigate)
  const user = useAuthStore((s) => s.user)
  const isAdmin = useAuthStore((s) => s.isAdmin)

  return (
    <aside className={cn(
      'h-full w-64 flex-col bg-sidebar text-sidebar-foreground',
      mobile ? 'flex w-full' : 'hidden border-r border-sidebar-border md:flex',
    )}>
      {/* Brand */}
      <div className="flex h-14 items-center gap-2.5 border-b border-sidebar-border px-4">
        <IolLogo size={32} className="rounded-md shadow-sm" />
        <div className="flex flex-col leading-tight">
          <span className="text-sm font-semibold tracking-tight text-sidebar-foreground">IOL ETL</span>
          <span className="text-[10px] uppercase tracking-wider text-muted-foreground">Admin Console</span>
        </div>
      </div>

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto px-2 py-3">
        {NAV_GROUPS.map((group) => {
          const visibleItems = group.items.filter((it) => !it.adminOnly || isAdmin)
          if (visibleItems.length === 0) return null
          return (
            <div key={group.titleKey} className="mb-6">
              {/* Titres de section : casse normale, semibold, couleur de texte
                  pleine. Ils etaient en 10px gris majuscules, trop discrets
                  pour structurer la navigation. */}
              <p className="px-3 pb-2 text-[15px] font-semibold text-sidebar-foreground">
                {t(group.titleKey)}
              </p>
              <div className="space-y-0.5">
                {visibleItems.map((item) => {
                  const Icon = item.icon
                  const active = view === item.id || (item.id === 'workflows' && (view === 'workflow-detail' || view === 'workflow-builder'))
                  return (
                    <button
                      key={item.id}
                      onClick={() => { navigate(item.id); onNavigate?.() }}
                      className={cn(
                        'group flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-left text-sm transition-colors',
                        active
                          ? 'bg-[var(--sidebar-active)] text-white font-medium shadow-sm'
                          : 'text-sidebar-foreground/80 hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground'
                      )}
                      title={item.descriptionKey ? t(item.descriptionKey) : undefined}
                    >
                      <Icon className={cn('h-4 w-4 shrink-0', active ? 'text-white' : 'text-muted-foreground group-hover:text-sidebar-foreground')} />
                      <span className="truncate">{t(item.labelKey)}</span>
                    </button>
                  )
                })}
              </div>
            </div>
          )
        })}
      </nav>

      {/* User footer */}
      {user && (
        <div className="border-t border-sidebar-border p-3">
          <div className="flex items-center gap-2.5 rounded-md px-2 py-1.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-sidebar-accent text-xs font-semibold uppercase text-sidebar-accent-foreground">
              {user.name?.[0] || user.email[0]}
            </div>
            <div className="min-w-0 flex-1 leading-tight">
              <p className="truncate text-xs font-medium text-sidebar-foreground">{user.name || user.email}</p>
              <p className="truncate text-[10px] text-muted-foreground">{user.email}</p>
            </div>
            <RoleBadge role={user.role} />
          </div>
        </div>
      )}
    </aside>
  )
}

export function AppTopbar({ onOpenNavigation }: { onOpenNavigation?: () => void }) {
  const { t } = useTranslation()
  const [confirmLogoutOpen, setConfirmLogoutOpen] = useState(false)
  const { theme, setTheme } = useTheme()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const back = useNavStore((s) => s.back)
  const history = useNavStore((s) => s.history)
  const navigate = useNavStore((s) => s.navigate)
  const mounted = useMounted()
  const canGoBack = history.length > 0
  const currentLanguage = getCurrentLanguage()
  const nextLanguage: AppLanguage = currentLanguage === 'fr' ? 'en' : 'fr'

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-4 border-b border-border bg-header/80 px-4 backdrop-blur-sm">
      <div className="flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          onClick={onOpenNavigation}
          className="h-8 w-8 md:hidden"
          aria-label="Ouvrir la navigation"
        >
          <Menu className="h-4 w-4" />
        </Button>
        {canGoBack && (
          <Button variant="ghost" size="icon" onClick={back} className="h-8 w-8">
            <ChevronLeft className="h-4 w-4" />
          </Button>
        )}
        <button
          onClick={() => navigate('dashboard')}
          className="hidden text-xs text-muted-foreground hover:text-foreground sm:block"
        >
          IOL ETL Platform
        </button>
      </div>

      <div className="flex items-center gap-1.5">
        <div className="mr-2 hidden items-center gap-1.5 rounded-md border border-border px-2 py-1 text-[10px] text-muted-foreground sm:flex">
          <span className="h-1.5 w-1.5 rounded-full bg-success" />
          {t('nav.sessionActive')}
        </div>

        <Button
          variant="ghost"
          size="sm"
          onClick={() => setAppLanguage(nextLanguage)}
          className="h-8 gap-1.5 px-2 text-xs"
          aria-label={t('nav.changeLanguage')}
          title={t('nav.currentLanguage', { language: currentLanguage.toUpperCase() })}
        >
          <Languages className="h-3.5 w-3.5" />
          <span>{currentLanguage.toUpperCase()}</span>
        </Button>

        <Button
          variant="ghost"
          size="icon"
          onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
          className="h-8 w-8"
          aria-label={t('nav.themeToggle')}
        >
          {mounted && theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        </Button>

        {user && (
          <>
            <Button variant="ghost" size="sm" onClick={() => setConfirmLogoutOpen(true)} className="h-8 gap-1.5 text-muted-foreground hover:text-foreground">
              <LogOut className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">{t('nav.logout')}</span>
            </Button>
            <ConfirmDialog
              open={confirmLogoutOpen}
              onOpenChange={setConfirmLogoutOpen}
              title={t('confirm.logoutTitle')}
              description={t('confirm.logoutDescription')}
              confirmLabel={t('confirm.logoutLabel')}
              onConfirm={logout}
            />
          </>
        )}
      </div>
    </header>
  )
}
