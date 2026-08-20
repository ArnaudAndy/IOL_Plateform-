import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

export const PAGE_SIZE_OPTIONS = [25, 50, 100, 200]

type TablePaginationProps = {
  /** Page courante, indexee a partir de 1. */
  page: number
  pageSize: number
  /** Nombre total de lignes avant decoupage. */
  total: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
}

/**
 * Pagination cote client pour les tables de la console.
 * Le parent garde l'etat (page / taille) et decoupe lui-meme ses lignes via
 * `paginate()` : ce composant ne fait que la navigation et l'affichage.
 */
export function TablePagination({
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
}: TablePaginationProps) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize))
  const current = Math.min(page, pageCount)
  const firstRow = total === 0 ? 0 : (current - 1) * pageSize + 1
  const lastRow = Math.min(current * pageSize, total)

  return (
    <div className="flex flex-col gap-2 pt-1 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-xs text-muted-foreground">
        {total === 0
          ? 'Aucune ligne'
          : `${firstRow}–${lastRow} sur ${total}`}
      </p>

      <div className="flex items-center gap-2">
        <div className="flex items-center gap-1.5">
          <span className="hidden text-xs text-muted-foreground sm:inline">Lignes</span>
          <Select
            value={String(pageSize)}
            onValueChange={(value) => {
              onPageSizeChange(Number(value))
              onPageChange(1)
            }}
          >
            <SelectTrigger className="h-8 w-[74px]" aria-label="Lignes par page">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PAGE_SIZE_OPTIONS.map((size) => (
                <SelectItem key={size} value={String(size)}>{size}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-1">
          <Button
            variant="outline" size="icon" className="h-8 w-8"
            onClick={() => onPageChange(1)}
            disabled={current <= 1}
            aria-label="Première page"
          >
            <ChevronsLeft className="h-4 w-4" />
          </Button>
          <Button
            variant="outline" size="icon" className="h-8 w-8"
            onClick={() => onPageChange(current - 1)}
            disabled={current <= 1}
            aria-label="Page précédente"
          >
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="px-1.5 text-xs tabular-nums text-muted-foreground">
            {current} / {pageCount}
          </span>
          <Button
            variant="outline" size="icon" className="h-8 w-8"
            onClick={() => onPageChange(current + 1)}
            disabled={current >= pageCount}
            aria-label="Page suivante"
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
          <Button
            variant="outline" size="icon" className="h-8 w-8"
            onClick={() => onPageChange(pageCount)}
            disabled={current >= pageCount}
            aria-label="Dernière page"
          >
            <ChevronsRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}

/** Decoupe `rows` pour la page demandee, en bornant la page au dernier index. */
export function paginate<T>(rows: T[], page: number, pageSize: number): T[] {
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize))
  const current = Math.min(Math.max(1, page), pageCount)
  const start = (current - 1) * pageSize
  return rows.slice(start, start + pageSize)
}
