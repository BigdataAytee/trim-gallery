// Generates the local media the gallery loads: JPEG stills, real MP4 clips with
// posters, and public/tiles.json in the shape from DESIGN_SPEC.md section 6.
//
// Run:  node tools/generate-media.mjs
// Needs: playwright (to rasterise the SVG artwork) and ffmpeg-static (to encode the
// clips). Both are dev-only; the generated files are committed, so building or
// running the app needs neither.
//
// Everything is deterministic — regenerating produces byte-identical artwork for a
// given id, so re-running does not churn the diff.

import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { artworkSvg, rng } from './artwork.mjs';

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const media = resolve(root, 'public/media');

const FFMPEG = process.env.FFMPEG_PATH ?? require('ffmpeg-static');
const { chromium } = require('playwright');

// ---------------------------------------------------------------- content

const BUYERS = [
  ['Amara O.', '2026-07-14'], ['Tobias K.', '2026-07-19'], ['Priya S.', '2026-07-22'],
  ['Lena M.', '2026-07-28'], ['Jun H.', '2026-08-01'], ['Femi A.', '2026-08-03'],
  ['Sofia R.', '2026-08-06'], ['Noah B.', '2026-08-09'], ['Ines D.', '2026-08-12'],
  ['Kwame T.', '2026-08-15'], ['Mira L.', '2026-08-18'], ['Otto V.', '2026-08-21'],
];

const REVIEWS = [
  'The glaze pools darker in the bottom of each cup — better than the listing photo.',
  "Heavier than expected in a good way. Feels like it'll outlive me.",
  'Arrived wrapped like a gift. The blue shifts green in morning light.',
  'Used it every day for a month. Zero crazing, zero regrets.',
  'The rim has a tiny wobble that makes it obviously handmade. Love that.',
  "My tea tastes different in this. Placebo? Don't care.",
  'Third order from this shop. The ochre is even better in person than the sea glaze.',
  'Packed in straw, not plastic. Small thing, but it mattered to me.',
  'Holds heat far longer than the mug it replaced. Morning coffee is still hot at the end.',
  'One arrived chipped and was replaced in four days, no argument. Buying again.',
];

const VARIANTS = [
  'Tea set · Night glaze', 'Serving bowl · Sea glaze', 'Plate set · Ochre',
  'Mug · Ash white', 'Vase · Raw clay', 'Jar · Celadon',
];

const TOTAL = 30;
const CLIP_INDICES = new Set([1, 6, 11, 17, 22, 27]);
const FILE_INDEX = 14;

// ---------------------------------------------------------------- helpers

function sh(bin, args) {
  execFileSync(bin, args, { stdio: ['ignore', 'ignore', 'pipe'] });
}

/** Rasterises one square SVG to JPEG at `size`, via headless Chromium. */
async function raster(page, svg, out, size) {
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(
    `<style>html,body{margin:0;padding:0;overflow:hidden}svg{display:block;width:${size}px;height:${size}px}</style>${svg}`,
    { waitUntil: 'load' },
  );
  await page.screenshot({ path: out, type: 'jpeg', quality: 82 });
}

/**
 * Encodes a still into a short clip with a slow push-in — the closest honest
 * stand-in for a buyer's handheld clip, and it exercises the real <video> path.
 */
function encodeClip(still, out, seconds, seed) {
  const r = rng(seed);
  const fps = 25;
  const frames = seconds * fps;
  const zoomTo = 1.12 + r() * 0.1;
  // zoompan wants the zoom expressed per frame; drift the centre a little too.
  const xDrift = (r() * 2 - 1) * 0.06;
  const yDrift = (r() * 2 - 1) * 0.06;

  sh(FFMPEG, [
    '-y', '-loglevel', 'error',
    '-loop', '1', '-i', still,
    '-vf', [
      `scale=1440:1440`,
      `zoompan=z='min(1+(${zoomTo - 1})*on/${frames},${zoomTo})'` +
        `:x='iw/2-(iw/zoom/2)+${(xDrift * 100).toFixed(2)}*on/${frames}'` +
        `:y='ih/2-(ih/zoom/2)+${(yDrift * 100).toFixed(2)}*on/${frames}'` +
        `:d=${frames}:s=640x640:fps=${fps}`,
      'format=yuv420p',
    ].join(','),
    '-frames:v', String(frames),
    '-c:v', 'libx264', '-preset', 'veryslow', '-crf', '30',
    '-movflags', '+faststart',
    '-an',
    out,
  ]);
}

// ---------------------------------------------------------------- build

// The session's preinstalled Chromium; PLAYWRIGHT_BROWSERS_PATH may point at a
// build the installed playwright package does not recognise.
const browser = await chromium.launch(
  process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {},
);
const page = await browser.newPage();

rmSync(media, { recursive: true, force: true });
mkdirSync(`${media}/photos`, { recursive: true });
mkdirSync(`${media}/clips`, { recursive: true });
mkdirSync(`${media}/posters`, { recursive: true });

const tiles = [];

for (let i = 0; i < TOTAL; i++) {
  const r = rng(i + 100);
  const [buyer, date] = BUYERS[i % BUYERS.length];
  const variant = VARIANTS[i % VARIANTS.length];
  const rating = r() < 0.78 ? 5 : 4;
  const id = `t${String(i).padStart(2, '0')}`;

  if (i === FILE_INDEX) {
    tiles.push({
      id,
      type: 'file',
      src: '/media/care-guide.pdf',
      label: 'Care guide from a buyer',
      meta: 'PDF · 2 pages',
      rating,
      buyer,
      date,
      text: 'Attached the care guide I wrote for my own studio — mostly "don\'t put it in the dishwasher, it deserves better."',
      variant,
    });
    continue;
  }

  const svg = artworkSvg(i);

  if (CLIP_INDICES.has(i)) {
    const poster = `${media}/posters/${id}.jpg`;
    await raster(page, svg, poster, 640);
    const seconds = 6 + (i % 3) * 2;
    encodeClip(poster, `${media}/clips/${id}.mp4`, seconds, i + 7);
    tiles.push({
      id,
      type: 'clip',
      src: `/media/clips/${id}.mp4`,
      poster: `/media/posters/${id}.jpg`,
      duration: `0:${String(seconds).padStart(2, '0')}`,
      rating,
      buyer,
      date,
      text: REVIEWS[i % REVIEWS.length],
      variant,
    });
    continue;
  }

  const still = `${media}/photos/${id}.jpg`;
  await raster(page, svg, still, 720);
  tiles.push({
    id,
    type: 'photo',
    src: `/media/photos/${id}.jpg`,
    rating,
    buyer,
    date,
    text: REVIEWS[i % REVIEWS.length],
    variant,
  });
}

await browser.close();

writeFileSync(resolve(root, 'public/tiles.json'), `${JSON.stringify(tiles, null, 2)}\n`);

const counts = tiles.reduce((a, t) => ({ ...a, [t.type]: (a[t.type] ?? 0) + 1 }), {});
console.log('tiles.json written:', counts);
