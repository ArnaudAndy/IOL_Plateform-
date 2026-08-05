# IOL ETL Platform — Version React + Vite

Console d'administration de la plateforme IOL ETL (workflows interopérables Bronze → Silver → Gold).
Version **React.js pure** (Vite + TypeScript + Tailwind CSS 4 + shadcn/ui), sans Next.js.

## Stack

| Techno | Rôle |
|---|---|
| **React 19** | UI library |
| **Vite 6** | Build tool + dev server |
| **TypeScript 5** | Typage statique |
| **Tailwind CSS 4** | Styling (via `@tailwindcss/vite`) |
| **shadcn/ui** | Composants (Radix UI + Tailwind) |
| **TanStack Query 5** | State serveur (cache, polling, mutations) |
| **Zustand 5** | State client (auth, navigation) |
| **React Flow 11** | Canvas de flux (vue Monitoring Flux) |
| **Lucide React** | Icônes |

## Différences avec la version Next.js

| Aspect | Version Next.js | Version React (Vite) |
|---|---|---|
| Framework | Next.js 16 (App Router) | Vite 6 (SPA) |
| Rendu | SSR + client | Client uniquement (CSR) |
| Routing | File-based (`src/app/`) | View-switching (Zustand) |
| Fonts | `next/font/google` | `<link>` Google Fonts dans `index.html` |
| Thème | `next-themes` | `src/lib/theme.tsx` (custom, localStorage) |
| Variables d'env | `NEXT_PUBLIC_*` | `VITE_*` |
| `'use client'` | Requis sur composants client | Non nécessaire (tout est client) |
| API proxy | Rewrite Next.js | Proxy Vite (`vite.config.ts`) |
| Port dev | 3000 | 5173 |

## Structure du projet

```
react-version/
├── index.html              # Point d'entrée HTML (fonts, meta)
├── package.json
├── vite.config.ts          # Config Vite + proxy /api
├── tsconfig.json
├── .env                    # VITE_API_BASE_URL, VITE_API_PROXY_TARGET
└── src/
    ├── main.tsx            # Bootstrap React (createRoot)
    ├── App.tsx             # Rend AppShell
    ├── index.css           # Tailwind + thème dark/light
    ├── vite-env.d.ts       # Types Vite + CSS
    ├── lib/
    │   ├── api/            # types.ts, client.ts (JWT + enveloppe), services.ts
    │   ├── theme.tsx       # ThemeProvider custom (remplace next-themes)
    │   ├── format.ts       # formatDuration, formatRelative, useMounted
    │   ├── query-client.ts # Config TanStack Query
    │   └── utils.ts        # cn() helper
    ├── stores/
    │   ├── auth-store.ts   # Zustand persisté (token, user, isAdmin)
    │   └── nav-store.ts    # Navigation SPA par view
    ├── hooks/
    │   ├── use-toast.ts    # Toast hook
    │   └── use-mobile.ts
    ├── components/
    │   ├── ui/             # 48 composants shadcn/ui
    │   ├── common/         # badges, states (loading/empty/error)
    │   ├── layout/         # sidebar, topbar, app-shell
    │   ├── providers/      # AppProviders (Theme + QueryClient + Toaster)
    │   └── views/          # 14 vues (auth, dashboard, flow-monitor, workflows, ...)
    └── public/
```

## Installation & démarrage

### Prérequis
- **Node.js 18+** ou **Bun** (recommandé)
- Le **backend IOL ETL** en cours d'exécution (Spring Boot, port 8080 par défaut)

### Étapes

```bash
# 1. Installer les dépendances
bun install
# ou: npm install

# 2. Configurer l'API
cp .env.example .env
# Éditez .env :
#   VITE_API_BASE_URL=/api          # chemin relatif (utilise le proxy Vite)
#   VITE_API_PROXY_TARGET=http://localhost:8080  # target du backend

# 3. Démarrer le dev server
bun run dev
# ou: npm run dev

# 4. Ouvrir http://localhost:5173
```

### Build de production

```bash
bun run build      # génère dist/
bun run preview    # prévisualise le build de prod
```

## Configuration de l'API

Deux modes :

### Mode 1 : Proxy Vite (recommandé en dev)
`.env` :
```
VITE_API_BASE_URL=/api
VITE_API_PROXY_TARGET=http://localhost:8080
```
Le proxy Vite forward `/api/*` vers `http://localhost:8080/api/*` (évite les problèmes CORS).

### Mode 2 : URL absolue (prod)
`.env` :
```
VITE_API_BASE_URL=https://mon-backend.com/api
```

## Vues incluses (13)

1. **Auth** — Login / Register / Bootstrap admin
2. **Dashboard** — KPIs, synthèse interop, exécutions récentes
3. **Monitoring Flux** — Canvas temps réel (Sources→Bronze→Silver→Gold→Interop + DLQ + IA)
4. **Workflows** — Liste + CRUD + run + export
5. **Workflow Detail** — 7 tabs (overview, sources, schedule, gold, executions, performance, discovery)
6. **Workflow Builder** — Wizard 7 étapes avec IA + SQL Workbench intégrés
7. **Executions** — Liste filtrable + détails + métriques par source
8. **Interopérabilité** — Synthèse temps réel + trace par correlationId
9. **Standards** — CRUD + termes + activate/deprecate + validate
10. **Connexions** — CRUD + test
11. **SQL Workbench** — Validate + execute
12. **Assistant IA** — Chat + génération SQL (Silver/Gold)
13. **Users** — Gestion comptes + rôles
14. **Audit** — Failed / par ressource / par utilisateur
15. **Métadonnées** — Génération JSON + Hop orchestration

## RBAC

Deux rôles : `ADMIN` et `USER`. L'UI masque/désactive les actions non autorisées.
Le serveur applique les 403 — l'UI ne propose aucune action vouée à l'échec.

## Couverture API

100% des endpoints du backend sont câblés :
- **Auth** : `/api/auth/*`
- **Workflows** : `/api/workflows/*` (CRUD + discover + draft + execute + export)
- **Orchestrator** : `/api/orchestrator/run/{id}`
- **Logs** : `/api/logs/*` (all, byWorkflow, details, performance, sources, interop, summary, correlation)
- **Standards** : `/api/v1/standards/*` (RAW, sans enveloppe)
- **Connections** : `/api/connections/*`
- **AI** : `/api/ai/*` (chat, generate-sql, contextual, aggregation, cleaning)
- **SQL** : `/api/sql/*` (validate, execute)
- **Users** : `/api/users/*` (ADMIN)
- **Audit** : `/api/v1/audit/*` (RAW, ADMIN)
- **Metadata** : `/api/v1/metadata/*` (RAW, ADMIN)

L'API interne `/api/internal/interop/**` n'est **jamais** appelée depuis le navigateur.

## Notes techniques

- **Temps réel** : polling 8-15s sur `/logs` et `/logs/interop/summary`. Pour du vrai temps réel poussé (SSE/WebSocket), un ajout backend est nécessaire.
- **JWT** : stocké dans `localStorage`, attaché via intercepteur `Authorization: Bearer`. Logout auto sur 401.
- **Double enveloppe** : gérée automatiquement (`ApiResponse<T>` pour la plupart, réponses brutes pour Standard/Audit/Metadata).
- **Aucune donnée mockée** : si le backend est absent, messages d'erreur honnêtes + boutons retry.

## Scripts

| Commande | Action |
|---|---|
| `bun run dev` | Démarre le dev server (port 5173) avec HMR |
| `bun run build` | Build de production (`tsc -b && vite build`) |
| `bun run preview` | Prévisualise le build de prod |
| `bun run lint` | ESLint |

## Licence

Projet interne — IOL ETL Platform.
