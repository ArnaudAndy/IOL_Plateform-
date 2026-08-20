import { useState } from 'react'
import { ArrowRight, Loader2, AlertCircle, KeyRound, ChevronLeft } from 'lucide-react'
import { IolLogo } from '@/components/common/iol-logo'
import { MorphingParticlesBackground } from '@/components/common/morphing-particles-background'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '@/stores/auth-store'
import { authService } from '@/lib/api/services'
import { describeError, ApiRequestError } from '@/lib/api/client'
import { roleLabel } from '@/lib/i18n'
import { keycloakEnabled, loginWithIdentity } from '@/lib/auth/keycloak'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { useToast } from '@/hooks/use-toast'

type Mode = 'login' | 'register' | 'forgot' | 'reset'

export function AuthScreen() {
  const { t } = useTranslation()
  const { toast } = useToast()
  const setSession = useAuthStore((state) => state.setSession)
  const [mode, setMode] = useState<Mode>('login')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')

  if (keycloakEnabled) {
    return (
      <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background p-4">
        <MorphingParticlesBackground />
        <div className="relative z-10 w-full max-w-md">
          <div className="mb-6 flex flex-col items-center gap-3">
            <IolLogo size={48} className="rounded-xl shadow-sm" />
            <div className="text-center">
              <h1 className="text-lg font-semibold text-white">IOL ETL Platform</h1>
              <p className="text-xs text-white/70">{t('app.console')}</p>
            </div>
          </div>
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-base">{t('auth.accessTitle')}</CardTitle>
              <CardDescription className="text-xs">{t('auth.permissions')}</CardDescription>
            </CardHeader>
            <CardContent>
              <Button className="w-full" onClick={() => void loginWithIdentity()}>
                <KeyRound className="mr-1.5 h-4 w-4" />
                {t('auth.loginButton')}
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>
    )
  }

  async function submitAuth(event: React.FormEvent) {
    event.preventDefault()
    await run(async () => {
      const result = mode === 'login'
        ? await authService.login({ email: email.trim(), password })
        : await authService.register({ name: name.trim(), email: email.trim(), password })
      if (!result?.token || !result?.user) throw new Error(t('auth.invalidResponse'))
      setSession(result.token, result.user)
      toast({
        title: t('auth.welcome', { name: result.user.name || result.user.email }),
        description: t('auth.signedInAs', { role: roleLabel(result.user.role) }),
      })
    })
  }

  async function submitForgot(event: React.FormEvent) {
    event.preventDefault()
    await run(async () => {
      await authService.forgotPassword({ email: email.trim() })
      setMode('reset')
      toast({ title: 'Code envoyé', description: 'Consultez votre messagerie puis saisissez le code à 6 chiffres.' })
    })
  }

  async function submitReset(event: React.FormEvent) {
    event.preventDefault()
    await run(async () => {
      await authService.resetPassword({ email: email.trim(), code, newPassword: password })
      setPassword('')
      setCode('')
      setMode('login')
      toast({ title: 'Mot de passe modifié', description: 'Vous pouvez maintenant vous connecter.' })
    })
  }

  async function run(action: () => Promise<void>) {
    setLoading(true)
    setError(null)
    try {
      await action()
    } catch (caught) {
      const message = caught instanceof ApiRequestError && caught.status === 0
        ? t('auth.serverUnavailable')
        : describeError(caught)
      setError(message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background p-4">
      <MorphingParticlesBackground />
      <div className="relative z-10 w-full max-w-md">
        <div className="mb-6 flex flex-col items-center gap-3">
          <IolLogo size={48} className="rounded-xl shadow-sm" />
          <div className="text-center">
            <h1 className="text-lg font-semibold text-white">IOL ETL Platform</h1>
            <p className="text-xs text-white/70">{t('app.console')}</p>
          </div>
        </div>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">
              {mode === 'forgot' ? 'Mot de passe oublié' : mode === 'reset' ? 'Saisir le code' : t('auth.accessTitle')}
            </CardTitle>
            <CardDescription className="text-xs">
              {mode === 'forgot' || mode === 'reset'
                ? 'La récupération est liée à votre adresse email.'
                : t('auth.permissions')}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {(mode === 'login' || mode === 'register') ? (
              <Tabs value={mode} onValueChange={(value) => { setMode(value as Mode); setError(null) }}>
                <TabsList className="grid w-full grid-cols-2">
                  <TabsTrigger value="login" className="text-xs">{t('auth.login')}</TabsTrigger>
                  <TabsTrigger value="register" className="text-xs">{t('auth.register')}</TabsTrigger>
                </TabsList>
                {(['login', 'register'] as const).map((tab) => (
                  <TabsContent key={tab} value={tab} className="mt-4">
                    <form onSubmit={submitAuth} className="space-y-3">
                      {tab === 'register' && (
                        <Field label={t('auth.fullName')}>
                          <Input value={name} onChange={(event) => setName(event.target.value)} required disabled={loading} autoComplete="name" />
                        </Field>
                      )}
                      <EmailField email={email} setEmail={setEmail} loading={loading} />
                      <Field label={t('common.password')}>
                        <Input type="password" value={password} onChange={(event) => setPassword(event.target.value)} required disabled={loading} minLength={tab === 'register' ? 8 : 1} autoComplete={tab === 'login' ? 'current-password' : 'new-password'} />
                      </Field>
                      {tab === 'login' && (
                        <Button type="button" variant="link" size="sm" className="h-auto px-0 text-xs" onClick={() => { setMode('forgot'); setError(null) }}>
                          Mot de passe oublié ?
                        </Button>
                      )}
                      <ErrorMessage error={error} />
                      <SubmitButton loading={loading} label={tab === 'login' ? t('auth.loginButton') : t('auth.createAccount')} />
                    </form>
                  </TabsContent>
                ))}
              </Tabs>
            ) : (
              <form onSubmit={mode === 'forgot' ? submitForgot : submitReset} className="space-y-3">
                <EmailField email={email} setEmail={setEmail} loading={loading} />
                {mode === 'reset' && (
                  <>
                    <Field label="Code reçu par email">
                      <Input value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} inputMode="numeric" pattern="\d{6}" maxLength={6} required disabled={loading} className="font-mono text-center text-lg" />
                    </Field>
                    <Field label="Nouveau mot de passe">
                      <Input type="password" value={password} onChange={(event) => setPassword(event.target.value)} minLength={8} required disabled={loading} autoComplete="new-password" />
                    </Field>
                  </>
                )}
                <ErrorMessage error={error} />
                <SubmitButton loading={loading} label={mode === 'forgot' ? 'Envoyer le code' : 'Modifier le mot de passe'} icon={<KeyRound className="mr-1.5 h-4 w-4" />} />
                <Button type="button" variant="ghost" className="w-full" onClick={() => { setMode('login'); setError(null) }}>
                  <ChevronLeft className="mr-1.5 h-4 w-4" /> Retour à la connexion
                </Button>
              </form>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="space-y-1.5"><Label className="text-xs">{label}</Label>{children}</div>
}

function EmailField({ email, setEmail, loading }: { email: string; setEmail: (value: string) => void; loading: boolean }) {
  const { t } = useTranslation()
  return (
    <Field label={t('common.email')}>
      <Input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required disabled={loading} autoComplete="email" />
    </Field>
  )
}

function ErrorMessage({ error }: { error: string | null }) {
  if (!error) return null
  return (
    <div className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-2.5 text-xs text-destructive">
      <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
      <span>{error}</span>
    </div>
  )
}

function SubmitButton({ loading, label, icon }: { loading: boolean; label: string; icon?: React.ReactNode }) {
  return (
    <Button type="submit" disabled={loading} className="w-full">
      {loading ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : icon || <ArrowRight className="mr-1.5 h-4 w-4" />}
      {label}
    </Button>
  )
}
