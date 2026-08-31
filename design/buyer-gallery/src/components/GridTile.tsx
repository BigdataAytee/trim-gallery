import { motion } from 'motion/react'
import { useArrival } from '../hooks/useIntersection'
import type { Tile } from '../types'
import { Chip } from './Chip'
import { ClipArt, FileArt, PhotoArt } from './TileArt'

interface Props {
  tile: Tile
  index: number
  resetKey: string
  reduced: boolean
  hidden: boolean
  onOpen: (tile: Tile) => void
}

const BREATH_MS = 4600
const STAGGER_MS = 70
const STAGGER_GROUP = 6

/** First name only, as in the mockup's tile chips. */
const firstName = (buyer: string) => buyer.split(' ')[0]

/**
 * A stable per-tile offset into the breathing loop, so neighbours never pulse together
 * (DESIGN_SPEC.md section 4.1).
 *
 * Derived from the id rather than Math.random(): the spread is just as arbitrary across
 * tiles, but it is the same on every render and every reload, so a re-render cannot make
 * the animation jump and a screenshot test stays reproducible.
 *
 * FNV-1a followed by a murmur3 finalizer. The finalizer is the point: a plain
 * `hash * 31 + char` gives sequential ids hashes about 31 apart, so `% 4600` maps `t00`
 * and `t01` to phases 31ms apart out of 4600 — distinct on paper, identical to the eye,
 * and the whole reason for the offset is that neighbours must not pulse together.
 */
function phaseFor(id: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < id.length; i++) {
    hash = Math.imul(hash ^ id.charCodeAt(i), 0x01000193)
  }
  hash = Math.imul(hash ^ (hash >>> 16), 0x7feb352d)
  hash = Math.imul(hash ^ (hash >>> 15), 0x846ca68b)
  hash ^= hash >>> 16
  return -((hash >>> 0) % BREATH_MS)
}

export function GridTile({ tile, index, resetKey, reduced, hidden, onOpen }: Props) {
  const ref = useArrival<HTMLButtonElement>(resetKey)
  const phase = phaseFor(tile.id)

  return (
    <motion.button
      ref={ref}
      type="button"
      // Motion pairs this with the hero of the same layoutId and morphs between them,
      // which is the FLIP in section 4.4 — including the return to whatever position
      // the tile now occupies if the grid scrolled while it was open.
      layoutId={`tile-${tile.id}`}
      data-type={tile.type}
      data-testid={`tile-${tile.id}`}
      // Hidden through CSS rather than an inline style: Motion takes over the `style`
      // prop for the duration of a layout animation and drops anything it does not
      // manage, so an inline `visibility` here is silently discarded on open.
      data-open={hidden ? 'true' : undefined}
      onClick={() => onOpen(tile)}
      whileTap={{ scale: 0.97 }}
      aria-label={`${tile.buyer}, ${tile.rating} stars. Open review.`}
      className="tile relative aspect-square w-full overflow-hidden text-left"
      style={{
        borderRadius: 'var(--radius)',
        background: 'var(--card)',
        ['--arrive-delay' as string]: `${(index % STAGGER_GROUP) * STAGGER_MS}ms`,
        ['--phase' as string]: `${phase}ms`,
      }}
    >
      {tile.type === 'clip' && <ClipArt tile={tile} reduced={reduced} />}
      {tile.type === 'photo' && <PhotoArt tile={tile} />}
      {tile.type === 'file' && <FileArt tile={tile} />}

      <div
        className="absolute right-[10px] bottom-[10px] left-[10px] flex items-center justify-between gap-1.5 font-semibold"
        style={{ fontSize: 12 }}
      >
        {tile.type === 'file' ? (
          <Chip>{firstName(tile.buyer)}</Chip>
        ) : (
          <>
            <Chip star>★ {tile.rating}</Chip>
            <Chip>{tile.type === 'clip' ? tile.duration : firstName(tile.buyer)}</Chip>
          </>
        )}
      </div>
    </motion.button>
  )
}
