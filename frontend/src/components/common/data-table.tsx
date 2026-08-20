import { cn } from '@/lib/utils'

// ============================================================
// DataTable — modele de table unique de la console.
//
// Caracteristiques du modele :
//  - en-tetes en casse normale, gris, sans aplat de fond (les anciennes
//    tables avaient un thead grise en majuscules 10px)
//  - lignes aerees (py-4) separees par un filet fin, surlignees au survol
//  - premiere colonne en semibold : c'est l'entree que l'oeil cherche
//  - colonnes chiffrees alignees a droite et en chiffres tabulaires, pour que
//    les unites s'empilent verticalement
//  - conteneur arrondi qui scrolle horizontalement sur petit ecran
//
// Toutes les vues passent par ces primitives : modifier le modele ici le
// propage a l'application entiere.
// ============================================================

type Align = 'left' | 'right' | 'center'

const ALIGN_CLASS: Record<Align, string> = {
  left: 'text-left',
  right: 'text-right',
  center: 'text-center',
}

export function DataTable({
  children,
  minWidth,
  className,
}: {
  children: React.ReactNode
  /** Largeur minimale en px avant declenchement du scroll horizontal. */
  minWidth?: number
  className?: string
}) {
  return (
    <div className="w-full overflow-x-auto rounded-xl border border-border bg-card">
      <table
        className={cn('w-full border-collapse text-sm', className)}
        style={minWidth ? { minWidth: `${minWidth}px` } : undefined}
      >
        {children}
      </table>
    </div>
  )
}

export function THead({ children }: { children: React.ReactNode }) {
  return (
    <thead>
      <tr className="border-b border-border">{children}</tr>
    </thead>
  )
}

export function TBody({ children }: { children: React.ReactNode }) {
  return <tbody className="divide-y divide-border">{children}</tbody>
}

export function Th({
  children,
  align = 'left',
  className,
}: {
  children?: React.ReactNode
  align?: Align
  className?: string
}) {
  return (
    <th
      scope="col"
      className={cn(
        'whitespace-nowrap px-4 py-3 text-[13px] font-normal text-muted-foreground',
        ALIGN_CLASS[align],
        className,
      )}
    >
      {children}
    </th>
  )
}

export function Tr({
  children,
  className,
  onClick,
}: {
  children: React.ReactNode
  className?: string
  onClick?: () => void
}) {
  return (
    <tr
      onClick={onClick}
      className={cn(
        'transition-colors hover:bg-muted/40',
        onClick && 'cursor-pointer',
        className,
      )}
    >
      {children}
    </tr>
  )
}

export function Td({
  children,
  align = 'left',
  /** Libelle principal de la ligne : rendu en semibold. */
  strong = false,
  /** Donnee secondaire (dates, identifiants) : rendue en gris. */
  muted = false,
  /** Chiffres : alignes a droite et en chiffres tabulaires. */
  numeric = false,
  colSpan,
  className,
}: {
  children?: React.ReactNode
  align?: Align
  strong?: boolean
  muted?: boolean
  numeric?: boolean
  colSpan?: number
  className?: string
}) {
  return (
    <td
      colSpan={colSpan}
      className={cn(
        'px-4 py-4 align-middle',
        ALIGN_CLASS[numeric ? 'right' : align],
        numeric && 'tabular-nums',
        strong && 'font-semibold text-foreground',
        muted && 'text-muted-foreground',
        className,
      )}
    >
      {children}
    </td>
  )
}
