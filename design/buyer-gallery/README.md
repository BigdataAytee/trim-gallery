# Buyer gallery — motion reference (not shipped)

The "Photos and clips from buyers" screen, built from
[`buyer-gallery-spec/`](../../buyer-gallery-spec) (`DESIGN_SPEC.md`,
`reference-mockup.png`, `reference-prototype.html`). React + Vite, Tailwind v4, Motion,
Lucide.

## Why it is here, and why it is not in the app

ARCHITECTURE.md §11 is unambiguous: **Compose Multiplatform for every screen.** A React
app cannot ship inside Trim Gallery, and the subject does not match either — this is a
product-review gallery for a ceramics shop, not a personal photo library.

What *does* transfer is the motion, which is close to what BUILD.md §9 asks of the
gallery shell:

| Prototype behaviour | Trim Gallery equivalent (milestone 8) |
|---|---|
| Hero zoom, FLIP from tile rect, closes back into place | Shared-element transition grid ↔ viewer |
| Clips autoplay muted only while ≥50% visible | Videos autoplay muted on hover in the grid |
| Staggered arrival, 70ms, once per tile | Grid population |
| Breathing tiles, random phase | Thin progress ring on thumbnails being processed |
| `data-theme` token swap, 350ms crossfade, persisted | Dark by default, chrome fades when idle |
| `prefers-reduced-motion` fallback throughout | Accessibility floor for the shell |

Treat it as an executable motion spec to port to Compose MP, not a codebase to integrate.

## Running it

```
npm install
npm run dev
```

## Acceptance checklist

DESIGN_SPEC.md §7, driven in a real browser — 45 assertions, all passing:

```
npm run build && npm run preview   # in one terminal
npm run test:acceptance            # in another
```

The suite exits non-zero on failure, so it can gate a change. `--headed` watches it run.

## How it is built

- **Tokens** (`src/index.css`) — every colour from the §3 table lives on `:root` and
  `[data-theme='dark']`, so the theme switch is one attribute change. Nothing hard-codes
  a colour.
- **Theme** — light on first load; an inline script in `index.html` applies the stored
  choice *before first paint*, so dark never flashes light on reload.
- **Motion split** — arrival and breathing are CSS keyframes, because thirty tiles on an
  infinite 4.6s loop is compositor work React should not re-render for. The hero zoom and
  press feedback are Motion, where the FLIP and gesture handling earn their keep.
- **Hero zoom** — a shared-layout animation: the grid tile and the hero carry the same
  `layoutId`, so Motion measures both and FLIPs between them. Closing needs no separate
  animation — unmounting the hero hands the id back to the tile, which returns to
  whatever position it now occupies even if the grid scrolled.
- **Observers** — two shared `IntersectionObserver`s (arrival at `-6%` root margin,
  playback at threshold 0.5) rather than one per tile.
- **Breathing phase** — derived from a hash of the tile id, not `Math.random()`: the
  spread across tiles is just as arbitrary, but it is stable across re-renders and
  reloads, so the animation cannot jump and screenshots stay reproducible.

## Media

`public/tiles.json` is the §6 shape (23 photos, 6 clips, 1 file). `public/media/` holds
generated stand-in artwork — not real buyer photos — as JPEG stills, **real H.264 clips
with WebM alternates**, posters and a small PDF. ~1.1 MB.

Clips ship in both codecs because many Chromium builds have no H.264 decoder and would
otherwise show only the poster. `srcWebm` is an optional field; a real API may omit it
and the player falls back to `src`.

Regenerate deterministically (needs `playwright` and `ffmpeg-static`):

```
npm run generate:media
```

## Known deviations from the mockup

- **The header title wraps to two lines.** `reference-mockup.png` shows it on one, but
  that screenshot has no theme toggle; DESIGN_SPEC.md §2 requires one. At 430px, a 46px
  button on each side leaves ~270px for a title that needs ~320px at the specified 23px.
  The spec's requirement wins over the screenshot.
- **Tab counts come from the data** (`Photos (30)`), not the mockup's `214`. Counts are
  content, not layout, and a UI that reports what it actually loaded is more useful than
  one repeating a number from a screenshot. Swap in an API with 214 photos and it reads
  214.
- **The theme icon settles upright.** The reference prototype parks the moon at a
  permanent 200° rotation; here the rotation is the transition and the icon ends level,
  because a permanently rotated crescent reads as the wrong moon.
