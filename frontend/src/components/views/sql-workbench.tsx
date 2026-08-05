
import { useEffect, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ShieldCheck, Play, CheckCircle2, AlertCircle, Loader2, Terminal, Database } from 'lucide-react'
import { connectionService, sqlService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useToast } from '@/hooks/use-toast'
import type { ValidateSqlResponse, ExecuteSqlResponse } from '@/lib/api/types'

export function SqlWorkbenchView() {
  const { toast } = useToast()
  const [sql, setSql] = useState('')
  const [connectionId, setConnectionId] = useState('')
  const [limit, setLimit] = useState(100)
  const [validateResult, setValidateResult] = useState<ValidateSqlResponse | null>(null)
  const [executeResult, setExecuteResult] = useState<ExecuteSqlResponse | null>(null)

  const connectionsQ = useQuery({ queryKey: ['connections'], queryFn: connectionService.list })
  useEffect(() => {
    if (!connectionId && connectionsQ.data?.[0]?.id) setConnectionId(connectionsQ.data[0].id)
  }, [connectionId, connectionsQ.data])

  const validateMut = useMutation({
    mutationFn: () => sqlService.validate({ sql }),
    onSuccess: (data) => {
      setValidateResult(data)
      toast({
        title: data.valid ? 'SQL valide' : 'SQL invalide',
        description: data.message,
        variant: data.valid ? 'default' : 'destructive',
      })
    },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })

  const executeMut = useMutation({
    mutationFn: () => sqlService.execute({ sql, connectionId, limit }),
    onSuccess: (data) => {
      setExecuteResult(data)
      toast({
        title: data.success ? 'Exécution OK' : 'Échec exécution',
        description: data.success ? `${data.rowCount} ligne(s) en ${data.executionTimeMs}ms` : data.error,
        variant: data.success ? 'default' : 'destructive',
      })
    },
    onError: (e) => toast({ title: 'Échec', description: describeError(e), variant: 'destructive' }),
  })

  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader
        title="Atelier SQL"
        description="Validez la sûreté d'un SQL et exécutez-le en test avec une limite de lignes."
      />

      <Card className="mb-4">
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-sm"><Terminal className="h-4 w-4" /> Éditeur SQL</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label className="text-xs">Connexion à interroger</Label>
              <Select value={connectionId} onValueChange={setConnectionId} disabled={connectionsQ.isLoading}>
                <SelectTrigger><SelectValue placeholder="Choisir une connexion" /></SelectTrigger>
                <SelectContent>
                  {(connectionsQ.data ?? []).map((connection) => (
                    <SelectItem key={connection.id} value={connection.id!}>
                      {connection.name} ({connection.dbType})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label className="text-xs">Limite de lignes pour le test</Label>
              <Input type="number" value={limit} onChange={(e) => setLimit(Number(e.target.value))} min={1} max={1000} />
            </div>
          </div>
          <div className="space-y-1.5">
            <Label className="text-xs">SQL</Label>
            <Textarea
              value={sql}
              onChange={(e) => setSql(e.target.value)}
              rows={10}
              placeholder="-- SELECT * FROM bronze.clients LIMIT 10;"
              className="sql-block"
            />
          </div>
          <div className="flex gap-2">
            <Button onClick={() => validateMut.mutate()} disabled={validateMut.isPending || !sql}>
              {validateMut.isPending ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : <ShieldCheck className="mr-1.5 h-3.5 w-3.5" />}
              Valider (sûreté)
            </Button>
            <Button onClick={() => executeMut.mutate()} disabled={executeMut.isPending || !sql || !connectionId} variant="outline">
              {executeMut.isPending ? <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" /> : <Play className="mr-1.5 h-3.5 w-3.5" />}
              Exécuter (test, limit={limit})
            </Button>
          </div>
        </CardContent>
      </Card>

      <Tabs defaultValue="validate">
        <TabsList>
          <TabsTrigger value="validate">Résultat validation</TabsTrigger>
          <TabsTrigger value="execute">Résultat exécution</TabsTrigger>
        </TabsList>

        <TabsContent value="validate">
          {!validateResult ? (
            <Card><CardContent className="p-8 text-center text-sm text-muted-foreground">Lancez une validation pour voir le résultat.</CardContent></Card>
          ) : (
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="flex items-center gap-2 text-sm">
                  {validateResult.valid
                    ? <><CheckCircle2 className="h-4 w-4 text-success" /> SQL valide</>
                    : <><AlertCircle className="h-4 w-4 text-destructive" /> SQL invalide</>
                  }
                  {validateResult.riskLevel && (
                    <Badge variant="outline" className="ml-auto text-[10px]">Risque: {validateResult.riskLevel}</Badge>
                  )}
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {validateResult.message && (
                  <p className={validateResult.valid ? 'text-xs text-success' : 'text-xs text-destructive'}>
                    {validateResult.message}
                  </p>
                )}
                {validateResult.errors && validateResult.errors.length > 0 && (
                  <div>
                    <p className="mb-1 text-xs font-medium text-destructive">Erreurs</p>
                    <ul className="list-disc pl-5 text-xs text-destructive">{validateResult.errors.map((e, i) => <li key={i}>{e}</li>)}</ul>
                  </div>
                )}
                {validateResult.warnings && validateResult.warnings.length > 0 && (
                  <div>
                    <p className="mb-1 text-xs font-medium text-warning">Avertissements</p>
                    <ul className="list-disc pl-5 text-xs text-warning">{validateResult.warnings.map((e, i) => <li key={i}>{e}</li>)}</ul>
                  </div>
                )}
                {validateResult.operations && validateResult.operations.length > 0 && (
                  <div>
                    <p className="mb-1 text-xs font-medium">Opérations détectées</p>
                    <div className="flex flex-wrap gap-1.5">
                      {validateResult.operations.map((op, i) => <Badge key={i} variant="secondary" className="text-[10px]">{op}</Badge>)}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>

        <TabsContent value="execute">
          {!executeResult ? (
            <Card><CardContent className="p-8 text-center text-sm text-muted-foreground">Lancez une exécution pour voir le résultat.</CardContent></Card>
          ) : (
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="flex items-center gap-2 text-sm">
                  <Database className="h-4 w-4" />
                  {executeResult.success ? `Exécution réussie — ${executeResult.rowCount} ligne(s)` : 'Échec exécution'}
                  {executeResult.executionTimeMs && <span className="ml-auto text-xs font-normal text-muted-foreground">{executeResult.executionTimeMs}ms</span>}
                </CardTitle>
              </CardHeader>
              <CardContent>
                {executeResult.error ? (
                  <pre className="sql-block rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">{executeResult.error}</pre>
                ) : executeResult.columns && executeResult.columns.length > 0 ? (
                  <div className="overflow-auto">
                    <table className="w-full text-xs">
                      <thead className="bg-muted/40">
                        <tr className="text-left text-[10px] uppercase tracking-wide text-muted-foreground">
                          {executeResult.columns.map((c, i) => <th key={i} className="px-2 py-1.5">{c}</th>)}
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border">
                        {(executeResult.rows || []).map((row, i) => (
                          <tr key={i}>
                            {executeResult.columns!.map((column) => (
                              <td key={column} className="px-2 py-1.5 font-mono">{String(row[column] ?? 'NULL')}</td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">Aucune ligne retournée.</p>
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
