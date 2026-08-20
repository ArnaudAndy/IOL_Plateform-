'use client'

// ============================================================
// MorphingParticlesBackground
// Inspiré d'antigravity.google : nuage de particules qui se transforment
// en continu, attirées vers des formes cibles (sphère, spirale, torus, galaxie).
//
// Palette IOL ETL : emerald/teal (140°-180° HSL) cohérente avec le logiciel.
//
// Le mouvement cherche la souplesse plutôt que la précision : aucune particule
// ne se pose jamais vraiment sur sa cible.
//
// Techniques :
//  - Champ de particules (400-700) avec position + vélocité
//  - Cible morphing : change de forme toutes les ~6s, interpolée en ease-in-out
//  - Ressort volontairement mou + fort amortissement : les particules glissent
//    vers leur cible au lieu d'y claquer
//  - Rotation lente et respiration continues de la formation : elle n'est
//    jamais figée, même entre deux morphings
//  - Champ de flux sinusoïdal (et non du bruit aléatoire) pour une dérive
//    organique, chaque particule ayant sa propre phase
//  - Connexions entre particules proches (effet constellation)
//  - Glow via additive blending (lighter)
//  - Suivi du pointeur : la formation dérive vers le curseur, les particules
//    proches sont repoussées et mises en orbite, et un halo suit la souris
//  - Physique normalisée sur le temps écoulé (indépendante du framerate)
//  - Respect prefers-reduced-motion
// ============================================================

import { useEffect, useRef } from 'react'

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  /** Cible dans le repère de la forme, relative au centre du canvas. */
  targetX: number
  targetY: number
  size: number
  hue: number
  alpha: number
  /** Décalage propre dans le champ de flux : évite tout mouvement en bloc. */
  phase: number
  /** Intensite du halo du curseur sur cette particule, 0 (hors portee) → 1. */
  glow: number
}

type ShapeType = 'sphere' | 'spiral' | 'torus' | 'galaxy'

// La forme 'wave' (ligne d'un bord a l'autre de l'ecran) a ete retiree du
// cycle : elle cassait la lecture en nuage des autres formes.
const SHAPES: ShapeType[] = ['sphere', 'spiral', 'torus', 'galaxy']

// Palette IOL ETL : emerald (140°) → teal (170°) → cyan-vert (190°)
// Cohérente avec le primary emerald-500 (#10b981 = HSL 160°) de l'app.
function hueForShape(shape: ShapeType, t: number): number {
  // t ∈ [0,1] position dans la forme
  switch (shape) {
    case 'sphere': return 150 + t * 40   // emerald → teal (150° → 190°)
    case 'spiral': return 140 + t * 50   // vert-émeraude → cyan (140° → 190°)
    case 'torus': return 155 + t * 35    // emerald → teal (155° → 190°)
    case 'galaxy': return 145 + t * 45   // vert → teal (145° → 190°)
  }
}

/** Ease-in-out cubique : demarrage et arrivee de morphing sans a-coup. */
function easeInOutCubic(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2
}

export function MorphingParticlesBackground({ className = '' }: { className?: string }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const animationRef = useRef<number>(0)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    let width = 0
    let height = 0
    let dpr = 1
    let particles: Particle[] = []
    let currentShape: ShapeType = 'sphere'
    let nextShape: ShapeType = 'spiral'
    let morphProgress = 0
    let shapeHoldTime = 0
    const SHAPE_HOLD_DURATION = 5200
    // Morphing long et progressif : c'est lui qui donne le caractere "soft".
    const MORPH_DURATION = 4200

    // Rotation et respiration de la formation. Elles tournent en permanence,
    // y compris pendant les phases de repos entre deux morphings, pour qu'aucun
    // instant de l'animation ne soit statique.
    let formationAngle = 0
    let elapsed = 0

    // ---- Suivi du pointeur ----------------------------------------------
    let pointerActive = false
    let pointerX = 0
    let pointerY = 0
    let driftX = 0
    let driftY = 0
    // Halo dessine sous le curseur : monte a 1 quand la souris entre, retombe
    // a 0 quand elle sort, pour que l'interaction ne s'allume jamais d'un coup.
    let pointerFade = 0

    // Part de la distance centre→curseur reprise par la formation.
    const DRIFT_RATIO = 0.3
    // Vitesse de rattrapage du decalage (0 = fige, 1 = instantane).
    const DRIFT_EASE = 0.035
    // Rayon du halo dans lequel les particules reagissent au curseur.
    const POINTER_RADIUS = 240
    const POINTER_RADIUS_SQ = POINTER_RADIUS * POINTER_RADIUS
    // Poussee radiale et composante tangentielle (mise en orbite autour du
    // curseur) : c'est le tourbillon qui rend l'interaction lisible.
    const POINTER_PUSH = 5.5
    const POINTER_SWIRL = 3.2

    function updatePointer(event: PointerEvent) {
      if (!canvas) return
      const rect = canvas.getBoundingClientRect()
      pointerX = event.clientX - rect.left
      pointerY = event.clientY - rect.top
      pointerActive =
        pointerX >= 0 && pointerX <= rect.width && pointerY >= 0 && pointerY <= rect.height
    }

    function releasePointer() {
      pointerActive = false
    }

    function resize() {
      if (!canvas || !ctx) return
      dpr = Math.min(window.devicePixelRatio || 1, 2)
      width = canvas.clientWidth
      height = canvas.clientHeight
      canvas.width = Math.max(1, Math.floor(width * dpr))
      canvas.height = Math.max(1, Math.floor(height * dpr))
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      initParticles()
      assignTargets(currentShape, 1)
    }

    function initParticles() {
      // Densité augmentée : +de particules pour un effet plus immersif
      const count = Math.max(320, Math.min(680, Math.floor((width * height) / 2000)))
      particles = []
      for (let i = 0; i < count; i++) {
        particles.push({
          x: Math.random() * width,
          y: Math.random() * height,
          vx: (Math.random() - 0.5) * 0.5,
          vy: (Math.random() - 0.5) * 0.5,
          targetX: 0,
          targetY: 0,
          size: 0.8 + Math.random() * 2.4,
          hue: 150 + Math.random() * 40,
          alpha: 0.4 + Math.random() * 0.55,
          phase: Math.random() * Math.PI * 2,
          glow: 0,
        })
      }
    }

    // Position cible d'une particule pour une forme donnee, exprimee en
    // coordonnees relatives au centre. Le centre, la rotation, la respiration
    // et la derive vers le curseur sont appliques au moment du calcul des
    // forces : la cible n'a donc jamais besoin d'etre recalculee par frame.
    function shapePosition(shape: ShapeType, i: number, total: number): { x: number; y: number } {
      const t = i / total
      const minDim = Math.min(width, height)
      // Facteur d'extension : formes larges, qui occupent l'ecran.
      const ext = 0.45

      switch (shape) {
        case 'sphere': {
          const phi = Math.acos(1 - 2 * t)
          const theta = Math.PI * (1 + Math.sqrt(5)) * i
          const r = minDim * ext
          const x3 = r * Math.sin(phi) * Math.cos(theta)
          const y3 = r * Math.sin(phi) * Math.sin(theta)
          const z3 = r * Math.cos(phi)
          const persp = 1 + z3 / (r * 2)
          return { x: x3 * persp, y: y3 * persp }
        }
        case 'spiral': {
          const angle = t * Math.PI * 10
          const r = t * minDim * ext * 1.1
          return { x: Math.cos(angle) * r, y: Math.sin(angle) * r }
        }
        case 'torus': {
          const u = t * Math.PI * 2
          const v = (i % 9) / 9 * Math.PI * 2
          const R = minDim * ext
          const r = minDim * 0.1
          const x3 = (R + r * Math.cos(v)) * Math.cos(u)
          const y3 = (R + r * Math.cos(v)) * Math.sin(u)
          const z3 = r * Math.sin(v)
          const persp = 1 + z3 / (r * 4)
          return { x: x3 * persp, y: y3 * persp }
        }
        case 'galaxy': {
          // Galaxie a 4 bras. Le desordre vient de la phase propre a chaque
          // particule et non d'un Math.random() : la cible reste stable d'une
          // frame a l'autre, sinon les bras vibreraient.
          const armCount = 4
          const arm = i % armCount
          const armT = Math.floor(i / armCount) / Math.floor(total / armCount)
          const angle = armT * Math.PI * 5 + (arm / armCount) * Math.PI * 2
          const r = armT * minDim * ext * 1.15 + 12
          const jitter = Math.sin(particles[i].phase * 3) * 26
          return { x: Math.cos(angle) * r + jitter, y: Math.sin(angle) * r + jitter }
        }
      }
    }

    /**
     * Recalcule les cibles. `blend` = 1 → forme pure ; en dessous, melange
     * progressif vers `nextShape`. L'appelant fournit une valeur deja lissee.
     */
    function assignTargets(shape: ShapeType, blend: number) {
      const total = particles.length
      for (let i = 0; i < total; i++) {
        const pos = shapePosition(shape, i, total)
        if (blend < 1 && nextShape !== shape) {
          const next = shapePosition(nextShape, i, total)
          particles[i].targetX = pos.x * blend + next.x * (1 - blend)
          particles[i].targetY = pos.y * blend + next.y * (1 - blend)
          particles[i].hue = hueForShape(shape, i / total) * blend + hueForShape(nextShape, i / total) * (1 - blend)
        } else {
          particles[i].targetX = pos.x
          particles[i].targetY = pos.y
          particles[i].hue = hueForShape(shape, i / total)
        }
      }
    }

    function draw(dt: number) {
      if (!ctx) return

      // Facteur d'echelle temporelle : la physique est calee sur une frame de
      // 60 Hz, mais reste identique sur un ecran 120 Hz ou lors d'un a-coup.
      const f = Math.min(2.5, dt / 16.67)
      elapsed += dt

      // Fond : zinc profond (cohérent avec le thème dark de l'app). Le foyer
      // du degrade suit legerement le curseur pour accompagner la derive.
      ctx.globalCompositeOperation = 'source-over'
      const bgX = width / 2 + driftX
      const bgY = height / 2 + driftY
      const bgGrad = ctx.createRadialGradient(bgX, bgY, 0, bgX, bgY, Math.max(width, height) * 0.7)
      bgGrad.addColorStop(0, '#0a0f14')
      bgGrad.addColorStop(1, '#050709')
      ctx.fillStyle = bgGrad
      ctx.fillRect(0, 0, width, height)

      // ---- Morphing --------------------------------------------------------
      if (!prefersReduced) {
        shapeHoldTime += dt
        if (shapeHoldTime > SHAPE_HOLD_DURATION) {
          const raw = Math.min(1, (shapeHoldTime - SHAPE_HOLD_DURATION) / MORPH_DURATION)
          morphProgress = raw
          // L'easing evite le depart et l'arret brusques d'une interpolation
          // lineaire : c'est la difference entre "ca bascule" et "ca coule".
          assignTargets(currentShape, 1 - easeInOutCubic(raw))
          if (raw >= 1) {
            currentShape = nextShape
            nextShape = SHAPES[(SHAPES.indexOf(nextShape) + 1) % SHAPES.length]
            morphProgress = 0
            shapeHoldTime = 0
            assignTargets(currentShape, 1)
          }
        } else if (morphProgress > 0) {
          morphProgress = 0
          assignTargets(currentShape, 1)
        }

        // Rotation continue, tres lente (~1 tour en 100 s).
        formationAngle += dt * 0.00006
      }

      // ---- Derive de la formation vers le curseur -------------------------
      const driftTargetX = pointerActive && !prefersReduced ? (pointerX - width / 2) * DRIFT_RATIO : 0
      const driftTargetY = pointerActive && !prefersReduced ? (pointerY - height / 2) * DRIFT_RATIO : 0
      driftX += (driftTargetX - driftX) * DRIFT_EASE * f
      driftY += (driftTargetY - driftY) * DRIFT_EASE * f
      const fadeTarget = pointerActive && !prefersReduced ? 1 : 0
      pointerFade += (fadeTarget - pointerFade) * 0.06 * f

      // Respiration : la formation enfle et se contracte de +/-5 %.
      const breath = 1 + Math.sin(elapsed * 0.00035) * 0.05
      const cosA = Math.cos(formationAngle)
      const sinA = Math.sin(formationAngle)
      const centerX = width / 2 + driftX
      const centerY = height / 2 + driftY

      // ---- Physique --------------------------------------------------------
      // Ressort volontairement mou et amortissement fort : les particules
      // rejoignent leur cible en glissant, avec un retard visible qui produit
      // les trainees souples de l'animation de reference.
      const damping = Math.pow(0.945, f)
      const springStrength = 0.0055

      for (const p of particles) {
        // Cible = position dans la forme, tournee, dilatee par la respiration,
        // puis replacee autour du centre derive.
        const tx = centerX + (p.targetX * cosA - p.targetY * sinA) * breath
        const ty = centerY + (p.targetX * sinA + p.targetY * cosA) * breath

        p.vx += (tx - p.x) * springStrength * f
        p.vy += (ty - p.y) * springStrength * f

        // Champ de flux : deux sinusoides croisees, decalees par la phase de la
        // particule. Contrairement a un bruit aleatoire, la direction varie de
        // maniere continue — d'ou une derive qui ondule au lieu de trembler.
        const flow = elapsed * 0.0004
        p.vx += Math.sin(p.y * 0.006 + flow + p.phase) * 0.055 * f
        p.vy += Math.cos(p.x * 0.006 + flow * 0.85 + p.phase) * 0.055 * f

        // Halo interactif : poussee radiale + mise en orbite. Le ressort
        // ramene ensuite les particules, d'ou la vague qui suit la souris.
        p.glow = 0
        if (pointerActive && !prefersReduced) {
          const pdx = p.x - pointerX
          const pdy = p.y - pointerY
          const pDistSq = pdx * pdx + pdy * pdy
          if (pDistSq < POINTER_RADIUS_SQ && pDistSq > 0.01) {
            const pDist = Math.sqrt(pDistSq)
            const falloff = 1 - pDist / POINTER_RADIUS
            const eased = falloff * falloff
            const nx = pdx / pDist
            const ny = pdy / pDist
            p.vx += nx * eased * POINTER_PUSH * f
            p.vy += ny * eased * POINTER_PUSH * f
            // Composante perpendiculaire : les particules contournent le
            // curseur au lieu de simplement fuir en ligne droite.
            p.vx += -ny * eased * POINTER_SWIRL * f
            p.vy += nx * eased * POINTER_SWIRL * f
            p.glow = falloff
          }
        }

        p.vx *= damping
        p.vy *= damping
        p.x += p.vx * f
        p.y += p.vy * f
      }

      // ---- Connexions entre particules proches ----
      ctx.globalCompositeOperation = 'lighter'
      const maxConnDist = 95
      const maxConnDistSq = maxConnDist * maxConnDist
      const step = Math.max(1, Math.floor(particles.length / 280))
      for (let i = 0; i < particles.length; i += step) {
        for (let j = i + step; j < particles.length; j += step) {
          const a = particles[i]
          const b = particles[j]
          const dx = a.x - b.x
          const dy = a.y - b.y
          const distSq = dx * dx + dy * dy
          if (distSq < maxConnDistSq) {
            const dist = Math.sqrt(distSq)
            // Les liens sous le curseur ressortent nettement : la constellation
            // se densifie la ou pointe la souris.
            const nearPointer = Math.max(a.glow, b.glow)
            const alpha = (1 - dist / maxConnDist) * (0.26 + nearPointer * 0.85)
            ctx.strokeStyle = `hsla(${(a.hue + b.hue) / 2}, 82%, ${65 + nearPointer * 25}%, ${alpha})`
            ctx.lineWidth = 0.7 + nearPointer * 0.9
            ctx.beginPath()
            ctx.moveTo(a.x, a.y)
            ctx.lineTo(b.x, b.y)
            ctx.stroke()
          }
        }
      }

      // ---- Halo du curseur ----
      // Nappe lumineuse ancree sous la souris : rend le point d'interaction
      // explicite, meme quand peu de particules passent a proximite.
      if (pointerFade > 0.01) {
        const halo = ctx.createRadialGradient(pointerX, pointerY, 0, pointerX, pointerY, POINTER_RADIUS)
        halo.addColorStop(0, `hsla(170, 90%, 65%, ${0.16 * pointerFade})`)
        halo.addColorStop(0.5, `hsla(165, 85%, 55%, ${0.06 * pointerFade})`)
        halo.addColorStop(1, 'hsla(160, 85%, 50%, 0)')
        ctx.fillStyle = halo
        ctx.beginPath()
        ctx.arc(pointerX, pointerY, POINTER_RADIUS, 0, Math.PI * 2)
        ctx.fill()
      }

      // ---- Particules (glow) ----
      // Celles prises dans le halo grossissent et s'eclaircissent : le pointeur
      // laisse une trainee lumineuse dans le nuage.
      for (const p of particles) {
        const boost = 1 + p.glow * 1.6
        const alpha = Math.min(1, p.alpha * (1 + p.glow * 1.2))
        const radius = p.size * 4 * boost
        const grad = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, radius)
        grad.addColorStop(0, `hsla(${p.hue}, 85%, ${70 + p.glow * 25}%, ${alpha})`)
        grad.addColorStop(0.4, `hsla(${p.hue}, 85%, 60%, ${alpha * 0.3})`)
        grad.addColorStop(1, `hsla(${p.hue}, 85%, 50%, 0)`)
        ctx.fillStyle = grad
        ctx.beginPath()
        ctx.arc(p.x, p.y, radius, 0, Math.PI * 2)
        ctx.fill()

        ctx.fillStyle = `hsla(${p.hue}, 90%, ${80 + p.glow * 18}%, ${alpha * 1.2})`
        ctx.beginPath()
        ctx.arc(p.x, p.y, p.size * 0.8 * boost, 0, Math.PI * 2)
        ctx.fill()
      }

      ctx.globalCompositeOperation = 'source-over'
    }

    resize()
    window.addEventListener('resize', resize)
    // Le canvas est en pointer-events:none (il ne doit rien intercepter) :
    // on ecoute donc le pointeur au niveau de la fenetre et on reprojette
    // les coordonnees dans le repere du canvas.
    if (!prefersReduced) {
      window.addEventListener('pointermove', updatePointer, { passive: true })
      window.addEventListener('pointerdown', updatePointer, { passive: true })
      window.addEventListener('pointerleave', releasePointer)
      window.addEventListener('blur', releasePointer)
    }

    let lastTime = performance.now()
    function loop(time: number) {
      const dt = Math.min(50, time - lastTime)
      lastTime = time
      draw(dt)
      animationRef.current = requestAnimationFrame(loop)
    }

    if (prefersReduced) {
      assignTargets(currentShape, 1)
      draw(16)
    } else {
      animationRef.current = requestAnimationFrame(loop)
    }

    return () => {
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', updatePointer)
      window.removeEventListener('pointerdown', updatePointer)
      window.removeEventListener('pointerleave', releasePointer)
      window.removeEventListener('blur', releasePointer)
      cancelAnimationFrame(animationRef.current)
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      className={`pointer-events-none absolute inset-0 h-full w-full ${className}`}
    />
  )
}
