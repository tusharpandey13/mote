// Renders design artboards to README screenshots + hero. See AGENTS.md.
// Usage: PLAYWRIGHT_DIR=<dir containing node_modules/playwright> node tools/readme-shots/shots.mjs
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
const names = ['media-light', 'media', 'pad', 'keys', 'macros', 'macro-editor', 'device-picker'];

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 3 });
const page = await ctx.newPage();
for (const n of names) {
  await page.goto(pathToFileURL(path.join(root, 'design/mote-v1', n + '.html')).href);
  await page.evaluate(() => document.fonts.ready);
  const raw = path.join(tmp, n + '.png');
  await page.locator('.screen').first().screenshot({ path: raw });
  const dst = path.join(out, n + '.png');
  execFileSync('sips', ['--resampleWidth', '600', raw, '--out', dst], { stdio: 'ignore' });
  if (hasPngquant()) execFileSync('pngquant', ['--force', '--quality=0-60', '--speed', '1', '--output', dst, '48', '--', dst]);
  console.log(n, statSync(dst).size);
}

// Hero: three phones in CSS frames.
const img = (n) => 'data:image/png;base64,' + readFileSync(path.join(tmp, n + '.png')).toString('base64');
const phone = (n, extra = '') => `<div class="p" style="${extra}"><img src="${img(n)}"></div>`;
const html = `<style>
body{margin:0;width:1800px;height:1000px;background:radial-gradient(ellipse at 50% 30%,#2a2e36,#0f1114);display:flex;align-items:center;justify-content:center;gap:70px}
.p{width:380px;height:844px;border-radius:56px;padding:12px;box-sizing:border-box;background:#0b0c0e;box-shadow:0 0 0 2px #3a3f47,0 40px 80px rgba(0,0,0,.55)}
.p img{width:100%;height:100%;border-radius:44px;display:block}
</style>${phone('pad', 'transform:scale(.9)')}${phone('media-light', 'transform:scale(1.05)')}${phone('media', 'transform:scale(.9)')}`;
const hp = await browser.newPage({ viewport: { width: 1800, height: 1000 }, deviceScaleFactor: 1 });
await hp.setContent(html);
const heroRaw = path.join(tmp, 'hero.png');
await hp.screenshot({ path: heroRaw });
const hero = path.join(out, 'hero.png');
execFileSync('sips', ['--resampleWidth', '1400', heroRaw, '--out', hero], { stdio: 'ignore' });
if (hasPngquant()) execFileSync('pngquant', ['--force', '--quality=60-90', '--output', hero, hero]);
console.log('hero', statSync(hero).size);
await browser.close();

function hasPngquant() { try { execFileSync('pngquant', ['--version'], { stdio: 'ignore' }); return true; } catch { return false; } }
