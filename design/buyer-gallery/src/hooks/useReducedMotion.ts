import { useSyncExternalStore } from 'react'

const QUERY = '(prefers-reduced-motion: reduce)'

function subscribe(onChange: () => void) {
  const mq = window.matchMedia(QUERY)
  mq.addEventListener('change', onChange)
  return () => mq.removeEventListener('change', onChange)
}

/**
 * Whether the viewer has asked for reduced motion (DESIGN_SPEC.md section 4.6).
 *
 * Live rather than read-once, so toggling the OS setting takes effect without a reload.
 * CSS handles the breathing and arrival; this hook is for the parts CSS cannot reach —
 * autoplay and the hero timings.
 */
export function useReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(QUERY).matches,
    () => false,
  )
}
