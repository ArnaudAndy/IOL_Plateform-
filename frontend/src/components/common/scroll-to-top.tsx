import { useEffect, useState } from 'react'
import { ArrowUp } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function ScrollToTop({ targetId }: { targetId: string }) {
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const target = document.getElementById(targetId)
    if (!target) return

    const updateVisibility = () => setVisible(target.scrollTop > 360)
    updateVisibility()
    target.addEventListener('scroll', updateVisibility, { passive: true })
    return () => target.removeEventListener('scroll', updateVisibility)
  }, [targetId])

  if (!visible) return null

  return (
    <Button
      type="button"
      size="icon"
      className="fixed bottom-5 right-4 z-40 h-10 w-10 rounded-full shadow-lg sm:right-6"
      onClick={() => document.getElementById(targetId)?.scrollTo({ top: 0, behavior: 'smooth' })}
      title="Revenir en haut"
      aria-label="Revenir en haut"
    >
      <ArrowUp className="h-4 w-4" />
    </Button>
  )
}
