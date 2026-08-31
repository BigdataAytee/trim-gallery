import { useCallback, useEffect, useState } from 'react'

const STORAGE_KEY = 'buyer-gallery-theme'

export type Theme = 'light' | 'dark'

/**
 * Light on first load, toggled from the header, remembered afterwards
 * (DESIGN_SPEC.md section 3).
 *
 * The first value is read from the DOM rather than from storage: an inline script in
 * index.html has already applied the stored choice before first paint, so trusting the
 * attribute avoids re-deciding — and avoids a light frame flashing before dark.
 */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(
    () => (document.documentElement.dataset.theme as Theme) ?? 'light',
  )

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try {
      localStorage.setItem(STORAGE_KEY, theme)
    } catch {
      // Private mode or blocked storage: the toggle still works for this session.
    }
  }, [theme])

  const toggle = useCallback(() => setTheme((t) => (t === 'dark' ? 'light' : 'dark')), [])

  return [theme, toggle]
}
