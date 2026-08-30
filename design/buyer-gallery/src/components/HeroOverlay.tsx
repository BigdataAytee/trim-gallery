import { AnimatePresence, motion } from 'motion/react'
import { X } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { Tile } from '../types'
import { ReviewSheet } from './ReviewSheet'
import { FileArt, PhotoArt } from './TileArt'

interface Props {
  tile: Tile | null
  reduced: boolean
  onClose: () => void
}

const SHELL_MAX = 430
const SIDE_INSET = 36
/** How far down the square sits in the space left over. */
const VERTICAL_BIAS = 0.35
const MIN_TOP = 80
/** Ignore the scroll jitter that a tap can produce; only a real scroll dismisses. */
const SCROLL_DISMISS_PX = 4

interface Rect {
  left: number
  top: number
  size: number
}

function heroRect(): Rect {
  const size = Math.min(window.innerWidth, SHELL_MAX) - SIDE_INSET
  return {
    size,
    left: (window.innerWidth - size) / 2,
    top: Math.max(MIN_TOP, (window.innerHeight - size) * VERTICAL_BIAS),
  }
}

/**
 * The hero zoom (DESIGN_SPEC.md section 4.4).
 *
 * The morph is a shared-layout animation: the grid tile and this element carry the same
 * `layoutId`, so Motion measures both and FLIPs between them. Closing needs no separate
 * animation — unmounting the hero hands the id back to the tile, which animates into
 * whatever position it now occupies, even if the grid scrolled while it was open.
 */
export function HeroOverlay({ tile, reduced, onClose }: Props) {
  const open = tile !== null
  const [rect, setRect] = useState<Rect>(() => heroRect())

  // The target square depends only on the viewport, so it is measured once and kept in
  // step by the resize listener — no need to re-measure on open, and no setState during
  // an effect that runs on every open.
  useEffect(() => {
    const onResize = () => setRect(heroRect())
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  // Escape, and scrolling the page behind, both dismiss (section 4.4).
  useEffect(() => {
    if (!open) return

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    const openedAt = window.scrollY
    const onScroll = () => {
      if (Math.abs(window.scrollY - openedAt) > SCROLL_DISMISS_PX) onClose()
    }

    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('scroll', onScroll)
    }
  }, [open, onClose])

  const instant = { duration: 0 }

  return (
    <>
      {/* Outside AnimatePresence on purpose: it must unmount the instant the close
          starts, so the grid tile takes the layoutId back and performs the return
          morph itself. */}
      {tile && (
        <motion.div
          layoutId={`tile-${tile.id}`}
          data-testid="hero"
          className="fixed z-21 overflow-hidden"
          style={{
            left: rect.left,
            top: rect.top,
            width: rect.size,
            height: rect.size,
            borderRadius: 'var(--radius-hero)',
            background: 'var(--card)',
            boxShadow: '0 30px 80px rgb(0 0 0 / 0.28)',
          }}
          transition={{
            layout: reduced
              ? instant
              : // 420ms with a little overshoot, as the prototype.
                { duration: 0.42, ease: [0.2, 0.9, 0.25, 1.1] },
          }}
        >
          {tile.type === 'file' ? (
            <FileArt tile={tile} />
          ) : tile.type === 'clip' ? (
            <video
              className="absolute inset-0 size-full object-cover"
              poster={tile.poster}
              muted
              playsInline
              loop
              preload="metadata"
              autoPlay={!reduced}
              aria-hidden
            >
              {tile.srcWebm && <source src={tile.srcWebm} type="video/webm" />}
              <source src={tile.src} type="video/mp4" />
            </video>
          ) : (
            <PhotoArt tile={tile} />
          )}
        </motion.div>
      )}

      <AnimatePresence>
        {open && (
          <motion.div
            key="veil"
            data-testid="veil"
            className="fixed inset-0 z-20"
            onClick={onClose}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={reduced ? instant : { duration: 0.35 }}
            style={{
              background: 'var(--veil)',
              backdropFilter: 'blur(14px)',
              WebkitBackdropFilter: 'blur(14px)',
            }}
          />
        )}

        {open && (
          <motion.button
            key="close"
            type="button"
            aria-label="Close"
            data-testid="close"
            onClick={onClose}
            className="fixed z-23 grid size-[46px] place-items-center rounded-full"
            style={{
              top: 'calc(env(safe-area-inset-top) + 16px)',
              right: 18,
              background: 'var(--card)',
              boxShadow: '0 2px 8px rgb(0 0 0 / 0.12)',
            }}
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.8 }}
            whileTap={{ scale: 0.94 }}
            transition={reduced ? instant : { duration: 0.25 }}
          >
            <X size={22} strokeWidth={2.4} />
          </motion.button>
        )}

        {tile && <ReviewSheet key="sheet" tile={tile} reduced={reduced} />}
      </AnimatePresence>
    </>
  )
}
