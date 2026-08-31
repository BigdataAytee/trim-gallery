import { useCallback, useMemo, useState } from 'react'
import { GridTile } from './components/GridTile'
import { Header } from './components/Header'
import { HeroOverlay } from './components/HeroOverlay'
import { Tabs } from './components/Tabs'
import { useReducedMotion } from './hooks/useReducedMotion'
import { useTheme } from './hooks/useTheme'
import { useTiles } from './hooks/useTiles'
import type { TabId, Tile } from './types'

export default function App() {
  const [theme, toggleTheme] = useTheme()
  const reduced = useReducedMotion()
  const { tiles, error, loading } = useTiles()

  const [tab, setTab] = useState<TabId>('all')
  const [openTile, setOpenTile] = useState<Tile | null>(null)

  // The "Photos" tab is the whole library, as in reference-prototype.html; "Clips"
  // narrows to video. Counts come from the data so they stay true when the API is
  // swapped in.
  const counts = useMemo(
    () => ({ all: tiles.length, clips: tiles.filter((t) => t.type === 'clip').length }),
    [tiles],
  )

  const visible = useMemo(
    () => (tab === 'clips' ? tiles.filter((t) => t.type === 'clip') : tiles),
    [tiles, tab],
  )

  const close = useCallback(() => setOpenTile(null), [])

  return (
    <div className="relative mx-auto min-h-dvh" style={{ maxWidth: 'var(--shell)' }}>
      <Header theme={theme} onToggleTheme={toggleTheme} />
      <Tabs active={tab} counts={counts} onChange={setTab} />

      {error && (
        <p className="px-[18px] pb-6" style={{ color: 'var(--muted)' }}>
          Could not load the gallery ({error}). Check that <code>tiles.json</code> is being
          served, then reload.
        </p>
      )}

      {!error && !loading && tiles.length === 0 && (
        <p className="px-[18px] pb-6" style={{ color: 'var(--muted)' }}>
          No photos or clips yet. They appear here once buyers start adding them.
        </p>
      )}

      <div
        data-testid="grid"
        className="grid grid-cols-2 gap-[14px] px-[18px]"
        style={{ paddingBottom: 'calc(env(safe-area-inset-bottom) + 90px)' }}
      >
        {visible.map((tile, index) => (
          <GridTile
            // Keyed by tab as well as id so switching tabs remounts the tiles and the
            // arrival animation replays, which is what section 4.3 asks for.
            key={`${tab}-${tile.id}`}
            tile={tile}
            index={index}
            resetKey={tab}
            reduced={reduced}
            hidden={openTile?.id === tile.id}
            onOpen={setOpenTile}
          />
        ))}
      </div>

      <HeroOverlay tile={openTile} reduced={reduced} onClose={close} />
    </div>
  )
}
