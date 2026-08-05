import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  AlertTriangle,
  CheckCircle2,
  Clipboard,
  Code2,
  Database,
  Loader2,
  Wand2,
} from 'lucide-react'
import { aiService, connectionService, workflowService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { PageHeader, LoadingState, ErrorState, EmptyState } from '@/components/common/states'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useToast } from '@/hooks/use-toast'
import type { SchemaOnlySqlRequest } from '@/lib/api/types'

type GenerationType = NonNullable<SchemaOnlySqlRequest['generationType']>

export function AiAssistantView() {
  const { toast } = useToast()
  const [workflowId, setWorkflowId] = useState('')
  const [sourceIndex, setSourceIndex] = useState('ALL')
  const [generationType, setGenerationType] = useState<GenerationType>('CUSTOM')
  const [instruction, setInstruction] = useState('')
  const [columns, setColumns] = useState('')
  const [targetTable, setTargetTable] = useState('')
  const [sql, setSql] = useState('')

  const statusQ = useQuery({
    queryKey: ['ai', 'status'],
    queryFn: aiService.status,
    staleTime: 30_000,
  })
  const workflowsQ = useQuery({ queryKey: ['workflows'], queryFn: workflowService.list })
  const connectionsQ = useQuery({ queryKey: ['connections'], queryFn: connectionService.list })

  const workflow = useMemo(
    () => (workflowsQ.data ?? []).find((item) => item.id === workflowId),
    [workflowId, workflowsQ.data],
  )
  const selectedSources = useMemo(() => {
    if (!workflow) return []
    if (sourceIndex === 'ALL') return workflow.sources ?? []
    const source = workflow.sources?.[Number(sourceIndex)]
    return source ? [source] : []
  }, [sourceIndex, workflow])
  const destination = useMemo(
    () => (connectionsQ.data ?? []).find((item) => item.id === workflow?.destinationConnectionId),
    [connectionsQ.data, workflow?.destinationConnectionId],
  )

  useEffect(() => {
    if (!workflowId && workflowsQ.data?.[0]?.id) {
      setWorkflowId(workflowsQ.data[0].id)
    }
  }, [workflowId, workflowsQ.data])

  useEffect(() => {
    if (!workflow) return
    const sourceSet = sourceIndex === 'ALL'
      ? workflow.sources ?? []
      : [workflow.sources?.[Number(sourceIndex)]].filter(Boolean)
    const names = sourceSet.flatMap((source) =>
      (source?.fields ?? []).filter((field) => field.selected !== false).map((field) => field.name),
    )
    setColumns([...new Set(names)].join(', '))
    if (generationType === 'AGGREGATION') {
      setTargetTable(workflow.goldConfigGlobal?.target_table_gold || '')
    } else if (sourceSet[0]) {
      setTargetTable(sourceSet[0].silver_config?.target_table_silver || '')
    }
  }, [generationType, sourceIndex, workflow])

  const generateMut = useMutation({
    mutationFn: () => aiService.generateSchemaSql({
      instruction: instruction.trim(),
      columns: columns.split(',').map((column) => column.trim()).filter(Boolean),
      sourceTable: selectedSources[0]?.target_table,
      sourceTables: selectedSources.map((source) =>
        generationType === 'AGGREGATION'
          ? source.silver_config?.target_table_silver || source.target_table || 'source_table'
          : source.target_table || 'source_table'),
      targetTable,
      workflowId,
      destinationConnectionId: workflow?.destinationConnectionId,
      databaseType: destination?.dbType,
      generationType,
    }),
    onSuccess: (result) => {
      setSql(result.sql)
      toast({ title: 'SQL généré', description: `Dialecte ${destination?.dbType || 'de la destination'}` })
    },
    onError: (error) => {
      toast({ title: 'Génération impossible', description: describeError(error), variant: 'destructive' })
    },
  })

  const copySql = async () => {
    await navigator.clipboard.writeText(sql)
    toast({ title: 'SQL copié' })
  }

  if (workflowsQ.isLoading || connectionsQ.isLoading) return <LoadingState />
  if (workflowsQ.isError) {
    return <ErrorState message={describeError(workflowsQ.error)} onRetry={() => workflowsQ.refetch()} />
  }
  if (connectionsQ.isError) {
    return <ErrorState message={describeError(connectionsQ.error)} onRetry={() => connectionsQ.refetch()} />
  }
  if ((workflowsQ.data ?? []).length === 0) {
    return <EmptyState title="Aucun traitement disponible" description="Créez un traitement avant de générer son SQL." />
  }

  return (
    <div className="mx-auto w-full max-w-6xl">
      <PageHeader
        title="Assistant SQL"
        description="Génération limitée au schéma du traitement et au dialecte de sa destination."
      />

      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted/20 p-3 text-xs">
        {statusQ.data?.configured
          ? <CheckCircle2 className="h-4 w-4 shrink-0 text-success" />
          : <AlertTriangle className="h-4 w-4 shrink-0 text-warning" />}
        <span className="font-medium">
          {statusQ.data?.configured ? 'Service disponible' : 'Service non configuré'}
        </span>
        <span className="ml-auto text-muted-foreground">Schéma uniquement</span>
      </div>

      <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <Card className="min-w-0">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <Database className="h-4 w-4" /> Contexte SQL
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label className="text-xs">Traitement</Label>
                <Select value={workflowId} onValueChange={(value) => {
                  setWorkflowId(value)
                  setSourceIndex('ALL')
                }}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {(workflowsQ.data ?? []).map((item) => (
                      <SelectItem key={item.id} value={item.id!}>{item.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs">Dialecte de destination</Label>
                <Input value={destination ? `${destination.dbType} · ${destination.name}` : 'Destination non définie'} readOnly />
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label className="text-xs">Portée</Label>
                <Select value={sourceIndex} onValueChange={setSourceIndex}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">Toutes les sources</SelectItem>
                    {(workflow?.sources ?? []).map((source, index) => (
                      <SelectItem key={`${source.source_name}-${index}`} value={String(index)}>
                        {source.source_name} · {source.target_table || index + 1}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs">Type de requête</Label>
                <Select value={generationType} onValueChange={(value: GenerationType) => setGenerationType(value)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="CUSTOM">Sélection personnalisée</SelectItem>
                    <SelectItem value="CLEANING">Nettoyage</SelectItem>
                    <SelectItem value="AGGREGATION">Agrégation</SelectItem>
                    <SelectItem value="MAPPING">Mapping</SelectItem>
                    <SelectItem value="SELECT">Lecture</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs">Colonnes autorisées</Label>
              <Textarea
                value={columns}
                onChange={(event) => setColumns(event.target.value)}
                rows={4}
                className="font-mono text-xs"
              />
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs">Table logique de sortie</Label>
              <Input value={targetTable} onChange={(event) => setTargetTable(event.target.value)} />
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs">Transformation demandée</Label>
              <Textarea
                value={instruction}
                onChange={(event) => setInstruction(event.target.value)}
                rows={4}
                placeholder="Exemple : regrouper par client et calculer le montant total"
              />
            </div>

            <Button
              onClick={() => generateMut.mutate()}
              disabled={generateMut.isPending || !statusQ.data?.configured || !workflowId
                || !instruction.trim() || !columns.trim() || !destination}
            >
              {generateMut.isPending
                ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
                : <Wand2 className="mr-1.5 h-4 w-4" />}
              Générer le SQL
            </Button>
          </CardContent>
        </Card>

        <Card className="min-w-0">
          <CardHeader>
            <div className="flex items-center justify-between gap-2">
              <CardTitle className="flex items-center gap-2 text-sm">
                <Code2 className="h-4 w-4" /> Requête générée
              </CardTitle>
              <Button
                type="button"
                size="icon"
                variant="ghost"
                title="Copier le SQL"
                aria-label="Copier le SQL"
                disabled={!sql}
                onClick={copySql}
              >
                <Clipboard className="h-4 w-4" />
              </Button>
            </div>
          </CardHeader>
          <CardContent>
            {sql ? (
              <pre className="sql-block min-h-80 max-h-[65vh] max-w-full overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/20 p-4">
                {sql}
              </pre>
            ) : (
              <div className="flex min-h-80 items-center justify-center rounded-md border border-dashed border-border text-sm text-muted-foreground">
                Aucune requête générée
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
