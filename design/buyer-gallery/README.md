# Buyer gallery — motion reference (not shipped)

A React + Vite prototype of the "Photos and clips from buyers" screen, built from
[`buyer-gallery-spec/`](../../buyer-gallery-spec) (`DESIGN_SPEC.md`,
`reference-mockup.png`, `reference-prototype.html`).

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

So: treat this as an executable motion spec to port to Compose MP at milestone 8, not
as a codebase to integrate.

## Status

Scaffolded and stocked, **not finished** — parked on request when the KMP brief
arrived. What exists:

- Vite + React + TypeScript, Tailwind v4, Motion (`motion/react`), `lucide-react`.
- `public/tiles.json` in the DESIGN_SPEC.md §6 shape (23 photos, 6 clips, 1 file).
- `public/media/` — generated stand-in artwork: JPEG stills, **real H.264 MP4 clips**
  with posters, and a small PDF for the file tile. 888 KB total.
- `tools/generate-media.mjs` + `tools/artwork.mjs` regenerate all of it
  deterministically. Dev-only; the committed output means neither Playwright nor
  ffmpeg is needed to run the app.

Not yet written: the components, theming, and the section 7 acceptance pass.

## Running it

```
npm install
npm run dev
```

Regenerating the media (needs `playwright` and `ffmpeg-static`):

```
node tools/generate-media.mjs
```
