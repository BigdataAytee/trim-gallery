import { Play } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useHalfVisible } from '../hooks/useIntersection'
import type { Tile } from '../types'

/**
 * A clip's artwork: a real `<video>`, muted and looping, that plays only while at least
 * half of it is on screen (DESIGN_SPEC.md section 4.2).
 *
 * `preload="metadata"` keeps thirty tiles from pulling thirty full files; the poster
 * carries the first frame until playback starts.
 */
export function ClipArt({ tile, reduced }: { tile: Tile; reduced: boolean }) {
  const [ref, visible] = useHalfVisible<HTMLVideoElement>()
  const shouldPlay = visible && !reduced

  // Driven by the element's own play/pause events rather than by our intent. play() can
  // be refused — an autoplay policy, a backgrounded tab — and a badge that says "playing"
  // over a still frame is a lie the viewer can see.
  const [playing, setPlaying] = useState(false)

  useEffect(() => {
    const video = ref.current
    if (!video) return
    if (shouldPlay) {
      // A rejected play() is normal (a tab in the background, an autoplay policy);
      // the poster stays up and nothing breaks.
      void video.play().catch(() => undefined)
    } else {
      video.pause()
    }
  }, [shouldPlay, ref])

  return (
    <>
      <video
        ref={ref}
        className="absolute inset-0 size-full object-cover"
        poster={tile.poster}
        muted
        playsInline
        loop
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        // Decorative here: the review sheet carries the meaning when the tile is opened.
        aria-hidden
      >
        {tile.srcWebm && <source src={tile.srcWebm} type="video/webm" />}
        <source src={tile.src} type="video/mp4" />
      </video>
      <span
        className="absolute grid size-[30px] place-items-center rounded-full"
        style={{
          top: 10,
          left: 10,
          background: playing ? 'var(--ember)' : 'var(--chip-bg)',
          color: playing ? '#fff' : 'var(--chip-fg)',
          transition: 'background 300ms, color 300ms',
        }}
      >
        <Play size={12} fill="currentColor" strokeWidth={0} />
      </span>
    </>
  )
}

/** A photo tile. */
export function PhotoArt({ tile }: { tile: Tile }) {
  return (
    <img
      className="absolute inset-0 size-full object-cover"
      src={tile.src}
      alt={`Photo from ${tile.buyer}`}
      loading="lazy"
      decoding="async"
    />
  )
}

/** An attached file: the cream band colour with a centred label (section 2). */
export function FileArt({ tile }: { tile: Tile }) {
  return (
    <div
      className="absolute inset-0 grid place-items-center p-4 text-center"
      style={{ background: 'var(--band)' }}
    >
      <div>
        <div style={{ fontSize: 14, fontWeight: 600, lineHeight: 1.3 }}>{tile.label}</div>
        <div
          style={{
            fontSize: 11,
            fontWeight: 600,
            letterSpacing: '0.08em',
            marginTop: 8,
            color: 'var(--muted)',
            textTransform: 'uppercase',
          }}
        >
          {tile.meta}
        </div>
      </div>
    </div>
  )
}
