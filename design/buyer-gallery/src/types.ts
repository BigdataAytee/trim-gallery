/** DESIGN_SPEC.md section 6. The shape the API will return, one object per tile. */
export interface Tile {
  id: string
  type: 'photo' | 'clip' | 'file'
  src: string
  /**
   * Optional alternate encoding offered alongside `src`. Not part of the section 6
   * shape — the player falls back to `src` alone when the API omits it. It exists
   * because many Chromium builds ship without the proprietary H.264 decoder.
   */
  srcWebm?: string
  poster?: string
  /** Display duration, e.g. "0:18". Clips only. */
  duration?: string
  rating: 1 | 2 | 3 | 4 | 5
  buyer: string
  /** ISO date. */
  date: string
  text: string
  variant: string
  /** File tiles only: what the attachment is. */
  label?: string
  meta?: string
}

export type TabId = 'all' | 'clips'
