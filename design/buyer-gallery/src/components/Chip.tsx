import type { ReactNode } from 'react'

/** The frosted pill used for ratings, names and durations over a tile. */
export function Chip({ children, star = false }: { children: ReactNode; star?: boolean }) {
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full"
      style={{
        padding: '5px 9px',
        background: 'var(--chip-bg)',
        color: star ? 'var(--star)' : 'var(--chip-fg)',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
      }}
    >
      {children}
    </span>
  )
}
