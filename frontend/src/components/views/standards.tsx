
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { BookOpen, CheckCircle2, ChevronLeft, ChevronRight, FlaskConical, Pencil, Plus, Search, Tag, XCircle } from 'lucide-react'
import { standardService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter, DialogClose } from '@/components/ui/dialog'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { StandardStatusBadge } from '@/components/common/badges'
import { useAuthStore } from '@/stores/auth-store'
import { useToast } from '@/hooks/use-toast'
import type { StandardDomain, StandardDto, StandardTermDto } from '@/lib/api/types'

const STANDARD_DATA_TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'DATE', 'DATETIME', 'BOOLEAN', 'TIME', 'UUID', 'TIMESTAMP', 'JSON']
const STANDARD_PAGE_SIZE = 6
const STANDARD_DOMAINS: Array<{ value: StandardDomain; label: string }> = [
  { value: 'HEALTH', label: 'Santé' },
  { value: 'FINANCE', label: 'Finance' },
  { value: 'EDUCATION', label: 'Éducation' },
  { value: 'RETAIL', label: 'Commerce' },
  { value: 'LOGISTICS', label: 'Logistique' },
  { value: 'COMPLIANCE', label: 'Conformité' },
  { value: 'CUSTOM', label: 'Personnalisé' },
]

function standardDomainLabel(domain: StandardDomain) {
  return STANDARD_DOMAINS.find((item) => item.value === domain)?.label || domain
}

export function StandardsView() {
  const isAdmin = useAuthStore((s) => s.isAdmin)
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const [selectedId, setSelectedId] = useState<string | undefined>()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)

  const standardsQ = useQuery({ queryKey: ['standards'], queryFn: standardService.list })

  const createMut = useMutation({
    mutationFn: (body: Partial<StandardDto>) => standardService.create(body),
    onSuccess: (standard) => {
      setSelectedId(standard.id)
      queryClient.invalidateQueries({ queryKey: ['standards'] })
      toast({ title: 'Norme créée' })
    },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })
  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: string; body: Partial<StandardDto> }) => standardService.update(id, body),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['standards'] }); toast({ title: 'Norme mise à jour' }) },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })
  const activateMut = useMutation({
    mutationFn: (id: string) => standardService.activate(id),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['standards'] }); toast({ title: 'Norme activée' }) },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })
  const deprecateMut = useMutation({
    mutationFn: (id: string) => standardService.deprecate(id),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['standards'] }); toast({ title: 'Norme dépréciée' }) },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })

  const standards = useMemo(() => standardsQ.data ?? [], [standardsQ.data])
  const selected = standards.find((s) => s.id === selectedId) || standards[0]
  const filteredStandards = useMemo(() => {
    const term = search.trim().toLocaleLowerCase('fr')
    if (!term) return standards
    return standards.filter((standard) =>
      [standard.name, standard.domain, standard.version, standard.description]
        .filter(Boolean)
        .some((value) => String(value).toLocaleLowerCase('fr').includes(term)),
    )
  }, [search, standards])
  const totalPages = Math.max(1, Math.ceil(filteredStandards.length / STANDARD_PAGE_SIZE))
  const activePage = Math.min(page, totalPages)
  const pageStandards = filteredStandards.slice(
    (activePage - 1) * STANDARD_PAGE_SIZE,
    activePage * STANDARD_PAGE_SIZE,
  )

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title="Normes & champs"
        description="Format commun interopérable et champs de la norme."
        actions={isAdmin && (
          <CreateStandardDialog
            pending={createMut.isPending}
            onCreate={(body) => createMut.mutateAsync(body)}
          />
        )}
      />

      <div className="grid gap-4 lg:grid-cols-[320px_1fr]">
        {/* List */}
        <div>
          {standardsQ.isLoading ? <LoadingState />
          : standardsQ.isError ? <ErrorState message={describeError(standardsQ.error)} onRetry={() => standardsQ.refetch()} />
          : standards.length === 0 ? (
            <EmptyState title="Aucune norme" description={isAdmin ? 'Créez votre première norme.' : undefined} />
          ) : (
            <div className="space-y-3">
              <div className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  value={search}
                  onChange={(event) => {
                    setSearch(event.target.value)
                    setPage(1)
                  }}
                  placeholder="Rechercher une norme"
                  className="pl-9"
                />
              </div>
              {pageStandards.length === 0 ? (
                <EmptyState title="Aucun résultat" description="Modifiez votre recherche." />
              ) : (
                <div className="space-y-1.5">
                  {pageStandards.map((s) => (
                    <Card
                      key={s.id}
                      className={`cursor-pointer transition-colors ${(selected?.id === s.id) ? 'border-primary bg-accent/40' : 'hover:bg-muted/40'}`}
                      onClick={() => setSelectedId(s.id)}
                    >
                      <CardContent className="p-3">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate text-sm font-medium">{s.name}</p>
                            <p className="text-xs text-muted-foreground">{standardDomainLabel(s.domain)} · v{s.version || '—'}</p>
                          </div>
                          <StandardStatusBadge status={s.status} />
                        </div>
                      </CardContent>
                    </Card>
                  ))}
                </div>
              )}
              <div className="flex items-center justify-between gap-2 border-t border-border pt-3">
                <p className="text-xs text-muted-foreground">{filteredStandards.length} norme{filteredStandards.length > 1 ? 's' : ''}</p>
                <div className="flex items-center gap-1">
                  <Button
                    size="icon"
                    variant="outline"
                    className="h-8 w-8"
                    onClick={() => setPage(Math.max(1, activePage - 1))}
                    disabled={activePage === 1}
                    aria-label="Page précédente"
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                  <span className="min-w-[56px] text-center text-xs">{activePage} / {totalPages}</span>
                  <Button
                    size="icon"
                    variant="outline"
                    className="h-8 w-8"
                    onClick={() => setPage(Math.min(totalPages, activePage + 1))}
                    disabled={activePage === totalPages}
                    aria-label="Page suivante"
                  >
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Detail */}
        <div>
          {selected ? (
            <StandardDetail
              key={selected.id}
              standard={selected}
              isAdmin={isAdmin}
              onActivate={() => activateMut.mutate(selected.id)}
              onDeprecate={() => deprecateMut.mutate(selected.id)}
              onEdit={(body) => updateMut.mutateAsync({ id: selected.id, body })}
            />
          ) : (
            <EmptyState title="Sélectionnez une norme" icon={BookOpen} />
          )}
        </div>
      </div>
    </div>
  )
}

function StandardDetail({
  standard, isAdmin, onActivate, onDeprecate, onEdit,
}: {
  standard: StandardDto
  isAdmin: boolean
  onActivate: () => void
  onDeprecate: () => void
  onEdit: (body: Partial<StandardDto>) => Promise<StandardDto>
}) {
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const [tab, setTab] = useState('info')
  const [terms, setTerms] = useState<StandardTermDto[]>([])
  const [termsLoading, setTermsLoading] = useState(false)

  // Load terms
  useQuery({
    queryKey: ['standards', standard.id, 'terms'],
    queryFn: async () => {
      setTermsLoading(true)
      try {
        const t = await standardService.listTerms(standard.id)
        setTerms(t || [])
        return t
      } finally { setTermsLoading(false) }
    },
    enabled: !!standard.id,
  })

  const addTermMut = useMutation({
    mutationFn: (body: Partial<StandardTermDto>) => standardService.addTerm(standard.id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['standards', standard.id, 'terms'] })
      toast({ title: 'Champ ajouté' })
    },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })

  const updateTermMut = useMutation({
    mutationFn: ({ id, body }: { id: string; body: Partial<StandardTermDto> }) => standardService.updateTerm(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['standards', standard.id, 'terms'] })
      toast({ title: 'Champ mis à jour' })
    },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2 text-base">
          {standard.name}
          <StandardStatusBadge status={standard.status} />
          <span className="ml-auto flex gap-1.5">
            {isAdmin && standard.status !== 'ACTIVE' && (
              <Button size="sm" variant="outline" onClick={onActivate}><CheckCircle2 className="mr-1 h-3.5 w-3.5" /> Activer</Button>
            )}
            {isAdmin && standard.status !== 'DEPRECATED' && (
              <Button size="sm" variant="outline" onClick={onDeprecate}><XCircle className="mr-1 h-3.5 w-3.5" /> Déprécier</Button>
            )}
            {isAdmin && <EditStandardDialog standard={standard} onEdit={onEdit} />}
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList>
            <TabsTrigger value="info">Informations</TabsTrigger>
            <TabsTrigger value="terms">Champs de la norme ({terms.length})</TabsTrigger>
            <TabsTrigger value="validate">Valider une valeur</TabsTrigger>
          </TabsList>

          <TabsContent value="info" className="space-y-2 text-sm">
            <Row label="ID" value={<code className="font-mono text-xs">{standard.id}</code>} />
            <Row label="Domaine" value={standardDomainLabel(standard.domain)} />
            <Row label="Description" value={standard.description || '—'} />
            <Row label="Version" value={standard.version || '—'} />
            <Row label="Statut" value={<StandardStatusBadge status={standard.status} />} />
            <Row
              label="Référence"
              value={standard.referenceUrl
                ? <a className="text-primary underline-offset-4 hover:underline" href={standard.referenceUrl} target="_blank" rel="noreferrer">{standard.referenceUrl}</a>
                : '—'}
            />
            <Row label="Créé le" value={standard.createdAt || '—'} />
          </TabsContent>

          <TabsContent value="terms" className="space-y-3">
            <div className="flex items-center justify-between">
              <p className="text-xs text-muted-foreground">Champs attendus pour cette norme.</p>
              {isAdmin && <AddTermDialog onAdd={(b) => addTermMut.mutate(b)} standardId={standard.id} />}
            </div>
            {termsLoading ? <LoadingState label="Chargement des champs…" /> : terms.length === 0 ? (
              <EmptyState title="Aucun champ" description={isAdmin ? 'Ajoutez le premier champ.' : undefined} icon={Tag} />
            ) : (
              <div className="space-y-2">
                {terms.map((t) => {
                  const format = termFormat(t)
                  const constraints = termConstraints(t)
                  const notes = t.notes || t.cleaningRules
                  return (
                    <div key={t.id} className="rounded-md border border-border bg-muted/20 p-3">
                      <div className="flex items-start justify-between gap-2">
                        <div>
                          <p className="font-mono text-sm font-medium">{t.termName}</p>
                          <p className="text-xs text-muted-foreground">{t.dataType} · {t.required ? 'Requis' : 'Optionnel'}</p>
                          {t.description && <p className="mt-1 text-xs text-muted-foreground">{t.description}</p>}
                        </div>
                        {isAdmin && <EditTermDialog term={t} onEdit={(body) => updateTermMut.mutate({ id: t.id, body })} />}
                      </div>
                      {format && <p className="mt-2 text-xs"><span className="text-muted-foreground">Format :</span> <code className="font-mono">{format}</code></p>}
                      {constraints.length > 0 && <p className="mt-1 text-xs"><span className="text-muted-foreground">Contraintes :</span> {constraints.join(' · ')}</p>}
                      {t.enumValues && t.enumValues.length > 0 && (
                        <p className="mt-1 text-xs"><span className="text-muted-foreground">Valeurs :</span> {t.enumValues.join(', ')}</p>
                      )}
                      {t.exampleValue && <p className="mt-1 text-xs"><span className="text-muted-foreground">Exemple :</span> <code className="font-mono">{t.exampleValue}</code></p>}
                      {notes && <p className="mt-1 text-xs"><span className="text-muted-foreground">Notes :</span> {notes}</p>}
                      {t.systemMappings && Object.keys(t.systemMappings).length > 0 && (
                        <div className="mt-2">
                          <p className="text-[10px] uppercase tracking-wide text-muted-foreground">Correspondances système</p>
                          <pre className="mt-1 max-h-32 overflow-auto rounded bg-muted/40 p-2 font-mono text-[10px]">{JSON.stringify(t.systemMappings, null, 2)}</pre>
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            )}
          </TabsContent>

          <TabsContent value="validate">
            <ValidateValue standardId={standard.id} />
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  )
}

function ValidateValue({ standardId }: { standardId: string }) {
  const { toast } = useToast()
  const [termName, setTermName] = useState('')
  const [value, setValue] = useState('')
  const [dataType, setDataType] = useState('STRING')
  const [result, setResult] = useState<import('@/lib/api/types').ValidationResult | null>(null)
  const validateMut = useMutation({
    mutationFn: () => standardService.validate(standardId, { termName, value, dataType }),
    onSuccess: (data) => setResult(data),
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">Valide une valeur par rapport aux règles d'un champ de la norme.</p>
      <div className="grid gap-3 sm:grid-cols-[1fr_2fr_150px_auto]">
        <div className="space-y-1.5">
          <Label className="text-xs">Champ</Label>
          <Input value={termName} onChange={(e) => setTermName(e.target.value)} placeholder="ex: customerId" />
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">Valeur</Label>
          <Input value={value} onChange={(e) => setValue(e.target.value)} placeholder="valeur à tester" />
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">Type</Label>
          <Select value={dataType} onValueChange={setDataType}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="STRING">STRING</SelectItem>
              <SelectItem value="INTEGER">INTEGER</SelectItem>
              <SelectItem value="DECIMAL">DECIMAL</SelectItem>
              <SelectItem value="DATE">DATE</SelectItem>
              <SelectItem value="BOOLEAN">BOOLEAN</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="flex items-end">
          <Button onClick={() => validateMut.mutate()} disabled={validateMut.isPending || !termName}>
            <FlaskConical className="mr-1.5 h-3.5 w-3.5" /> Valider
          </Button>
        </div>
      </div>
      {result && (
        <div className={`rounded-md border p-3 text-sm ${result.valid ? 'border-success/30 bg-success/5 text-success' : 'border-destructive/30 bg-destructive/5 text-destructive'}`}>
          <p className="font-medium">{result.valid ? '✓ Valeur valide' : '✗ Valeur invalide'}</p>
          {result.errors && result.errors.length > 0 && (
            <ul className="mt-1 list-disc pl-5 text-xs">{result.errors.map((e, i) => <li key={i}>{e}</li>)}</ul>
          )}
          {result.message && <p className="mt-1 text-xs">{result.message}</p>}
        </div>
      )}
    </div>
  )
}

function CreateStandardDialog({
  onCreate,
  pending,
}: {
  onCreate: (body: Partial<StandardDto>) => Promise<StandardDto>
  pending: boolean
}) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [domain, setDomain] = useState<StandardDomain>('CUSTOM')
  const [version, setVersion] = useState('1.0')
  const [description, setDescription] = useState('')
  const [referenceUrl, setReferenceUrl] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const submit = async () => {
    setFormError(null)
    try {
      await onCreate({
        name: name.trim(),
        domain,
        version: optionalText(version),
        description: optionalText(description),
        referenceUrl: optionalText(referenceUrl),
      })
      setOpen(false)
      setName('')
      setDomain('CUSTOM')
      setVersion('1.0')
      setDescription('')
      setReferenceUrl('')
    } catch (error) {
      setFormError(describeError(error))
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button><Plus className="mr-1.5 h-4 w-4" /> Nouvelle norme</Button></DialogTrigger>
      <DialogContent>
        <DialogHeader><DialogTitle>Créer une norme</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5"><Label className="text-xs">Nom *</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label className="text-xs">Domaine *</Label>
              <Select value={domain} onValueChange={(value) => setDomain(value as StandardDomain)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {STANDARD_DOMAINS.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5"><Label className="text-xs">Version</Label><Input value={version} onChange={(e) => setVersion(e.target.value)} /></div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Description</Label><Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} /></div>
          <div className="space-y-1.5"><Label className="text-xs">URL de référence</Label><Input type="url" value={referenceUrl} onChange={(e) => setReferenceUrl(e.target.value)} placeholder="https://…" /></div>
          {formError && <p className="text-xs text-destructive">{formError}</p>}
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="outline">Annuler</Button></DialogClose>
          <Button onClick={submit} disabled={pending || !name.trim()}>{pending ? 'Création…' : 'Créer'}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EditStandardDialog({
  standard,
  onEdit,
}: {
  standard: StandardDto
  onEdit: (body: Partial<StandardDto>) => Promise<StandardDto>
}) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState(standard.name)
  const [domain, setDomain] = useState<StandardDomain>(standard.domain)
  const [version, setVersion] = useState(standard.version || '')
  const [description, setDescription] = useState(standard.description || '')
  const [referenceUrl, setReferenceUrl] = useState(standard.referenceUrl || '')
  const [pending, setPending] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const submit = async () => {
    setPending(true)
    setFormError(null)
    try {
      await onEdit({
        name: name.trim(),
        domain,
        version: optionalText(version),
        description: optionalText(description),
        referenceUrl: optionalText(referenceUrl),
      })
      setOpen(false)
    } catch (error) {
      setFormError(describeError(error))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button size="icon" variant="ghost" className="h-8 w-8" title="Modifier la norme" aria-label="Modifier la norme"><Pencil className="h-3.5 w-3.5" /></Button></DialogTrigger>
      <DialogContent>
        <DialogHeader><DialogTitle>Modifier la norme</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5"><Label className="text-xs">Nom</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label className="text-xs">Domaine</Label>
              <Select value={domain} onValueChange={(value) => setDomain(value as StandardDomain)}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {STANDARD_DOMAINS.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5"><Label className="text-xs">Version</Label><Input value={version} onChange={(e) => setVersion(e.target.value)} /></div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Description</Label><Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} /></div>
          <div className="space-y-1.5"><Label className="text-xs">URL de référence</Label><Input type="url" value={referenceUrl} onChange={(e) => setReferenceUrl(e.target.value)} /></div>
          {formError && <p className="text-xs text-destructive">{formError}</p>}
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="outline">Annuler</Button></DialogClose>
          <Button onClick={submit} disabled={pending || !name.trim()}>{pending ? 'Enregistrement…' : 'Enregistrer'}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function termFormat(term: StandardTermDto) {
  return term.formatRule || term.format || ''
}

function termConstraints(term: StandardTermDto) {
  const parts: string[] = []
  if (term.minLength !== undefined) parts.push(`min ${term.minLength}`)
  if (term.maxLength !== undefined) parts.push(`max ${term.maxLength}`)
  if (term.precision !== undefined) parts.push(`précision ${term.precision}`)
  if (term.scale !== undefined) parts.push(`échelle ${term.scale}`)
  return parts
}

function mappingsToText(mappings?: Record<string, string>) {
  return Object.entries(mappings || {}).map(([system, field]) => `${system}=${field}`).join('\n')
}

function optionalText(value: string) {
  const trimmed = value.trim()
  return trimmed ? trimmed : undefined
}

function optionalNumber(value: string, label: string) {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number(trimmed)
  if (!Number.isFinite(parsed)) {
    throw new Error(`${label} doit être un nombre.`)
  }
  return parsed
}

function parseEnumValues(value: string) {
  const items = value
    .split(/[,\n]/)
    .map((item) => item.trim())
    .filter(Boolean)
  return items.length > 0 ? items : undefined
}

function parseSystemMappings(value: string) {
  const text = value.trim()
  if (!text) return undefined

  if (text.startsWith('{')) {
    const parsed = JSON.parse(text) as unknown
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
      throw new Error('Les correspondances doivent être un objet JSON.')
    }
    const mappings: Record<string, string> = {}
    for (const [system, field] of Object.entries(parsed)) {
      if (typeof field !== 'string' || !system.trim() || !field.trim()) {
        throw new Error('Chaque correspondance JSON doit avoir une clé et une valeur texte.')
      }
      mappings[system.trim()] = field.trim()
    }
    return Object.keys(mappings).length > 0 ? mappings : undefined
  }

  const mappings: Record<string, string> = {}
  for (const rawLine of text.split(/[,\n]/)) {
    const line = rawLine.trim()
    if (!line) continue
    const separatorIndex = line.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex === line.length - 1) {
      throw new Error('Utilisez une ligne par correspondance, par exemple generic-json=patientId.')
    }
    const system = line.slice(0, separatorIndex).trim()
    const field = line.slice(separatorIndex + 1).trim()
    if (!system || !field) {
      throw new Error('Chaque correspondance doit avoir un système et un champ.')
    }
    mappings[system] = field
  }
  return Object.keys(mappings).length > 0 ? mappings : undefined
}

function AddTermDialog({ onAdd, standardId }: { onAdd: (b: Partial<StandardTermDto>) => void; standardId: string }) {
  const [open, setOpen] = useState(false)
  const [termName, setTermName] = useState('')
  const [dataType, setDataType] = useState('STRING')
  const [required, setRequired] = useState(false)
  const [description, setDescription] = useState('')
  const [formatRule, setFormatRule] = useState('')
  const [minLength, setMinLength] = useState('')
  const [maxLength, setMaxLength] = useState('')
  const [precision, setPrecision] = useState('')
  const [scale, setScale] = useState('')
  const [enumValues, setEnumValues] = useState('')
  const [systemMappings, setSystemMappings] = useState('')
  const [exampleValue, setExampleValue] = useState('')
  const [notes, setNotes] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const reset = () => {
    setTermName('')
    setDataType('STRING')
    setRequired(false)
    setDescription('')
    setFormatRule('')
    setMinLength('')
    setMaxLength('')
    setPrecision('')
    setScale('')
    setEnumValues('')
    setSystemMappings('')
    setExampleValue('')
    setNotes('')
    setFormError(null)
  }

  const submit = () => {
    try {
      const body: Partial<StandardTermDto> = {
        standardId,
        termName: termName.trim(),
        dataType,
        required,
        description: optionalText(description),
        formatRule: optionalText(formatRule),
        minLength: optionalNumber(minLength, 'Longueur min'),
        maxLength: optionalNumber(maxLength, 'Longueur max'),
        precision: optionalNumber(precision, 'Précision'),
        scale: optionalNumber(scale, 'Échelle'),
        enumValues: parseEnumValues(enumValues),
        systemMappings: parseSystemMappings(systemMappings),
        exampleValue: optionalText(exampleValue),
        notes: optionalText(notes),
      }
      onAdd(body)
      reset()
      setOpen(false)
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Le formulaire contient une valeur invalide.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button size="sm"><Plus className="mr-1 h-3.5 w-3.5" /> Ajouter un champ</Button></DialogTrigger>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>Ajouter un champ de norme</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5"><Label className="text-xs">Nom du champ *</Label><Input value={termName} onChange={(e) => setTermName(e.target.value)} placeholder="ex: customerId" /></div>
          <div className="space-y-1.5"><Label className="text-xs">Description</Label><Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} /></div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label className="text-xs">Type</Label>
              <Select value={dataType} onValueChange={setDataType}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {STANDARD_DATA_TYPES.map((type) => <SelectItem key={type} value={type}>{type}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">Requis</Label>
              <div className="flex h-9 items-center"><Switch checked={required} onCheckedChange={setRequired} /></div>
            </div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Format (regex ou pattern)</Label><Input value={formatRule} onChange={(e) => setFormatRule(e.target.value)} placeholder="^[A-Z]{2}\\d+$" /></div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5"><Label className="text-xs">Longueur min</Label><Input value={minLength} onChange={(e) => setMinLength(e.target.value)} inputMode="numeric" /></div>
            <div className="space-y-1.5"><Label className="text-xs">Longueur max</Label><Input value={maxLength} onChange={(e) => setMaxLength(e.target.value)} inputMode="numeric" /></div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5"><Label className="text-xs">Précision</Label><Input value={precision} onChange={(e) => setPrecision(e.target.value)} inputMode="numeric" /></div>
            <div className="space-y-1.5"><Label className="text-xs">Échelle</Label><Input value={scale} onChange={(e) => setScale(e.target.value)} inputMode="numeric" /></div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Valeurs autorisées</Label><Input value={enumValues} onChange={(e) => setEnumValues(e.target.value)} placeholder="A, B, C" /></div>
          <div className="space-y-1.5">
            <Label className="text-xs">Correspondances système</Label>
            <Textarea value={systemMappings} onChange={(e) => setSystemMappings(e.target.value)} rows={4} placeholder={'generic-json=patientId\nfhir=patient.id'} />
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Exemple</Label><Input value={exampleValue} onChange={(e) => setExampleValue(e.target.value)} placeholder="P-001" /></div>
          <div className="space-y-1.5"><Label className="text-xs">Notes</Label><Textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} /></div>
          {formError && <p className="text-xs text-destructive">{formError}</p>}
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="outline">Annuler</Button></DialogClose>
          <Button onClick={submit} disabled={!termName.trim()}>Ajouter</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function EditTermDialog({ term, onEdit }: { term: StandardTermDto; onEdit: (b: Partial<StandardTermDto>) => void }) {
  const [open, setOpen] = useState(false)
  const [termName, setTermName] = useState(term.termName)
  const [dataType, setDataType] = useState(term.dataType)
  const [required, setRequired] = useState(term.required)
  const [description, setDescription] = useState(term.description || '')
  const [formatRule, setFormatRule] = useState(termFormat(term))
  const [minLength, setMinLength] = useState(term.minLength?.toString() || '')
  const [maxLength, setMaxLength] = useState(term.maxLength?.toString() || '')
  const [precision, setPrecision] = useState(term.precision?.toString() || '')
  const [scale, setScale] = useState(term.scale?.toString() || '')
  const [enumValues, setEnumValues] = useState((term.enumValues || []).join(', '))
  const [systemMappings, setSystemMappings] = useState(mappingsToText(term.systemMappings))
  const [exampleValue, setExampleValue] = useState(term.exampleValue || '')
  const [notes, setNotes] = useState(term.notes || term.cleaningRules || '')
  const [formError, setFormError] = useState<string | null>(null)

  const submit = () => {
    try {
      onEdit({
        required,
        description: description.trim(),
        formatRule: formatRule.trim(),
        minLength: optionalNumber(minLength, 'Longueur min'),
        maxLength: optionalNumber(maxLength, 'Longueur max'),
        precision: optionalNumber(precision, 'Précision'),
        scale: optionalNumber(scale, 'Échelle'),
        enumValues: parseEnumValues(enumValues) || [],
        systemMappings: systemMappings.trim() ? parseSystemMappings(systemMappings) : {},
        exampleValue: exampleValue.trim(),
        notes: notes.trim(),
      })
      setFormError(null)
      setOpen(false)
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Le formulaire contient une valeur invalide.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button size="sm" variant="ghost"><Pencil className="h-3.5 w-3.5" /></Button></DialogTrigger>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>Modifier le champ de norme</DialogTitle></DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1.5"><Label className="text-xs">Nom du champ</Label><Input value={termName} onChange={(e) => setTermName(e.target.value)} disabled /></div>
          <div className="space-y-1.5"><Label className="text-xs">Description</Label><Textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={2} /></div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label className="text-xs">Type</Label>
              <Select value={dataType} onValueChange={setDataType} disabled>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {STANDARD_DATA_TYPES.map((type) => <SelectItem key={type} value={type}>{type}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5"><Label className="text-xs">Requis</Label><div className="flex h-9 items-center"><Switch checked={required} onCheckedChange={setRequired} /></div></div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Format</Label><Input value={formatRule} onChange={(e) => setFormatRule(e.target.value)} /></div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5"><Label className="text-xs">Longueur min</Label><Input value={minLength} onChange={(e) => setMinLength(e.target.value)} inputMode="numeric" /></div>
            <div className="space-y-1.5"><Label className="text-xs">Longueur max</Label><Input value={maxLength} onChange={(e) => setMaxLength(e.target.value)} inputMode="numeric" /></div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5"><Label className="text-xs">Précision</Label><Input value={precision} onChange={(e) => setPrecision(e.target.value)} inputMode="numeric" /></div>
            <div className="space-y-1.5"><Label className="text-xs">Échelle</Label><Input value={scale} onChange={(e) => setScale(e.target.value)} inputMode="numeric" /></div>
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Valeurs autorisées</Label><Input value={enumValues} onChange={(e) => setEnumValues(e.target.value)} placeholder="A, B, C" /></div>
          <div className="space-y-1.5">
            <Label className="text-xs">Correspondances système</Label>
            <Textarea value={systemMappings} onChange={(e) => setSystemMappings(e.target.value)} rows={4} placeholder={'generic-json=patientId\nfhir=patient.id'} />
          </div>
          <div className="space-y-1.5"><Label className="text-xs">Exemple</Label><Input value={exampleValue} onChange={(e) => setExampleValue(e.target.value)} /></div>
          <div className="space-y-1.5"><Label className="text-xs">Notes</Label><Textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} /></div>
          {formError && <p className="text-xs text-destructive">{formError}</p>}
        </div>
        <DialogFooter>
          <DialogClose asChild><Button variant="outline">Annuler</Button></DialogClose>
          <Button onClick={submit}>Enregistrer</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-1 border-b border-border/50 py-1.5 sm:grid-cols-[180px_1fr]">
      <span className="text-xs uppercase tracking-wide text-muted-foreground">{label}</span>
      <span className="text-sm">{value}</span>
    </div>
  )
}
