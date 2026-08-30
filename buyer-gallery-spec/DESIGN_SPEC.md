# Buyer gallery – front-end design spec

Use this document, `reference-mockup.png`, and `reference-prototype.html` as the source of truth for the "Photos and clips from buyers" screen. Open `reference-prototype.html` on a phone to feel the motion; match it, don't reinvent it.

## 1. Screen purpose
A mobile gallery of buyer-submitted photos, clips, and attached files for a product (handmade ceramics). Tapping any tile opens it full-size with the buyer's review.

## 2. Layout (match `reference-mockup.png` exactly)
- Max content width 430px, centred; safe-area insets respected.
- Header band: cream background, sticky at top.
  - Left: circular white back button (46px).
  - Title: "Photos and clips from buyers", bold, ~23px, letter-spacing -0.02em.
  - Right: circular theme toggle (sun/moon icon), same size as back button.
- Tabs: two outlined pills, `Photos (214)` and `Clips (6)`. Active pill has a 2px dark border and dark text; inactive has a light grey border and muted text. No filled/sliding background.
- Grid: 2 columns, 14px gap, square tiles, 14px corner radius, 18px page padding.
- Tile overlays: a rating chip (★ 5) bottom-left and a name/duration chip bottom-right, frosted white pill style. Clips get a circular play badge top-left.
- File tiles (PDF etc.) use the cream band colour with a centred label.

## 3. Theme
Light is the default on first load. A toggle in the header switches to dark. Persist the choice (localStorage or user setting). All colours come from CSS variables so the switch is a single attribute change (`data-theme="light|dark"`) with a 350ms crossfade.

| Token      | Light     | Dark      | Use |
|------------|-----------|-----------|-----|
| --page     | #faf9f6   | #161412   | body background |
| --band     | #f0efe9   | #1d1a17   | header band, file tiles |
| --card     | #ffffff   | #221f1b   | tiles, buttons, sheets |
| --text     | #1c1b1a   | #f1ebe2   | primary text |
| --muted    | #8a8782   | #8f877c   | secondary text, inactive tab |
| --line     | #d9d6cf   | #3a3630   | borders, inactive tab outline |
| --glaze    | #3f9aa0   | #4fa3a8   | breathing colour for photos/files |
| --ember    | #c9663f   | #c9663f   | breathing colour for clips, play badge |
| --glow-a   | 0.55      | 0.40      | glow strength multiplier |

Font: Inter (400/500/600/700), system-ui fallback.

## 4. Motion (all required)
1. **Breathing tiles.** Every tile slowly radiates: a 4.6s ease-in-out infinite loop that grows a 2px coloured ring plus a soft 30px halo (`box-shadow`) and brightens by 5%, then fades back. Each tile starts at a random negative animation-delay so they never pulse in sync. Photos/files use `--glaze`, clips use `--ember`.
2. **Clips play while scrolling.** Use an IntersectionObserver with threshold 0.5. When a clip tile is ≥50% visible: `video.play()` (muted, playsinline, loop, preload=metadata) and turn its play badge ember-orange. When it leaves: `video.pause()` and revert the badge. Never autoplay with sound.
3. **Staggered arrival.** Tiles start at opacity 0, translateY 18px, scale 0.96 and animate in over 600ms with a 70ms stagger as they enter the viewport (IntersectionObserver, run once per tile, re-run when the tab changes).
4. **Hero zoom on tap.** FLIP transition: the tapped tile's rect animates to a full-width square (page width minus 36px) over 420ms with a slight overshoot easing; the page behind dims to a translucent `--page` and blurs 14px; the review sheet slides up from the bottom (450ms, delayed 120ms). Closing reverses the zoom back into the tile's current position. Close on: × button, tapping the veil, Escape, or scroll.
5. **Press feedback.** Tiles and tabs scale to 0.97 on press.
6. **Reduced motion.** If `prefers-reduced-motion: reduce`: no breathing (static 1.5px tinted ring instead), no autoplay, no stagger, instant open/close.

## 5. Review sheet content
Avatar, buyer name, date + "verified buyer", star rating, review text (~17px), and "Bought: <variant>" line.

## 6. Data shape (per tile)
```json
{ "id": "string", "type": "photo|clip|file", "src": "url", "poster": "url?",
  "duration": "0:18?", "rating": 1-5, "buyer": "Amara O.", "date": "ISO",
  "text": "review body", "variant": "Tea set · Night glaze" }
```

## 7. Acceptance checklist
- [ ] First load is light, matches `reference-mockup.png` in spacing and type.
- [ ] Theme toggle flips to dark and back with a crossfade; choice persists.
- [ ] Every tile breathes, out of phase with its neighbours.
- [ ] Clips autoplay muted only while ≥50% on screen; pause when off screen.
- [ ] Tapping a tile zooms it open from its own position; closing returns it there.
- [ ] Reduced-motion users get a static, fully usable version.
- [ ] Works at 360–430px widths; no horizontal scroll.

## 8. Suggested prompt for Claude Code
> Build the buyer gallery screen described in DESIGN_SPEC.md. Match the layout in reference-mockup.png and the motion in reference-prototype.html. Use the token table for theming, default to light, and keep all animations behind a reduced-motion guard.
