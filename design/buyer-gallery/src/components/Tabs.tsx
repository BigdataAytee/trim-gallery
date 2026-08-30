import { motion } from 'motion/react'
import type { TabId } from '../types'

interface Props {
  active: TabId
  counts: Record<TabId, number>
  onChange: (tab: TabId) => void
}

const TABS: { id: TabId; label: string }[] = [
  { id: 'all', label: 'Photos' },
  { id: 'clips', label: 'Clips' },
]

/**
 * Two outlined pills. The active one takes a 2px dark border and dark text; the other
 * keeps the light grey outline and muted text. No filled or sliding background — the
 * outline *is* the state (DESIGN_SPEC.md section 2).
 */
export function Tabs({ active, counts, onChange }: Props) {
  return (
    <div className="flex gap-[10px] px-[18px] pt-[26px] pb-5" role="tablist">
      {TABS.map(({ id, label }) => {
        const selected = active === id
        return (
          <motion.button
            key={id}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(id)}
            whileTap={{ scale: 0.97 }}
            className="rounded-full font-semibold"
            style={{
              border: '2px solid',
              borderColor: selected ? 'var(--tab-on)' : 'var(--line)',
              color: selected ? 'var(--text)' : 'var(--muted)',
              padding: '14px 24px',
              fontSize: 18,
              lineHeight: 1,
              transition: 'border-color 300ms, color 300ms',
            }}
          >
            {label} ({counts[id]})
          </motion.button>
        )
      })}
    </div>
  )
}
