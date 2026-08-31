# DESIGN_SYSTEM.md — Trim Gallery

## Principles
Media first: UI is near-invisible, media is the interface. Dark by default. Motion is continuous and physical, never decorative. One typeface, one accent. Nothing jumps or cuts.

## Colour
| Token | Dark (default) | Light |
|---|---|---|
| bg | #0B0B0C | #FAFAF8 |
| surface | #141416 | #FFFFFF |
| surface-2 | #1E1E21 | #F1F1EE |
| text | #F2F2F0 | #141416 |
| text-muted | #9A9A9F | #6B6B70 |
| accent | #7CE7C4 (mint) | #16A37B |
| accent-on | #062018 | #FFFFFF |
| danger | #FF6B6B | #D63B3B |
| warning | #FFC857 | #B8860B |
| scrim | rgba(0,0,0,.6) | rgba(0,0,0,.4) |
Media always sits on `bg`; chrome uses `surface` at 85% with blur. Accent is used only for progress, primary actions and "freed" numbers.

## Typography
Family: Inter (variable). Fallback: system.
| Style | Size/Line | Weight | Use |
|---|---|---|---|
| display | 40/44 | 600 | "Freed 6.2 GB" |
| title | 22/28 | 600 | screen titles |
| heading | 17/24 | 600 | cards, sections |
| body | 15/22 | 400 | default |
| label | 13/18 | 500 | chips, meta |
| caption | 11/16 | 400 | timestamps |
Numbers use tabular figures.

## Spacing and shape
4-pt grid. Insets 16. Card padding 16. Grid gutter 2 (day), 1 (month), 0 (year). Radii: thumbnail 4, card 16, sheet 24, button 12, chip 999. Elevation via blur + 1-px hairline (`text` at 8%), not shadows.

## Motion tokens
| Token | Spec | Use |
|---|---|---|
| spring-standard | stiffness 400, damping 30 | most transitions |
| spring-gentle | stiffness 250, damping 28 | sheets, cards |
| spring-snappy | stiffness 700, damping 35 | toggles, chips |
| shared-element | spring-standard on bounds + corner radius 4→0 | grid ↔ viewer |
| dismiss | drag follows finger 1:1, release → spring-standard back to grid slot, chrome fades 120 ms | viewer close |
| grid-zoom | pinch scales cells continuously; snap to level with spring-gentle | day/month/year |
| progress-ring | 2-px stroke, accent, animates length; 60 fps | thumbnail while processing |
| result-card | slides in from top with spring-gentle; number counts up 800 ms ease-out | morning |
| reveal | opacity 0→1 over 150 ms, y 8→0 | lists |
Reduce-motion: springs become 200 ms ease-out, no count-ups.

## Components
Grid cell (image, video badge with duration, progress ring, selection check) · Viewer (chrome top/bottom, info sheet) · Result card · Space ring (total freed, this month vs cap) · History row (before → after, factor, restore) · Skipped row (reason) · Duplicate group (best pre-selected) · Person chip/avatar · Search chip · Sheet (bottom, drag handle) · Button (primary accent, secondary surface-2, text) · Toggle · Segmented control · Empty state (icon, one line, one action) · Snackbar with undo.

## Iconography
Outline, 1.75-px stroke, 24 grid; Material Symbols Rounded as base, custom: trim (single diagonal cut), freed (down-arrow into ring), offload (arrow to card).

## Copy tone
Short, calm, concrete. Numbers over adjectives. Never "compress", "shrink" in user-facing copy — use "optimise", "freed", "smaller". Never alarm: "Paused for heat" not "Overheating".

## Accessibility
Contrast ≥ 4.5:1 for text; all controls ≥ 48 dp; TalkBack labels for every cell (date, type, optimised state); dynamic type up to 200%; reduce-motion honoured.
