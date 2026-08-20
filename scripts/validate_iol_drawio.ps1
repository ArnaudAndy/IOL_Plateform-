param(
    [string]$Path = "IOL_Diagrammes_v2.drawio"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Path)) {
    throw "Diagramme introuvable: $Path"
}

[xml]$document = Get-Content -LiteralPath $Path -Raw
$diagrams = @($document.mxfile.diagram)
$expectedPages = @(
    "Architecture",
    "Deploiement_Production",
    "Kafka",
    "Spark_Distribue",
    "CasUtilisation",
    "UC_Acces",
    "UC_Configuration",
    "UC_Execution",
    "UC_Interop",
    "Classes_v2",
    "CD_Securite",
    "CD_Configuration",
    "CD_Interoperabilite",
    "CD_Execution",
    "CD_IA",
    "CD_Transport",
    "Seq_Execution",
    "Seq_INBOUND",
    "Seq_OUTBOUND"
)

$errors = [System.Collections.Generic.List[string]]::new()

$actualPages = @($diagrams | ForEach-Object { [string]$_.name })
foreach ($page in $expectedPages) {
    if ($page -notin $actualPages) {
        $errors.Add("Page obligatoire absente: $page")
    }
}
if ($actualPages.Count -ne $expectedPages.Count) {
    $errors.Add("Nombre de pages inattendu: $($actualPages.Count), attendu: $($expectedPages.Count)")
}

$totalCells = 0
$totalEdges = 0
foreach ($diagram in $diagrams) {
    $cells = @($diagram.mxGraphModel.root.mxCell)
    $totalCells += $cells.Count
    $ids = @{}

    foreach ($cell in $cells) {
        $id = [string]$cell.id
        if ($ids.ContainsKey($id)) {
            $errors.Add("[$($diagram.name)] identifiant dupliqué: $id")
        } else {
            $ids[$id] = $true
        }
    }

    foreach ($cell in $cells) {
        $id = [string]$cell.id
        $parent = [string]$cell.parent
        if ($parent -and -not $ids.ContainsKey($parent)) {
            $errors.Add("[$($diagram.name)] parent absent pour ${id}: $parent")
        }
        if ([string]$cell.edge -eq "1") {
            $totalEdges++
            $source = [string]$cell.source
            $target = [string]$cell.target
            if (-not $source -or -not $target) {
                $errors.Add("[$($diagram.name)] relation détachée: $id")
                continue
            }
            if (-not $ids.ContainsKey($source)) {
                $errors.Add("[$($diagram.name)] source absente pour ${id}: $source")
            }
            if (-not $ids.ContainsKey($target)) {
                $errors.Add("[$($diagram.name)] cible absente pour ${id}: $target")
            }
        }
    }

    $pageName = [string]$diagram.name
    if ($pageName -like "Seq_*") {
        $frames = @($cells | Where-Object {
            [string]$_.style -like "*shape=umlFrame*"
        })
        if ($frames.Count -eq 0) {
            $errors.Add("[$pageName] aucun fragment combiné UML (alt/opt/loop)")
        }
        foreach ($reply in @($cells | Where-Object {
            [string]$_.edge -eq "1" -and [string]$_.style -like "*dashed=1*"
        })) {
            $replyStyle = [string]$reply.style
            if ($replyStyle -notlike "*endArrow=open*" -or $replyStyle -notlike "*endFill=0*") {
                $errors.Add("[$pageName] réponse UML sans flèche ouverte: $($reply.id)")
            }
        }
    }

    if ($pageName -eq "Classes_v2" -or $pageName -like "CD_*") {
        foreach ($relation in @($cells | Where-Object { [string]$_.edge -eq "1" })) {
            if ([string]$relation.source -match "_(attrs|methods)$" -or
                [string]$relation.target -match "_(attrs|methods)$") {
                $errors.Add("[$pageName] relation ancrée à un compartiment au lieu d’un classificateur: $($relation.id)")
            }
        }
    }

    if ($pageName -eq "CasUtilisation" -or $pageName -like "UC_*") {
        foreach ($actor in @($cells | Where-Object { [string]$_.style -like "*shape=umlActor*" })) {
            $label = [string]$actor.value
            if ($label -match "(?i)Hop|Spark|planificateur|ordonnanceur|moteur") {
                $errors.Add("[$pageName] composant interne modélisé comme acteur: $label")
            }
        }
        foreach ($useCase in @($cells | Where-Object { [string]$_.style -like "*shape=ellipse*" })) {
            if ([string]$useCase.style -notlike "*fillColor=none*") {
                $errors.Add("[$pageName] cas d’utilisation non monochrome: $($useCase.id)")
            }
        }
    }
}

$raw = Get-Content -LiteralPath $Path -Raw
$forbidden = @(
    "iol.pipeline.commands.high",
    "iol.pipeline.commands.low",
    "iol.transport.dlq",
    "50 Mo",
    "50 Mio",
    "AiSqlAssistant",
    "&lt;b&gt;IolMediator&lt;/b&gt;",
    "«record»",
    "«private record»",
    "«HTTP interface»",
    "Identifier",
    "EmailAddress",
    "Instant",
    "Quantity",
    "Checksum",
    "TermValue",
    "Provenance",
    "byte[]",
    "ConcurrentHashMap"
)
foreach ($value in $forbidden) {
    if ($raw.Contains($value)) {
        $errors.Add("Libellé obsolète détecté: $value")
    }
}

$required = @(
    "iol.transport.requests",
    "iol.transport.requests.dlq",
    "iol.pipeline.high",
    "iol.pipeline.commands",
    "iol.pipeline.low",
    "iol.pipeline.status",
    "iol.pipeline.commands.dlq",
    "iol.outbound.delivery",
    "iol.outbound.status",
    "iol.outbound.delivery.dlq",
    "source-gateway",
    "PipelineExecution",
    "InteroperabilityExchange",
    "MessageAdapter",
    "FhirAdapter",
    "Iso20022Adapter",
    "EdFiAdapter",
    "SqlGenerator",
    "TransportChannel",
    ""
)
foreach ($value in $required) {
    if (-not $raw.Contains($value)) {
        $errors.Add("Élément d’architecture obligatoire absent: $value")
    }
}

# Les attributs sont volontairement limités au profil UML portable :
# visibilité, nom, deux-points, type UML primitif ou classificateur visible,
# puis éventuellement une multiplicité ou une contrainte entre accolades.
foreach ($diagram in $diagrams | Where-Object { $_.name -eq "Classes_v2" -or $_.name -like "CD_*" }) {
    $cells = @($diagram.mxGraphModel.root.mxCell)
    $classDiagramText = ($cells | ForEach-Object { [string]$_.value }) -join "\n"
    foreach ($implementationName in @("PipelineExecutionClaim", "InboundIdempotencyRecord", "OutboundDeliveryRecord", "FhirR4PayloadAdapter", "Iso20022PayloadAdapter", "EdFiPayloadAdapter", "AiService")) {
        if ($classDiagramText.Contains($implementationName)) {
            $errors.Add("[$($diagram.name)] détail d’implémentation interdit dans un diagramme de classes: $implementationName")
        }
    }
    foreach ($cell in @($cells | Where-Object { [string]$_.id -match "_attrs$" })) {
        $lines = ([string]$cell.value) -split "<br>"
        foreach ($line in $lines) {
            if (-not $line -or $line -notmatch "^[+-]") { continue }
            if ($line -notmatch "^[+-]\s+[A-Za-z][A-Za-z0-9]*\s*:\s*[A-Za-z][A-Za-z0-9]*(\s*\[[0-9*]+\.\.[0-9*]+\])?(\s*\{[^}]+\})?$") {
                $errors.Add("[$($diagram.name)] attribut non portable UML: $line")
            }
        }
    }
    foreach ($cell in @($cells | Where-Object { [string]$_.id -match "_methods$" })) {
        $lines = ([string]$cell.value) -split "<br>"
        foreach ($line in $lines) {
            if (-not $line -or $line -notmatch "^[+-]") { continue }
            if ($line -notmatch "^[+-]\s+[A-Za-z][A-Za-z0-9]*\([^)]*\)\s*:\s*[A-Za-z][A-Za-z0-9]*$") {
                $errors.Add("[$($diagram.name)] opération non portable UML: $line")
            }
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    throw "Validation du diagramme échouée avec $($errors.Count) erreur(s)."
}

Write-Host "IOL draw.io valide: $($diagrams.Count) pages, $totalCells cellules, $totalEdges relations, zéro référence orpheline."
