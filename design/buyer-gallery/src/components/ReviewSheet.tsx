import { motion } from 'motion/react'
import type { Tile } from '../types'

/** DESIGN_SPEC.md section 5. Avatar, name, date, rating, review, and the variant. */
export function ReviewSheet({ tile, reduced }: { tile: Tile; reduced: boolean }) {
  const date = new Date(tile.date).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })

  return (
    <motion.div
      role="dialog"
      aria-label={`Review by ${tile.buyer}`}
      data-testid="review-sheet"
      className="fixed inset-x-0 bottom-0 z-22 mx-auto"
      style={{
        maxWidth: 'var(--shell)',
        padding: '22px 24px calc(env(safe-area-inset-bottom) + 26px)',
        borderRadius: '26px 26px 0 0',
        background: 'var(--sheet)',
        boxShadow: '0 -10px 40px rgb(0 0 0 / 0.12)',
      }}
      initial={{ y: '110%' }}
      animate={{ y: 0 }}
      exit={{ y: '110%' }}
      transition={
        reduced
          ? { duration: 0 }
          : // Slides up 450ms behind the zoom, starting 120ms in, so the image lands first.
            { duration: 0.45, delay: 0.12, ease: [0.2, 0.8, 0.2, 1] }
      }
    >
      <div className="mb-3 flex items-center gap-3">
        <div
          className="size-9 shrink-0 rounded-full"
          style={{ background: 'linear-gradient(135deg, var(--glaze), var(--ember))' }}
        />
        <div className="min-w-0">
          <b style={{ fontSize: 15, fontWeight: 600 }}>{tile.buyer}</b>
          <span className="block" style={{ fontSize: 12, color: 'var(--muted)' }}>
            {date} · verified buyer
          </span>
        </div>
        <div
          className="ml-auto shrink-0"
          style={{ color: 'var(--star)', letterSpacing: 2, fontSize: 14 }}
          aria-label={`${tile.rating} out of 5`}
        >
          {'★'.repeat(tile.rating)}
          {'☆'.repeat(5 - tile.rating)}
        </div>
      </div>

      <p style={{ fontSize: 17, lineHeight: 1.45 }}>{tile.text}</p>

      <div style={{ marginTop: 14, fontSize: 13, color: 'var(--muted)' }}>
        Bought:{' '}
        <em
          style={{
            fontStyle: 'normal',
            color: 'var(--text)',
            borderBottom: '1px dotted var(--muted)',
          }}
        >
          {tile.variant}
        </em>
      </div>
    </motion.div>
  )
}
