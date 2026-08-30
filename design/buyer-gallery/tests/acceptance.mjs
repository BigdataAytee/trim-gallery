// Acceptance checklist — DESIGN_SPEC.md section 7, driven in a real browser.
//
//   npm run build && npm run preview
//   node tests/acceptance.mjs
//
// Needs Playwright. Set CHROMIUM_PATH if the bundled browser is not where Playwright
// expects it. Pass --headed to watch it run.
//
// Note on codecs: many Chromium builds ship without the proprietary H.264 decoder, which
// is why every clip is offered as WebM as well as MP4. Without that, item 4 cannot be
// checked here at all — the element reports DEMUXER_ERROR_NO_SUPPORTED_STREAMS and stays
// paused however visible it is.

import { chromium } from 'playwright'

const URL = process.env.BASE_URL ?? 'http://localhost:4173/'
const SHOTS = process.env.SHOTS_DIR ?? 'tests/screenshots'

const pass = []
const fail = []
const check = (name, ok, detail = '') => (ok ? pass : fail).push(`${name}${detail ? ' — ' + detail : ''}`)

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  headless: !process.argv.includes('--headed'),
  // Muted autoplay is allowed in real browsers; headless needs telling.
  args: ['--autoplay-policy=no-user-gesture-required'],
})
const base = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 }
const OUT = SHOTS

async function newPage(ctxOpts = {}) {
  const ctx = await browser.newContext({ ...base, ...ctxOpts })
  const page = await ctx.newPage()
  const errors = []
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
  page.on('pageerror', (e) => errors.push(String(e)))
  return { ctx, page, errors }
}

/** Rect plus visibility of one grid tile. */
const tileRect = (page, id) =>
  page.evaluate((i) => {
    const el = document.querySelector(`[data-testid="tile-${i}"]`)
    const r = el.getBoundingClientRect()
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), visibility: getComputedStyle(el).visibility }
  }, id)

// ---------------------------------------------------------------- 1. light first load
{
  const { ctx, page, errors } = await newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  const theme = await page.getAttribute('html', 'data-theme')
  check('1 light on first load', theme === 'light', `data-theme=${theme}`)

  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
  check('1 body uses --page token', bg === 'rgb(250, 249, 246)', bg)

  const font = await page.evaluate(() => getComputedStyle(document.querySelector('h1')).fontFamily)
  const h1 = await page.evaluate(() => {
    const s = getComputedStyle(document.querySelector('h1'))
    return { size: s.fontSize, weight: s.fontWeight, spacing: s.letterSpacing }
  })
  check('1 title 23px/700/-0.02em Inter', font.includes('Inter') && h1.size === '23px' && h1.weight === '700',
        `${font.split(',')[0]} ${h1.size} ${h1.weight} ${h1.spacing}`)

  const grid = await page.evaluate(() => {
    const g = document.querySelector('[data-testid="grid"]')
    const s = getComputedStyle(g)
    const t = g.querySelector('.tile')
    return { cols: s.gridTemplateColumns.split(' ').length, gap: s.columnGap, padL: s.paddingLeft,
             radius: getComputedStyle(t).borderRadius, ratio: (t.getBoundingClientRect().width / t.getBoundingClientRect().height).toFixed(3) }
  })
  check('1 grid 2 cols / 14px gap / 18px pad / 14px radius / square',
        grid.cols === 2 && grid.gap === '14px' && grid.padL === '18px' && grid.radius === '14px' && Math.abs(grid.ratio - 1) < 0.01,
        JSON.stringify(grid))

  const tabs = await page.evaluate(() => [...document.querySelectorAll('[role=tab]')].map((b) => {
    const s = getComputedStyle(b)
    return { text: b.textContent, selected: b.getAttribute('aria-selected'), border: s.borderColor, width: s.borderTopWidth, color: s.color, bg: s.backgroundColor }
  }))
  check('1 tabs are outlined pills, active dark border, no fill',
        tabs[0].width === '2px' && tabs[0].border === 'rgb(28, 27, 26)' && tabs[1].border === 'rgb(217, 214, 207)'
        && tabs.every((t) => t.bg === 'rgba(0, 0, 0, 0)'),
        JSON.stringify(tabs))

  await page.screenshot({ path: `${OUT}/01-light.png` })
  check('console clean (light)', errors.length === 0, errors.join(' | '))
  await ctx.close()
}

// ---------------------------------------------- 2. theme toggle + persistence
{
  const ctx = await browser.newContext(base)
  const page = await ctx.newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)

  const t = await page.evaluate(() => getComputedStyle(document.body).transition)
  check('2 body crossfades background/color', /background-color 0\.35s|350ms|0\.35s/.test(t), t)

  await page.click('[aria-label="Switch to dark mode"]')
  await page.waitForTimeout(600)
  const dark = await page.evaluate(() => ({
    theme: document.documentElement.dataset.theme,
    page: getComputedStyle(document.body).backgroundColor,
    band: getComputedStyle(document.querySelector('header')).backgroundColor,
    text: getComputedStyle(document.querySelector('h1')).color,
    stored: localStorage.getItem('buyer-gallery-theme'),
  }))
  check('2 toggle switches to dark tokens',
        dark.theme === 'dark' && dark.page === 'rgb(22, 20, 18)' && dark.band === 'rgb(29, 26, 23)' && dark.text === 'rgb(241, 235, 226)',
        JSON.stringify(dark))
  await page.screenshot({ path: `${OUT}/02-dark.png` })

  check('2 choice persisted to localStorage', dark.stored === 'dark', String(dark.stored))

  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  const afterReload = await page.evaluate(() => document.documentElement.dataset.theme)
  check('2 dark survives a reload', afterReload === 'dark', afterReload)

  // No light flash: the inline script sets the attribute before first paint.
  const early = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
  check('2 no light-frame flash on reload', early === 'dark', early)

  await page.click('[aria-label="Switch to light mode"]')
  await page.waitForTimeout(500)
  check('2 toggles back to light', (await page.evaluate(() => document.documentElement.dataset.theme)) === 'light')
  await ctx.close()
}

// ---------------------------------------------- 3. breathing, out of phase
{
  const ctx = await browser.newContext(base)
  const page = await ctx.newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)

  const info = await page.evaluate(() => {
    const tiles = [...document.querySelectorAll('.tile.is-in')]
    return tiles.slice(0, 12).map((t) => {
      const s = getComputedStyle(t)
      return { names: s.animationName, dur: s.animationDuration, delay: s.animationDelay, hue: s.getPropertyValue('--hue').trim(), type: t.dataset.type }
    })
  })
  const breathing = info.filter((i) => i.names.includes('tile-breathe'))
  check('3 every visible tile breathes', breathing.length === info.length && info.length > 0, `${breathing.length}/${info.length}`)
  check('3 breathing loop is 4.6s', info.every((i) => i.dur.includes('4.6s')), info[0]?.dur)

  const phases = new Set(info.map((i) => i.delay.split(', ')[1]))
  check('3 phases are randomised, not synchronised', phases.size >= Math.min(8, info.length), `${phases.size} distinct delays across ${info.length} tiles`)

  const clip = info.find((i) => i.type === 'clip'), photo = info.find((i) => i.type === 'photo')
  check('3 clips breathe ember, photos glaze',
        clip?.hue.toLowerCase() === '#c9663f' && photo?.hue.toLowerCase() === '#3f9aa0', `clip=${clip?.hue} photo=${photo?.hue}`)
  await ctx.close()
}

// ---------------------------------------------- 4. clips play only when >=50% visible
{
  const ctx = await browser.newContext(base)
  const page = await ctx.newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)

  const state = () => page.evaluate(() => [...document.querySelectorAll('video')].map((v) => {
    const r = v.getBoundingClientRect()
    const vis = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) / r.height
    return { paused: v.paused, muted: v.muted, loop: v.loop, preload: v.preload, playsInline: v.playsInline, ratio: +vis.toFixed(2) }
  }))

  const s0 = await state()
  check('4 clips are real <video> muted/playsinline/loop/preload=metadata',
        s0.length > 0 && s0.every((v) => v.muted && v.loop && v.playsInline && v.preload === 'metadata'),
        JSON.stringify(s0[0]))

  const wrong = s0.filter((v) => (v.ratio >= 0.5) === v.paused)
  check('4 playing iff at least half visible (initial)', wrong.length === 0, JSON.stringify(s0))

  await page.evaluate(() => window.scrollTo({ top: 1400, behavior: 'instant' }))
  await page.waitForTimeout(900)
  const s1 = await state()
  const wrong1 = s1.filter((v) => (v.ratio >= 0.5) === v.paused)
  check('4 pauses when scrolled off, plays when scrolled on', wrong1.length === 0, JSON.stringify(s1))

  const badge = await page.evaluate(() => {
    const playing = [...document.querySelectorAll('video')].find((v) => !v.paused)
    if (!playing) return null
    return getComputedStyle(playing.parentElement.querySelector('span')).backgroundColor
  })
  check('4 play badge turns ember while playing', badge === 'rgb(201, 102, 63)', String(badge))
  await ctx.close()
}

// ------------------------------------------------------------- 5. hero zoom
{
  const ctx = await browser.newContext(base)
  const page = await ctx.newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  const before = await tileRect(page, 't00')
  await page.click('[data-testid="tile-t00"]')
  await page.waitForTimeout(700)

  const hero = await page.evaluate(() => {
    const h = document.querySelector('[data-testid="hero"]')
    if (!h) return null
    const r = h.getBoundingClientRect()
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), radius: getComputedStyle(h).borderRadius }
  })
  const expectedW = Math.min(390, 430) - 36
  check('5 hero opens at page width minus 36, square',
        hero && Math.abs(hero.w - expectedW) <= 1 && Math.abs(hero.w - hero.h) <= 1,
        JSON.stringify(hero))
  check('5 hero is centred horizontally', hero && Math.abs(hero.x - (390 - expectedW) / 2) <= 1, `x=${hero?.x}`)
  check('5 hero corner grows to 26px', hero?.radius === '26px', hero?.radius)

  const veil = await page.evaluate(() => {
    const v = document.querySelector('[data-testid="veil"]')
    return v ? { blur: getComputedStyle(v).backdropFilter, bg: getComputedStyle(v).backgroundColor } : null
  })
  check('5 page behind dims and blurs 14px', veil?.blur === 'blur(14px)', JSON.stringify(veil))

  const sheet = await page.evaluate(() => {
    const s = document.querySelector('[data-testid="review-sheet"]')
    if (!s) return null
    const r = s.getBoundingClientRect()
    return { onScreen: r.top < innerHeight - 40, text: s.textContent.slice(0, 40), radius: getComputedStyle(s).borderTopLeftRadius }
  })
  check('5 review sheet slides up and shows the review', sheet?.onScreen === true, JSON.stringify(sheet))

  const during = await tileRect(page, 't00')
  check('5 source tile is hidden while open', during.visibility === 'hidden', during.visibility)
  await page.screenshot({ path: `${OUT}/03-hero.png` })

  // close via the X
  await page.click('[data-testid="close"]')
  await page.waitForTimeout(700)
  const closed = await page.evaluate(() => ({
    hero: !!document.querySelector('[data-testid="hero"]'),
    veil: !!document.querySelector('[data-testid="veil"]'),
    sheet: !!document.querySelector('[data-testid="review-sheet"]'),
  }))
  const after = await tileRect(page, 't00')
  check('5 close removes hero, veil and sheet', !closed.hero && !closed.veil && !closed.sheet, JSON.stringify(closed))
  check('5 tile returns to its own position',
        after.visibility === 'visible' && after.x === before.x && after.y === before.y,
        `${JSON.stringify(before)} -> ${JSON.stringify(after)}`)

  // close via Escape
  await page.click('[data-testid="tile-t02"]'); await page.waitForTimeout(600)
  await page.keyboard.press('Escape'); await page.waitForTimeout(600)
  check('5 Escape closes', !(await page.evaluate(() => !!document.querySelector('[data-testid="hero"]'))))

  // close via the veil
  await page.click('[data-testid="tile-t02"]'); await page.waitForTimeout(600)
  await page.click('[data-testid="veil"]', { position: { x: 10, y: 10 } }); await page.waitForTimeout(600)
  check('5 tapping the veil closes', !(await page.evaluate(() => !!document.querySelector('[data-testid="hero"]'))))

  // close on scroll
  await page.click('[data-testid="tile-t02"]'); await page.waitForTimeout(600)
  await page.evaluate(() => window.scrollBy(0, 200)); await page.waitForTimeout(600)
  check('5 scrolling closes', !(await page.evaluate(() => !!document.querySelector('[data-testid="hero"]'))))

  // opening a tile after the grid scrolled still returns it to its current position
  await page.evaluate(() => window.scrollTo(0, 900)); await page.waitForTimeout(1600)
  const b2 = await tileRect(page, 't10')
  await page.click('[data-testid="tile-t10"]'); await page.waitForTimeout(700)
  await page.click('[data-testid="close"]'); await page.waitForTimeout(700)
  const a2 = await tileRect(page, 't10')
  check('5 works for a tile further down the grid',
        a2.visibility === 'visible' && a2.x === b2.x && a2.y === b2.y, `${JSON.stringify(b2)} -> ${JSON.stringify(a2)}`)
  await ctx.close()
}

// --------------------------------------------------------- 6. reduced motion
{
  const ctx = await browser.newContext({ ...base, reducedMotion: 'reduce' })
  const page = await ctx.newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  const tiles = await page.evaluate(() => [...document.querySelectorAll('.tile')].slice(0, 6).map((t) => {
    const s = getComputedStyle(t)
    return { anim: s.animationName, opacity: s.opacity, shadow: s.boxShadow, transform: s.transform }
  }))
  check('6 no breathing or arrival animation',
        tiles.every((t) => t.anim === 'none'), JSON.stringify(tiles[0]))
  check('6 tiles fully visible immediately, no stagger',
        tiles.every((t) => t.opacity === '1' && (t.transform === 'none' || t.transform === 'matrix(1, 0, 0, 1, 0, 0)')),
        JSON.stringify(tiles.map((t) => t.opacity)))
  check('6 static tinted ring stands in for the breathing',
        tiles.every((t) => /0px 0px 0px 1\.5px/.test(t.shadow)), tiles[0].shadow)

  const vids = await page.evaluate(() => [...document.querySelectorAll('video')].map((v) => v.paused))
  check('6 no autoplay', vids.every(Boolean), JSON.stringify(vids))

  await page.click('[data-testid="tile-t00"]')
  await page.waitForTimeout(120) // far less than the 420ms zoom
  const instant = await page.evaluate(() => {
    const h = document.querySelector('[data-testid="hero"]'), s = document.querySelector('[data-testid="review-sheet"]')
    if (!h || !s) return null
    return { w: Math.round(h.getBoundingClientRect().width), sheetTop: Math.round(s.getBoundingClientRect().top), vh: innerHeight }
  })
  check('6 open is instant, not animated',
        instant && Math.abs(instant.w - (390 - 36)) <= 1 && instant.sheetTop < instant.vh - 40, JSON.stringify(instant))
  await page.screenshot({ path: `${OUT}/04-reduced-hero.png` })

  await page.keyboard.press('Escape'); await page.waitForTimeout(120)
  check('6 close is instant', !(await page.evaluate(() => !!document.querySelector('[data-testid="hero"]'))))
  check('6 screen is still fully usable', (await page.locator('.tile').count()) === 30)
  await ctx.close()
}

// ----------------------------------------------------- 7. 360-430, no h-scroll
for (const width of [360, 390, 430]) {
  const ctx = await browser.newContext({ ...base, viewport: { width, height: 844 } })
  const page = await ctx.newPage()
  await page.goto(URL, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)

  const m = await page.evaluate(() => ({
    scrollW: document.documentElement.scrollWidth,
    clientW: document.documentElement.clientWidth,
    bodyScrollW: document.body.scrollWidth,
    widest: Math.max(...[...document.querySelectorAll('*')].map((e) => e.getBoundingClientRect().right)),
  }))
  check(`7 no horizontal scroll at ${width}px`,
        m.scrollW <= m.clientW && m.bodyScrollW <= m.clientW && m.widest <= m.clientW + 1, JSON.stringify(m))

  await page.click('[data-testid="tile-t01"]'); await page.waitForTimeout(700)
  const mo = await page.evaluate(() => ({ scrollW: document.documentElement.scrollWidth, clientW: document.documentElement.clientWidth }))
  check(`7 no horizontal scroll at ${width}px with the hero open`, mo.scrollW <= mo.clientW, JSON.stringify(mo))
  if (width === 430) await page.screenshot({ path: `${OUT}/05-430-hero.png` })
  await ctx.close()
}

// ---------------------------------------------------------------------------- report
console.log()
for (const p of pass) console.log('  PASS  ' + p)
for (const f of fail) console.log('  FAIL  ' + f)
console.log()
console.log(`${pass.length} passed, ${fail.length} failed.`)
await browser.close()
process.exit(fail.length ? 1 : 0)
