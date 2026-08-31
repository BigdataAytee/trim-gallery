import { useEffect, useState } from 'react'
import type { Tile } from '../types'

interface State {
  tiles: Tile[]
  error: string | null
  loading: boolean
}

/**
 * Loads the gallery from a local JSON file.
 *
 * This is the seam for the real API: swap the URL for the endpoint and nothing else in
 * the screen changes, because everything downstream consumes the section 6 shape.
 */
export function useTiles(url = '/tiles.json'): State {
  const [state, setState] = useState<State>({ tiles: [], error: null, loading: true })

  useEffect(() => {
    let cancelled = false
    fetch(url)
      .then((r) => {
        if (!r.ok) throw new Error(`${r.status} ${r.statusText}`)
        return r.json() as Promise<Tile[]>
      })
      .then((tiles) => {
        if (!cancelled) setState({ tiles, error: null, loading: false })
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setState({ tiles: [], error: e instanceof Error ? e.message : 'Unknown error', loading: false })
        }
      })
    return () => {
      cancelled = true
    }
  }, [url])

  return state
}
