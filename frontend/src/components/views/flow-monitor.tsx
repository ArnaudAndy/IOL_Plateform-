import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import ReactFlow, {
  BaseEdge,
  Background,
  BackgroundVariant,
  Controls,
  getSmoothStepPath,
  Handle,
  MarkerType,
  Position,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  AlertCircle,
  CheckCircle2,
  Database,
  Layers,
  Radio,
  Send,
  Zap,
} from 'lucide-react'
import { logsService } from '@/lib/api/services'
import { describeError } from '@/lib/api/client'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ErrorState, LoadingState, EmptyState } from '@/components/common/states'
import { ExecutionStatusBadge } from '@/components/common/badges'
import { useIsMobile } from '@/hooks/use-mobile'
import { formatDateTime, formatRelative } from '@/lib/format'
import { TECH_LABELS, layerStatusLabel } from '@/lib/i18n'
import type { ExecutionLogDto } from '@/lib/api/types'

type LayerId = 'sources' | 'bronze' | 'silver' | 'gold' | 'destination'
type LayerStatus = 'idle' | 'running' | 'success' | 'failed' | 'stalled' | 'skipped'

interface LayerState {
  id: LayerId
  label: string
  subtitle: string
  status: LayerStatus
  icon: ReactNode
  accent: string
  vertical?: boolean
}

const LAYER_ORDER: LayerId[] = ['sources', 'bronze', 'silver', 'gold', 'destination']

function stageLayer(stage?: string): LayerId | undefined {
  const value = String(stage || '').toUpperCase()
  if (['QUEUED', 'SUBMISSION', 'PREPARATION', 'EXTRACTION'].includes(value)) return 'sources'
  if (value === 'HOP' || value.startsWith('BRONZE')) return 'bronze'
  if (value.startsWith('SILVER')) return 'silver'
  if (value.startsWith('GOLD')) return 'gold'
  if (value === 'DESTINATION' || value === 'INTEROPERABILITY') return 'destination'
  return undefined
}

function statusesForLayer(execution: ExecutionLogDto, layer: LayerId): string[] {
  const stages = execution.stageStatuses || {}
  return Object.entries(stages)
    .filter(([stage]) => stageLayer(stage) === layer)
    .map(([, status]) => String(status).toUpperCase())
}

function layerStatus(execution: ExecutionLogDto, layer: LayerId): LayerStatus {
  const persisted = statusesForLayer(execution, layer)
  if (persisted.includes('FAILED')) return 'failed'
  if (persisted.length > 0 && persisted.every((status) => status === 'SKIPPED')) return 'skipped'

  const failedLayer = stageLayer(execution.failedStage)
  if (execution.status === 'FAILED' && failedLayer === layer) return 'failed'

  const activeLayer = stageLayer(execution.currentStage)
  if (execution.status === 'RUNNING' && activeLayer === layer) {
    const heartbeat = execution.lastHeartbeatAt ? new Date(execution.lastHeartbeatAt).getTime() : 0
    return heartbeat > 0 && Date.now() - heartbeat > 20_000 ? 'stalled' : 'running'
  }
  if (persisted.includes('RUNNING')) return 'running'

  if (persisted.includes('SUCCESS')) return 'success'
  if (execution.status === 'SUCCESS' || execution.status === 'DELIVERED') return 'success'

  const referenceLayer = execution.status === 'FAILED' ? failedLayer : activeLayer
  if (referenceLayer && LAYER_ORDER.indexOf(layer) < LAYER_ORDER.indexOf(referenceLayer)) return 'success'
  return 'idle'
}

function FlowNode({ data }: NodeProps<LayerState>) {
  const blocked = data.status === 'failed' || data.status === 'stalled'
  const running = data.status === 'running'
  const border = blocked
    ? 'border-destructive ring-2 ring-destructive/50'
    : running
      ? 'border-info ring-1 ring-info/40'
      : data.status === 'success'
        ? 'border-success/50'
        : 'border-border'

  return (
    <div className={`relative w-[180px] overflow-hidden rounded-md border-2 bg-card px-3 py-3 shadow-lg transition-colors ${running ? 'flow-node-running' : ''} ${border}`}>
      <span className="absolute inset-x-0 top-0 h-1" style={{ backgroundColor: data.accent }} />
      <Handle type="target" position={data.vertical ? Position.Top : Position.Left} className="!opacity-0" />
      <Handle type="source" position={data.vertical ? Position.Bottom : Position.Right} className="!opacity-0" />
      <div className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md" style={{ backgroundColor: `${data.accent}22`, color: data.accent }}>
          {data.icon}
        </div>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{data.label}</p>
          <p className="truncate text-[10px] text-muted-foreground">{data.subtitle}</p>
        </div>
      </div>
      <div className="mt-2 flex items-center gap-1.5">
        <span className={`h-1.5 w-1.5 rounded-full ${
          blocked ? 'bg-destructive' : running ? 'animate-pulse bg-info' :
          data.status === 'success' ? 'bg-success' : 'bg-muted-foreground'
        }`} />
        <span className="text-[10px] uppercase text-muted-foreground">{layerStatusLabel(data.status)}</span>
      </div>
    </div>
  )
}

const nodeTypes = { flow: FlowNode }

function DataFlowEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps<{ moving?: boolean; color?: string }>) {
  const [path] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 12,
  })
  const color = data?.color || '#38bdf8'

  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} style={style} />
      {data?.moving && (
        <>
          <circle r="4" fill={color} className="flow-packet">
            <animateMotion dur="1.1s" repeatCount="indefinite" path={path} />
          </circle>
          <circle r="2.5" fill={color} className="flow-packet">
            <animateMotion begin="0.55s" dur="1.1s" repeatCount="indefinite" path={path} />
          </circle>
        </>
      )}
    </>
  )
}

const edgeTypes = { dataFlow: DataFlowEdge }

export function FlowMonitorView() {
  const isMobile = useIsMobile()
  const [live, setLive] = useState(true)
  const [selectedId, setSelectedId] = useState<string>()

  const logsQ = useQuery({
    queryKey: ['logs', 'all'],
    queryFn: logsService.all,
    refetchInterval: 3_000,
    refetchIntervalInBackground: true,
  })

  const executions = useMemo(() => [...(logsQ.data || [])]
    .sort((a, b) => new Date(b.startTime).getTime() - new Date(a.startTime).getTime()), [logsQ.data])
  const running = executions.find((execution) => execution.status === 'RUNNING')

  useEffect(() => {
    if (live && running && selectedId !== running.id) setSelectedId(running.id)
    if (!selectedId && executions[0]) setSelectedId(executions[0].id)
  }, [executions, live, running, selectedId])

  const execution = executions.find((item) => item.id === selectedId) || running || executions[0]

  const layers = useMemo<LayerState[]>(() => {
    if (!execution) return []
    return [
      { id: 'sources', label: 'Sources', subtitle: 'Lecture et préparation', status: layerStatus(execution, 'sources'), icon: <Database className="h-4 w-4" />, accent: '#3b82f6' },
      { id: 'bronze', label: TECH_LABELS.bronze, subtitle: 'Chargement brut', status: layerStatus(execution, 'bronze'), icon: <Layers className="h-4 w-4" />, accent: '#f59e0b' },
      { id: 'silver', label: TECH_LABELS.silver, subtitle: 'Nettoyage', status: layerStatus(execution, 'silver'), icon: <Zap className="h-4 w-4" />, accent: '#14b8a6' },
      { id: 'gold', label: TECH_LABELS.gold, subtitle: 'Résultat final', status: layerStatus(execution, 'gold'), icon: <CheckCircle2 className="h-4 w-4" />, accent: '#10b981' },
      { id: 'destination', label: 'Destination', subtitle: 'Publication', status: layerStatus(execution, 'destination'), icon: <Send className="h-4 w-4" />, accent: '#0ea5e9' },
    ]
  }, [execution])

  const nodes = useMemo<Node[]>(() => layers.map((layer, index) => ({
    id: layer.id,
    type: 'flow',
    data: { ...layer, vertical: isMobile },
    position: isMobile ? { x: 20, y: index * 125 } : { x: index * 240, y: 90 },
  })), [isMobile, layers])

  const activeLayerIndex = layers.findIndex((layer) => layer.status === 'running')
  const flowIsMoving = execution?.status === 'RUNNING' && activeLayerIndex >= 0

  const edges = useMemo<Edge[]>(() => LAYER_ORDER.slice(0, -1).map((source, index) => {
    const moving = Boolean(flowIsMoving && index <= activeLayerIndex)
    const completed = layers[index]?.status === 'success'
    const color = moving ? (layers[Math.min(index + 1, activeLayerIndex)]?.accent || '#38bdf8')
      : completed ? '#22c55e' : '#64748b'
    return {
      id: `${source}-${LAYER_ORDER[index + 1]}`,
      source,
      target: LAYER_ORDER[index + 1],
      type: 'dataFlow',
      data: { moving, color },
      animated: moving,
      className: moving ? 'flow-edge-moving' : completed ? 'flow-edge-completed' : undefined,
      markerEnd: { type: MarkerType.ArrowClosed, color },
      style: { stroke: color, strokeWidth: moving ? 3 : 2 },
    }
  }), [activeLayerIndex, flowIsMoving, layers])

  const blocker = layers.find((layer) => layer.status === 'failed' || layer.status === 'stalled')

  return (
    <div className="mx-auto min-w-0 max-w-[1400px]">
      <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div className="min-w-0">
          <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold">
            Monitoring du flux
            <span className="rounded-md border px-2 py-0.5 text-[10px] font-normal uppercase text-muted-foreground">3 s</span>
          </h1>
          <p className="mt-1 text-xs text-muted-foreground">Une exécution à la fois. Seule l'étape réellement active ou bloquante est mise en évidence.</p>
        </div>
        <div className="flex w-full flex-col gap-2 sm:flex-row lg:w-auto">
          <Select value={execution?.id || ''} onValueChange={(value) => { setSelectedId(value); setLive(false) }}>
            <SelectTrigger className="w-full sm:w-[300px]"><SelectValue placeholder="Choisir une exécution" /></SelectTrigger>
            <SelectContent>
              {executions.slice(0, 40).map((item) => (
                <SelectItem key={item.id} value={item.id}>{item.workflowName || item.workflowId || item.id} · {item.status}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button size="sm" variant={live ? 'default' : 'outline'} onClick={() => setLive((value) => !value)}>
            <Radio className={`mr-1.5 h-3.5 w-3.5 ${live ? 'animate-pulse' : ''}`} />{live ? 'Suivi automatique' : 'Suivre le dernier'}
          </Button>
        </div>
      </div>

      {logsQ.isLoading ? <LoadingState label="Connexion au flux..." />
        : logsQ.isError ? <ErrorState message={describeError(logsQ.error)} onRetry={() => logsQ.refetch()} />
          : !execution ? <EmptyState title="Aucune exécution" description="Lancez un traitement pour suivre sa progression." />
            : (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-2 text-xs">
                  <ExecutionStatusBadge status={execution.status} />
                  <span className="font-medium">{execution.workflowName || execution.workflowId}</span>
                  <span className="text-muted-foreground">{formatRelative(execution.startTime)}</span>
                  {execution.currentStage && <span className="rounded-md border px-2 py-0.5">Étape : {execution.currentStage}</span>}
                  {execution.lastHeartbeatAt && <span className="text-muted-foreground">Signal : {formatRelative(execution.lastHeartbeatAt)}</span>}
                  {flowIsMoving && (
                    <span className="flex items-center gap-1.5 font-medium text-info">
                      <Activity className="h-3.5 w-3.5 animate-pulse" />Flux en mouvement
                    </span>
                  )}
                </div>

                {blocker && (
                  <div className="mb-3 flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                    <div className="min-w-0">
                      <p className="font-semibold">Blocage localisé : {blocker.label}</p>
                      <p className="mt-1 break-words text-xs">{execution.errorMessage || 'Le worker ne publie plus de progression pour cette étape.'}</p>
                    </div>
                  </div>
                )}

                <Card className="overflow-hidden border-border/80 shadow-lg">
                  <CardContent className="p-0">
                    <div className="flow-monitor-canvas h-[430px] bg-[oklch(0.16_0.005_240)] sm:h-[500px]">
                      <ReactFlow
                        nodes={nodes}
                        edges={edges}
                        nodeTypes={nodeTypes}
                        edgeTypes={edgeTypes}
                        fitView
                        fitViewOptions={{ padding: isMobile ? 0.2 : 0.1 }}
                        proOptions={{ hideAttribution: true }}
                        nodesDraggable={false}
                        nodesConnectable={false}
                        minZoom={0.35}
                        maxZoom={1.5}
                      >
                        <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#334155" />
                        <Controls className="!border-border !bg-card !shadow-md" showInteractive={false} />
                      </ReactFlow>
                    </div>
                  </CardContent>
                </Card>

                <ExecutionConsole execution={execution} />
              </>
            )}
    </div>
  )
}

function consoleLines(execution: ExecutionLogDto): { level: string; message: string }[] {
  const raw = execution.detailedLogs || execution.logOutput || ''
  const lines = raw.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).slice(-250)
  const result = lines.map((message) => ({
    level: /error|exception|failed|echec|erreur/i.test(message) ? 'ERREUR' : 'INFO',
    message,
  }))
  if (execution.errorMessage && !lines.some((line) => line.includes(execution.errorMessage!))) {
    result.push({ level: 'ERREUR', message: `${execution.failedStage || execution.currentStage || 'ETAPE'} : ${execution.errorMessage}` })
  }
  return result
}

function ExecutionConsole({ execution }: { execution: ExecutionLogDto }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const lines = useMemo(() => consoleLines(execution), [execution])

  useEffect(() => {
    if (containerRef.current) containerRef.current.scrollTop = containerRef.current.scrollHeight
  }, [lines])

  return (
    <Card className="mt-4 min-w-0">
      <CardHeader className="pb-2">
        <CardTitle className="flex flex-wrap items-center justify-between gap-2 text-sm">
          <span className="flex items-center gap-2"><Activity className="h-4 w-4" />Historique du flux ({lines.length})</span>
          <span className="text-[10px] font-normal text-muted-foreground">Mis à jour {formatDateTime(execution.lastHeartbeatAt || execution.endTime || execution.startTime)}</span>
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div ref={containerRef} className="sql-block max-h-72 min-h-[140px] overflow-auto rounded-md border bg-[oklch(0.13_0.005_240)] p-3 text-xs">
          {lines.length === 0 ? <p className="text-muted-foreground">En attente du premier message du worker...</p> : lines.map((line, index) => (
            <div key={`${index}-${line.message.slice(0, 20)}`} className="flex min-w-0 gap-2 py-0.5">
              <span className={line.level === 'ERREUR' ? 'shrink-0 font-semibold text-destructive' : 'shrink-0 text-info'}>[{line.level}]</span>
              <span className="min-w-0 whitespace-pre-wrap break-words text-foreground">{line.message}</span>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}
