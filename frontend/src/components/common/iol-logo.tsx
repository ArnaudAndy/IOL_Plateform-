import { cn } from '@/lib/utils'

// ============================================================
// IolLogo — marque unique de la plateforme.
//
// Ce composant reproduit exactement l'artwork de public/favicon.svg :
// l'onglet du navigateur, la sidebar, l'ecran de connexion et le loader
// affichent donc le meme signe. Toute retouche du dessin doit etre
// reportee dans les deux fichiers pour qu'ils ne divergent pas.
//
// Le vert #008856 est la couleur de marque (identique a --sidebar-active) :
// il reste fixe en clair comme en sombre, comme un vrai logo.
// ============================================================

type IolLogoProps = {
  /** Cote du bloc en pixels (le SVG est carre). */
  size?: number
  className?: string
  /** Sans la plaque verte : glyphe seul, colore par `currentColor`. */
  bare?: boolean
}

export function IolLogo({ size = 32, className, bare = false }: IolLogoProps) {
  return (
    <svg
      viewBox="0 0 64 64"
      width={size}
      height={size}
      role="img"
      aria-label="IOL ETL Platform"
      className={cn('shrink-0', className)}
    >
      {!bare && <rect width="64" height="64" rx="12" fill="#008856" />}
      <g fill={bare ? 'currentColor' : '#ffffff'}>
        <path d="M14 19h8v26h-8zM28 19h8v26h-8zM42 19h8v26h-8z" />
        <circle cx="18" cy="16" r="4" />
        <circle cx="32" cy="48" r="4" />
        <circle cx="46" cy="16" r="4" />
      </g>
    </svg>
  )
}
