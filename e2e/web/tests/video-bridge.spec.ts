// Labs: Mote Video Bridge (docs/labs/video-bridge.md). Loads extension/ unpacked into a persistent
// Chromium context, pairs it with a --dry-run helper (bridge on, own port), and drives local test
// pages from a fake phone on the main WebSocket. Real sites are never contacted: every request that
// isn't to 127.0.0.1 is aborted, and www.youtube.com is a local mock served by a route.
import { test, expect, chromium, BrowserContext, Page, Worker } from '@playwright/test';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { Helper, REPO } from '../lib/helper';

const EXT = path.join(REPO, 'extension');
const FIX = path.join(__dirname, '../fixtures/video');
const TYPES: Record<string, string> = { '.html': 'text/html', '.webm': 'video/webm', '.vtt': 'text/vtt' };

test.describe.configure({ mode: 'serial' });

/** Static server for the fixtures, with Range support so the <video> can seek. */
function serve(): Promise<http.Server> {
  const srv = http.createServer((req, res) => {
    const f = path.join(FIX, path.basename(new URL(req.url!, 'http://x').pathname));
    if (!fs.existsSync(f)) { res.writeHead(404).end(); return; }
    const body = fs.readFileSync(f);
    const type = TYPES[path.extname(f)] || 'application/octet-stream';
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (m) {
      const a = +m[1], b = m[2] ? +m[2] : body.length - 1;
      res.writeHead(206, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${a}-${b}/${body.length}`, 'Content-Length': b - a + 1 });
      res.end(body.subarray(a, b + 1));
    } else {
      res.writeHead(200, { 'Content-Type': type, 'Accept-Ranges': 'bytes', 'Content-Length': body.length });
      res.end(body);
    }
  });
  return new Promise((r) => srv.listen(0, '127.0.0.1', () => r(srv)));
}

/** The phone: a native client on the main port, authed and subscribed to labs.video.state. */
class Phone {
  states: any[] = [];
  errors: any[] = [];
  private constructor(private ws: WebSocket) {}
  static async connect(h: Helper) {
    const cred = await h.pairDevice('video bridge phone');
    const ws = new WebSocket(`ws://127.0.0.1:${h.port}/ws`);
    const p = new Phone(ws);
    const hello = await new Promise<any>((r, j) => { ws.onmessage = (e) => r(JSON.parse(String(e.data))); ws.onerror = j; });
    ws.onmessage = (e) => {
      const m = JSON.parse(String(e.data));
      if (m.t === 'labs.video.state') p.states.push(m);
      if (m.t === 'err') p.errors.push(m);
    };
    const mac = crypto.createHmac('sha256', Buffer.from(cred.token, 'hex')).update(hello.nonce).digest('hex');
    ws.send(JSON.stringify({ t: 'auth', id: cred.id, mac }));
    ws.send(JSON.stringify({ t: 'labs.video.sub', on: true }));
    return p;
  }
  get last() { return this.states[this.states.length - 1]; }
  cmd(cmd: string, value?: unknown) { this.ws.send(JSON.stringify({ t: 'labs.video.cmd', cmd, ...(value === undefined ? {} : { value }) })); }
  close() { this.ws.close(); }
}

let helper: Helper, bport: number, srv: http.Server, base: string;
let ctx: BrowserContext, sw: Worker, extId: string, phone: Phone, userDir: string;

test.beforeAll(async () => {
  helper = await Helper.start({ args: ['--labs-bridge', '--bridge-port', '0'] });
  bport = +(await helper.waitFor(/bridge: listening on 127\.0\.0\.1:(\d+)/))[1];
  srv = await serve();
  base = `http://127.0.0.1:${(srv.address() as any).port}`;
  userDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-ext-'));
  ctx = await chromium.launchPersistentContext(userDir, {
    channel: 'chromium', // the full Chromium build: headless with extensions
    viewport: { width: 1000, height: 700 },
    args: [`--disable-extensions-except=${EXT}`, `--load-extension=${EXT}`, '--autoplay-policy=no-user-gesture-required'],
  });
  // Nothing leaves the machine: abort everything that isn't loopback (registered first, so it runs last).
  await ctx.route((u) => u.hostname !== '127.0.0.1' && u.protocol.startsWith('http'), (r) => r.abort());
  await ctx.route('https://www.youtube.com/**', (r) => {
    const p = new URL(r.request().url()).pathname;
    const f = p.endsWith('.webm') ? 'clip.webm' : 'youtube.html';
    return r.fulfill({ status: 200, contentType: TYPES[path.extname(f)], body: fs.readFileSync(path.join(FIX, f)) });
  });
  sw = ctx.serviceWorkers()[0] ?? (await ctx.waitForEvent('serviceworker'));
  extId = new URL(sw.url()).host;
  phone = await Phone.connect(helper);
  await expect.poll(() => phone.last?.bridge).toBe('waiting');
});

test.afterAll(async () => {
  phone?.close();
  await ctx?.close();
  srv?.close();
  await helper?.stop();
  fs.rmSync(userDir, { recursive: true, force: true });
});

const video = (p: Page, sel = 'video') => p.locator(sel).first().evaluate((v: HTMLVideoElement) => ({
  paused: v.paused, time: v.currentTime, rate: v.playbackRate, volume: v.volume, muted: v.muted,
  captions: [...v.textTracks].map((t) => t.mode).join(','), fs: v.hasAttribute('data-mote-fs') || document.fullscreenElement === v,
}));

test('pairs from the popup with the code from the helper menu', async () => {
  await sw.evaluate((port) => chrome.storage.local.set({ port }), bport);
  const popup = await ctx.newPage();
  await popup.goto(`chrome-extension://${extId}/popup.html`);
  const from = helper.mark();
  helper.cmd('bridge pair');
  const code = (await helper.waitFor(/BRIDGE CODE: (\d{6})/, { from }))[1];
  await popup.fill('#code', code);
  await popup.click('button[type=submit]');
  await expect(popup.locator('#status')).toHaveAttribute('data-status', 'connected');
  await helper.waitFor(new RegExp(`bridge: authed chrome-extension://${extId}`));
  await expect.poll(() => phone.last?.bridge).toBe('connected');
  const token = await sw.evaluate(() => chrome.storage.local.get('token').then((s) => s.token));
  expect(token).toMatch(/^[0-9a-f]{64}$/);
  await popup.close();
});

test('plain <video>: state relayed and every command changes the video', async () => {
  const page = await ctx.newPage();
  await page.goto(`${base}/plain.html`);
  await page.bringToFront();
  await page.locator('#v').evaluate((v: HTMLVideoElement) => v.readyState >= 1 || new Promise((r) => v.addEventListener('loadedmetadata', r, { once: true })));
  await expect.poll(() => phone.last?.active && phone.last.duration > 0, { timeout: 15_000 }).toBe(true);
  const st = phone.last;
  expect(st).toMatchObject({ host: new URL(base).host, site: 'generic', paused: true, seekable: true, captions: 'off', live: false });
  expect(st.duration).toBeGreaterThan(55);
  expect(st.title).toBe('Plain video test page');
  expect(st.url).toBeUndefined();

  phone.cmd('play');
  await expect.poll(async () => (await video(page)).paused).toBe(false);
  await expect.poll(() => phone.last.paused).toBe(false);

  phone.cmd('seek', 30);
  await expect.poll(async () => (await video(page)).time).toBeGreaterThanOrEqual(29.9);
  phone.cmd('seekBy', -10);
  await expect.poll(async () => Math.round((await video(page)).time / 5) * 5).toBe(20);
  phone.cmd('rate', 2);
  await expect.poll(async () => (await video(page)).rate).toBe(2);
  await expect.poll(() => phone.last.rate).toBe(2);
  phone.cmd('volume', 0.3);
  await expect.poll(async () => (await video(page)).volume).toBeCloseTo(0.3);
  expect((await video(page)).muted).toBe(false);
  phone.cmd('mute');
  await expect.poll(async () => (await video(page)).muted).toBe(true);
  phone.cmd('captions', true);
  await expect.poll(async () => (await video(page)).captions).toBe('showing');
  await expect.poll(() => phone.last.captions).toBe('on');
  phone.cmd('fullscreen', true);
  await expect.poll(async () => (await video(page)).fs).toBe(true);
  await expect.poll(() => phone.last.fullscreen).toBe(true);
  phone.cmd('fullscreen', false);
  await expect.poll(async () => (await video(page)).fs).toBe(false);
  phone.cmd('pause');
  await expect.poll(async () => (await video(page)).paused).toBe(true);
  await expect.poll(() => phone.last.paused).toBe(true);

  const errs = phone.errors.length;
  phone.cmd('next'); // a plain page has no next control
  await expect.poll(() => phone.errors.slice(errs).find((e) => e.ref === 'labs.video.cmd')?.code).toBe('notfound');
  await page.close();
});

test('mock YouTube DOM: title, next, captions button and skip ad via the adapter', async () => {
  const page = await ctx.newPage();
  await page.goto('https://www.youtube.com/watch?v=mock');
  await page.bringToFront();
  await expect.poll(() => phone.last?.site, { timeout: 15_000 }).toBe('youtube');
  expect(phone.last).toMatchObject({ active: true, host: 'www.youtube.com', title: 'Mock Video Title', hasNext: true, captions: 'off', skips: ['ad'] });

  phone.cmd('skip', 'ad');
  await expect.poll(() => page.evaluate(() => (window as any).adSkipped)).toBe(true);
  await expect.poll(() => phone.last.skips).toEqual([]);
  phone.cmd('captions');
  await expect.poll(() => page.locator('.ytp-subtitles-button').getAttribute('aria-pressed')).toBe('true');
  await expect.poll(() => phone.last.captions).toBe('on');
  phone.cmd('next');
  await expect.poll(() => page.evaluate(() => (window as any).nextClicks)).toBe(1);
  phone.cmd('toggle');
  await expect.poll(async () => (await video(page)).paused).toBe(false);
  await page.close();
});

test('no video left: the phone sees active false and commands get notfound', async () => {
  await expect.poll(() => phone.last?.active).toBe(false);
  const errs = phone.errors.length;
  phone.cmd('play');
  await expect.poll(() => phone.errors.slice(errs)[0]?.code).toBe('notfound');
});
