// Deterministic stand-in artwork for the buyer gallery.
//
// These are not real buyer photos — they are generated ceramic studies that carry the
// same palette and weight as the tiles in reference-mockup.png, so layout, glaze
// colours and the hero zoom can be judged honestly without shipping stock imagery.
// Swap `public/media` for real assets when the API lands; nothing else changes.

/** Small deterministic PRNG so a given id always produces the same picture. */
export function rng(seed) {
  let s = seed * 2654435761 % 2147483647;
  if (s <= 0) s += 2147483646;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

/** Glaze palettes: [deep, mid, ground]. Warm and cool, all readable on cream. */
export const PALETTES = [
  ['#2a6a72', '#7fc4c8', '#e8f1f0'], // sea glaze
  ['#8a4a30', '#d98c5f', '#f3e4d6'], // ochre
  ['#5d6a3f', '#b7c48a', '#eef1e3'], // celadon
  ['#6a4a2a', '#c9a06a', '#f2e9da'], // raw clay
  ['#3f4d6a', '#98a8c8', '#e9edf4'], // night glaze
  ['#4a4a4a', '#b9b2a6', '#f0ede7'], // ash white
  ['#7d3f52', '#c98aa0', '#f4e6ea'], // plum
  ['#2f5f4a', '#84b79e', '#e6f0ea'], // pine
];

/** Vessel profiles, drawn as a filled path in a 100x100 box. */
const VESSELS = [
  // wide bowl
  'M20 46 q30 -10 60 0 q-4 30 -30 34 q-26 -4 -30 -34z',
  // tall vase
  'M38 20 q12 -6 24 0 q4 16 -4 24 q10 10 8 24 q-2 16 -20 16 q-18 0 -20 -16 q-2 -14 8 -24 q-8 -8 -4 -24z',
  // teapot body
  'M28 44 q22 -14 44 0 q4 26 -22 32 q-26 -6 -22 -32z',
  // stacked plates
  'M18 54 q32 -8 64 0 q-32 8 -64 0z M18 64 q32 -8 64 0 q-32 8 -64 0z M18 74 q32 -8 64 0 q-32 8 -64 0z',
  // mug
  'M32 34 h32 v30 q0 12 -16 12 q-16 0 -16 -12z',
  // bottle
  'M44 18 h12 v16 q14 10 14 28 q0 18 -20 18 q-20 0 -20 -18 q0 -18 14 -28z',
  // shallow dish
  'M16 50 q34 -6 68 0 q-8 20 -34 20 q-26 0 -34 -20z',
  // jar with lid
  'M32 38 q18 -8 36 0 q4 24 -18 30 q-22 -6 -18 -30z M34 30 q16 -6 32 0 q-16 6 -32 0z',
];

/**
 * One square SVG: pooled glaze ground, a vessel, rim light, speckle, vignette.
 * `slice`d square so it fills a tile with no letterboxing.
 */
export function artworkSvg(id) {
  const r = rng(id + 1);
  const p = PALETTES[id % PALETTES.length];
  const vessel = VESSELS[Math.floor(r() * VESSELS.length)];
  const angle = -7 + r() * 14;
  const cx = 24 + r() * 30;
  const cy = 18 + r() * 26;

  // Speckle: the iron spots you get in a reduction firing.
  let speckle = '';
  for (let i = 0; i < 26; i++) {
    const x = r() * 100;
    const y = r() * 100;
    const rad = 0.25 + r() * 0.6;
    speckle += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${rad.toFixed(2)}" fill="${p[0]}" opacity="${(0.05 + r() * 0.1).toFixed(2)}"/>`;
  }

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice">
  <defs>
    <radialGradient id="ground" cx="${cx}%" cy="${cy}%" r="95%">
      <stop offset="0" stop-color="${p[2]}"/>
      <stop offset=".62" stop-color="${p[1]}"/>
      <stop offset="1" stop-color="${p[0]}"/>
    </radialGradient>
    <linearGradient id="glaze" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${p[1]}"/>
      <stop offset=".55" stop-color="${p[0]}"/>
      <stop offset="1" stop-color="${p[0]}"/>
    </linearGradient>
    <linearGradient id="rim" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#ffffff" stop-opacity=".55"/>
      <stop offset=".5" stop-color="#ffffff" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="vig" cx="50%" cy="46%" r="72%">
      <stop offset=".55" stop-color="#000000" stop-opacity="0"/>
      <stop offset="1" stop-color="#000000" stop-opacity=".28"/>
    </radialGradient>
  </defs>

  <rect width="100" height="100" fill="url(#ground)"/>
  ${speckle}

  <!-- Table plane: the surface stays level even when the piece is set down askew. -->
  <path d="M0 74 q50 -7 100 0 v26 H0z" fill="${p[0]}" opacity=".13"/>
  <ellipse cx="50" cy="78" rx="30" ry="5.5" fill="${p[0]}" opacity=".3"/>

  <g transform="rotate(${angle.toFixed(1)} 50 70)">
    <path d="${vessel}" fill="url(#glaze)"/>
    <path d="${vessel}" fill="url(#rim)"/>
    <path d="${vessel}" fill="none" stroke="${p[2]}" stroke-opacity=".45" stroke-width=".7"/>
  </g>

  <rect width="100" height="100" fill="url(#vig)"/>
</svg>`;
}
