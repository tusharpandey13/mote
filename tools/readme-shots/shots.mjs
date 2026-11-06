// Renders design/mote-v1 artboards to README screenshots, the hero, and site/og.png. See AGENTS.md.
// Artboards = every docs/assets/readme/<name>.png referenced in README.md (except hero), so adding one there is enough.
// Usage: [PLAYWRIGHT_DIR=<dir with node_modules/playwright>] node tools/readme-shots/shots.mjs
// pngquant is optional (smaller files); install it in CI and locally for identical output.
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const pwDir = process.env.PLAYWRIGHT_DIR || path.join(root, 'e2e/web');
const { chromium } = createRequire(path.join(pwDir, 'x.js'))('playwright');
const out = path.join(root, 'docs/assets/readme');
const tmp = mkdtempSync(path.join(tmpdir(), 'mote-shots-'));
const readme = readFileSync(path.join(root, 'README.md'), 'utf8');
const names = [...new Set([...readme.matchAll(/docs\/assets\/readme\/([\w-]+)\.png/g)].map((m) => m[1]))].filter((n) => n !== 'hero');

const quant = (file, q, extra = []) => {
  try { execFileSync('pngquant', ['--force', `--quality=${q}`, '--speed', '1', ...extra, '--output', file, file]); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  console.log(path.relative(root, file), statSync(file).size);
};

const browser = await chromium.launch();
// Scale factor 600/412 renders each 412px-wide screen at 600px directly (no resampler needed).
const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 600 / 412, reducedMotion: 'reduce' });
const page = await ctx.newPage();
for (const n of names) {
  await page.goto(pathToFileURL(path.join(root, 'design/mote-v1', n + '.html')).href);
  await page.evaluate(() => document.fonts.ready);
  const dst = path.join(out, n + '.png');
  await page.locator('.screen').first().screenshot({ path: path.join(tmp, n + '.png') });
  await page.locator('.screen').first().screenshot({ path: dst });
  quant(dst, '0-60', ['48']);
}

// Hero (README) and og image (site): three phones in CSS frames.
const img = (n) => 'data:image/png;base64,' + readFileSync(path.join(tmp, n + '.png')).toString('base64');
const phone = (n, s) => `<div class="p" style="transform:scale(${s})"><img src="${img(n)}"></div>`;
const html = (w, h, zoom) => `<style>
html{zoom:${zoom}}
body{margin:0;width:${w}px;height:${h}px;background:radial-gradient(ellipse at 50% 30%,#2a2e36,#0f1114);display:flex;align-items:center;justify-content:center;gap:70px}
.p{width:380px;height:844px;flex:none;border-radius:56px;padding:12px;box-sizing:border-box;background:#0b0c0e;box-shadow:0 0 0 2px #3a3f47,0 40px 80px rgba(0,0,0,.55)}
.p img{width:100%;height:100%;border-radius:44px;display:block}
</style>${phone('pad', 0.9)}${phone('media-light', 1.05)}${phone('media', 0.9)}`;
for (const [file, w, h, zoom, q] of [
  [path.join(out, 'hero.png'), 1800, 1000, 1400 / 1800, '60-90'],
  [path.join(root, 'site/og.png'), 1800, 945, 1200 / 1800, '60-90'],
]) {
  const hp = await browser.newPage({ viewport: { width: Math.round(w * zoom), height: Math.round(h * zoom) }, deviceScaleFactor: 1 });
  await hp.setContent(html(w, h, zoom));
  await hp.evaluate(() => Promise.all([...document.images].map((i) => i.decode())));
  await hp.screenshot({ path: file });
  quant(file, q);
}
await browser.close();
