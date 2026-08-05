import { useEffect, useState } from 'react'
import { KeyRound, Loader2, Save, UserRound } from 'lucide-react'
import { authService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { useAuthStore } from '@/stores/auth-store'
import { PageHeader } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/hooks/use-toast'
import { keycloakEnabled, openIdentityAccount } from '@/lib/auth/keycloak'

export function SettingsView() {
  const { toast } = useToast()
  const user = useAuthStore((state) => state.user)
  const setSession = useAuthStore((state) => state.setSession)
  const [name, setName] = useState(user?.name ?? '')
  const [email, setEmail] = useState(user?.email ?? '')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [savingProfile, setSavingProfile] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)

  if (keycloakEnabled) {
    return (
      <div className="mx-auto w-full max-w-7xl">
        <PageHeader title="Paramètres" description="Compte et sécurité" />
        <div className="max-w-2xl">
          <Card>
            <CardHeader className="border-b border-border">
              <CardTitle className="flex items-center gap-2 text-sm"><UserRound className="h-4 w-4" /> Compte</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-5">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Nom complet"><Input value={user?.name || ''} readOnly /></Field>
                <Field label="Adresse email"><Input value={user?.email || ''} readOnly /></Field>
              </div>
              <Button type="button" onClick={openIdentityAccount}>
                <KeyRound className="mr-1.5 h-4 w-4" /> Gérer le compte et le mot de passe
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>
    )
  }

  useEffect(() => {
    setName(user?.name ?? '')
    setEmail(user?.email ?? '')
  }, [user])

  async function saveProfile(event: React.FormEvent) {
    event.preventDefault()
    setSavingProfile(true)
    try {
      const session = await authService.updateProfile({ name: name.trim(), email: email.trim() })
      setSession(session.token, session.user)
      toast({ title: 'Profil mis à jour' })
    } catch (error) {
      toast({ title: 'Échec', description: describeError(error), variant: 'destructive' })
    } finally {
      setSavingProfile(false)
    }
  }

  async function savePassword(event: React.FormEvent) {
    event.preventDefault()
    if (newPassword !== confirmation) {
      toast({ title: 'Les nouveaux mots de passe ne correspondent pas.', variant: 'destructive' })
      return
    }
    setSavingPassword(true)
    try {
      const session = await authService.changePassword({ currentPassword, newPassword })
      setSession(session.token, session.user)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmation('')
      toast({ title: 'Mot de passe modifié' })
    } catch (error) {
      toast({ title: 'Échec', description: describeError(error), variant: 'destructive' })
    } finally {
      setSavingPassword(false)
    }
  }

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader title="Paramètres" description="Compte et sécurité" />
      <div className="grid max-w-5xl items-stretch gap-4 lg:grid-cols-2">
        <Card className="flex h-full flex-col">
          <CardHeader className="border-b border-border"><CardTitle className="flex items-center gap-2 text-sm"><UserRound className="h-4 w-4" /> Informations personnelles</CardTitle></CardHeader>
          <CardContent className="flex flex-1 pt-5">
            <form onSubmit={saveProfile} className="flex w-full flex-col gap-4">
              <Field label="Nom complet"><Input value={name} onChange={(event) => setName(event.target.value)} required /></Field>
              <Field label="Adresse email"><Input type="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></Field>
              <Button type="submit" disabled={savingProfile} className="mt-auto self-start">
                {savingProfile ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <Save className="mr-1.5 h-4 w-4" />}
                Enregistrer
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card className="flex h-full flex-col">
          <CardHeader className="border-b border-border"><CardTitle className="flex items-center gap-2 text-sm"><KeyRound className="h-4 w-4" /> Mot de passe</CardTitle></CardHeader>
          <CardContent className="flex flex-1 pt-5">
            <form onSubmit={savePassword} className="flex w-full flex-col gap-4">
              <Field label="Mot de passe actuel"><Input type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} required autoComplete="current-password" /></Field>
              <Field label="Nouveau mot de passe"><Input type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} required minLength={8} autoComplete="new-password" /></Field>
              <Field label="Confirmer le nouveau mot de passe"><Input type="password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} required minLength={8} autoComplete="new-password" /></Field>
              <Button type="submit" disabled={savingPassword} className="mt-auto self-start">
                {savingPassword ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <KeyRound className="mr-1.5 h-4 w-4" />}
                Modifier
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="space-y-1.5"><Label className="text-xs">{label}</Label>{children}</div>
}
