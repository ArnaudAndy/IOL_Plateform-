# Frontend

Ce dossier contient la console web du projet IOL ETL.

## Stack

- React 19
- Vite 6
- TypeScript
- Tailwind CSS
- Zustand et TanStack Query

## Structure principale

- src/App.tsx : point d’entrée principal de l’interface.
- src/components : composants UI réutilisables.
- src/lib : clients API, utilitaires, gestion du thème et helpers.
- src/stores : état global de l’application.
- src/hooks : hooks personnalisés.
- src/locales : ressources i18n.

## Démarrage rapide

```bash
cd frontend
npm ci
npm run dev
```

## Variables d’environnement

Le frontend utilise des variables d’environnement Vite :
- .env.local pour le développement local
- .env.production pour la production

## Points importants

- L’interface dépend du backend API pour fonctionner.
- Les variables d’environnement doivent être définies avant le build.
- Les fichiers de configuration sont volontairement limités à local et production.
