import { useEffect, useRef, useState } from 'react'

type Handler = (entry: IntersectionObserverEntry) => void

/**
 * One IntersectionObserver per configuration, shared by every element that wants it.
 *
 * Both places the spec uses an observer — staggered arrival and clip playback — apply
 * the same options to every tile, so thirty tiles can share two observers instead of
 * creating sixty.
 */
function sharedObserver(options: IntersectionObserverInit) {
  const handlers = new Map<Element, Handler>()
  let observer: IntersectionObserver | null = null

  const ensure = () => {
    observer ??= new IntersectionObserver((entries) => {
      for (const entry of entries) handlers.get(entry.target)?.(entry)
    }, options)
    return observer
  }

  return {
    observe(el: Element, handler: Handler) {
      handlers.set(el, handler)
      ensure().observe(el)
    },
    unobserve(el: Element) {
      handlers.delete(el)
      observer?.unobserve(el)
    },
  }
}

// Section 4.3: tiles animate in as they enter the viewport. The negative bottom margin
// holds the animation back until a tile is properly on screen rather than clipping the
// edge.
const arrivalObserver = sharedObserver({ rootMargin: '0px 0px -6% 0px' })

// Section 4.2: a clip plays only while at least half of it is visible.
const playbackObserver = sharedObserver({ threshold: [0, 0.5, 1] })

/**
 * Adds `is-in` once the element has scrolled into view, which starts the arrival and
 * breathing animations.
 *
 * @param resetKey change it (the active tab) to replay the arrival for every tile.
 */
export function useArrival<T extends HTMLElement>(resetKey: string) {
  const ref = useRef<T | null>(null)

  useEffect(() => {
    const el = ref.current
    if (!el) return

    el.classList.remove('is-in')
    // Force a reflow so removing and re-adding the class restarts the animation rather
    // than being coalesced into no change at all.
    void el.offsetWidth

    arrivalObserver.observe(el, (entry) => {
      if (!entry.isIntersecting) return
      entry.target.classList.add('is-in')
      arrivalObserver.unobserve(entry.target) // once per tile per tab
    })

    return () => arrivalObserver.unobserve(el)
  }, [resetKey])

  return ref
}

/** True while at least half the element is on screen (section 4.2). */
export function useHalfVisible<T extends HTMLElement>() {
  const ref = useRef<T | null>(null)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    playbackObserver.observe(el, (entry) => setVisible(entry.intersectionRatio >= 0.5))
    return () => playbackObserver.unobserve(el)
  }, [])

  return [ref, visible] as const
}
