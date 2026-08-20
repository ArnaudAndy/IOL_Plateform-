import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const OUTPUT = new URL('../IOL_Diagrammes_v2.drawio', import.meta.url);

const C = {
  ink: '#1f2937', muted: '#5f6b7a', line: '#64748b', paper: '#ffffff',
  gray: '#f3f4f6', grayStroke: '#6b7280',
  blue: '#dbeafe', blueStroke: '#2563eb',
  green: '#dcfce7', greenStroke: '#16a34a',
  orange: '#ffedd5', orangeStroke: '#ea580c',
  purple: '#f3e8ff', purpleStroke: '#9333ea',
  red: '#fee2e2', redStroke: '#dc2626',
  yellow: '#fef3c7', yellowStroke: '#d97706',
  teal: '#ccfbf1', tealStroke: '#0f766e',
};

const pages = [];
const fmt = n => Number(n).toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
const esc = value => String(value ?? '')
  .replaceAll('&', '&amp;').replaceAll('"', '&quot;')
  .replaceAll('<', '&lt;').replaceAll('>', '&gt;');

function graph(id, name, width = 2520, height = 1400) {
  const g = { id, name, width, height, cells: [], lifelines: new Map(), counter: 1 };
  pages.push(g);
  return g;
}

function nextId(g, prefix = 'c') { return `${g.id}_${prefix}_${g.counter++}`; }

function vertex(g, id, value, style, x, y, width, height, extra = {}) {
  const cell = {
    id: id || nextId(g, 'v'), value, style, vertex: true,
    parent: extra.parent ?? '1', connectable: extra.connectable,
    geometry: { x, y, width, height, relative: extra.relative, offset: extra.offset },
  };
  g.cells.push(cell);
  return cell.id;
}

function edge(g, id, source, target, value = '', style = '', extra = {}) {
  const cell = {
    id: id || nextId(g, 'e'), value, style, edge: true,
    parent: extra.parent ?? '1', source, target,
    geometry: { relative: true, points: extra.points, sourcePoint: extra.sourcePoint, targetPoint: extra.targetPoint },
  };
  g.cells.push(cell);
  return cell.id;
}

const styles = {
  title: `text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontColor=${C.ink};fontSize=24;fontStyle=1;whiteSpace=wrap;`,
  subtitle: `text;html=1;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;fontColor=${C.muted};fontSize=12;whiteSpace=wrap;`,
  boundary: `rounded=0;whiteSpace=wrap;html=1;verticalAlign=top;align=left;spacingTop=8;spacingLeft=10;fillColor=none;strokeColor=${C.line};fontColor=${C.ink};fontStyle=1;fontSize=13;dashed=1;`,
  component: `shape=component;whiteSpace=wrap;html=1;align=center;verticalAlign=middle;spacingLeft=18;fillColor=${C.blue};strokeColor=${C.blueStroke};fontColor=${C.ink};fontStyle=1;fontSize=12;`,
  componentGreen: `shape=component;whiteSpace=wrap;html=1;align=center;verticalAlign=middle;spacingLeft=18;fillColor=${C.green};strokeColor=${C.greenStroke};fontColor=${C.ink};fontStyle=1;fontSize=12;`,
  componentPurple: `shape=component;whiteSpace=wrap;html=1;align=center;verticalAlign=middle;spacingLeft=18;fillColor=${C.purple};strokeColor=${C.purpleStroke};fontColor=${C.ink};fontStyle=1;fontSize=12;`,
  componentOrange: `shape=component;whiteSpace=wrap;html=1;align=center;verticalAlign=middle;spacingLeft=18;fillColor=${C.orange};strokeColor=${C.orangeStroke};fontColor=${C.ink};fontStyle=1;fontSize=12;`,
  external: `rounded=0;whiteSpace=wrap;html=1;fillColor=${C.gray};strokeColor=${C.grayStroke};fontColor=${C.ink};fontStyle=1;fontSize=11;`,
  datastore: `shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;backgroundOutline=1;size=15;fillColor=${C.orange};strokeColor=${C.orangeStroke};fontColor=${C.ink};fontStyle=1;fontSize=11;`,
  topic: `shape=note;whiteSpace=wrap;html=1;size=14;fillColor=${C.blue};strokeColor=${C.blueStroke};fontColor=${C.ink};fontStyle=1;fontSize=10;`,
  note: `shape=note;whiteSpace=wrap;html=1;size=14;fillColor=${C.yellow};strokeColor=${C.yellowStroke};fontColor=${C.ink};fontSize=10;align=left;verticalAlign=top;spacing=7;`,
  node: `shape=cube;size=14;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacingTop=12;spacingLeft=12;fillColor=${C.gray};strokeColor=${C.grayStroke};fontColor=${C.ink};fontStyle=1;fontSize=12;`,
  action: `rounded=1;arcSize=12;whiteSpace=wrap;html=1;fillColor=${C.blue};strokeColor=${C.blueStroke};fontColor=${C.ink};fontSize=11;`,
  actionGreen: `rounded=1;arcSize=12;whiteSpace=wrap;html=1;fillColor=${C.green};strokeColor=${C.greenStroke};fontColor=${C.ink};fontSize=11;`,
  decision: `rhombus;whiteSpace=wrap;html=1;fillColor=${C.yellow};strokeColor=${C.yellowStroke};fontColor=${C.ink};fontSize=10;`,
  initial: `ellipse;aspect=fixed;html=1;fillColor=${C.ink};strokeColor=${C.ink};`,
  finalOuter: `ellipse;aspect=fixed;html=1;fillColor=${C.paper};strokeColor=${C.ink};strokeWidth=2;`,
  finalInner: `ellipse;aspect=fixed;html=1;fillColor=${C.ink};strokeColor=${C.ink};`,
  actor: `shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;fillColor=${C.paper};strokeColor=${C.ink};fontColor=${C.ink};fontSize=11;`,
  usecase: `ellipse;whiteSpace=wrap;html=1;fillColor=none;strokeColor=${C.ink};fontColor=${C.ink};fontSize=11;`,
  usecaseAdmin: `ellipse;whiteSpace=wrap;html=1;fillColor=none;strokeColor=${C.ink};fontColor=${C.ink};fontSize=11;`,
  usecaseInterop: `ellipse;whiteSpace=wrap;html=1;fillColor=none;strokeColor=${C.ink};fontColor=${C.ink};fontSize=11;`,
  usecaseNote: `shape=note;whiteSpace=wrap;html=1;size=14;fillColor=none;strokeColor=${C.ink};fontColor=${C.ink};fontSize=10;align=left;verticalAlign=top;spacing=7;`,
  lifeline: `shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;recursiveResize=0;collapsible=0;outlineConnect=0;fillColor=${C.paper};strokeColor=${C.line};fontColor=${C.ink};fontStyle=1;fontSize=10;`,
  frame: `shape=umlFrame;whiteSpace=wrap;html=1;pointerEvents=0;fillColor=none;strokeColor=${C.line};fontColor=${C.ink};fontStyle=1;fontSize=11;align=left;verticalAlign=top;spacingTop=5;spacingLeft=8;`,
};

const FLOW = `edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=open;endFill=0;strokeColor=${C.line};fontColor=${C.ink};fontSize=10;`;
const DEP = `${FLOW}dashed=1;`;
const ASSOC = `edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=none;startArrow=none;strokeColor=${C.line};fontColor=${C.ink};fontSize=10;`;
const INCLUDE = `${DEP}endArrow=open;endFill=0;`;
const GENERALIZE = `${FLOW}endArrow=block;endFill=0;`;

const UML_FR = new Map([
  ['User', 'Utilisateur'], ['Workflow', 'FluxDeTravail'], ['Standard', 'Norme'], ['StandardTerm', 'TermeNorme'],
  ['Execution', 'Execution'], ['StageResult', 'ResultatEtape'], ['DataBatch', 'LotDonnees'], ['ArtifactReference', 'ReferenceArtefact'],
  ['Delivery', 'Livraison'], ['AuditEntry', 'EntreeAudit'], ['WorkflowDirection', 'DirectionFlux'], ['ExecutionStatus', 'StatutExecution'],
  ['SourceType', 'TypeSource'], ['DatabaseDialect', 'DialecteSGBD'], ['DeliveryStatus', 'StatutLivraison'],
  ['Account', 'Compte'], ['Role', 'Role'], ['AuthenticationSession', 'SessionAuthentification'], ['PasswordResetRequest', 'DemandeReinitialisationMotDePasse'],
  ['SecurityAuditEntry', 'EntreeAuditSecurite'], ['AccountState', 'EtatCompte'], ['', 'FournisseurIdentite'],
  ['Local', 'FournisseurIdentiteLocal'], ['ExternalIdentityProvider', 'FournisseurIdentiteExterne'],
  ['WorkflowDefinition', 'DefinitionFlux'], ['SourceBinding', 'LiaisonSource'], ['TargetBinding', 'LiaisonDestination'],
  ['SchedulePolicy', 'PolitiquePlanification'], ['TransformationPolicy', 'PolitiqueTransformation'], ['FieldMapping', 'CorrespondanceChamp'],
  ['DataContract', 'ContratDonnees'], ['StandardReference', 'ReferenceNorme'],
  ['InteroperabilityExchange', 'EchangeInteroperabilite'], ['ExternalMessage', 'MessageExterne'],
  ['InteroperabilityContract', 'ContratInteroperabilite'], ['CanonicalRecord', 'EnregistrementPivot'],
  ['DeliveryRequest', 'DemandeLivraison'], ['PartnerEndpoint', 'PointAccesPartenaire'], ['MessageAdapter', 'AdaptateurMessage'],
  ['FhirAdapter', 'AdaptateurFHIR'], ['Iso20022Adapter', 'AdaptateurISO20022'], ['EdFiAdapter', 'AdaptateurEdFi'], ['ExchangeDirection', 'DirectionEchange'],
  ['PipelineExecution', 'ExecutionFlux'], ['ExecutionPolicy', 'PolitiqueExecution'], ['ProcessingStage', 'EtapeTraitement'],
  ['ExecutionLease', 'BailExecution'], ['TemporaryArtifact', 'ArtefactTemporaire'], ['ExecutionEngine', 'MoteurExecution'],
  ['LocalExecutionEngine', 'MoteurExecutionLocal'], ['DistributedExecutionEngine', 'MoteurExecutionDistribue'], ['ExecutionMode', 'ModeExecution'],
  ['SqlGenerationRequest', 'DemandeGenerationSQL'], ['SchemaContext', 'ContexteSchema'], ['TableDescriptor', 'DescriptionTable'],
  ['ColumnDescriptor', 'DescriptionColonne'], ['GenerationPolicy', 'PolitiqueGeneration'], ['SqlGenerator', 'GenerateurSQL'],
  ['SqlQuery', 'RequeteSQL'], ['QueryHistoryEntry', 'EntreeHistoriqueRequete'], ['LanguageModelProvider', 'FournisseurModeleLangage'],
  ['TransportRequest', 'DemandeTransport'], ['SourceEndpoint', 'PointAccesSource'], ['TransportSession', 'SessionTransport'],
  ['TransportClaim', 'ReservationTransport'], ['ObjectManifest', 'ManifesteObjet'], ['TransportChannel', 'CanalTransport'],
  ['MessageBrokerChannel', 'CanalCourtierMessages'], ['ObjectStorageChannel', 'CanalStockageObjets'], ['TransportCommand', 'CommandeTransport'], ['SessionState', 'EtatSession'],
  ['id', 'identifiant'], ['name', 'nom'], ['email', 'courriel'], ['secret', 'secret'], ['state', 'etat'], ['status', 'statut'],
  ['direction', 'direction'], ['version', 'version'], ['domain', 'domaine'], ['active', 'actif'], ['type', 'type'],
  ['connectionRef', 'referenceConnexion'], ['databaseDialect', 'dialecteSGBD'], ['credentialRef', 'referenceSecret'],
  ['startedAt', 'demarreLe'], ['occurredAt', 'survientLe'], ['rowsIn', 'lignesEntree'], ['rowsOut', 'lignesSortie'],
  ['rowCount', 'nombreLignes'], ['byteSize', 'tailleOctets'], ['sizeBytes', 'tailleOctets'], ['mediaType', 'typeMedia'],
  ['expiresAt', 'expireLe'], ['attemptCount', 'nombreTentatives'], ['deliveredAt', 'livreLe'], ['actorId', 'identifiantActeur'],
  ['validate', 'valider'], ['activate', 'activer'], ['deactivate', 'desactiver'], ['start', 'demarrer'], ['complete', 'terminer'],
  ['fail', 'echouer'], ['checksum', 'empreinte'], ['isExpired', 'estExpire'], ['recordAttempt', 'enregistrerTentative'],
  ['authenticate', 'authentifier'], ['revoke', 'revoquer'], ['consume', 'consommer'], ['renew', 'renouveler'], ['release', 'liberer'],
  ['open', 'ouvrir'], ['close', 'fermer'], ['chooseMode', 'choisirMode'], ['execute', 'executer'], ['purge', 'purger'],
  ['supports', 'prendEnCharge'], ['toCanonical', 'versPivot'], ['fromCanonical', 'depuisPivot'], ['markDelivered', 'marquerLivree'],
  ['isAllowed', 'estAutorise'], ['isSchemaOnly', 'utiliseUniquementSchema'], ['accepts', 'accepte'], ['generate', 'generer'],
  ['publishBatch', 'publierLot'], ['publishCommand', 'publierCommande'],
  ['owns', 'possede'], ['reads from', 'lit depuis'], ['writes to', 'ecrit vers'], ['conforms to', 'est conforme a'],
  ['defines', 'definit'], ['is executed as', 'est execute comme'], ['reports', 'rapporte'], ['produces', 'produit'],
  ['may be represented by', 'peut etre represente par'], ['may result in', 'peut produire'], ['is traced by', 'est trace par'],
  ['is assigned', 'est attribue a'], ['opens', 'ouvre'], ['requests', 'demande'], ['is recorded in', 'est consigne dans'],
  ['realizes', 'realise'], ['authenticates', 'authentifie'], ['is triggered by', 'est declenche par'], ['applies', 'applique'],
  ['declares', 'declare'], ['references', 'reference'], ['contains', 'contient'], ['is governed by', 'est regi par'],
  ['may create', 'peut creer'], ['targets', 'cible'], ['uses', 'utilise'], ['holds', 'detient'],
  ['consumes and produces', 'consomme et produit'], ['may be stored as', 'peut etre stocke comme'],
  ['creates', 'cree'], ['describes', 'decrit'], ['is checked by', 'est controlee par'], ['may invoke', 'peut invoquer'],
  ['is recorded as', 'est consigne comme'], ['is protected by', 'est protege par'], ['emits', 'emet'], ['transports last', 'transporte en dernier'],
  ['INTERNAL', 'INTERNE'], ['INBOUND', 'ENTRANT'], ['OUTBOUND', 'SORTANT'], ['PENDING', 'EN_ATTENTE'],
  ['RUNNING', 'EN_COURS'], ['SUCCEEDED', 'REUSSIE'], ['FAILED', 'ECHOUEE'], ['ACTIVE', 'ACTIF'], ['DISABLED', 'DESACTIVE'],
  ['DATABASE', 'BASE_DE_DONNEES'], ['FILE', 'FICHIER'], ['PUSH', 'POUSSE'], ['DISTRIBUTED', 'DISTRIBUE'],
  ['OPEN', 'OUVERTE'], ['COMPLETED', 'TERMINEE'], ['DELIVERED', 'LIVREE']
]);

function umlFr(value) {
  let translated = String(value);
  for (const [source, target] of [...UML_FR].sort(([left], [right]) => right.length - left.length)) {
    translated = translated.replaceAll(source, target);
  }
  return translated;
}

function isClassDiagram(g) {
  return g.id === 'Classes_v2' || g.id.startsWith('CD_');
}

function title(g, text, subtitle) {
  vertex(g, `${g.id}_title`, text, styles.title, 30, 18, g.width - 60, 38);
  vertex(g, `${g.id}_subtitle`, subtitle, styles.subtitle, 32, 58, g.width - 64, 34);
}

function boundary(g, id, label, x, y, w, h, style = styles.boundary) {
  return vertex(g, id, label, style, x, y, w, h);
}

function component(g, id, label, x, y, w = 220, h = 70, style = styles.component) {
  return vertex(g, id, label, style, x, y, w, h);
}

function note(g, id, label, x, y, w, h) {
  return vertex(g, id, label, styles.note, x, y, w, h);
}

function usecaseNote(g, id, label, x, y, w, h) {
  return vertex(g, id, label, styles.usecaseNote, x, y, w, h);
}

function actor(g, id, label, x, y) {
  return vertex(g, id, label, styles.actor, x, y, 70, 110);
}

function usecase(g, id, label, x, y, w = 210, h = 70, style = styles.usecase) {
  return vertex(g, id, label, style, x, y, w, h);
}

function relation(g, id, source, target, label = '', style = FLOW) {
  return edge(g, id, source, target, label, style);
}

function association(g, id, source, target, name = '', sourceMult = '', targetMult = '', kind = 'association') {
  let style = ASSOC;
  if (kind === 'composition') style += 'startArrow=diamond;startFill=1;';
  if (kind === 'aggregation') style += 'startArrow=diamondThin;startFill=0;';
  if (kind === 'dependency') style = DEP;
  if (kind === 'generalization') style = GENERALIZE;
  if (kind === 'realization') style = `${GENERALIZE}dashed=1;`;
  const edgeId = edge(g, id, source, target, isClassDiagram(g) ? umlFr(name) : name, style);
  if (sourceMult) edgeLabel(g, `${edgeId}_sm`, edgeId, sourceMult, -0.82, -1);
  if (targetMult) edgeLabel(g, `${edgeId}_tm`, edgeId, targetMult, 0.82, -1);
  return edgeId;
}

function edgeLabel(g, id, parent, value, x, y) {
  return vertex(g, id, value,
    `edgeLabel;html=1;align=center;verticalAlign=middle;resizable=0;points=[];fillColor=${C.paper};strokeColor=none;fontColor=${C.ink};fontSize=9;`,
    x, y, 0, 0, { parent, relative: true, connectable: false, offset: { x: 0, y: -9 } });
}

function classBox(g, id, name, attributes, methods, x, y, w = 280, options = {}) {
  const header = options.kind ? `${options.kind}<br><b>${name}</b>` : `<b>${name}</b>`;
  const attrLines = attributes?.length ? attributes : [''];
  const methodLines = methods?.length ? methods : [];
  const attrHeight = Math.max(34, attrLines.length * 17 + 12);
  const methodHeight = methodLines.length ? Math.max(34, methodLines.length * 17 + 12) : 0;
  const total = 36 + attrHeight + methodHeight;
  vertex(g, id, header,
    `swimlane;fontStyle=1;align=center;verticalAlign=top;startSize=36;horizontal=1;childLayout=stackLayout;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=0;whiteSpace=wrap;html=1;fillColor=${C.paper};strokeColor=${C.ink};fontColor=${C.ink};fontSize=11;`,
    x, y, w, total);
  vertex(g, `${id}_attrs`, attrLines.join('<br>'),
    `text;strokeColor=${C.ink};fillColor=${C.paper};align=left;verticalAlign=top;spacing=6;overflow=hidden;rotatable=0;whiteSpace=wrap;html=1;fontColor=${C.ink};fontSize=10;`,
    0, 36, w, attrHeight, { parent: id });
  if (methodHeight) {
    vertex(g, `${id}_methods`, methodLines.join('<br>'),
      `text;strokeColor=${C.ink};fillColor=${C.paper};align=left;verticalAlign=top;spacing=6;overflow=hidden;rotatable=0;whiteSpace=wrap;html=1;fontColor=${C.ink};fontSize=10;`,
      0, 36 + attrHeight, w, methodHeight, { parent: id });
  }
  return id;
}

function lifeline(g, id, label, x, y = 100, h = 1180, w = 150) {
  vertex(g, id, label, styles.lifeline, x, y, w, h);
  g.lifelines.set(id, { x, y, w, h });
  return id;
}

function frame(g, id, label, x, y, w, h, divisions = []) {
  vertex(g, id, label, styles.frame, x, y, w, h);
  for (const division of divisions) {
    vertex(g, `${id}_line_${division}`, '', `shape=line;strokeColor=${C.line};dashed=1;`, x, division, w, 1);
  }
  return id;
}

function message(g, id, source, target, label, y, kind = 'sync') {
  const s = g.lifelines.get(source);
  const t = g.lifelines.get(target);
  if (!s || !t) throw new Error(`Unknown lifeline for ${id}: ${source} -> ${target}`);
  const sr = Math.max(0.02, Math.min(0.98, (y - s.y) / s.h));
  const tr = Math.max(0.02, Math.min(0.98, ((source === target ? y + 32 : y) - t.y) / t.h));
  const arrow = kind === 'sync'
    ? 'endArrow=block;endFill=1;'
    : 'endArrow=open;endFill=0;';
  const dashed = kind === 'reply' ? 'dashed=1;' : '';
  const routing = source === target
    ? 'edgeStyle=orthogonalEdgeStyle;orthogonalLoop=1;jettySize=28;'
    : 'edgeStyle=none;';
  return edge(g, id, source, target, label,
    `${routing}rounded=0;html=1;${arrow}${dashed}strokeColor=${C.ink};fontColor=${C.ink};fontSize=9;labelBackgroundColor=${C.paper};exitX=0.5;exitY=${fmt(sr)};entryX=0.5;entryY=${fmt(tr)};exitPerimeter=0;entryPerimeter=0;`);
}

// Mise en page automatique des diagrammes de séquence : les ordonnées, la
// hauteur des lignes de vie, les cadres combinés et la hauteur de page sont
// déduits de l'ordre des messages. Aucune coordonnée verticale n'est écrite à
// la main, ce qui garantit un rythme régulier et des cadres qui contiennent
// toujours leurs messages.
const SEQ = {
  start: 205,       // ordonnée du premier message, sous les têtes de ligne de vie
  step: 40,         // pas vertical entre deux messages
  selfExtra: 14,    // hauteur supplémentaire d'un message réflexif
  headTop: 100,     // ordonnée des têtes de ligne de vie
  tailBelow: 44,    // prolongement des lignes de vie sous le dernier message
  frameTop: 18,     // respiration avant le titre d'un cadre
  frameBottom: 16,  // respiration après la fermeture d'un cadre
  titleBand: 30,    // bandeau du titre au-dessus du premier message du cadre
  frameFoot: 20,    // bas du cadre sous son dernier message
  padX: 20,         // marge horizontale d'un cadre
  nestX: 16,        // décalage horizontal d'un cadre imbriqué
  nestY: 22,        // décalage vertical d'un cadre imbriqué
  divLead: 4,       // espace avant un séparateur d'opérandes
  divTrail: 26,     // espace après un séparateur d'opérandes
  charW: 4.75,      // largeur moyenne d'un caractère de libellé (fontSize 9)
  noteTop: 44,      // écart entre le bas des lignes de vie et les notes
  noteCharW: 5,     // largeur moyenne d'un caractère de note (fontSize 10)
  noteLine: 14,     // interligne d'une note
  pageFoot: 40,     // marge basse de la page
};

const textLength = value => String(value ?? '').replace(/<br\s*\/?>/g, ' ').replace(/<[^>]+>/g, '').length;

function sequence(g, options = {}) {
  const step = options.step ?? SEQ.step;
  const actors = [];
  const messages = [];
  const roots = [];
  const stack = [];
  const notes = [];
  let y = options.start ?? SEQ.start;
  let lastY = y - step;

  const labelSpan = (m) => {
    const s = g.lifelines.get(m.source);
    const t = g.lifelines.get(m.target);
    const sx = s.x + s.w / 2;
    const tx = t.x + t.w / 2;
    const centre = m.source === m.target ? sx + 34 : (sx + tx) / 2;
    const half = (textLength(m.label) * SEQ.charW + 12) / 2;
    return [centre - half, centre + half];
  };

  const api = {
    // Une ligne de vie ; la hauteur est calculée à la construction.
    actor(id, label, x, w = 150) {
      actors.push({ id, label, x, w });
      return id;
    },
    msg(source, target, label, kind = 'sync', extra = 0) {
      const m = { source, target, label, kind, y };
      messages.push(m);
      if (stack.length) stack[stack.length - 1].messages.push(m);
      lastY = y;
      y += step + extra + (source === target ? SEQ.selfExtra : 0);
      return api;
    },
    // Respiration explicite entre deux phases du scénario.
    gap(px = step / 2) {
      y += px;
      return api;
    },
    open(id, label) {
      const node = { id, label, messages: [], children: [], dividers: [], minTop: lastY + 20 };
      (stack.length ? stack[stack.length - 1].children : roots).push(node);
      stack.push(node);
      y += SEQ.frameTop;
      return api;
    },
    // Séparateur entre deux opérandes d'un cadre alt / par.
    divider() {
      if (!stack.length) throw new Error(`${g.id}: séparateur hors cadre`);
      const pos = y + SEQ.divLead;
      stack[stack.length - 1].dividers.push(pos);
      y = pos + SEQ.divTrail;
      return api;
    },
    close() {
      if (!stack.length) throw new Error(`${g.id}: fermeture de cadre sans ouverture`);
      stack.pop();
      y += SEQ.frameBottom;
      return api;
    },
    note(id, label, x, width) {
      notes.push({ id, label, x, width });
      return api;
    },
    build() {
      if (stack.length) throw new Error(`${g.id}: cadre non fermé (${stack.map(n => n.id).join(', ')})`);
      const bottom = lastY + SEQ.tailBelow;
      for (const a of actors) lifeline(g, a.id, a.label, a.x, SEQ.headTop, bottom - SEQ.headTop, a.w);

      const measure = (node) => {
        node.children.forEach(measure);
        let top = Infinity, foot = -Infinity, left = Infinity, right = -Infinity;
        for (const m of node.messages) {
          const s = g.lifelines.get(m.source);
          const t = g.lifelines.get(m.target);
          const [ll, lr] = labelSpan(m);
          top = Math.min(top, m.y - SEQ.titleBand);
          foot = Math.max(foot, m.y + SEQ.frameFoot + (m.source === m.target ? SEQ.selfExtra : 0));
          left = Math.min(left, s.x, t.x, ll);
          right = Math.max(right, s.x + s.w, t.x + t.w, lr);
        }
        for (const c of node.children) {
          top = Math.min(top, c.box.y - SEQ.nestY);
          foot = Math.max(foot, c.box.y + c.box.height + SEQ.nestY / 2);
          left = Math.min(left, c.box.x - SEQ.nestX);
          right = Math.max(right, c.box.x + c.box.width + SEQ.nestX);
        }
        left = Math.max(8, left - SEQ.padX);
        right = Math.min(g.width - 8, right + SEQ.padX);
        top = Math.max(top, node.minTop);
        const width = Math.max(right - left, textLength(node.label) * 5.6 + 24);
        node.box = {
          x: Math.round(left),
          y: Math.round(top),
          width: Math.round(Math.min(width, g.width - left - 8)),
          height: Math.round(foot - top),
        };
      };
      roots.forEach(measure);

      // Cadres avant messages : les parents restent en arrière-plan.
      const emit = (node) => {
        frame(g, node.id, node.label, node.box.x, node.box.y, node.box.width, node.box.height, node.dividers);
        node.children.forEach(emit);
      };
      roots.forEach(emit);
      for (const m of messages) message(g, null, m.source, m.target, m.label, m.y, m.kind);

      let noteBottom = bottom + SEQ.noteTop;
      for (const n of notes) {
        const perLine = Math.max(10, Math.floor((n.width - 18) / SEQ.noteCharW));
        const lines = String(n.label).split(/<br\s*\/?>/)
          .reduce((sum, part) => sum + Math.max(1, Math.ceil(textLength(part) / perLine)), 0);
        const h = lines * SEQ.noteLine + 22;
        note(g, n.id, n.label, n.x, bottom + SEQ.noteTop, n.width, h);
        noteBottom = Math.max(noteBottom, bottom + SEQ.noteTop + h);
      }
      g.height = Math.round(noteBottom + SEQ.pageFoot);
      return api;
    },
  };
  return api;
}

function activityEdge(g, id, source, target, guard = '') {
  return edge(g, id, source, target, guard, FLOW);
}

function addFinalNode(g, id, x, y) {
  vertex(g, `${id}_outer`, '', styles.finalOuter, x, y, 26, 26);
  vertex(g, `${id}_inner`, '', styles.finalInner, x + 6, y + 6, 14, 14);
  return `${id}_outer`;
}

// ---------------------------------------------------------------------------
// 1. UML component architecture
// ---------------------------------------------------------------------------
{
  const g = graph('Architecture', 'Architecture', 2820, 1460);
  title(g, 'Architecture logique IOL', 'Diagramme de composants UML — responsabilités et dépendances runtime; la topologie physique est décrite dans « Deploiement_Production ».');
  boundary(g, 'arch_external', 'Systèmes externes', 30, 110, 300, 1220);
  boundary(g, 'arch_iol', 'Plateforme IOL', 360, 110, 2110, 1220);
  boundary(g, 'arch_services', 'Services externes de confiance', 2500, 110, 290, 1220);

  component(g, 'arch_browser', 'Navigateur utilisateur', 70, 180, 220, 65, styles.external);
  component(g, 'arch_source', 'Source JDBC / API / fichier', 70, 670, 220, 75, styles.external);
  component(g, 'arch_sender', 'Système émetteur INBOUND', 70, 370, 220, 75, styles.external);
  component(g, 'arch_receiver', 'Système destinataire OUTBOUND', 70, 1110, 220, 75, styles.external);

  component(g, 'arch_front', 'Frontend React', 430, 180, 220, 70);
  component(g, 'arch_keycloak', 'Keycloak<br><font style="font-size:9px">OIDC en production</font>', 730, 165, 230, 85, styles.componentPurple);
  component(g, 'arch_api', 'api-core<br><font style="font-size:9px">configuration, orchestration, audit</font>', 1050, 165, 270, 85, styles.component);
  component(g, 'arch_mongo', 'MongoDB<br><font style="font-size:9px">configurations, logs, claims</font>', 1440, 165, 240, 85, styles.componentOrange);

  component(g, 'arch_openhim', 'OpenHIM Core<br><font style="font-size:9px">authentifie, route, trace sans corps sensible</font>', 430, 380, 260, 90, styles.componentPurple);
  component(g, 'arch_domain_med', 'Médiateurs Java<br><font style="font-size:9px">FHIR R4 · ISO 20022 · Ed-Fi</font>', 790, 380, 270, 90, styles.componentPurple);
  component(g, 'arch_generic_med', 'iol-mediator<br><font style="font-size:9px">pivot générique + worker OUTBOUND</font>', 1160, 380, 280, 90, styles.componentPurple);

  component(g, 'arch_gateway', 'source-gateway<br><font style="font-size:9px">seul lecteur des sources pendant une exécution interne</font>', 530, 690, 290, 95, styles.componentGreen);
  component(g, 'arch_kafka', 'Kafka<br><font style="font-size:9px">ordres, lots, commandes, statuts, DLQ</font>', 1000, 680, 260, 100, styles.component);
  component(g, 'arch_consumer', 'pipeline-consumer<br><font style="font-size:9px">claim persistant, matérialisation, pilotage</font>', 1450, 680, 300, 100, styles.componentGreen);
  component(g, 'arch_hop', 'Apache Hop<br><font style="font-size:9px">runtime LOCAL</font>', 1880, 610, 220, 75, styles.componentGreen);
  component(g, 'arch_spark', 'Apache Spark<br><font style="font-size:9px">runtime distribué automatique</font>', 1880, 785, 220, 75, styles.componentGreen);
  component(g, 'arch_target', 'Destination JDBC<br><font style="font-size:9px">Bronze · Silver · Gold</font>', 2180, 680, 240, 100, styles.componentOrange);
  component(g, 'arch_rustfs', 'RustFS / S3<br><font style="font-size:9px">objets volumineux ou incompatibles Kafka</font>', 1040, 970, 250, 90, styles.componentOrange);

  component(g, 'arch_vault', 'Vault Transit / KMS', 2535, 225, 220, 70, styles.componentPurple);
  component(g, 'arch_clam', 'ClamAV', 2535, 445, 220, 65, styles.componentPurple);
  component(g, 'arch_llm', 'Fournisseurs LLM<br><font style="font-size:9px">schéma et dialecte uniquement</font>', 2535, 650, 220, 80, styles.componentPurple);
  component(g, 'arch_mail', 'Serveur SMTP', 2535, 850, 220, 65, styles.componentPurple);

  relation(g, null, 'arch_browser', 'arch_front', 'HTTPS');
  relation(g, null, 'arch_front', 'arch_keycloak', 'OIDC / PKCE');
  relation(g, null, 'arch_front', 'arch_api', 'REST + JWT');
  relation(g, null, 'arch_api', 'arch_mongo', 'TLS : configuration et journaux');
  relation(g, null, 'arch_api', 'arch_vault', 'mTLS : chiffrer / déchiffrer');
  relation(g, null, 'arch_api', 'arch_clam', 'mTLS : analyser fichiers');
  relation(g, null, 'arch_api', 'arch_llm', 'HTTPS : noms de colonnes + dialecte');
  relation(g, null, 'arch_api', 'arch_mail', 'STARTTLS');

  relation(g, null, 'arch_sender', 'arch_openhim', 'HTTPS / mTLS : message normé');
  relation(g, null, 'arch_openhim', 'arch_domain_med', 'route selon domaine');
  relation(g, null, 'arch_domain_med', 'arch_generic_med', 'pivot validé');
  relation(g, null, 'arch_generic_med', 'arch_api', 'OAuth2 client_credentials + mTLS : INBOUND');

  relation(g, null, 'arch_api', 'arch_kafka', 'publie iol.transport.requests');
  relation(g, null, 'arch_kafka', 'arch_gateway', 'livre ordre de transport');
  relation(g, null, 'arch_gateway', 'arch_source', 'lecture streaming de la source');
  relation(g, null, 'arch_gateway', 'arch_mongo', 'lit workflow + claim transport');
  relation(g, null, 'arch_gateway', 'arch_vault', 'mTLS : résout credential source');
  relation(g, null, 'arch_gateway', 'arch_kafka', 'lots de lignes puis commande');
  relation(g, null, 'arch_gateway', 'arch_rustfs', 'écrit objet volumineux');
  relation(g, null, 'arch_kafka', 'arch_consumer', 'commandes HIGH / NORMAL / LOW');
  relation(g, null, 'arch_rustfs', 'arch_consumer', 'lit objet référencé');
  relation(g, null, 'arch_consumer', 'arch_mongo', 'claim pipeline + chunks temporaires');
  relation(g, null, 'arch_consumer', 'arch_api', 'lease credential cible');
  relation(g, null, 'arch_consumer', 'arch_hop', '[LOCAL] lance pipeline');
  relation(g, null, 'arch_consumer', 'arch_spark', '[SPARK] spark-submit');
  relation(g, null, 'arch_hop', 'arch_target', 'JDBC');
  relation(g, null, 'arch_spark', 'arch_target', 'JDBC distribué');
  relation(g, null, 'arch_consumer', 'arch_kafka', 'publie iol.pipeline.status');
  relation(g, null, 'arch_kafka', 'arch_api', 'livre statuts pipeline / OUTBOUND');

  relation(g, null, 'arch_api', 'arch_kafka', 'publie iol.outbound.delivery');
  relation(g, null, 'arch_kafka', 'arch_generic_med', 'livre commande OUTBOUND');
  relation(g, null, 'arch_generic_med', 'arch_openhim', '[canal OpenHIM] egress contrôlé');
  relation(g, null, 'arch_openhim', 'arch_receiver', 'HTTPS / mTLS');
  relation(g, null, 'arch_generic_med', 'arch_receiver', '[endpoint autorisé] HTTPS direct');

  note(g, 'arch_note', '<b>Règle structurante</b><br>Hop et Spark ne se connectent jamais à la source métier. Ils consomment uniquement les artefacts transportés par Kafka ou RustFS. Le choix LOCAL/SPARK est interne et automatique. Les objets RustFS sont supprimés après succès durable; en échec, une rétention de secours de 72 h permet le diagnostic puis la purge.', 1530, 1050, 720, 150);
}

// ---------------------------------------------------------------------------
// 2. UML deployment view — current production compose topology
// ---------------------------------------------------------------------------
{
  const g = graph('Deployment_Production', 'Deploiement_Production', 2900, 1580);
  title(g, 'Déploiement de production', 'Diagramme de déploiement UML — topologie décrite par docker-compose.production.yml et openhim/docker-compose.openhim.production.yml.');

  boundary(g, 'dep_public', 'Zone publique / edge', 40, 120, 430, 320, styles.node);
  component(g, 'dep_nginx', 'Nginx<br><font style="font-size:9px">terminaison TLS + frontend</font>', 100, 210, 270, 80);

  boundary(g, 'dep_apps', 'Réseau iol-internal — services applicatifs', 520, 120, 900, 560, styles.node);
  component(g, 'dep_api', 'api-core ×1', 590, 220, 220, 70);
  component(g, 'dep_gateway', 'source-gateway ×3', 870, 220, 230, 70, styles.componentGreen);
  component(g, 'dep_consumer', 'pipeline-consumer ×3', 1150, 220, 230, 70, styles.componentGreen);
  component(g, 'dep_clam', 'clamav-tls', 590, 390, 200, 65, styles.componentPurple);
  component(g, 'dep_mail', 'mailpit / SMTP configuré', 850, 390, 230, 65, styles.componentPurple);
  component(g, 'dep_hop', 'Hop embarqué', 1130, 390, 210, 65, styles.componentGreen);

  boundary(g, 'dep_compute', 'Calcul distribué', 1470, 120, 500, 560, styles.node);
  component(g, 'dep_spark_master', 'Spark master ×1', 1540, 220, 190, 70, styles.componentGreen);
  component(g, 'dep_spark_worker', 'Spark worker ×3', 1740, 390, 190, 70, styles.componentGreen);

  boundary(g, 'dep_identity', 'Identité', 2020, 120, 420, 560, styles.node);
  component(g, 'dep_kc_lb', 'Keycloak LB', 2090, 205, 170, 60, styles.componentPurple);
  component(g, 'dep_kc1', 'Keycloak 1', 2070, 340, 150, 60, styles.componentPurple);
  component(g, 'dep_kc2', 'Keycloak 2', 2260, 340, 150, 60, styles.componentPurple);
  component(g, 'dep_pg', 'PostgreSQL ×1<br><font style="font-size:9px">lakehouse + DB Keycloak</font>', 2110, 500, 250, 75, styles.componentOrange);

  boundary(g, 'dep_data', 'Couche de données HA', 520, 750, 1420, 660, styles.node);
  component(g, 'dep_kafka', 'Kafka KRaft<br><b>3 brokers</b>', 600, 870, 250, 80);
  component(g, 'dep_mongo', 'MongoDB replica set<br><b>3 nœuds</b>', 950, 870, 270, 80, styles.componentOrange);
  component(g, 'dep_rustlb', 'RustFS LB', 1330, 835, 190, 60, styles.componentOrange);
  component(g, 'dep_rust', 'RustFS distribué<br><b>4 nœuds</b>', 1300, 990, 260, 80, styles.componentOrange);
  component(g, 'dep_backup', 'Volumes persistants<br>+ sauvegarde / restauration testée', 1650, 890, 230, 105, styles.componentOrange);

  boundary(g, 'dep_interop', 'Stack OpenHIM séparée', 2020, 750, 840, 660, styles.node);
  component(g, 'dep_oh_core', 'OpenHIM Core', 2090, 850, 190, 65, styles.componentPurple);
  component(g, 'dep_oh_console', 'OpenHIM Console', 2330, 850, 190, 65, styles.componentPurple);
  component(g, 'dep_oh_mongo', 'OpenHIM MongoDB', 2570, 850, 200, 65, styles.componentOrange);
  component(g, 'dep_med_generic', 'iol-mediator', 2090, 1050, 190, 65, styles.componentPurple);
  component(g, 'dep_med_fhir', 'FHIR mediator', 2330, 1020, 170, 60, styles.componentPurple);
  component(g, 'dep_med_iso', 'ISO 20022 mediator', 2540, 1020, 190, 60, styles.componentPurple);
  component(g, 'dep_med_edfi', 'Ed-Fi mediator', 2430, 1130, 180, 60, styles.componentPurple);

  boundary(g, 'dep_trust', 'Service de confiance externe', 2490, 120, 370, 560, styles.node);
  component(g, 'dep_vault', 'Vault Transit / KMS<br><font style="font-size:9px">réseau iol-vault externe</font>', 2560, 260, 230, 85, styles.componentPurple);
  component(g, 'dep_ca', 'PKI / CA interne', 2560, 455, 230, 65, styles.componentPurple);

  relation(g, null, 'dep_nginx', 'dep_api', 'HTTPS');
  relation(g, null, 'dep_nginx', 'dep_kc_lb', 'HTTPS / OIDC');
  relation(g, null, 'dep_kc_lb', 'dep_kc1', 'mTLS');
  relation(g, null, 'dep_kc_lb', 'dep_kc2', 'mTLS');
  relation(g, null, 'dep_kc1', 'dep_pg', 'TLS');
  relation(g, null, 'dep_kc2', 'dep_pg', 'TLS');
  relation(g, null, 'dep_api', 'dep_kafka', 'TLS + ACL');
  relation(g, null, 'dep_gateway', 'dep_kafka', 'mTLS + ACL');
  relation(g, null, 'dep_consumer', 'dep_kafka', 'mTLS + ACL');
  relation(g, null, 'dep_api', 'dep_mongo', 'mTLS');
  relation(g, null, 'dep_gateway', 'dep_mongo', 'mTLS');
  relation(g, null, 'dep_consumer', 'dep_mongo', 'mTLS');
  relation(g, null, 'dep_gateway', 'dep_rustlb', 'TLS / S3');
  relation(g, null, 'dep_consumer', 'dep_rustlb', 'TLS / S3');
  relation(g, null, 'dep_rustlb', 'dep_rust', 'TLS');
  relation(g, null, 'dep_consumer', 'dep_spark_master', 'spark-submit sécurisé');
  relation(g, null, 'dep_spark_master', 'dep_spark_worker', 'TLS');
  relation(g, null, 'dep_api', 'dep_vault', 'mTLS AppRole');
  relation(g, null, 'dep_gateway', 'dep_vault', 'mTLS AppRole');
  relation(g, null, 'dep_rust', 'dep_vault', 'KMS Transit');
  relation(g, null, 'dep_ca', 'dep_apps', 'certificats / truststore', DEP);
  relation(g, null, 'dep_oh_core', 'dep_oh_mongo', 'TLS');
  relation(g, null, 'dep_oh_console', 'dep_oh_core', 'HTTPS');
  relation(g, null, 'dep_med_fhir', 'dep_med_generic', 'mTLS : pivot');
  relation(g, null, 'dep_med_iso', 'dep_med_generic', 'mTLS : pivot');
  relation(g, null, 'dep_med_edfi', 'dep_med_generic', 'mTLS : pivot');
  relation(g, null, 'dep_med_generic', 'dep_api', 'OAuth2 + mTLS');
  relation(g, null, 'dep_med_generic', 'dep_kafka', 'mTLS + ACL');
  relation(g, null, 'dep_oh_core', 'dep_med_fhir', 'HTTPS');
  relation(g, null, 'dep_oh_core', 'dep_med_iso', 'HTTPS');
  relation(g, null, 'dep_oh_core', 'dep_med_edfi', 'HTTPS');

  note(g, 'dep_note', '<b>Limite actuelle explicitée</b><br>Kafka (3), MongoDB (3), RustFS (4), Keycloak (2), source-gateway (3), pipeline-consumer (3) et Spark worker (3) sont redondés dans la topologie déclarée. api-core, Nginx, PostgreSQL et Spark master restent des points uniques à traiter selon le niveau de disponibilité exigé.', 70, 520, 380, 205);
}

// ---------------------------------------------------------------------------
// 3. Kafka topics and ownership
// ---------------------------------------------------------------------------
{
  const g = graph('Kafka', 'Kafka', 2960, 1600);
  title(g, 'Contrats Kafka et responsabilités', 'Diagramme de composants UML avec profil documentaire «topic» — flèches = publication ou consommation; tous les échanges sont TLS + ACL en production.');
  boundary(g, 'k_producers', 'Producteurs / consommateurs', 40, 120, 560, 1340);
  boundary(g, 'k_cluster', '«component» Cluster Kafka', 650, 120, 1660, 1340);
  boundary(g, 'k_observers', 'Consommateurs / effets', 2360, 120, 550, 1340);

  component(g, 'k_api', 'api-core', 130, 230, 300, 80);
  component(g, 'k_gateway', 'source-gateway', 130, 570, 300, 80, styles.componentGreen);
  component(g, 'k_consumer', 'pipeline-consumer', 130, 940, 300, 80, styles.componentGreen);
  component(g, 'k_mediator', 'iol-mediator<br><font style="font-size:9px">worker OUTBOUND</font>', 130, 1230, 300, 80, styles.componentPurple);

  const topics = [
    ['k_t_req', '«topic»<br>iol.transport.requests', 760, 205],
    ['k_t_high', '«topic»<br>iol.pipeline.high', 760, 430],
    ['k_t_normal', '«topic»<br>iol.pipeline.commands', 1120, 430],
    ['k_t_low', '«topic»<br>iol.pipeline.low', 1480, 430],
    ['k_t_status', '«topic»<br>iol.pipeline.status', 1840, 430],
    ['k_t_tdlq', '«topic»<br>iol.transport.requests.dlq', 760, 750],
    ['k_t_pdlq', '«topic»<br>iol.pipeline.commands.dlq', 1120, 750],
    ['k_t_out', '«topic»<br>iol.outbound.delivery', 1480, 750],
    ['k_t_out_status', '«topic»<br>iol.outbound.status', 1840, 750],
    ['k_t_out_dlq', '«topic»<br>iol.outbound.delivery.dlq', 1480, 1060],
  ];
  for (const [id, label, x, y] of topics) vertex(g, id, label, styles.topic, x, y, 260, 85);

  component(g, 'k_mongo', 'MongoDB<br><font style="font-size:9px">claims persistants</font>', 2460, 250, 290, 80, styles.componentOrange);
  component(g, 'k_source', 'Sources métier', 2460, 540, 290, 70, styles.external);
  component(g, 'k_runtime', 'Hop / Spark', 2460, 815, 290, 70, styles.componentGreen);
  component(g, 'k_target', 'Système destinataire', 2460, 1140, 290, 70, styles.external);

  relation(g, null, 'k_api', 'k_t_req', 'publie ordre minimal, sans donnée ni secret');
  relation(g, null, 'k_t_req', 'k_gateway', 'consomme');
  relation(g, null, 'k_gateway', 'k_source', 'lecture après claim');
  relation(g, null, 'k_gateway', 'k_mongo', 'claim transport + fencing');
  relation(g, null, 'k_gateway', 'k_t_high', 'publie lots + commande [priorité haute]');
  relation(g, null, 'k_gateway', 'k_t_normal', 'publie lots + commande [normale]');
  relation(g, null, 'k_gateway', 'k_t_low', 'publie lots + commande [basse]');
  relation(g, null, 'k_gateway', 'k_t_tdlq', 'ordre invalide / reprises épuisées');
  relation(g, null, 'k_gateway', 'k_t_status', 'échec transport / progression');

  relation(g, null, 'k_api', 'k_t_high', 'INBOUND PUSH [priorité haute]');
  relation(g, null, 'k_api', 'k_t_normal', 'INBOUND PUSH [normale]');
  relation(g, null, 'k_api', 'k_t_low', 'INBOUND PUSH [basse]');
  relation(g, null, 'k_t_high', 'k_consumer', 'consomme pondéré');
  relation(g, null, 'k_t_normal', 'k_consumer', 'consomme pondéré');
  relation(g, null, 'k_t_low', 'k_consumer', 'consomme pondéré');
  relation(g, null, 'k_consumer', 'k_mongo', 'claim pipeline + chunks temporaires');
  relation(g, null, 'k_consumer', 'k_runtime', 'exécute');
  relation(g, null, 'k_consumer', 'k_t_status', 'SUCCESS / FAILED / progression');
  relation(g, null, 'k_consumer', 'k_t_pdlq', 'commande empoisonnée / terminale');
  relation(g, null, 'k_t_status', 'k_api', 'consomme et met à jour ExecutionLog');

  relation(g, null, 'k_api', 'k_t_out', 'publie après pipeline OUTBOUND réussi');
  relation(g, null, 'k_t_out', 'k_mediator', 'consomme avec claim durable');
  relation(g, null, 'k_mediator', 'k_target', 'livre avec contrôle SSRF / canal OpenHIM');
  relation(g, null, 'k_mediator', 'k_t_out_status', 'DELIVERED / FAILED');
  relation(g, null, 'k_mediator', 'k_t_out_dlq', 'reprises épuisées');
  relation(g, null, 'k_t_out_status', 'k_api', 'consomme et clôture le journal');

  note(g, 'k_note_data', '<b>Données normales</b><br>JDBC/API/PUSH utilisent des lots JSON; un fichier utilise des chunks de son format original. Aucune conversion interne en CSV. La commande reste le dernier événement.', 760, 1240, 430, 135);
  note(g, 'k_note_big', '<b>Big data / enregistrement non sûr pour Kafka</b><br>Kafka transporte uniquement une référence et un manifeste vers un objet RustFS.', 1260, 1240, 430, 125);
  note(g, 'k_note_poison', '<b>Poison pill</b><br>Le parsing est encapsulé. Après les reprises configurées, le message et son diagnostic vont en DLQ puis l’offset est acquitté.', 1760, 1240, 430, 125);
  note(g, 'k_profile', '<b>«profile» IOLKafka</b><br>«topic» étend UML::InformationItem et représente un journal Kafka durable nommé.', 760, 1400, 430, 90);
}

// ---------------------------------------------------------------------------
// 4. UML activity: automatic transport and runtime choice
// ---------------------------------------------------------------------------
{
  const g = graph('Spark_Distribue', 'Spark_Distribue', 2600, 1510);
  title(g, 'Décision automatique de transport et d’exécution', 'Diagramme d’activités UML — l’utilisateur exprime le traitement; la plateforme choisit Kafka/RustFS et LOCAL/SPARK.');
  boundary(g, 'act_gateway_lane', 'Partition : source-gateway', 40, 110, 1230, 1280);
  boundary(g, 'act_consumer_lane', 'Partition : pipeline-consumer', 1320, 110, 1230, 1280);

  vertex(g, 'act_start', '', styles.initial, 115, 180, 26, 26);
  vertex(g, 'act_claim', 'Valider TransportOrder<br>et acquérir le claim persistant', styles.action, 220, 150, 260, 80);
  vertex(g, 'act_read', 'Lire la configuration et estimer<br>lignes, octets et étapes distribuées', styles.actionGreen, 560, 150, 300, 80);
  vertex(g, 'act_mode', 'Mode distribué requis ?', styles.decision, 970, 145, 150, 100);
  vertex(g, 'act_mark_local', 'Fixer executionMode = LOCAL', styles.action, 865, 340, 250, 65);
  vertex(g, 'act_mark_spark', 'Fixer executionMode = SPARK', styles.actionGreen, 865, 510, 250, 65);
  vertex(g, 'act_transport', 'Transport compatible Kafka ?', styles.decision, 570, 500, 170, 110);
  vertex(g, 'act_kafka_rows', 'Publier lots JSON ou chunks<br>du fichier original dans Kafka', styles.action, 230, 690, 280, 80);
  vertex(g, 'act_rustfs', 'Streamer vers RustFS<br>puis publier référence + manifeste', styles.actionGreen, 790, 690, 310, 80);
  vertex(g, 'act_command', 'Purger les identifiants source<br>et publier la commande en dernier', styles.action, 520, 910, 330, 80);

  vertex(g, 'act_consume', 'Consommer lots / référence<br>puis commande', styles.action, 1420, 150, 290, 80);
  vertex(g, 'act_pclaim', 'Acquérir PipelineExecutionClaim<br>et verrou distribué', styles.action, 1800, 150, 300, 80);
  vertex(g, 'act_materialize', 'Vérifier hash / manifeste et<br>matérialiser les artefacts transportés', styles.actionGreen, 2170, 150, 300, 80);
  vertex(g, 'act_runtime', 'executionMode ?', styles.decision, 1900, 390, 160, 105);
  vertex(g, 'act_hop', 'Lancer Apache Hop<br>[LOCAL]', styles.action, 1510, 580, 260, 75);
  vertex(g, 'act_spark', 'Lancer spark-submit / spark_etl.py<br>[SPARK]', styles.actionGreen, 2180, 580, 300, 75);
  vertex(g, 'act_bronze', 'Écrire Bronze<br>append par défaut, replace configurable', styles.action, 1510, 810, 300, 80);
  vertex(g, 'act_silver', 'Appliquer Silver<br>nettoyage et transformations', styles.action, 1880, 810, 290, 80);
  vertex(g, 'act_gold', 'Appliquer Gold<br>jointures et agrégations', styles.action, 2240, 810, 270, 80);
  vertex(g, 'act_status', 'Publier statut et finaliser<br>claim + ExecutionLog', styles.actionGreen, 1880, 1030, 300, 80);
  vertex(g, 'act_success', 'Succès durable ?', styles.decision, 1970, 1160, 130, 90);
  vertex(g, 'act_cleanup', 'Supprimer chunks temporaires<br>et objets RustFS transférés', styles.actionGreen, 2190, 1165, 300, 80);
  const finalId = addFinalNode(g, 'act_final', 2020, 1340);

  activityEdge(g, null, 'act_start', 'act_claim');
  activityEdge(g, null, 'act_claim', 'act_read');
  activityEdge(g, null, 'act_read', 'act_mode');
  activityEdge(g, null, 'act_mode', 'act_mark_local', '[< 10 000 000 lignes ET < 2 Gio ET aucune étape distribuée]');
  activityEdge(g, null, 'act_mode', 'act_mark_spark', '[seuil atteint OU étape distribuée OU demande technique explicite]');
  activityEdge(g, null, 'act_mark_local', 'act_transport');
  activityEdge(g, null, 'act_mark_spark', 'act_transport');
  activityEdge(g, null, 'act_transport', 'act_kafka_rows', '[LOCAL et chaque événement reste sous la limite Kafka sûre]');
  activityEdge(g, null, 'act_transport', 'act_rustfs', '[SPARK ou enregistrement trop grand pour Kafka]');
  activityEdge(g, null, 'act_kafka_rows', 'act_command');
  activityEdge(g, null, 'act_rustfs', 'act_command');
  activityEdge(g, null, 'act_command', 'act_consume', 'commande Kafka');
  activityEdge(g, null, 'act_consume', 'act_pclaim');
  activityEdge(g, null, 'act_pclaim', 'act_materialize');
  activityEdge(g, null, 'act_materialize', 'act_runtime');
  activityEdge(g, null, 'act_runtime', 'act_hop', '[LOCAL]');
  activityEdge(g, null, 'act_runtime', 'act_spark', '[SPARK]');
  activityEdge(g, null, 'act_hop', 'act_bronze');
  activityEdge(g, null, 'act_spark', 'act_bronze');
  activityEdge(g, null, 'act_bronze', 'act_silver');
  activityEdge(g, null, 'act_silver', 'act_gold');
  activityEdge(g, null, 'act_gold', 'act_status');
  activityEdge(g, null, 'act_status', 'act_success');
  activityEdge(g, null, 'act_success', 'act_cleanup', '[oui]');
  activityEdge(g, null, 'act_success', finalId, '[non : objet conservé au plus 72 h]');
  activityEdge(g, null, 'act_cleanup', finalId);

  note(g, 'act_note', '<b>Deux seuils différents</b><br>8 Mio protège la taille maximale d’un événement Kafka. Ce n’est pas le seuil big data. Le basculement distribué de production utilise 10 000 000 lignes ou 2 Gio, ainsi que la présence d’une étape nécessitant un calcul distribué.', 100, 1120, 1050, 165);
  note(g, 'act_cleanup_note', '<b>RustFS est un transport temporaire</b><br>Suppression immédiate après succès durable; purge planifiée après 72 h pour les objets laissés par un échec.', 1370, 1230, 440, 125);
}

// ---------------------------------------------------------------------------
// 5. Global use cases
// ---------------------------------------------------------------------------
{
  const g = graph('CasUtilisation', 'CasUtilisation', 2500, 1450);
  title(g, 'Fonctions offertes par IOL', 'Diagramme de cas d’utilisation UML — les moteurs et ordonnanceurs sont internes à la frontière, jamais des acteurs.');
  boundary(g, 'ucg_system', 'Système IOL', 350, 120, 1770, 1210);

  actor(g, 'ucg_user', 'Utilisateur', 70, 260);
  actor(g, 'ucg_admin', 'Administrateur', 70, 650);
  actor(g, 'ucg_external', 'Système partenaire', 70, 1080);
  actor(g, 'ucg_idp', 'Fournisseur OIDC', 2260, 230);
  actor(g, 'ucg_llm', 'Service LLM', 2260, 720);

  usecase(g, 'ucg_auth', 'S’authentifier', 480, 190);
  usecase(g, 'ucg_profile', 'Gérer son profil', 780, 190);
  usecase(g, 'ucg_conn', 'Gérer les connexions', 480, 390);
  usecase(g, 'ucg_workflow', 'Configurer les workflows', 800, 390);
  usecase(g, 'ucg_execute', 'Exécuter un workflow', 1120, 390);
  usecase(g, 'ucg_monitor', 'Suivre les exécutions', 1450, 390);
  usecase(g, 'ucg_sql', 'Générer et exécuter<br>une requête SQL', 600, 710, 240, 80);
  usecase(g, 'ucg_inbound', 'Échanger des données<br>avec un partenaire', 1040, 710, 250, 80);
  usecase(g, 'ucg_schema', 'Consulter le schéma<br>technique', 600, 940, 230, 75);
  usecase(g, 'ucg_trace', 'Tracer un échange', 1040, 940, 230, 75);

  usecase(g, 'ucg_users', 'Administrer les utilisateurs', 1670, 650, 250, 75, styles.usecaseAdmin);
  usecase(g, 'ucg_standards', 'Administrer les normes', 1670, 830, 250, 75, styles.usecaseAdmin);
  usecase(g, 'ucg_audit', 'Consulter l’audit', 1670, 1020, 250, 75, styles.usecaseAdmin);

  association(g, 'ucg_admin_gen', 'ucg_admin', 'ucg_user', '', '', '', 'generalization');
  for (const target of ['ucg_auth', 'ucg_profile', 'ucg_conn', 'ucg_workflow', 'ucg_execute', 'ucg_monitor', 'ucg_sql']) {
    association(g, null, 'ucg_user', target);
  }
  association(g, null, 'ucg_admin', 'ucg_users');
  association(g, null, 'ucg_admin', 'ucg_standards');
  association(g, null, 'ucg_admin', 'ucg_audit');
  association(g, null, 'ucg_external', 'ucg_inbound');
  association(g, null, 'ucg_idp', 'ucg_auth');
  association(g, null, 'ucg_llm', 'ucg_sql');
  relation(g, null, 'ucg_sql', 'ucg_schema', '«include»', INCLUDE);
  relation(g, null, 'ucg_inbound', 'ucg_trace', '«include»', INCLUDE);

  usecaseNote(g, 'ucg_note', '<b>Rôles réels</b><br>ADMIN et USER peuvent créer/configurer des connexions et workflows, puis les exécuter. Les normes, utilisateurs et journaux d’audit restent réservés à ADMIN. Les choix d’infrastructure sont automatiques et internes à IOL.', 1450, 180, 520, 160);
}

// ---------------------------------------------------------------------------
// 6. Access and identity use cases
// ---------------------------------------------------------------------------
{
  const g = graph('UC_Acces', 'UC_Acces', 2360, 1370);
  title(g, 'Accès, identité et comptes', 'Diagramme de cas d’utilisation UML — distinction explicite entre le mode KEYCLOAK de production et le mode LOCAL.');
  boundary(g, 'uca_system', 'Sous-système Accès IOL', 350, 120, 1570, 1130);

  actor(g, 'uca_user', 'Utilisateur', 70, 260);
  actor(g, 'uca_admin', 'Administrateur', 70, 760);
  actor(g, 'uca_idp', 'Keycloak', 2100, 260);
  actor(g, 'uca_mail', 'Serveur SMTP', 2100, 820);

  usecase(g, 'uca_login', 'S’authentifier', 520, 200);
  usecase(g, 'uca_logout', 'Se déconnecter', 850, 200);
  usecase(g, 'uca_session', 'Maintenir / rafraîchir<br>la session', 1180, 200);
  usecase(g, 'uca_profile', 'Modifier son profil', 520, 450);
  usecase(g, 'uca_password', 'Réinitialiser son<br>mot de passe', 850, 450);
  usecase(g, 'uca_kc_reset', 'Réinitialisation via<br>Keycloak', 1180, 400, 210, 70, styles.usecaseInterop);
  usecase(g, 'uca_local_reset', 'Réinitialisation par<br>code e-mail LOCAL', 1180, 520, 220, 70, styles.usecaseInterop);

  usecase(g, 'uca_manage', 'Administrer les utilisateurs', 520, 790, 250, 75, styles.usecaseAdmin);
  usecase(g, 'uca_role', 'Changer un rôle', 880, 760, 210, 70, styles.usecaseAdmin);
  usecase(g, 'uca_account', 'Activer / désactiver<br>un compte', 880, 890, 210, 70, styles.usecaseAdmin);
  usecase(g, 'uca_audit', 'Auditer les opérations<br>de sécurité', 1260, 790, 230, 75, styles.usecaseAdmin);

  association(g, 'uca_admin_gen', 'uca_admin', 'uca_user', '', '', '', 'generalization');
  for (const target of ['uca_login', 'uca_logout', 'uca_session', 'uca_profile', 'uca_password']) association(g, null, 'uca_user', target);
  association(g, null, 'uca_admin', 'uca_manage');
  association(g, null, 'uca_admin', 'uca_audit');
  relation(g, null, 'uca_role', 'uca_manage', '«extend» [modification demandée]', INCLUDE);
  relation(g, null, 'uca_account', 'uca_manage', '«extend» [changement d’état]', INCLUDE);
  relation(g, null, 'uca_kc_reset', 'uca_password', '«extend» [AUTH_MODE=KEYCLOAK]', INCLUDE);
  relation(g, null, 'uca_local_reset', 'uca_password', '«extend» [AUTH_MODE=LOCAL]', INCLUDE);
  association(g, null, 'uca_idp', 'uca_login');
  association(g, null, 'uca_idp', 'uca_session');
  association(g, null, 'uca_idp', 'uca_kc_reset');
  association(g, null, 'uca_mail', 'uca_local_reset');

  usecaseNote(g, 'uca_note', '<b>Production</b><br>Keycloak possède l’identité, le mot de passe et la session OIDC. Les entités User/RefreshToken/PasswordResetCode de l’application concernent le mode LOCAL et les environnements qui l’activent explicitement.', 1520, 990, 330, 180);
}

// ---------------------------------------------------------------------------
// 7. Configuration use cases
// ---------------------------------------------------------------------------
{
  const g = graph('UC_Configuration', 'UC_Configuration', 2480, 1440);
  title(g, 'Configuration des connexions et workflows', 'Diagramme de cas d’utilisation UML — aucune décision Kafka/RustFS/Hop/Spark n’est demandée à l’utilisateur métier.');
  boundary(g, 'ucc_system', 'Sous-système Configuration IOL', 350, 120, 1770, 1200);

  actor(g, 'ucc_user', 'Utilisateur', 70, 280);
  actor(g, 'ucc_admin', 'Administrateur', 70, 820);
  actor(g, 'ucc_source', 'Source configurable', 2260, 300);
  actor(g, 'ucc_target', 'SGBD destination', 2260, 790);

  usecase(g, 'ucc_conn', 'Gérer une connexion', 500, 190);
  usecase(g, 'ucc_test', 'Tester une connexion', 820, 190);
  usecase(g, 'ucc_workflow', 'Créer ou modifier<br>un workflow', 500, 460);
  usecase(g, 'ucc_sources', 'Définir les sources', 820, 410);
  usecase(g, 'ucc_discover', 'Découvrir le schéma', 1150, 330);
  usecase(g, 'ucc_mapping', 'Définir les mappings<br>et transformations', 1150, 500, 230, 75);
  usecase(g, 'ucc_destination', 'Choisir la destination<br>et les couches cibles', 1480, 410, 240, 75);
  usecase(g, 'ucc_schedule', 'Configurer une<br>planification', 820, 700);
  usecase(g, 'ucc_outbound', 'Configurer une livraison<br>OUTBOUND', 1150, 700, 230, 75, styles.usecaseInterop);
  usecase(g, 'ucc_template', 'Créer depuis un modèle', 1480, 700);
  usecase(g, 'ucc_validate', 'Valider la configuration', 820, 950);
  usecase(g, 'ucc_standard', 'Gérer les normes<br>et leurs termes', 1480, 970, 230, 75, styles.usecaseAdmin);

  association(g, 'ucc_admin_gen', 'ucc_admin', 'ucc_user', '', '', '', 'generalization');
  for (const target of ['ucc_conn', 'ucc_workflow', 'ucc_template']) association(g, null, 'ucc_user', target);
  association(g, null, 'ucc_admin', 'ucc_standard');
  relation(g, null, 'ucc_test', 'ucc_conn', '«extend» [test demandé]', INCLUDE);
  relation(g, null, 'ucc_workflow', 'ucc_sources', '«include»', INCLUDE);
  relation(g, null, 'ucc_workflow', 'ucc_mapping', '«include»', INCLUDE);
  relation(g, null, 'ucc_workflow', 'ucc_destination', '«include»', INCLUDE);
  relation(g, null, 'ucc_workflow', 'ucc_validate', '«include»', INCLUDE);
  relation(g, null, 'ucc_discover', 'ucc_sources', '«extend» [source introspectable]', INCLUDE);
  relation(g, null, 'ucc_schedule', 'ucc_workflow', '«extend» [exécution planifiée]', INCLUDE);
  relation(g, null, 'ucc_outbound', 'ucc_workflow', '«extend» [direction OUTBOUND]', INCLUDE);
  association(g, null, 'ucc_source', 'ucc_test');
  association(g, null, 'ucc_source', 'ucc_discover');
  association(g, null, 'ucc_target', 'ucc_test');
  association(g, null, 'ucc_target', 'ucc_destination');

  usecaseNote(g, 'ucc_note', '<b>Incrémental</b><br>Ce n’est pas une étape autonome du workflow. Lorsqu’il est supporté, le watermark appartient à la configuration technique d’une source et reste masqué dans le parcours métier standard.', 450, 1120, 760, 130);
}

// ---------------------------------------------------------------------------
// 8. Execution use cases
// ---------------------------------------------------------------------------
{
  const g = graph('UC_Execution', 'UC_Execution', 2400, 1410);
  title(g, 'Exécution et supervision', 'Diagramme de cas d’utilisation UML — le mode de traitement est déterminé par IOL, sans intervention de l’utilisateur.');
  boundary(g, 'uce_system', 'Sous-système Exécution IOL', 350, 120, 1700, 1160);

  actor(g, 'uce_user', 'Utilisateur', 70, 300);
  actor(g, 'uce_admin', 'Administrateur', 70, 850);

  usecase(g, 'uce_execute', 'Exécuter un workflow', 700, 220, 240, 75);
  usecase(g, 'uce_prepare', 'Préparer l’exécution', 1050, 210, 220, 70);
  usecase(g, 'uce_process', 'Traiter les données', 1370, 210, 220, 70);
  usecase(g, 'uce_finalize', 'Finaliser l’exécution', 1690, 210, 220, 70);

  usecase(g, 'uce_monitor', 'Suivre l’étape active', 500, 610);
  usecase(g, 'uce_history', 'Consulter l’historique', 850, 610);
  usecase(g, 'uce_error', 'Consulter l’erreur et<br>la console du flux', 1200, 610, 230, 75);
  usecase(g, 'uce_retry', 'Relancer une exécution', 1550, 610);
  usecase(g, 'uce_audit', 'Consulter les métriques<br>et événements d’audit', 850, 900, 240, 75, styles.usecaseAdmin);

  association(g, 'uce_admin_gen', 'uce_admin', 'uce_user', '', '', '', 'generalization');
  for (const target of ['uce_execute', 'uce_monitor', 'uce_history', 'uce_error', 'uce_retry']) association(g, null, 'uce_user', target);
  association(g, null, 'uce_admin', 'uce_audit');
  relation(g, null, 'uce_execute', 'uce_prepare', '«include»', INCLUDE);
  relation(g, null, 'uce_execute', 'uce_process', '«include»', INCLUDE);
  relation(g, null, 'uce_execute', 'uce_finalize', '«include»', INCLUDE);
  relation(g, null, 'uce_error', 'uce_monitor', '«extend» [statut FAILED]', INCLUDE);
  relation(g, null, 'uce_retry', 'uce_execute', '«extend» [relance demandée]', INCLUDE);

  usecaseNote(g, 'uce_note', '<b>Scénarios alternatifs du cas «Exécuter»</b><br>409 : workflow déjà réservé. 429 : capacité temporairement saturée avec Retry-After. Une poison pill ou l’épuisement des reprises produit un statut FAILED et une entrée DLQ. Ces résultats ne sont pas des cas d’utilisation autonomes.', 1330, 930, 620, 180);
}

// ---------------------------------------------------------------------------
// 9. Interoperability use cases
// ---------------------------------------------------------------------------
{
  const g = graph('UC_Interop', 'UC_Interop', 2580, 1490);
  title(g, 'Interopérabilité multi-domaines', 'Diagramme de cas d’utilisation UML — les organisations échangent des données; les canaux et adaptateurs restent internes au sous-système.');
  boundary(g, 'uci_system', 'Sous-système Interopérabilité IOL', 350, 120, 1840, 1240);

  actor(g, 'uci_sender', 'Système émetteur', 60, 260);
  actor(g, 'uci_receiver', 'Système destinataire', 60, 920);
  actor(g, 'uci_admin', 'Administrateur interop', 2340, 520);

  usecase(g, 'uci_inbound', 'Envoyer des données<br>à IOL', 560, 260, 250, 80);
  usecase(g, 'uci_outbound', 'Recevoir des données<br>depuis IOL', 560, 780, 250, 80);
  usecase(g, 'uci_validate', 'Valider l’échange', 970, 260, 220, 70);
  usecase(g, 'uci_trace', 'Tracer l’échange', 970, 780, 220, 70);
  usecase(g, 'uci_status', 'Consulter le résultat<br>d’un échange', 980, 510, 240, 80);
  usecase(g, 'uci_config', 'Configurer les partenaires<br>et les contrats', 1430, 330, 250, 80, styles.usecaseAdmin);
  usecase(g, 'uci_replay', 'Rejouer un échange', 1430, 700, 220, 75, styles.usecaseAdmin);

  association(g, null, 'uci_sender', 'uci_inbound');
  association(g, null, 'uci_receiver', 'uci_outbound');
  association(g, null, 'uci_admin', 'uci_config');
  association(g, null, 'uci_admin', 'uci_replay');
  association(g, null, 'uci_sender', 'uci_status');
  association(g, null, 'uci_receiver', 'uci_status');
  relation(g, null, 'uci_inbound', 'uci_validate', '«include»', INCLUDE);
  relation(g, null, 'uci_inbound', 'uci_trace', '«include»', INCLUDE);
  relation(g, null, 'uci_outbound', 'uci_validate', '«include»', INCLUDE);
  relation(g, null, 'uci_outbound', 'uci_trace', '«include»', INCLUDE);
  relation(g, null, 'uci_replay', 'uci_inbound', '«extend» [rejeu demandé]', INCLUDE);

  usecaseNote(g, 'uci_note', '<b>Règles de l’échange</b><br>Chaque échange est authentifié, validé, tracé par métadonnées et idempotent. Un rejeu conserve la même clé d’idempotence : IOL restitue le résultat antérieur ou empêche un second effet de bord. Les corps sensibles ne sont pas conservés durablement.', 420, 1170, 600, 130);
}

// ---------------------------------------------------------------------------
// 10. Domain class model
// ---------------------------------------------------------------------------
{
  const g = graph('Classes_v2', 'Classes_v2', 3000, 1700);
  title(g, 'Modèle de domaine IOL', 'Diagramme de classes UML conceptuel : objets métier, responsabilités et relations stables. Les frameworks, bases et services sont volontairement absents de cette vue.');


  classBox(g, 'cdv_user', 'User', ['- id : String', '- name : String', '- email : String'], [], 90, 220, 300);
  classBox(g, 'cdv_workflow', 'Workflow', ['- id : String', '- name : String', '- direction : WorkflowDirection', '- active : Boolean'], [], 500, 190, 380);
  classBox(g, 'cdv_source_def', 'Source', ['- id : String', '- name : String', '- type : SourceType', '- connectionRef : String'], [], 990, 190, 330);
  classBox(g, 'cdv_destination', 'Destination', ['- id : String', '- name : String', '- databaseDialect : DatabaseDialect', '- credentialRef : String'], [], 500, 520, 380);
  classBox(g, 'cdv_standard', 'Standard', ['- id : String', '- name : String', '- version : String', '- domain : String'], [], 990, 520, 330);
  classBox(g, 'cdv_term', 'StandardTerm', ['- name : String', '- dataType : String', '- required : Boolean', '- validationRule : String'], [], 1400, 520, 360);

  classBox(g, 'cdv_execution', 'Execution', ['- id : String', '- startedAt : String {ISO-8601}', '- status : ExecutionStatus', '- correlationId : String'], [], 100, 1010, 380);
  classBox(g, 'cdv_stage', 'StageResult', ['- name : String', '- status : String', '- rowsIn : Integer', '- rowsOut : Integer', '- error : String'], [], 600, 1010, 350);
  classBox(g, 'cdv_batch', 'DataBatch', ['- id : String', '- sequence : Integer', '- sizeBytes : Integer', '- format : String'], [], 1060, 1010, 340);
  classBox(g, 'cdv_artifact', 'ArtifactReference', ['- uri : String {URI}', '- mediaType : String', '- expiresAt : String {ISO-8601}'], [], 1510, 1010, 360);
  classBox(g, 'cdv_delivery', 'Delivery', ['- id : String', '- status : DeliveryStatus', '- attemptCount : Integer', '- deliveredAt : String {ISO-8601}'], [], 1980, 1010, 350);
  classBox(g, 'cdv_audit', 'AuditEntry', ['- id : String', '- action : String', '- actorId : String', '- occurredAt : String {ISO-8601}'], [], 2440, 1010, 350);

  classBox(g, 'cdv_direction', 'WorkflowDirection', ['INTERNAL', 'INBOUND', 'OUTBOUND'], [], 2070, 220, 310, { kind: '«enumeration»' });
  classBox(g, 'cdv_status', 'ExecutionStatus', ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'], [], 2440, 220, 310, { kind: '«enumeration»' });
  classBox(g, 'cdv_source_type', 'SourceType', ['DATABASE', 'FILE', 'API', 'PUSH'], [], 2070, 500, 310, { kind: '«enumeration»' });
  classBox(g, 'cdv_dialect', 'DatabaseDialect', ['POSTGRESQL', 'MYSQL', 'ORACLE', 'SQLSERVER', 'OTHER'], [], 2440, 500, 310, { kind: '«enumeration»' });
  classBox(g, 'cdv_delivery_status', 'DeliveryStatus', ['PENDING', 'DELIVERED', 'FAILED'], [], 2650, 650, 180, { kind: '«enumeration»' });

  association(g, null, 'cdv_user', 'cdv_workflow', 'owns', '1', '0..*', 'association');
  association(g, null, 'cdv_workflow', 'cdv_source_def', 'reads from', '1', '1..*', 'composition');
  association(g, null, 'cdv_workflow', 'cdv_destination', 'writes to', '1', '1', 'association');
  association(g, null, 'cdv_workflow', 'cdv_standard', 'conforms to', '0..*', '0..1', 'association');
  association(g, null, 'cdv_standard', 'cdv_term', 'defines', '1', '1..*', 'composition');
  association(g, null, 'cdv_workflow', 'cdv_execution', 'is executed as', '1', '0..*', 'association');
  association(g, null, 'cdv_execution', 'cdv_stage', 'reports', '1', '1..*', 'composition');
  association(g, null, 'cdv_execution', 'cdv_batch', 'produces', '1', '0..*', 'association');
  association(g, null, 'cdv_batch', 'cdv_artifact', 'may be represented by', '0..1', '0..1', 'association');
  association(g, null, 'cdv_execution', 'cdv_delivery', 'may result in', '1', '0..1', 'association');
  association(g, null, 'cdv_execution', 'cdv_audit', 'is traced by', '1', '0..*', 'association');
  association(g, null, 'cdv_workflow', 'cdv_direction', 'uses', '', '', 'dependency');
  association(g, null, 'cdv_execution', 'cdv_status', 'uses', '', '', 'dependency');
  association(g, null, 'cdv_source_def', 'cdv_source_type', 'uses', '', '', 'dependency');
  association(g, null, 'cdv_destination', 'cdv_dialect', 'uses', '', '', 'dependency');
  association(g, null, 'cdv_delivery', 'cdv_delivery_status', 'uses', '', '', 'dependency');

  note(g, 'cdv_note', '<b>Lecture UML</b><br>Une classe représente une responsabilité métier ou un objet du domaine. Les cardinalités se lisent aux extrémités des associations; les diamants noirs indiquent une composition. Les références de secret, de connexion et de stockage sont des identifiants conceptuels, jamais des mots de passe.', 80, 1510, 1800, 110);
}

// ---------------------------------------------------------------------------
// 11. Security class diagram
// ---------------------------------------------------------------------------
// Archived implementation-oriented views. Kept temporarily for traceability;
// the portable UML conceptual views are defined immediately before sequences.
if (false) {
{
  const g = graph('CD_Securite', 'CD_Securite', 2600, 1510);
  title(g, 'Classes de sécurité et modes d’authentification', 'Diagramme de classes UML — types applicatifs réels; Keycloak reste un composant externe dans les vues Architecture et Déploiement.');
  boundary(g, 'cds_common', 'Chaîne Spring Security commune', 30, 120, 1050, 1160);
  boundary(g, 'cds_local', 'AUTH_MODE=LOCAL', 1120, 120, 700, 1160);
  boundary(g, 'cds_kc', 'AUTH_MODE=KEYCLOAK', 1860, 120, 700, 1160);

  classBox(g, 'cds_config', 'SecurityConfig', [
    '- authMode: String',
  ], ['+ securityFilterChain(HttpSecurity): SecurityFilterChain'], 80, 210, 320, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cds_audit_filter', 'ApiAuditFilter', [
    '- auditService: AuditService',
  ], ['+ doFilterInternal(request, response, chain): void'], 500, 210, 320, { fill: C.red, stroke: C.redStroke });
  classBox(g, 'cds_audit_service', 'AuditService', [
    '- auditLogRepository: AuditLogRepository',
  ], ['+ log(...): void'], 500, 590, 320, { fill: C.red, stroke: C.redStroke });

  classBox(g, 'cds_auth', 'AuthService', [
    '- userRepository: UserRepository', '- jwtService: JwtService',
  ], ['+ login(request): AuthResponse', '+ refresh(token): AuthResponse', '+ forgotPassword(email): void', '+ resetPassword(request): void'], 1180, 190, 300, { fill: C.red, stroke: C.redStroke });
  classBox(g, 'cds_jwt', 'JwtService', ['- secret: String', '- expiration: long'], ['+ generateToken(user): String', '+ validateToken(token): boolean'], 1510, 190, 260, { fill: C.red, stroke: C.redStroke });
  classBox(g, 'cds_user', 'User', ['- id: Long', '- email: String', '- password: String', '- role: UserRole'], [], 1180, 590, 300, { fill: C.red, stroke: C.redStroke });
  classBox(g, 'cds_refresh', 'RefreshToken', ['- id: Long', '- userId: Long', '- tokenHash: String', '- expiresAt: Instant', '- revoked: boolean'], [], 1510, 550, 260, { fill: C.red, stroke: C.redStroke });
  classBox(g, 'cds_reset', 'PasswordResetCode', ['- id: Long', '- userId: Long', '- codeHash: String', '- expiresAt: Instant', '- consumed: boolean', '- attempts: int'], [], 1510, 850, 260, { fill: C.red, stroke: C.redStroke });

  classBox(g, 'cds_converter', 'KeycloakJwtAuthenticationConverter', [], ['+ convert(jwt): AbstractAuthenticationToken'], 1920, 190, 330, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cds_admin_client', 'KeycloakAdminClient', [
    '- serverUrl: String', '- realm: String', '- clientId: String',
  ], ['+ createUser(...): void', '+ updateRole(...): void', '+ resetPassword(...): void'], 1920, 540, 330, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cds_kc_user_service', 'UserService', [
    '- keycloakAdminClient: KeycloakAdminClient', '- authMode: String',
  ], ['+ createUser(...): UserDto', '+ updateRole(...): UserDto'], 1920, 900, 330, { fill: C.purple, stroke: C.purpleStroke });

  association(g, null, 'cds_config', 'cds_audit_filter', 'enregistre dans la chaîne', '', '', 'dependency');
  association(g, null, 'cds_audit_filter', 'cds_audit_service', 'journalise', '', '', 'dependency');
  association(g, null, 'cds_config', 'cds_auth', '[LOCAL]', '', '', 'dependency');
  association(g, null, 'cds_config', 'cds_converter', '[KEYCLOAK]', '', '', 'dependency');
  association(g, null, 'cds_auth', 'cds_jwt', 'émet et vérifie', '', '', 'dependency');
  association(g, null, 'cds_auth', 'cds_user', 'authentifie', '1', '0..*', 'dependency');
  association(g, null, 'cds_user', 'cds_refresh', 'userId', '1', '0..*');
  association(g, null, 'cds_user', 'cds_reset', 'userId', '1', '0..*');
  association(g, null, 'cds_kc_user_service', 'cds_admin_client', '[KEYCLOAK]', '', '', 'dependency');

  note(g, 'cds_note', '<b>Règle de production</b><br>AUTH_MODE=KEYCLOAK : les mots de passe et refresh tokens ne sont pas gérés par les entités locales. L’API valide les JWT OIDC et délègue l’administration d’identité à Keycloak.', 80, 1010, 820, 160);
}

// ---------------------------------------------------------------------------
// 12. Configuration class diagram
// ---------------------------------------------------------------------------
{
  const g = graph('CD_Configuration', 'CD_Configuration', 2860, 1600);
  title(g, 'Classes de configuration', 'Diagramme de classes UML — distinction entre documents MongoDB, objets embarqués et DTO de l’API.');
  boundary(g, 'cdc_entity_pkg', 'Documents et valeurs persistées', 30, 120, 1770, 1320);
  boundary(g, 'cdc_api_pkg', 'API et mapping', 1840, 120, 980, 1320);

  classBox(g, 'cdc_workflow', 'WorkflowConfig', [
    '- id: String', '- workflowName: String', '- protocol: String', '- standardId: String',
    '- direction: WorkflowDirection', '- outboundConfig: Map', '- schedule: Map',
    '- sources: List&lt;SourceDefinition&gt;', '- goldConfigGlobal: GoldConfigGlobal',
    '- destinationConnectionId: String', '- fields: List&lt;Map&gt;', '- executionMode: String',
    '- priority: int', '- estimatedRows: Long', '- active: boolean', '- createdBy: String',
  ], [], 90, 190, 370, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdc_source', 'SourceDefinition', ['- sourceName: String', '- config: Map&lt;String,Object&gt;'], [], 540, 190, 320, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdc_gold', 'GoldConfigGlobal', [
    '- enabled: Boolean', '- inputLayer: String', '- targetTableGold: String', '- eltScriptsGold: String',
    '- executionEngine: String', '- sparkSql: String', '- preSql: String', '- postSql: String', '- indexes: List&lt;Map&gt;',
  ], [], 540, 520, 320, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdc_dest', 'DestinationConnection', [
    '- id: String', '- name: String', '- dbType: String', '- host: String', '- port: String',
    '- database: String', '- username: String', '- credential: CredentialEnvelope', '- schema: String',
    '- additionalProperties: Map', '- default: boolean', '- createdBy: String',
  ], [], 970, 190, 350, { fill: C.orange, stroke: C.orangeStroke });
  classBox(g, 'cdc_envelope', 'CredentialEnvelope', [
    '- provider: String', '- keyName: String', '- ciphertext: String', '- keyVersion: Integer', '- encryptedAt: Instant', '- schemaVersion: Integer',
  ], [], 970, 650, 350, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdc_standard', 'Standard', ['- id: String', '- name: String', '- domain: StandardDomain', '- version: String', '- status: StandardStatus'], [], 1410, 190, 320, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdc_term', 'StandardTerm', ['- id: String', '- standardId: String', '- termName: String', '- dataType: DataType', '- required: Boolean', '- systemMappings: Map'], [], 1410, 560, 320, { fill: C.purple, stroke: C.purpleStroke });

  classBox(g, 'cdc_dto', 'WorkflowConfigDto', [
    '- id: String', '- workflowName: String', '- direction: WorkflowDirection', '- sources: List&lt;SourceDefinition&gt;',
    '- destinationConnectionId: String', '- fields: List&lt;FieldMappingDto&gt;',
  ], [], 1910, 190, 340, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdc_field_dto', 'FieldMappingDto', [
    '- sourceName: String', '- type: String', '- iolTerm: String', '- cleaningRules: Map',
    '- mappingType: String', '- sourceFields: List&lt;String&gt;', '- expression: String',
  ], [], 2380, 190, 350, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdc_mapper', 'WorkflowConfigMapper', [], [
    '+ toDto(entity): WorkflowConfigDto', '+ toEntity(dto): WorkflowConfig',
    '+ mapFieldsToDto(fields): List&lt;FieldMappingDto&gt;', '+ mapFieldsToEntity(fields): List&lt;Map&gt;',
  ], 1910, 650, 340, { kind: '«interface»', fill: C.gray, stroke: C.grayStroke });
  classBox(g, 'cdc_service', 'WorkflowService', [
    '- repository: WorkflowConfigRepository', '- destinationConnectionService: DestinationConnectionService',
  ], ['+ create(dto, user): WorkflowConfigDto', '+ update(id, dto, user): WorkflowConfigDto', '+ validate(...): void'], 2380, 650, 350, { fill: C.blue, stroke: C.blueStroke });

  association(g, null, 'cdc_workflow', 'cdc_source', 'sources', '1', '0..*', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_gold', 'goldConfigGlobal', '1', '0..1', 'composition');
  association(g, null, 'cdc_dest', 'cdc_envelope', 'credential', '1', '0..1', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_dest', 'résout destinationConnectionId', '0..*', '0..1', 'dependency');
  association(g, null, 'cdc_workflow', 'cdc_standard', 'résout standardId', '0..*', '0..1', 'dependency');
  association(g, null, 'cdc_standard', 'cdc_term', 'standardId', '1', '0..*');
  association(g, null, 'cdc_dto', 'cdc_field_dto', 'fields', '1', '0..*', 'composition');
  association(g, null, 'cdc_mapper', 'cdc_dto', 'mappe', '', '', 'dependency');
  association(g, null, 'cdc_mapper', 'cdc_workflow', 'mappe', '', '', 'dependency');
  association(g, null, 'cdc_service', 'cdc_mapper', 'utilise', '', '', 'dependency');
  association(g, null, 'cdc_service', 'cdc_dest', 'valide la destination', '', '', 'dependency');

  note(g, 'cdc_note', '<b>Correction importante</b><br>createdBy identifie le propriétaire; il ne relie jamais WorkflowConfig à DestinationConnection. Cette dernière est résolue exclusivement par destinationConnectionId.', 1910, 1090, 820, 150);
}

// ---------------------------------------------------------------------------
// 13. Interoperability class diagram
// ---------------------------------------------------------------------------
{
  const g = graph('CD_Interoperabilite', 'CD_Interoperabilite', 3100, 1780);
  title(g, 'Classes d’interopérabilité', 'Diagramme de classes UML — runtime Java des médiateurs spécialisés et services api-core; le worker Node générique est signalé comme composant hors diagramme de classes Java.');
  boundary(g, 'cdi_med_pkg', 'openhim-mediators / mediator-runtime (Java)', 30, 120, 1440, 1450);
  boundary(g, 'cdi_api_pkg', 'api-core (Java)', 1510, 120, 1560, 1450);

  classBox(g, 'cdi_adapter', 'DomainPayloadAdapter', [], [
    '+ adapt(body: byte[], contentType: String, requestPath: String, headers: HttpHeaders): AdaptedPayload',
    '+ standardName(): String',
  ], 90, 210, 430, { kind: '«interface»', fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_adapted', 'AdaptedPayload {Java record}', [
    '+ records: List&lt;Map&lt;String,Object&gt;&gt;', '+ metadata: Map&lt;String,Object&gt;',
  ], [], 590, 210, 360, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_controller', 'MediatorController', [
    '- adapter: DomainPayloadAdapter', '- iolMediatorClient: IolMediatorClient',
    '- responseFactory: OpenHimResponseFactory', '- properties: MediatorProperties',
  ], ['+ mediate(body, request): ResponseEntity&lt;Map&gt;'], 1020, 210, 380, { fill: C.purple, stroke: C.purpleStroke });

  classBox(g, 'cdi_fhir', 'FhirR4PayloadAdapter', [], ['+ adapt(...): AdaptedPayload', '+ standardName(): String'], 90, 650, 350, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_iso', 'Iso20022PayloadAdapter', [], ['+ adapt(...): AdaptedPayload', '+ standardName(): String'], 520, 650, 350, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_edfi', 'EdFiPayloadAdapter', [], ['+ adapt(...): AdaptedPayload', '+ standardName(): String'], 950, 650, 350, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_client', 'IolMediatorClient', ['- properties: MediatorProperties', '- restClient: RestClient'], [
    '+ handOff(payload, headers): Map&lt;String,Object&gt;',
  ], 520, 1050, 400, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_generic_api', 'GenericMediatorApi (HTTP)', [], [
    '+ handOffNdjson(records, metadata): Acceptance',
  ], 1000, 1050, 360, { kind: '«interface»', fill: C.gray, stroke: C.grayStroke });

  classBox(g, 'cdi_internal', 'InternalInteropExecutionService', [
    '- workflowConfigRepository: WorkflowConfigRepository', '- executionLogRepository: ExecutionLogRepository',
    '- kafkaPipelineEventService: KafkaPipelineEventService', '- inboundIdempotencyService: InboundIdempotencyService',
  ], [
    '+ prepareInboundExecution(standardId, request): InboundExecutionPrepareResponse',
    '+ prepareInboundExecutionStream(standardId, request, stream): InboundExecutionPrepareResponse',
  ], 1570, 190, 430, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdi_idem_service', 'InboundIdempotencyService', ['- repository: InboundIdempotencyRecordRepository', '- leaseSeconds: long'], [
    '+ claim(...): Claim', '+ attachExecution(claim, executionLogId): void', '+ complete(claim, response): void', '+ fail(claim, error): void',
  ], 2070, 190, 390, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_in_record', 'InboundIdempotencyRecord', [
    '- id: String', '- status: String', '- leaseOwner: String', '- leaseExpiresAt: Instant',
    '- payloadHash: String', '- executionLogId: String', '- dataTransport: String', '- commandPublished: boolean',
  ], [], 2530, 190, 380, { fill: C.purple, stroke: C.purpleStroke });

  classBox(g, 'cdi_internal_controller', 'InternalInteropController', [
    '- standardService: StandardService', '- interopExecutionService: InternalInteropExecutionService',
    '- outboundDeliveryLedgerService: OutboundDeliveryLedgerService',
  ], [
    '+ prepareInboundExecution(...): ResponseEntity', '+ prepareInboundExecutionStream(...): ResponseEntity',
    '+ denormalizeFromPivot(...): ResponseEntity', '+ claimOutboundDelivery(...): ResponseEntity',
  ], 1570, 450, 430, { fill: C.blue, stroke: C.blueStroke });

  classBox(g, 'cdi_event_service', 'KafkaPipelineEventService', [
    '- sourceDataTransportService: SourceDataTransportService', '- sourceLoadEstimatorService: SourceLoadEstimatorService',
  ], [
    '+ publishInboundExecutionRequested(...): InboundPublication',
    '+ publishInboundExecutionRequestedStream(...): InboundPublication',
    '+ publishOutboundDeliveryRequested(...): String',
  ], 1570, 720, 430, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdi_out_orch', 'OutboundDeliveryOrchestrationService', [
    '- jdbcTemplate: JdbcTemplate', '- sqlSafetyValidator: SqlSafetyValidator',
    '- kafkaPipelineEventService: KafkaPipelineEventService',
  ], ['+ requestDeliveryIfEligible(execLog, statusNode): boolean'], 2070, 720, 390, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdi_out_ledger', 'OutboundDeliveryLedgerService', [
    '- mongoTemplate: MongoTemplate', '- repository: OutboundDeliveryRecordRepository',
  ], ['+ claim(request): OutboundDeliveryLedgerResponse', '+ complete(request): OutboundDeliveryLedgerResponse', '+ fail(request): OutboundDeliveryLedgerResponse'], 2530, 720, 380, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdi_out_record', 'OutboundDeliveryRecord', [
    '- id: String', '- status: String', '- leaseOwner: String', '- leaseExpiresAt: Instant', '- attempts: int', '- deliveredAt: Instant', '- lastError: String',
  ], [], 2530, 1190, 380, { fill: C.purple, stroke: C.purpleStroke });

  association(g, null, 'cdi_fhir', 'cdi_adapter', 'réalise', '', '', 'realization');
  association(g, null, 'cdi_iso', 'cdi_adapter', 'réalise', '', '', 'realization');
  association(g, null, 'cdi_edfi', 'cdi_adapter', 'réalise', '', '', 'realization');
  association(g, null, 'cdi_adapter', 'cdi_adapted', 'produit', '', '', 'dependency');
  association(g, null, 'cdi_controller', 'cdi_adapter', 'utilise', '', '', 'dependency');
  association(g, null, 'cdi_controller', 'cdi_client', 'remet le pivot', '', '', 'dependency');
  association(g, null, 'cdi_client', 'cdi_generic_api', 'HTTPS / mTLS vers iol-mediator Node.js', '', '', 'dependency');
  association(g, null, 'cdi_generic_api', 'cdi_internal_controller', 'OAuth2 service account + mTLS', '', '', 'dependency');
  association(g, null, 'cdi_internal_controller', 'cdi_internal', 'délègue INBOUND', '', '', 'dependency');
  association(g, null, 'cdi_internal_controller', 'cdi_out_ledger', 'délègue le ledger OUTBOUND', '', '', 'dependency');
  association(g, null, 'cdi_internal', 'cdi_idem_service', 'claim avant effet', '', '', 'dependency');
  association(g, null, 'cdi_idem_service', 'cdi_in_record', 'persiste', '1', '0..*', 'dependency');
  association(g, null, 'cdi_internal', 'cdi_event_service', 'publie données puis commande', '', '', 'dependency');
  association(g, null, 'cdi_out_orch', 'cdi_event_service', 'publie livraison', '', '', 'dependency');
  association(g, null, 'cdi_out_ledger', 'cdi_out_record', 'persiste', '1', '0..*', 'dependency');

  note(g, 'cdi_note', '<b>Composant Node hors de cette vue de classes Java</b><br>iol-mediator valide le pivot générique, gère le streaming INBOUND et exécute le worker OUTBOUND avec contrôle SSRF, reprises, ledger persistant et DLQ. Il est représenté dans les diagrammes de composants et de séquence.', 90, 1320, 1210, 150);
}

// ---------------------------------------------------------------------------
// 14. Pipeline execution class diagram
// ---------------------------------------------------------------------------
{
  const g = graph('CD_Execution', 'CD_Execution', 3000, 1710);
  title(g, 'Classes d’exécution du pipeline-consumer', 'Diagramme de classes UML — idempotence, fencing, matérialisation Kafka/RustFS et choix Hop/Spark.');
  boundary(g, 'cde_listener_pkg', 'Consommation et concurrence', 30, 120, 1390, 1420);
  boundary(g, 'cde_runtime_pkg', 'Matérialisation et runtime', 1460, 120, 1500, 1420);

  classBox(g, 'cde_listener', 'KafkaEventListenerService', [
    '- orchestrator: PipelineOrchestrator', '- dataChunkStore: KafkaDataChunkStore',
    '- executionLockService: DistributedExecutionLockService', '- executionRegistry: PipelineExecutionRegistry',
    '- maxAttempts: int', '- retryBackoffSeconds: long',
  ], [
    '+ onHighPriorityCommand(record, ack): void', '+ onNormalPriorityCommand(record, ack): void', '+ onLowPriorityCommand(record, ack): void',
  ], 90, 190, 410, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cde_registry', 'PipelineExecutionRegistry', [
    '- mongoTemplate: MongoTemplate', '- owner: String', '- leaseSeconds: long', '- heartbeatSeconds: long',
  ], [
    '+ claim(execLogId, workflowId, payload): Claim', '+ heartbeat(claim, execLogId): Lease',
    '+ complete(execLogId, claim, lease, outcome): void', '+ release(execLogId, claim, cause): void',
  ], 570, 190, 390, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cde_claim', 'PipelineExecutionClaim', [
    '- executionLogId: String', '- workflowId: String', '- commandHash: String', '- owner: String',
    '- fencingToken: String', '- state: State', '- attempts: int', '- leaseExpiresAt: Instant',
    '- success: Boolean', '- errorMessage: String', '- durationMs: long',
  ], [], 1030, 190, 340, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cde_lock', 'DistributedExecutionLockService', [
    '- mode: String', '- jdbcUrl: String', '- acquireTimeoutSeconds: long', '- localLocks: ConcurrentHashMap',
  ], ['+ acquire(executionKey): LockHandle'], 570, 750, 390, { fill: C.green, stroke: C.greenStroke });

  classBox(g, 'cde_orch', 'PipelineOrchestrator', [
    '- dataChunkStore: KafkaDataChunkStore', '- objectStorageClient: ObjectStorageClient',
    '- runtimeCredentialClient: RuntimeCredentialClient', '- sparkRowThreshold: long', '- sparkDistributedReady: boolean',
  ], [
    '+ execute(command, workflowId, execLogId): boolean', '- resolveExecutionMode(command): ExecutionMode',
    '- materializeKafkaSources(command, execLogId, files): void',
  ], 1530, 190, 430, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cde_chunk_store', 'KafkaDataChunkStore', [
    '- mongoTemplate: MongoTemplate', '- tempDir: String', '- retentionHours: long',
  ], ['+ accept(event): void', '+ abort(event): void', '+ materialize(manifest, execLogId): Path', '+ cleanup(command): void'], 2030, 190, 390, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cde_chunk', 'StagedKafkaChunk', [
    '- id: String', '- transferId: String', '- sequence: int', '- payload: String', '- sha256: String', '- expiresAt: Instant',
  ], [], 2490, 190, 360, { fill: C.orange, stroke: C.orangeStroke });
  classBox(g, 'cde_object', 'ObjectStorageClient', [], ['+ materialize(manifest, execLogId): Path', '+ deleteTransferredObjects(command): int', '~ purgeExpiredTemporaryObjects(): void'], 1530, 760, 350, { fill: C.orange, stroke: C.orangeStroke });
  classBox(g, 'cde_credential', 'RuntimeCredentialClient', [], ['+ lease(destinationConnectionId, workflowId, execLogId): CredentialLease'], 1980, 760, 390, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cde_mode', 'ExecutionMode', ['LOCAL', 'SPARK'], [], 2490, 760, 360, { kind: '«enumeration»', fill: C.gray, stroke: C.grayStroke });

  association(g, null, 'cde_listener', 'cde_registry', 'claim / heartbeat / complete', '', '', 'dependency');
  association(g, null, 'cde_registry', 'cde_claim', 'persiste', '1', '0..*', 'dependency');
  association(g, null, 'cde_listener', 'cde_lock', 'verrouille executionKey', '', '', 'dependency');
  association(g, null, 'cde_listener', 'cde_chunk_store', 'stocke événements DATA_CHUNK', '', '', 'dependency');
  association(g, null, 'cde_listener', 'cde_orch', 'exécute PIPELINE_COMMAND', '', '', 'dependency');
  association(g, null, 'cde_chunk_store', 'cde_chunk', 'persiste jusqu’au succès durable', '1', '0..*', 'composition');
  association(g, null, 'cde_orch', 'cde_chunk_store', 'matérialise lots Kafka', '', '', 'dependency');
  association(g, null, 'cde_orch', 'cde_object', 'matérialise référence RustFS', '', '', 'dependency');
  association(g, null, 'cde_orch', 'cde_credential', 'loue credential cible', '', '', 'dependency');
  association(g, null, 'cde_orch', 'cde_mode', 'résout', '', '', 'dependency');

  note(g, 'cde_note', '<b>Invariant</b><br>PipelineOrchestrator refuse toute source JDBC/API qui n’est pas marquée transport_materialized. Les seuls chemins acceptés sont un artefact Kafka matérialisé ou une référence RustFS vérifiée.', 1530, 1180, 1220, 150);
}

// ---------------------------------------------------------------------------
// 15. AI class diagram
// ---------------------------------------------------------------------------
{
  const g = graph('CD_IA', 'CD_IA', 2700, 1510);
  title(g, 'Assistant IA Text-to-SQL', 'Diagramme de classes UML — génération SQL limitée au schéma, au dialecte cible et à une intention sans valeur métier.');
  boundary(g, 'cda_api_pkg', 'api-core', 30, 120, 2100, 1220);
  boundary(g, 'cda_external_pkg', 'Service externe', 2170, 120, 490, 1220);

  classBox(g, 'cda_controller', 'AiController', ['- aiService: AiService'], [
    '+ generateSql(request): ResponseEntity', '+ generateSchemaSql(request): ResponseEntity',
    '+ generateContextualSql(request): ResponseEntity', '+ generateAggregationSql(...): ResponseEntity', '+ generateCleaningSql(...): ResponseEntity',
  ], 90, 210, 360, { fill: C.teal, stroke: C.tealStroke });
  classBox(g, 'cda_service', 'AiService', [
    '- workflowService: WorkflowService', '- destinationConnectionService: DestinationConnectionService',
    '- aiPromptPrivacyGuard: AiPromptPrivacyGuard', '- sqlSafetyValidator: SqlSafetyValidator',
    '- queryHistoryRepository: QueryHistoryRepository', '- providerSequence: AtomicLong',
  ], [
    '+ generateSchemaOnlySql(request): AiSqlResponse', '+ generateSql(request): AiSqlResponse',
    '+ generateContextualSql(request): String', '- resolveDialect(...): String', '- callLlm(prompt, dialect): String',
  ], 530, 180, 450, { fill: C.teal, stroke: C.tealStroke });
  classBox(g, 'cda_privacy', 'AiPromptPrivacyGuard', [], ['+ validateAndNormalize(instruction): String'], 1070, 190, 350, { fill: C.red, stroke: C.redStroke });
  classBox(g, 'cda_sqlsafe', 'SqlSafetyValidator', [], [
    '+ validateReadOnlySql(sql): void', '+ validateOperationalSql(sql): void', '+ validateEltScript(sql, workflowId): void',
  ], 1510, 190, 350, { fill: C.red, stroke: C.redStroke });

  classBox(g, 'cda_request', 'SchemaOnlySqlRequest', [
    '- instruction: String', '- columns: List&lt;String&gt;', '- sourceTable: String', '- sourceTables: List&lt;String&gt;',
    '- targetTable: String', '- workflowId: String', '- destinationConnectionId: String',
    '- databaseType: String', '- generationType: GenerationType',
  ], [], 90, 750, 360, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cda_response', 'AiSqlResponse', ['- generatedSql: String'], [], 530, 750, 350, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cda_history', 'QueryHistory', ['- id: String', '- userPrompt: String', '- generatedSql: String', '- createdAt: Instant'], [], 970, 750, 350, { fill: C.orange, stroke: C.orangeStroke });
  classBox(g, 'cda_dest_service', 'DestinationConnectionService', [], ['+ getEntityById(connectionId): DestinationConnection'], 1410, 750, 390, { fill: C.orange, stroke: C.orangeStroke });
  classBox(g, 'cda_provider', 'ProviderSpec {private Java record}', ['- name: String', '- endpoint: String', '- apiKey: String', '- model: String'], [], 2240, 330, 340, { fill: C.purple, stroke: C.purpleStroke });

  association(g, null, 'cda_controller', 'cda_service', 'délègue', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_privacy', 'filtre avant appel externe', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_sqlsafe', 'valide le SQL généré', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_request', 'consomme', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_response', 'produit', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_history', 'persiste catégorie assainie + SQL', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_dest_service', 'résout le dialecte cible', '', '', 'dependency');
  association(g, null, 'cda_service', 'cda_provider', 'rotation et repli', '', '', 'dependency');

  note(g, 'cda_note_privacy', '<b>Contexte envoyé au LLM</b><br>Instruction normalisée sans valeur ni exemple, noms de table/colonnes, types et dialecte du SGBD destination. Aucun échantillon, corps de ligne, credential ou URL de connexion.', 90, 1110, 950, 140);
  note(g, 'cda_note_ui', '<b>Interface utilisateur</b><br>Le frontend n’affiche ni fournisseur, ni modèle, ni clé. ProviderSpec est strictement privé à AiService.', 1110, 1110, 650, 140);
}

// ---------------------------------------------------------------------------
// 16. Source transport class diagram
// ---------------------------------------------------------------------------
{
  const g = graph('CD_Transport', 'CD_Transport', 3180, 1870);
  title(g, 'Classes du transport source-gateway', 'Diagramme de classes UML — ordre minimal, claim persistant avec fencing, lecture source, Kafka/RustFS et commande publiée en dernier.');
  boundary(g, 'cdt_listener_pkg', 'Entrée et idempotence', 30, 120, 1160, 1550);
  boundary(g, 'cdt_pipeline_pkg', 'Pipeline de transport', 1230, 120, 1910, 1550);

  classBox(g, 'cdt_order', 'TransportOrder {Java record}', [
    '+ eventType: String', '+ schemaVersion: Integer', '+ organizationId: String', '+ workflowId: String',
    '+ workflowRevision: String', '+ execLogId: String', '+ executionKey: String', '+ requestedAt: String',
    '+ requestedBy: String', '+ priority: Integer',
  ], ['+ validate(): void'], 90, 190, 390, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdt_listener', 'TransportOrderListener', [
    '- executionGuard: TransportExecutionGuard', '- dlqTopic: String', '- maxAttempts: int', '- retryBackoffSeconds: long',
  ], ['+ onTransportOrder(record, ack): void'], 560, 190, 370, { fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdt_guard', 'TransportExecutionGuard', [], [
    '+ claim(order): Claim', '+ transportAndPublish(order, claim): void',
    '+ release(order, claim, cause): void', '+ failPermanently(order, claim, cause): void',
  ], 90, 760, 420, { kind: '«interface»', fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdt_mongo_guard', 'MongoTransportExecutionGuard', [
    '- mongoTemplate: MongoTemplate', '- transportPipeline: TransportPipeline', '- statusPublisher: TransportStatusPublisher',
    '- owner: String', '- claimLeaseSeconds: long', '- claimHeartbeatSeconds: long',
  ], [
    '+ claim(order): Claim', '+ transportAndPublish(order, claim): void', '+ release(order, claim, cause): void', '+ failPermanently(order, claim, cause): void',
  ], 590, 720, 500, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdt_claim', 'TransportClaim', [
    '- executionLogId: String', '- workflowId: String', '- organizationId: String', '- owner: String',
    '- fencingToken: String', '- attempts: int', '- status: Status', '- leaseExpiresAt: Instant', '- failureReason: String',
  ], ['+ leaseExpired(now): boolean'], 330, 1240, 430, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdt_claim_status', 'Status', ['IN_PROGRESS', 'COMPLETED', 'FAILED'], [], 800, 1300, 290, { kind: '«enumeration»', fill: C.gray, stroke: C.grayStroke });

  classBox(g, 'cdt_pipeline_if', 'TransportPipeline', [], ['+ run(order, assertOwnership): void'], 1300, 190, 350, { kind: '«interface»', fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdt_pipeline', 'DefaultTransportPipeline', [
    '- workflowReader: WorkflowConfigReader', '- commandBuilder: CommandBuilder', '- transportService: SourceDataTransportService',
    '- kafkaTemplate: KafkaTemplate',
  ], ['+ run(order, assertOwnership): void'], 1720, 180, 410, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdt_reader', 'WorkflowConfigReader', [], ['+ requireById(workflowId): WorkflowConfig'], 2200, 190, 350, { kind: '«interface»', fill: C.blue, stroke: C.blueStroke });
  classBox(g, 'cdt_builder', 'CommandBuilder', [
    '- credentialResolver: SourceCredentialResolver', '- watermarkReader: ExecutionWatermarkReader',
    '- sourceLoadEstimatorService: SourceLoadEstimatorService', '- sparkRowThreshold: long', '- sparkByteThreshold: long',
  ], ['+ publishExecutionRequested(workflow, execLogId): String', '+ topicForPriority(priority): String'], 2620, 180, 420, { fill: C.blue, stroke: C.blueStroke });

  classBox(g, 'cdt_transport', 'SourceDataTransportService', [
    '- objectStorageService: ObjectStorageService', '- sourceConnectionLimiter: SourceConnectionLimiter',
    '- chunkBytes: int', '- rowBatchRows: int', '- maxRowBatchEventBytes: int',
    '- inboundBigDataRowThreshold: long', '- inboundBigDataByteThreshold: long',
  ], ['+ publishSourceData(topic, kafkaKey, workflowId, execLogId, command): List&lt;Map&gt;', '+ publishInboundData(...): List&lt;Map&gt;'], 1300, 720, 440, { fill: C.green, stroke: C.greenStroke });
  classBox(g, 'cdt_object', 'ObjectStorageService', [], ['+ isEnabled(): boolean', '+ assertReady(): void', '+ store(path, workflowId, execLogId, sourceIndex, fileName, contentType): StoredObject', '+ storeStreaming(...): StoredObject'], 1800, 760, 330, { fill: C.orange, stroke: C.orangeStroke });
  classBox(g, 'cdt_resolver', 'SourceCredentialResolver', [
    '- mongoTemplate: MongoTemplate', '- credentialCipher: CredentialCipher', '- allowLegacyPlaintext: boolean',
  ], ['+ requireConnectionForOwner(id, owner): DestinationConnection', '+ resolveRuntimeHost(host): String'], 2200, 720, 390, { fill: C.purple, stroke: C.purpleStroke });
  classBox(g, 'cdt_cipher', 'CredentialCipher', [], [
    '+ decrypt(envelope, context): String', '+ provider(): String', '+ assertReady(): void',
  ], 2680, 760, 350, { kind: '«interface»', fill: C.purple, stroke: C.purpleStroke });

  association(g, null, 'cdt_listener', 'cdt_order', 'désérialise et valide', '', '', 'dependency');
  association(g, null, 'cdt_listener', 'cdt_guard', 'claim avant traitement', '', '', 'dependency');
  association(g, null, 'cdt_mongo_guard', 'cdt_guard', 'réalise', '', '', 'realization');
  association(g, null, 'cdt_mongo_guard', 'cdt_claim', 'persiste', '1', '0..*', 'composition');
  association(g, null, 'cdt_claim', 'cdt_claim_status', 'status', '0..*', '1', 'dependency');
  association(g, null, 'cdt_mongo_guard', 'cdt_pipeline_if', 'exécute si claim acquis', '', '', 'dependency');
  association(g, null, 'cdt_pipeline', 'cdt_pipeline_if', 'réalise', '', '', 'realization');
  association(g, null, 'cdt_pipeline', 'cdt_reader', '1. lit configuration', '', '', 'dependency');
  association(g, null, 'cdt_pipeline', 'cdt_builder', '2. construit commande assainie', '', '', 'dependency');
  association(g, null, 'cdt_pipeline', 'cdt_transport', '3. transporte données', '', '', 'dependency');
  association(g, null, 'cdt_transport', 'cdt_object', '[big data / record trop grand]', '', '', 'dependency');
  association(g, null, 'cdt_builder', 'cdt_resolver', 'résout les sources', '', '', 'dependency');
  association(g, null, 'cdt_resolver', 'cdt_cipher', 'déchiffre avec contexte', '', '', 'dependency');

  note(g, 'cdt_note', '<b>Ordre transactionnel imposé par TransportPipeline</b><br>1 lire workflow; 2 résoudre credential; 3 transporter Kafka/RustFS; 4 purger les identifiants source; 5 vérifier l’absence de secret; 6 publier PIPELINE_COMMAND comme dernier effet; 7 acquitter TransportOrder.', 1330, 1300, 1640, 170);
}
}

// ---------------------------------------------------------------------------
// 11-16. Portable UML conceptual class views
// ---------------------------------------------------------------------------
{
  const g = graph('CD_Securite', 'CD_Securite', 2600, 1460);
  title(g, 'Modèle de sécurité', 'Vue UML portable : comptes, rôles, sessions, demandes de réinitialisation et fournisseurs d’identité.');

  classBox(g, 'cds_account', 'Account', ['- id : String', '- email : String', '- state : AccountState', '- roles : Role [1..*]'], ['+ changeState(state : AccountState) : void'], 100, 210, 330);
  classBox(g, 'cds_role', 'Role', ['ADMIN', 'USER'], [], 550, 210, 300, { kind: '«enumeration»' });
  classBox(g, 'cds_session', 'AuthenticationSession', ['- id : String', '- issuedAt : String {ISO-8601}', '- expiresAt : String {ISO-8601}', '- revoked : Boolean'], ['+ revoke() : void'], 980, 210, 390);
  classBox(g, 'cds_reset', 'PasswordResetRequest', ['- id : String', '- requestedAt : String {ISO-8601}', '- expiresAt : String {ISO-8601}', '- consumed : Boolean'], ['+ consume() : void'], 100, 620, 390);
  classBox(g, 'cds_audit', 'SecurityAuditEntry', ['- id : String', '- action : String', '- occurredAt : String {ISO-8601}'], [], 550, 620, 320);
  classBox(g, 'cds_state', 'AccountState', ['ACTIVE', 'DISABLED'], [], 980, 620, 390, { kind: '«enumeration»' });

  classBox(g, 'cds_provider', 'IdentityProvider', [], ['+ authenticate(email : String, secret : String) : Boolean', '+ revoke(session : AuthenticationSession) : void'], 1650, 210, 420, { kind: '«interface»' });
  classBox(g, 'cds_local', 'LocalIdentityProvider', [], ['+ authenticate(email : String, secret : String) : Boolean'], 2150, 210, 330);
  classBox(g, 'cds_keycloak', 'ExternalIdentityProvider', ['- issuer : String {URI}'], ['+ authenticate(email : String, secret : String) : Boolean'], 2150, 620, 330);

  association(g, null, 'cds_account', 'cds_session', 'opens', '1', '0..*', 'composition');
  association(g, null, 'cds_account', 'cds_reset', 'requests', '1', '0..*', 'composition');
  association(g, null, 'cds_account', 'cds_audit', 'is recorded in', '0..1', '0..*', 'association');
  association(g, null, 'cds_local', 'cds_provider', 'realizes', '', '', 'realization');
  association(g, null, 'cds_keycloak', 'cds_provider', 'realizes', '', '', 'realization');
  association(g, null, 'cds_provider', 'cds_account', 'authenticates', '', '', 'dependency');
  association(g, null, 'cds_account', 'cds_role', 'uses', '', '', 'dependency');
  association(g, null, 'cds_account', 'cds_state', 'uses', '', '', 'dependency');
}

{
  const g = graph('CD_Configuration', 'CD_Configuration', 2860, 1540);
  title(g, 'Modèle de configuration d’un workflow', 'Vue UML portable : les choix de source, cible, mapping, planification et contrat sont des objets de configuration.');

  classBox(g, 'cdc_workflow', 'WorkflowDefinition', ['- id : String', '- name : String', '- direction : WorkflowDirection', '- priority : Integer', '- active : Boolean'], ['+ validate() : Boolean'], 100, 210, 390);
  classBox(g, 'cdc_source', 'SourceBinding', ['- name : String', '- type : String', '- connectionRef : String'], [], 600, 210, 350);
  classBox(g, 'cdc_target', 'TargetBinding', ['- connectionRef : String', '- schema : String', '- table : String'], [], 1060, 210, 350);
  classBox(g, 'cdc_schedule', 'SchedulePolicy', ['- expression : String', '- enabled : Boolean'], [], 600, 620, 350);
  classBox(g, 'cdc_transform', 'TransformationPolicy', ['- layer : String', '- ruleSet : String'], [], 1060, 620, 350);
  classBox(g, 'cdc_direction', 'WorkflowDirection', ['INTERNAL', 'INBOUND', 'OUTBOUND'], [], 100, 700, 390, { kind: '«enumeration»' });

  classBox(g, 'cdc_mapping', 'FieldMapping', ['- sourcePath : String', '- targetName : String', '- expression : String', '- required : Boolean'], [], 1820, 210, 400);
  classBox(g, 'cdc_contract', 'DataContract', ['- name : String', '- version : String'], [], 2320, 210, 400);
  classBox(g, 'cdc_standard', 'StandardReference', ['- standardId : String', '- version : String'], [], 1820, 620, 400);
  classBox(g, 'cdc_term', 'StandardTerm', ['- name : String', '- dataType : String', '- validationRule : String'], [], 2320, 620, 400);

  association(g, null, 'cdc_workflow', 'cdc_source', 'reads from', '1', '1..*', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_target', 'writes to', '1', '1', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_schedule', 'is triggered by', '1', '0..1', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_transform', 'applies', '1', '0..1', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_mapping', 'declares', '1', '0..*', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_contract', 'conforms to', '0..*', '0..1', 'association');
  association(g, null, 'cdc_contract', 'cdc_standard', 'references', '1', '1', 'association');
  association(g, null, 'cdc_standard', 'cdc_term', 'defines', '1', '1..*', 'composition');
  association(g, null, 'cdc_workflow', 'cdc_direction', 'uses', '', '', 'dependency');
}

{
  const g = graph('CD_Interoperabilite', 'CD_Interoperabilite', 3000, 1600);
  title(g, 'Modèle d’interopérabilité', 'Vue UML portable : message externe, contrat, pivot, adaptation et livraison.');

  classBox(g, 'cdi_exchange', 'InteroperabilityExchange', ['- id : String', '- direction : ExchangeDirection', '- correlationId : String', '- receivedAt : String {ISO-8601}'], ['+ accept() : void', '+ reject(reason : String) : void'], 100, 210, 400);
  classBox(g, 'cdi_message', 'ExternalMessage', ['- mediaType : String', '- sourceSystem : String', '- payloadReference : String {URI}'], [], 600, 210, 370);
  classBox(g, 'cdi_contract', 'InteroperabilityContract', ['- standard : String', '- version : String', '- profile : String'], ['+ validate(message : ExternalMessage) : Boolean'], 1060, 210, 390);
  classBox(g, 'cdi_record', 'CanonicalRecord', ['- schemaVersion : String', '- provenance : String'], ['+ validate(contract : InteroperabilityContract) : Boolean'], 100, 650, 400);
  classBox(g, 'cdi_delivery', 'DeliveryRequest', ['- idempotencyKey : String', '- targetSystem : String', '- status : DeliveryStatus'], ['+ markDelivered() : void'], 600, 650, 390);
  classBox(g, 'cdi_endpoint', 'PartnerEndpoint', ['- name : String', '- address : String {URI}', '- protocol : String'], ['+ isAllowed() : Boolean'], 1060, 650, 390);

  classBox(g, 'cdi_adapter', 'MessageAdapter', [], ['+ supports(contract : InteroperabilityContract) : Boolean', '+ toCanonical(message : ExternalMessage) : CanonicalRecord', '+ fromCanonical(record : CanonicalRecord) : ExternalMessage'], 1880, 210, 440, { kind: '«interface»' });
  classBox(g, 'cdi_fhir', 'FhirAdapter', [], [], 2400, 210, 350);
  classBox(g, 'cdi_iso', 'Iso20022Adapter', [], [], 1880, 650, 390);
  classBox(g, 'cdi_edfi', 'EdFiAdapter', [], [], 2400, 650, 350);
  classBox(g, 'cdi_direction', 'ExchangeDirection', ['INBOUND', 'OUTBOUND'], [], 1880, 1010, 390, { kind: '«enumeration»' });
  classBox(g, 'cdi_status', 'DeliveryStatus', ['PENDING', 'DELIVERED', 'FAILED'], [], 2360, 1010, 390, { kind: '«enumeration»' });

  association(g, null, 'cdi_exchange', 'cdi_message', 'contains', '1', '1..*', 'composition');
  association(g, null, 'cdi_exchange', 'cdi_contract', 'is governed by', '1', '1', 'association');
  association(g, null, 'cdi_exchange', 'cdi_record', 'produces', '1', '0..*', 'composition');
  association(g, null, 'cdi_exchange', 'cdi_delivery', 'may create', '1', '0..*', 'composition');
  association(g, null, 'cdi_delivery', 'cdi_endpoint', 'targets', '0..*', '1', 'association');
  association(g, null, 'cdi_fhir', 'cdi_adapter', 'realizes', '', '', 'realization');
  association(g, null, 'cdi_iso', 'cdi_adapter', 'realizes', '', '', 'realization');
  association(g, null, 'cdi_edfi', 'cdi_adapter', 'realizes', '', '', 'realization');
  association(g, null, 'cdi_exchange', 'cdi_direction', 'uses', '', '', 'dependency');
  association(g, null, 'cdi_delivery', 'cdi_status', 'uses', '', '', 'dependency');
}

{
  const g = graph('CD_Execution', 'CD_Execution', 3000, 1600);
  title(g, 'Modèle d’exécution', 'Vue UML portable : exécution, étapes, bail de concurrence, lots et moteurs interchangeables.');

  classBox(g, 'cde_execution', 'PipelineExecution', ['- id : String', '- workflowId : String', '- status : ExecutionStatus', '- startedAt : String {ISO-8601}'], [], 100, 210, 400);
  classBox(g, 'cde_policy', 'ExecutionPolicy', ['- estimatedRows : Integer', '- estimatedBytes : Integer', '- preferredMode : ExecutionMode'], [], 600, 210, 400);
  classBox(g, 'cde_stage', 'ProcessingStage', ['- name : String', '- order : Integer', '- state : String'], [], 1100, 210, 350);
  classBox(g, 'cde_lease', 'ExecutionLease', ['- owner : String', '- fencingToken : String', '- expiresAt : String {ISO-8601}'], [], 100, 650, 400);
  classBox(g, 'cde_batch', 'DataBatch', ['- sequence : Integer', '- rowCount : Integer', '- byteSize : Integer', '- format : String'], [], 600, 650, 400);
  classBox(g, 'cde_artifact', 'TemporaryArtifact', ['- location : String {URI}', '- mediaType : String', '- expiresAt : String {ISO-8601}'], [], 1100, 650, 350);

  classBox(g, 'cde_engine', 'ExecutionEngine', [], [], 1940, 210, 400, { kind: '«interface»' });
  classBox(g, 'cde_local', 'LocalExecutionEngine', [], [], 2410, 210, 350);
  classBox(g, 'cde_distributed', 'DistributedExecutionEngine', [], [], 1940, 650, 400);
  classBox(g, 'cde_mode', 'ExecutionMode', ['LOCAL', 'DISTRIBUTED'], [], 2410, 650, 350, { kind: '«enumeration»' });
  classBox(g, 'cde_status', 'ExecutionStatus', ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'], [], 1940, 1010, 400, { kind: '«enumeration»' });

  association(g, null, 'cde_execution', 'cde_policy', 'uses', '1', '1', 'association');
  association(g, null, 'cde_execution', 'cde_stage', 'contains', '1', '1..*', 'composition');
  association(g, null, 'cde_execution', 'cde_lease', 'holds', '1', '0..1', 'composition');
  association(g, null, 'cde_stage', 'cde_batch', 'consumes and produces', '0..*', '0..*', 'association');
  association(g, null, 'cde_batch', 'cde_artifact', 'may be stored as', '0..*', '0..1', 'association');
  association(g, null, 'cde_local', 'cde_engine', 'realizes', '', '', 'realization');
  association(g, null, 'cde_distributed', 'cde_engine', 'realizes', '', '', 'realization');
  association(g, null, 'cde_execution', 'cde_status', 'uses', '', '', 'dependency');
  association(g, null, 'cde_policy', 'cde_mode', 'uses', '', '', 'dependency');
}

{
  const g = graph('CD_IA', 'CD_IA', 2800, 1500);
  title(g, 'Modèle de génération SQL', 'Vue UML portable : intention, schéma, politique, requête générée et fournisseur de modèle.');

  classBox(g, 'cda_request', 'SqlGenerationRequest', ['- intention : String', '- targetTable : String', '- dialect : DatabaseDialect'], [], 100, 210, 390);
  classBox(g, 'cda_schema', 'SchemaContext', ['- relationCount : Integer'], [], 600, 210, 390);
  classBox(g, 'cda_table', 'TableDescriptor', ['- name : String'], [], 1100, 210, 350);
  classBox(g, 'cda_column', 'ColumnDescriptor', ['- name : String', '- type : String', '- nullable : Boolean'], [], 600, 600, 390);
  classBox(g, 'cda_policy', 'GenerationPolicy', ['- allowDataManipulation : Boolean', '- allowDataDefinition : Boolean', '- maxComplexity : Integer'], [], 1100, 600, 350);

  classBox(g, 'cda_generator', 'SqlGenerator', [], [], 1840, 210, 400, { kind: '«interface»' });
  classBox(g, 'cda_query', 'SqlQuery', ['- text : String', '- dialect : DatabaseDialect', '- explanation : String'], [], 2310, 210, 360);
  classBox(g, 'cda_history', 'QueryHistoryEntry', ['- id : String', '- createdAt : String {ISO-8601}', '- requestSummary : String'], [], 1840, 650, 400);
  classBox(g, 'cda_provider', 'LanguageModelProvider', [], [], 2310, 650, 360, { kind: '«interface»' });
  classBox(g, 'cda_dialect', 'DatabaseDialect', ['POSTGRESQL', 'MYSQL', 'ORACLE', 'SQLSERVER', 'OTHER'], [], 1840, 980, 400, { kind: '«enumeration»' });

  association(g, null, 'cda_request', 'cda_schema', 'uses', '1', '1', 'association');
  association(g, null, 'cda_schema', 'cda_table', 'describes', '1', '1..*', 'composition');
  association(g, null, 'cda_table', 'cda_column', 'contains', '1', '1..*', 'composition');
  association(g, null, 'cda_request', 'cda_policy', 'is checked by', '1', '1', 'association');
  association(g, null, 'cda_generator', 'cda_query', 'creates', '', '', 'dependency');
  association(g, null, 'cda_generator', 'cda_provider', 'may invoke', '', '', 'dependency');
  association(g, null, 'cda_query', 'cda_history', 'is recorded as', '0..1', '0..*', 'association');
  association(g, null, 'cda_request', 'cda_dialect', 'uses', '', '', 'dependency');
  association(g, null, 'cda_query', 'cda_dialect', 'uses', '', '', 'dependency');
}

{
  const g = graph('CD_Transport', 'CD_Transport', 3000, 1600);
  title(g, 'Modèle de transport des données', 'Vue UML portable : demande, session, claim, lots, manifeste et canaux de transport.');

  classBox(g, 'cdt_request', 'TransportRequest', ['- id : String', '- workflowId : String', '- executionId : String', '- priority : Integer'], [], 100, 210, 390);
  classBox(g, 'cdt_source', 'SourceEndpoint', ['- type : String', '- connectionRef : String'], [], 600, 210, 370);
  classBox(g, 'cdt_session', 'TransportSession', ['- id : String', '- state : SessionState', '- startedAt : String {ISO-8601}'], [], 1060, 210, 350);
  classBox(g, 'cdt_claim', 'TransportClaim', ['- owner : String', '- fencingToken : String', '- attempts : Integer', '- expiresAt : String {ISO-8601}'], [], 100, 650, 390);
  classBox(g, 'cdt_batch', 'DataBatch', ['- sequence : Integer', '- rowCount : Integer', '- byteSize : Integer', '- checksum : String'], [], 600, 650, 370);
  classBox(g, 'cdt_manifest', 'ObjectManifest', ['- uri : String {URI}', '- mediaType : String', '- sizeBytes : Integer', '- expiresAt : String {ISO-8601}'], [], 1060, 650, 350);

  classBox(g, 'cdt_channel', 'TransportChannel', [], [], 1740, 210, 420, { kind: '«interface»' });
  classBox(g, 'cdt_broker', 'MessageBrokerChannel', ['- topic : String', '- maxMessageBytes : Integer'], [], 2240, 210, 370);
  classBox(g, 'cdt_object', 'ObjectStorageChannel', ['- bucket : String', '- retentionHours : Integer'], [], 2240, 650, 370);
  classBox(g, 'cdt_command', 'TransportCommand', ['- executionId : String', '- dataReference : String', '- mode : ExecutionMode'], [], 1740, 650, 420);
  classBox(g, 'cdt_mode', 'ExecutionMode', ['LOCAL', 'DISTRIBUTED'], [], 1740, 1010, 420, { kind: '«enumeration»' });
  classBox(g, 'cdt_state', 'SessionState', ['OPEN', 'COMPLETED', 'FAILED'], [], 2240, 1010, 370, { kind: '«enumeration»' });

  association(g, null, 'cdt_request', 'cdt_source', 'reads from', '1', '1..*', 'association');
  association(g, null, 'cdt_request', 'cdt_session', 'creates', '1', '1', 'composition');
  association(g, null, 'cdt_request', 'cdt_claim', 'is protected by', '1', '0..1', 'composition');
  association(g, null, 'cdt_session', 'cdt_batch', 'emits', '1', '0..*', 'association');
  association(g, null, 'cdt_batch', 'cdt_manifest', 'may be represented by', '0..*', '0..1', 'association');
  association(g, null, 'cdt_broker', 'cdt_channel', 'realizes', '', '', 'realization');
  association(g, null, 'cdt_object', 'cdt_channel', 'realizes', '', '', 'realization');
  association(g, null, 'cdt_channel', 'cdt_command', 'transports last', '', '', 'dependency');
  association(g, null, 'cdt_session', 'cdt_state', 'uses', '', '', 'dependency');
  association(g, null, 'cdt_command', 'cdt_mode', 'uses', '', '', 'dependency');
}

// ---------------------------------------------------------------------------
// 17. Internal execution sequence
// ---------------------------------------------------------------------------
{
  const g = graph('Seq_Execution', 'Seq_Execution', 2900, 2120);
  title(g, 'Soumission et exécution d’un workflow interne', 'Diagramme de séquence UML — vue de synthèse du chemin réellement implémenté : soumission asynchrone, transport contrôlé, exécution et mise à jour du journal.');

  const s = sequence(g);
  s.actor('seqe_user', 'Utilisateur', 20, 125);
  s.actor('seqe_ui', 'Frontend', 190, 135);
  s.actor('seqe_api', 'api-core', 380, 145);
  s.actor('seqe_mongo', 'MongoDB<br>configuration + registres', 585, 165);
  s.actor('seqe_kafka', 'Kafka', 810, 125);
  s.actor('seqe_gateway', 'source-gateway', 995, 150);
  s.actor('seqe_source', 'Source métier', 1200, 140);
  s.actor('seqe_rustfs', 'RustFS', 1395, 125);
  s.actor('seqe_consumer', 'pipeline-consumer', 1580, 160);
  s.actor('seqe_postgres', 'PostgreSQL<br>verrou distribué', 1800, 150);
  s.actor('seqe_runtime', 'Hop / Spark', 2010, 140);
  s.actor('seqe_target', 'Base de destination', 2205, 150);
  s.actor('seqe_status', 'api-core<br>listener de statut', 2410, 155);

  // Soumission et contrôles préalables
  s.msg('seqe_user', 'seqe_ui', '1. Demander l’exécution', 'sync');
  s.msg('seqe_ui', 'seqe_api', '2. POST /api/orchestrator/run/{id}', 'sync');
  s.msg('seqe_api', 'seqe_mongo', '3. Charger le workflow et contrôler les droits', 'sync');
  s.msg('seqe_mongo', 'seqe_api', '4. Workflow trouvé', 'reply');
  s.msg('seqe_api', 'seqe_mongo', '5. Rechercher une exécution RUNNING', 'sync');
  s.open('seqe_alt_active', 'break [exécution RUNNING déjà présente]');
  s.msg('seqe_mongo', 'seqe_api', '[déjà actif] ExecutionLog existant', 'reply');
  s.msg('seqe_api', 'seqe_ui', '409 Workflow déjà en cours', 'reply');
  s.close();
  s.msg('seqe_mongo', 'seqe_api', '[aucun actif] continuer', 'reply');
  s.msg('seqe_api', 'seqe_mongo', '6. Créer ExecutionLog(RUNNING, QUEUED)', 'sync');
  s.msg('seqe_api', 'seqe_api', '7. pipelineExecutionExecutor.execute(task)', 'sync');
  s.open('seqe_alt_capacity', 'break [RejectedExecutionException]');
  s.msg('seqe_api', 'seqe_mongo', '[rejeté] Marquer ExecutionLog FAILED / SUBMISSION', 'sync');
  s.msg('seqe_api', 'seqe_ui', '429 Retry-After: 30', 'reply');
  s.close();

  // Accusé de réception et publication de l'ordre de transport
  s.open('seqe_par_accept', 'par après acceptation de la tâche');
  s.msg('seqe_api', 'seqe_ui', '8a. 202 Accepted + execLogId', 'reply');
  s.msg('seqe_ui', 'seqe_user', '8b. Afficher le suivi', 'reply');
  s.divider();
  s.msg('seqe_api', 'seqe_api', '8c. [tâche asynchrone] publier l’ordre de transport', 'async');
  s.msg('seqe_api', 'seqe_kafka', '9. Publier TransportOrder', 'async');
  s.close();
  s.gap();

  // Extraction par source-gateway
  s.msg('seqe_kafka', 'seqe_gateway', '10. Livrer TransportOrder', 'async');
  s.msg('seqe_gateway', 'seqe_mongo', '11. Acquérir le claim de transport (bail + fencing)', 'sync');
  s.msg('seqe_gateway', 'seqe_mongo', '12. Charger le workflow ; vérifier sa révision', 'sync');
  s.msg('seqe_gateway', 'seqe_gateway', '13. Résoudre brièvement les identifiants source', 'sync');
  s.msg('seqe_gateway', 'seqe_source', '14. Lire la source en streaming', 'sync');
  s.open('seqe_loop_rows', 'loop lots de lignes');
  s.msg('seqe_source', 'seqe_gateway', '15. Lot suivant', 'reply');
  s.close();
  s.open('seqe_alt_transport', 'alt transport choisi automatiquement');
  s.msg('seqe_gateway', 'seqe_kafka', '[volume normal] publier les lots de données', 'async');
  s.divider();
  s.msg('seqe_gateway', 'seqe_rustfs', '[volume important] déposer l’objet et son SHA-256', 'sync');
  s.close();
  s.msg('seqe_gateway', 'seqe_kafka', '16. Purger les secrets ; publier la commande et le manifeste en dernier', 'async');
  s.gap();

  // Exécution du pipeline et clôture
  s.open('seqe_opt_inbox', 'opt [lots Kafka]');
  s.msg('seqe_kafka', 'seqe_consumer', '17a. Livrer les lots de données', 'async');
  s.msg('seqe_consumer', 'seqe_mongo', '17b. Stocker les lots dans l’inbox idempotente', 'sync');
  s.close();
  s.msg('seqe_kafka', 'seqe_consumer', '18. Livrer PIPELINE_EXECUTION_REQUESTED', 'async');
  s.msg('seqe_consumer', 'seqe_mongo', '19. Claim de pipeline et registre de résultat', 'sync');
  s.msg('seqe_consumer', 'seqe_postgres', '20. Acquérir le verrou d’exécution', 'sync');
  s.open('seqe_alt_materialize', 'alt artefact de transport');
  s.msg('seqe_consumer', 'seqe_mongo', '[Kafka] reconstruire les lots et vérifier les empreintes', 'sync');
  s.divider();
  s.msg('seqe_consumer', 'seqe_rustfs', '[objet] télécharger et vérifier SHA-256', 'sync');
  s.close();
  s.open('seqe_alt_runtime', 'alt executionMode');
  s.msg('seqe_consumer', 'seqe_runtime', '[LOCAL] 21a. Lancer Apache Hop', 'sync');
  s.msg('seqe_runtime', 'seqe_target', '21b. Alimenter Bronze → Silver → Gold', 'sync');
  s.divider();
  s.msg('seqe_consumer', 'seqe_runtime', '[SPARK] 21c. Lancer spark-submit', 'sync');
  s.msg('seqe_runtime', 'seqe_target', '21d. Alimenter les zones en mode distribué', 'sync');
  s.close();
  s.msg('seqe_runtime', 'seqe_consumer', '22. Résultat du runtime', 'reply');
  s.msg('seqe_consumer', 'seqe_kafka', '23. Publier le statut terminal', 'async');
  s.msg('seqe_consumer', 'seqe_rustfs', '24. [succès] supprimer les objets transférés', 'sync');
  s.msg('seqe_consumer', 'seqe_mongo', '25. [succès] supprimer les lots temporaires', 'sync');
  s.msg('seqe_kafka', 'seqe_status', '26. Livrer le statut', 'async');
  s.msg('seqe_status', 'seqe_mongo', '27. Finaliser ExecutionLog et l’historique', 'sync');

  s.note('seqe_note', '<b>Portée de la sécurité</b><br>Le déchiffrement du secret source est limité à source-gateway. Les lots et la commande envoyés à Kafka ne contiennent aucun identifiant de connexion en clair.', 80, 680);
  s.note('seqe_cleanup_note', '<b>Conflit, reprise et nettoyage</b><br>409 est renvoyé avant la création d’un second journal. Après échec, les artefacts ne sont pas supprimés immédiatement : RustFS est purgé après 72 h et les lots MongoDB expirent après 168 h.', 850, 720);
  s.build();
}

// ---------------------------------------------------------------------------
// 18. Inbound interoperability sequence
// ---------------------------------------------------------------------------
{
  const g = graph('Seq_INBOUND', 'Seq_INBOUND', 2440, 2020);
  title(g, 'Réception d’un échange d’interopérabilité (INBOUND)', 'Diagramme de séquence UML — une réponse est retournée après confirmation de publication de la commande ; le raffinage est ensuite asynchrone.');

  const s = sequence(g);
  s.actor('seqi_sender', 'Système émetteur', 30, 145);
  s.actor('seqi_openhim', 'OpenHIM Core', 235, 145);
  s.actor('seqi_mediator', 'Couche de médiation IOL<br>spécialisée / générique', 440, 180);
  s.actor('seqi_api', 'api-core', 680, 145);
  s.actor('seqi_mongo', 'MongoDB<br>standards + registre + journal', 890, 170);
  s.actor('seqi_kafka', 'Kafka', 1120, 130);
  s.actor('seqi_rustfs', 'RustFS', 1310, 130);
  s.actor('seqi_consumer', 'pipeline-consumer', 1500, 165);
  s.actor('seqi_runtime', 'Hop / Spark', 1725, 140);
  s.actor('seqi_target', 'Base de destination', 1920, 155);
  s.actor('seqi_status', 'api-core<br>listener de statut', 2130, 155);

  // Réception et validation avant remise au pipeline.
  s.msg('seqi_sender', 'seqi_openhim', '1. POST message normé + Idempotency-Key', 'sync');
  s.open('seqi_alt_route', 'alt route choisie par OpenHIM');
  s.msg('seqi_openhim', 'seqi_mediator', '[norme sectorielle] route spécialisée', 'sync');
  s.msg('seqi_mediator', 'seqi_mediator', '2a. Valider la norme ; produire l’enveloppe canonique NDJSON', 'sync');
  s.divider();
  s.msg('seqi_openhim', 'seqi_mediator', '[norme déclarative] route générique', 'sync');
  s.msg('seqi_mediator', 'seqi_mediator', '2b. Adapter la charge vers le pivot', 'sync');
  s.close();
  s.open('seqi_break_validation', 'break [validation de domaine échouée]');
  s.msg('seqi_mediator', 'seqi_openhim', 'Enveloppe OpenHIM (statut 400)', 'reply');
  s.msg('seqi_openhim', 'seqi_sender', 'Message refusé ; aucune remise au pipeline', 'reply');
  s.close();
  s.msg('seqi_mediator', 'seqi_api', '3. Lire les termes, valider le pivot et préparer INBOUND', 'sync');
  s.msg('seqi_api', 'seqi_mongo', '4. Charger le standard et ses termes', 'sync');
  s.msg('seqi_mongo', 'seqi_api', '5. Règles de validation', 'reply');
  s.msg('seqi_api', 'seqi_mongo', '6. Claim(Idempotency-Key, payloadHash)', 'sync');

  // Idempotence
  s.open('seqi_alt_idem', 'break [rejeu identique déjà COMPLETED]');
  s.msg('seqi_mongo', 'seqi_api', 'Résultat mémorisé', 'reply');
  s.msg('seqi_api', 'seqi_mediator', 'Réponse idempotente ; aucun nouvel effet', 'reply');
  s.msg('seqi_mediator', 'seqi_openhim', 'Enveloppe OpenHIM de rejeu', 'reply');
  s.msg('seqi_openhim', 'seqi_sender', 'Réponse précédente', 'reply');
  s.close();
  s.msg('seqi_mongo', 'seqi_api', '7. [nouvelle clé] claim acquis', 'reply');
  s.msg('seqi_api', 'seqi_mongo', '8. Créer ExecutionLog RUNNING', 'sync');

  // Publication ordonnée des données puis de la commande.
  s.open('seqi_alt_handoff', 'alt transport du pivot');
  s.msg('seqi_api', 'seqi_kafka', '[volume normal] publier les lots JSON', 'async');
  s.divider();
  s.msg('seqi_api', 'seqi_rustfs', '[volume important] déposer un objet et son empreinte', 'sync');
  s.close();
  s.msg('seqi_api', 'seqi_kafka', '9. Publier PIPELINE_EXECUTION_REQUESTED en dernier', 'async');
  s.msg('seqi_api', 'seqi_mongo', '10. Clore le reçu idempotent (commandPublished=true)', 'sync');
  s.msg('seqi_api', 'seqi_mediator', '11. Réception acceptée + execLogId + transport', 'reply');
  s.msg('seqi_mediator', 'seqi_openhim', '12. Enveloppe OpenHIM', 'reply');
  s.msg('seqi_openhim', 'seqi_sender', '13. Réponse 200 avec référence d’exécution', 'reply');
  s.gap();

  // Exécution asynchrone : le détail du transport est développé dans Seq_Execution.
  s.msg('seqi_kafka', 'seqi_consumer', '14. [asynchrone] événements de données, puis commande', 'async');
  s.msg('seqi_consumer', 'seqi_runtime', '15. Matérialiser, vérifier l’intégrité et lancer le moteur', 'sync');
  s.msg('seqi_runtime', 'seqi_target', '16. Alimenter Bronze → Silver → Gold', 'sync');
  s.msg('seqi_runtime', 'seqi_consumer', '17. Résultat du traitement', 'reply');
  s.msg('seqi_consumer', 'seqi_kafka', '18. Publier le statut terminal', 'async');
  s.msg('seqi_kafka', 'seqi_status', '19. Livrer le statut', 'async');
  s.msg('seqi_status', 'seqi_mongo', '20. Finaliser ExecutionLog', 'sync');

  s.note('seqi_note', '<b>Deux routes, un même contrat</b><br>Pour FHIR R4, ISO 20022 et Ed-Fi, un médiateur Java spécialisé valide le message puis délègue son NDJSON canonique au médiateur générique. Les autres normes empruntent directement le médiateur générique piloté par le référentiel.', 70, 790);
  s.note('seqi_idem_note', '<b>Point temporel essentiel</b><br>La réponse est produite après confirmation de la publication de la commande, non après Bronze/Silver/Gold. Les lots sont publiés avant la commande, mais ces publications ne forment pas une transaction Kafka unique. Une clé réutilisée avec un contenu différent, ou un reçu IN_PROGRESS/FAILED, produit un conflit.', 920, 820);
  s.build();
}

// ---------------------------------------------------------------------------
// 19. Outbound delivery sequence
// ---------------------------------------------------------------------------
{
  const g = graph('Seq_OUTBOUND', 'Seq_OUTBOUND', 2330, 1870);
  title(g, 'Livraison d’interopérabilité (OUTBOUND)', 'Diagramme de séquence UML — lecture contrôlée de la zone Gold, claim persistant avant tout POST, reprises bornées et journalisation du résultat.');

  const s = sequence(g);
  s.actor('seqo_consumer', 'pipeline-consumer', 30, 155);
  s.actor('seqo_kafka', 'Kafka', 245, 130);
  s.actor('seqo_api', 'api-core', 430, 145);
  s.actor('seqo_mongo', 'MongoDB<br>journal + ledger', 630, 155);
  s.actor('seqo_gold', 'Zone Gold<br>PostgreSQL actuel', 845, 155);
  s.actor('seqo_worker', 'iol-mediator<br>delivery worker', 1060, 165);
  s.actor('seqo_openhim', 'OpenHIM Core<br>(optionnel)', 1285, 155);
  s.actor('seqo_target', 'Système destinataire', 1495, 155);

  // Déclenchement par le statut du pipeline
  s.msg('seqo_consumer', 'seqo_kafka', '1. Publier pipeline.status = SUCCESS', 'async');
  s.msg('seqo_kafka', 'seqo_api', '2. Livrer statut', 'async');
  s.msg('seqo_api', 'seqo_mongo', '3. Mettre ExecutionLog à SUCCESS', 'sync');
  s.msg('seqo_api', 'seqo_mongo', '4. Charger la configuration OUTBOUND', 'sync');
  s.msg('seqo_mongo', 'seqo_api', '5. Standard, destination et source Gold', 'reply');
  s.msg('seqo_api', 'seqo_gold', '6. Exécuter SELECT borné et validé', 'sync');
  s.msg('seqo_gold', 'seqo_api', '7. Lignes pivot Gold', 'reply');
  s.msg('seqo_api', 'seqo_kafka', '8. Publier iol.outbound.delivery', 'async');
  s.gap();

  // Claim durable avant toute livraison
  s.msg('seqo_kafka', 'seqo_worker', '9. Livrer commande OUTBOUND', 'async');
  s.msg('seqo_worker', 'seqo_api', '10. Claim(idempotencyKey, owner, lease)', 'sync');
  s.msg('seqo_api', 'seqo_mongo', '11. SHA-256(key) + findAndModify atomique', 'sync');
  s.msg('seqo_mongo', 'seqo_api', '12. ALREADY_DELIVERED / BUSY / CLAIMED', 'reply');
  s.open('seqo_break_delivered', 'break [ALREADY_DELIVERED]');
  s.msg('seqo_api', 'seqo_worker', 'Ne pas refaire le POST', 'reply');
  s.close();
  s.open('seqo_break_busy', 'break [BUSY]');
  s.msg('seqo_api', 'seqo_worker', 'Ignorer ce doublon ; le propriétaire actif poursuit', 'reply');
  s.close();
  s.msg('seqo_api', 'seqo_worker', '13. [CLAIMED] numéro de tentative', 'reply');

  // Préparation du message partenaire
  s.msg('seqo_worker', 'seqo_api', '14. Dé-normaliser le pivot selon StandardTerm', 'sync');
  s.msg('seqo_api', 'seqo_worker', '15. Lignes dé-normalisées', 'reply');
  s.msg('seqo_worker', 'seqo_worker', '16. Sérialiser selon targetAdapter', 'sync');
  s.gap();

  // Livraison avec reprises bornées
  s.open('seqo_loop_retry', 'loop tentative ≤ maxRetries');
  s.open('seqo_alt_route', 'alt route OUTBOUND');
  s.msg('seqo_worker', 'seqo_openhim', '[openhimChannel] 17a. Vérifier la politique no-body puis POST', 'sync');
  s.msg('seqo_openhim', 'seqo_target', '17b. POST HTTP(S) + Idempotency-Key', 'sync');
  s.divider();
  s.msg('seqo_worker', 'seqo_target', '[endpointUrl autorisé] 17c. POST HTTP(S) + Idempotency-Key', 'sync');
  s.close();
  s.msg('seqo_target', 'seqo_worker', '18. Réponse du partenaire', 'reply');
  s.close();

  // Issue terminale : livré ou DLQ
  s.open('seqo_alt_result', 'alt résultat terminal');
  s.msg('seqo_worker', 'seqo_api', '[succès] 19. complete(owner)', 'sync');
  s.msg('seqo_api', 'seqo_mongo', 'findAndModify status=DELIVERED si owner', 'sync');
  s.msg('seqo_mongo', 'seqo_api', 'DELIVERED / NOT_OWNER', 'reply');
  s.msg('seqo_api', 'seqo_worker', 'Réponse du ledger', 'reply');
  s.msg('seqo_worker', 'seqo_kafka', 'Publier outbound.status = DELIVERED', 'async');
  s.divider();
  s.msg('seqo_worker', 'seqo_api', '[échec final] 20. fail(owner, erreur)', 'sync');
  s.msg('seqo_api', 'seqo_mongo', 'findAndModify status=FAILED si owner', 'sync');
  s.msg('seqo_mongo', 'seqo_api', 'FAILED / NOT_OWNER', 'reply');
  s.msg('seqo_worker', 'seqo_kafka', 'Publier FAILED + outbound.delivery.dlq', 'async');
  s.close();
  s.msg('seqo_kafka', 'seqo_api', '21. Livrer le statut final', 'async');
  s.msg('seqo_api', 'seqo_mongo', '22. Clôturer ExecutionLog', 'sync');

  s.note('seqo_note', '<b>Sécurité et reprise</b><br>Le worker n’appelle qu’un canal OpenHIM conforme ou une URL dont l’hôte est autorisé ; les réseaux privés sont refusés par défaut. Chaque tentative doit obtenir le claim durable avant le POST. Le transport est HTTPS lorsque le mode sécurisé est activé.', 70, 880);
  s.note('seqo_adapter_note', '<b>Portée actuelle</b><br>Les sérialiseurs OUTBOUND disponibles sont generic-json et fhir-basic/fhir ; ISO 20022 et Ed-Fi sont, à ce stade, pris en charge en réception. Le résultat fonctionnel DELIVERED / NOT_OWNER retourné par le ledger est tracé dans le diagramme car le worker ne l’interprète pas encore après une réponse HTTP 200.', 910, 900);
  s.build();
}

function serializeCell(cell) {
  const attrs = [`id="${esc(cell.id)}"`, `parent="${esc(cell.parent)}"`];
  if (cell.value !== undefined && cell.value !== '') attrs.push(`value="${esc(cell.value)}"`);
  if (cell.style) attrs.push(`style="${esc(cell.style)}"`);
  if (cell.vertex) attrs.push('vertex="1"');
  if (cell.edge) attrs.push('edge="1"');
  if (cell.source) attrs.push(`source="${esc(cell.source)}"`);
  if (cell.target) attrs.push(`target="${esc(cell.target)}"`);
  if (cell.connectable === false) attrs.push('connectable="0"');

  const geo = cell.geometry || {};
  const gAttrs = [];
  if (geo.x !== undefined) gAttrs.push(`x="${fmt(geo.x)}"`);
  if (geo.y !== undefined) gAttrs.push(`y="${fmt(geo.y)}"`);
  if (geo.width !== undefined) gAttrs.push(`width="${fmt(geo.width)}"`);
  if (geo.height !== undefined) gAttrs.push(`height="${fmt(geo.height)}"`);
  if (geo.relative) gAttrs.push('relative="1"');
  gAttrs.push('as="geometry"');

  const children = [];
  if (geo.offset) children.push(`<mxPoint x="${fmt(geo.offset.x)}" y="${fmt(geo.offset.y)}" as="offset"/>`);
  if (geo.sourcePoint) children.push(`<mxPoint x="${fmt(geo.sourcePoint.x)}" y="${fmt(geo.sourcePoint.y)}" as="sourcePoint"/>`);
  if (geo.targetPoint) children.push(`<mxPoint x="${fmt(geo.targetPoint.x)}" y="${fmt(geo.targetPoint.y)}" as="targetPoint"/>`);
  if (geo.points?.length) {
    children.push(`<Array as="points">${geo.points.map(p => `<mxPoint x="${fmt(p.x)}" y="${fmt(p.y)}"/>`).join('')}</Array>`);
  }
  return `        <mxCell ${attrs.join(' ')}><mxGeometry ${gAttrs.join(' ')}>${children.join('')}</mxGeometry></mxCell>`;
}

function serializePage(g) {
  const cells = g.cells.map(serializeCell).join('\n');
  return `  <diagram id="${esc(g.id)}" name="${esc(g.name)}">
    <mxGraphModel dx="1600" dy="900" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="${g.width}" pageHeight="${g.height}" math="0" shadow="0">
      <root>
        <mxCell id="0"/>
        <mxCell id="1" parent="0"/>
${cells}
      </root>
    </mxGraphModel>
  </diagram>`;
}

const existingXml = existsSync(OUTPUT) ? readFileSync(OUTPUT, 'utf8') : '';
const manuallyMaintainedClassPages = new Set([
  'Classes_v2',
  'CD_Securite',
  'CD_Configuration',
  'CD_Interoperabilite',
  'CD_Execution',
  'CD_IA',
  'CD_Transport',
]);

// Les pages non structurelles sont conservées telles quelles, sauf demande
// explicite : node scripts/generate_iol_drawio.mjs --regenerate=Seq_INBOUND,…
// ou --regenerate=all pour tout réécrire depuis ce fichier.
const regenerateArg = process.argv.find(a => a.startsWith('--regenerate='));
const forcedPages = new Set(
  regenerateArg ? regenerateArg.slice('--regenerate='.length).split(',').map(s => s.trim()).filter(Boolean) : [],
);
const forcesAll = forcedPages.has('all');

function preserveExistingPage(page) {
  if (forcesAll || forcedPages.has(page.name)) return null;
  if (!existingXml || (isClassDiagram(page) && !manuallyMaintainedClassPages.has(page.name))) return null;
  const escapedName = page.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = existingXml.match(new RegExp(`  <diagram id="[^"]+" name="${escapedName}">[\\s\\S]*?  </diagram>`));
  return match?.[0] ?? null;
}

const serializedPages = pages.map((page) => preserveExistingPage(page) ?? serializePage(page));
const xml = `<mxfile host="Electron" agent="IOL UML generator" version="24.7.17" pages="${pages.length}">
${serializedPages.join('\n')}
</mxfile>\n`;

writeFileSync(OUTPUT, xml, 'utf8');
console.log(`Generated ${pages.length} pages in ${OUTPUT.pathname}`);
