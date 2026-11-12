// site/install.sh against a local HTTP fixture (no network). Runs with a temp HOME and stubs for the
// commands that would touch the real session: uname (to fake the platform), pgrep/pkill (never quit a
// real Mote Helper), open (never launch anything).
import { test, expect } from '@playwright/test';
import { execFileSync, spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { REPO } from '../lib/helper';

const INSTALL = path.join(REPO, 'site/install.sh');

type Fixture = { dir: string; home: string; stubs: string; log: string; requests: string[]; base: string; files: Map<string, Buffer>; close: () => Promise<void> };

async function fixture(): Promise<Fixture> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-install-'));
  const home = path.join(dir, 'home'), stubs = path.join(dir, 'bin'), log = path.join(dir, 'stub.log');
  fs.mkdirSync(home); fs.mkdirSync(stubs);
  const stub = (name: string, body: string) => fs.writeFileSync(path.join(stubs, name), `#!/bin/bash\n${body}\n`, { mode: 0o755 });
  stub('uname', `case "$1" in -s) echo "\${FAKE_UNAME_S:-Darwin}";; -m) echo "\${FAKE_UNAME_M:-arm64}";; *) /usr/bin/uname "$@";; esac`);
  // A "running" helper is the file bin/running. pkill (TERM) ends it unless bin/stubborn exists; pkill -9 always ends it.
  stub('pgrep', `echo "pgrep $*" >> "${log}"; [ -e "${stubs}/running" ]`);
  stub('pkill', `echo "pkill $*" >> "${log}"; case "$1" in -9) rm -f "${stubs}/running";; *) [ -e "${stubs}/stubborn" ] || rm -f "${stubs}/running";; esac; exit 0`);
  stub('open', `echo "open $*" >> "${log}"; exit 0`);
  stub('tccutil', `echo "tccutil $*" >> "${log}"; exit 0`);   // never reset the real Mac's permissions
  // codesign -d -r- <app>: the unpacked new app lives under .../x/, the installed one doesn't.
  stub('codesign', `case "$3" in */x/*) echo "# designated => \${FAKE_REQ_NEW:-}";; *) echo "# designated => \${FAKE_REQ_OLD:-}";; esac >&2`);

  const files = new Map<string, Buffer>();
  const requests: string[] = [];
  const server = http.createServer((req, res) => {
    requests.push(req.url!);
    const f = files.get(req.url!);
    if (!f) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-length': f.length }); res.end(f);
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${(server.address() as any).port}/releases/latest/download`;
  return { dir, home, stubs, log, requests, base, files,
    close: async () => { await new Promise((r) => server.close(r)); fs.rmSync(dir, { recursive: true, force: true }); } };
}

/** A fake MoteHelper.app zipped the way build.sh does it (ditto --keepParent). */
function fakeZip(dir: string, marker: string, withApp = true): Buffer {
  const src = path.join(dir, 'src-' + marker);
  const app = path.join(src, withApp ? 'MoteHelper.app' : 'Other.app');
  fs.mkdirSync(path.join(app, 'Contents/MacOS'), { recursive: true });
  fs.writeFileSync(path.join(app, 'Contents/MacOS/MoteHelper'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  fs.writeFileSync(path.join(app, 'Contents/marker'), marker);
  const zip = path.join(dir, marker + '.zip');
  execFileSync('ditto', ['-c', '-k', '--keepParent', app, zip]);
  return fs.readFileSync(zip);
}

const sha = (b: Buffer) => crypto.createHash('sha256').update(b).digest('hex');

function run(fx: Fixture, env: Record<string, string> = {}, args: string[] = []): Promise<{ code: number; out: string }> {
  return new Promise((res) => {
    const p = spawn('/bin/bash', [INSTALL, ...args], {
      // MOTE_SYSTEM_APPLICATIONS points at an empty temp dir, so the real /Applications is never looked at.
      env: { HOME: fx.home, PATH: `${fx.stubs}:/usr/bin:/bin:/usr/sbin:/sbin`, MOTE_RELEASE_URL: fx.base,
             MOTE_SYSTEM_APPLICATIONS: path.join(fx.dir, 'sysapps'), ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    p.stdout.on('data', (d) => (out += d)); p.stderr.on('data', (d) => (out += d));
    p.on('exit', (code) => res({ code: code ?? -1, out }));
  });
}

const stubLog = (fx: Fixture) => (fs.existsSync(fx.log) ? fs.readFileSync(fx.log, 'utf8') : '');

test.describe('install.sh', () => {
  let fx: Fixture;
  // The real ~/Applications copy must never be touched.
  const realApp = path.join(os.homedir(), 'Applications/MoteHelper.app');
  let realBefore: number | null;
  test.beforeEach(async () => {
    fx = await fixture();
    realBefore = fs.existsSync(realApp) ? fs.statSync(realApp).mtimeMs : null;
  });
  test.afterEach(async () => {
    expect(fs.existsSync(realApp) ? fs.statSync(realApp).mtimeMs : null).toBe(realBefore);
    await fx.close();
  });
  const installed = () => path.join(fx.home, 'Applications/MoteHelper.app');

  test('downloads, verifies, installs into ~/Applications and opens it', async () => {
    const zip = fakeZip(fx.dir, 'v1');
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip', zip);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip.sha256', Buffer.from(`${sha(zip)}  MoteHelper-macos-arm64.zip\n`));
    const r = await run(fx);
    expect(r.code, r.out).toBe(0);
    expect(r.out).toContain(`ok ${sha(zip)}`);
    expect(fs.readFileSync(path.join(installed(), 'Contents/marker'), 'utf8')).toBe('v1');
    expect(stubLog(fx)).toContain(`open ${installed()}`);
    expect(fx.requests).toEqual(['/releases/latest/download/MoteHelper-macos-arm64.zip', '/releases/latest/download/MoteHelper-macos-arm64.zip.sha256']);
  });

  test('replaces an older install; x86_64 gets the x86_64 asset', async () => {
    const zip = fakeZip(fx.dir, 'v2');
    fx.files.set('/releases/latest/download/MoteHelper-macos-x86_64.zip', zip);
    fx.files.set('/releases/latest/download/MoteHelper-macos-x86_64.zip.sha256', Buffer.from(`${sha(zip)}  MoteHelper-macos-x86_64.zip\n`));
    fs.mkdirSync(path.join(installed(), 'Contents'), { recursive: true });
    fs.writeFileSync(path.join(installed(), 'Contents/stale'), 'old');
    const r = await run(fx, { FAKE_UNAME_M: 'x86_64' });
    expect(r.code, r.out).toBe(0);
    expect(fs.readFileSync(path.join(installed(), 'Contents/marker'), 'utf8')).toBe('v2');
    expect(fs.existsSync(path.join(installed(), 'Contents/stale'))).toBe(false);
    expect(stubLog(fx)).not.toContain('tccutil');   // signature unknown: nothing reset
  });

  test('upgrade from a differently signed (ad-hoc) build resets its stale grants once', async () => {
    const zip = fakeZip(fx.dir, 'v3');
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip', zip);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip.sha256', Buffer.from(`${sha(zip)}  MoteHelper-macos-arm64.zip\n`));
    fs.mkdirSync(path.join(installed(), 'Contents'), { recursive: true });
    const signed = 'identifier "dev.mote.helper" and certificate leaf = H"bb"';
    let r = await run(fx, { FAKE_REQ_OLD: 'cdhash H"aa"', FAKE_REQ_NEW: signed });
    expect(r.code, r.out).toBe(0);
    expect(r.out).toContain('signed differently');   // UP-2 detected-by line
    for (const svc of ['Accessibility', 'ScreenCapture', 'BluetoothAlways', 'AppleEvents']) {
      expect(stubLog(fx)).toContain(`tccutil reset ${svc} dev.mote.helper`);   // U9
    }
    expect(stubLog(fx)).toContain(`open ${installed()} --args --setup`);
    fs.rmSync(fx.log, { force: true });
    r = await run(fx, { FAKE_REQ_OLD: signed, FAKE_REQ_NEW: signed });   // same stable signature: keep grants
    expect(r.code, r.out).toBe(0);
    expect(r.out).not.toContain('signed differently');
    expect(stubLog(fx)).not.toContain('tccutil');
  });

  function serve(marker: string) {
    const zip = fakeZip(fx.dir, marker);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip', zip);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip.sha256', Buffer.from(`${sha(zip)}  MoteHelper-macos-arm64.zip\n`));
  }
  const sysApp = () => path.join(fx.dir, 'sysapps/MoteHelper.app');
  const makeSys = (marker: string) => { fs.mkdirSync(path.join(sysApp(), 'Contents'), { recursive: true }); fs.writeFileSync(path.join(sysApp(), 'Contents/marker'), marker); };

  test('INS-10 (U10): a second copy in /Applications -> warns, installs to ~/Applications, leaves the other alone', async () => {
    serve('v4');
    makeSys('sys-old');
    fs.mkdirSync(path.join(installed(), 'Contents'), { recursive: true });
    const r = await run(fx);
    expect(r.code, r.out).toBe(0);
    expect(r.out).toContain('there is also a copy at');
    expect(fs.readFileSync(path.join(installed(), 'Contents/marker'), 'utf8')).toBe('v4');
    expect(fs.readFileSync(path.join(sysApp(), 'Contents/marker'), 'utf8')).toBe('sys-old');
  });

  test('INS-10 (U10): the only copy is a writable /Applications one -> updated in place, nothing in ~/Applications', async () => {
    serve('v5');
    makeSys('sys-old');
    const r = await run(fx);
    expect(r.code, r.out).toBe(0);
    expect(fs.readFileSync(path.join(sysApp(), 'Contents/marker'), 'utf8')).toBe('v5');
    expect(fs.existsSync(installed())).toBe(false);
    expect(stubLog(fx)).toContain(`open ${sysApp()} --args --setup`);
  });

  test('INS-10 (U10): MOTE_INSTALL_DIR is honoured even when /Applications has a copy', async () => {
    serve('v6');
    makeSys('sys-old');
    const dir = path.join(fx.dir, 'custom');
    const r = await run(fx, { MOTE_INSTALL_DIR: dir });
    expect(r.code, r.out).toBe(0);
    expect(fs.readFileSync(path.join(dir, 'MoteHelper.app/Contents/marker'), 'utf8')).toBe('v6');
    expect(fs.readFileSync(path.join(sysApp(), 'Contents/marker'), 'utf8')).toBe('sys-old');
  });

  test('INS-7 (U14): a helper that quits within the wait -> installs without kill -9', async () => {
    serve('v7');
    fs.writeFileSync(path.join(fx.stubs, 'running'), '');
    const r = await run(fx);
    expect(r.code, r.out).toBe(0);
    expect(stubLog(fx)).toContain('pkill -x MoteHelper');
    expect(stubLog(fx)).not.toContain('pkill -9');
    expect(fs.readFileSync(path.join(installed(), 'Contents/marker'), 'utf8')).toBe('v7');
  });

  test('INS-7 (U14): a helper that will not quit -> after 5 s aborts "Quit Mote Helper first", nothing replaced', async () => {
    serve('v8');
    fs.writeFileSync(path.join(fx.stubs, 'running'), '');
    fs.writeFileSync(path.join(fx.stubs, 'stubborn'), '');
    fs.mkdirSync(path.join(installed(), 'Contents'), { recursive: true });
    fs.writeFileSync(path.join(installed(), 'Contents/marker'), 'old');
    const t0 = Date.now();
    const r = await run(fx);
    expect(Date.now() - t0).toBeGreaterThanOrEqual(4500);
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('Quit Mote Helper first');
    expect(stubLog(fx)).not.toContain('pkill -9');
    expect(fs.readFileSync(path.join(installed(), 'Contents/marker'), 'utf8')).toBe('old');
    expect(stubLog(fx)).not.toContain('open ');
  });

  test('INS-7 (U14): --force kills a stubborn helper and installs', async () => {
    serve('v9');
    fs.writeFileSync(path.join(fx.stubs, 'running'), '');
    fs.writeFileSync(path.join(fx.stubs, 'stubborn'), '');
    const r = await run(fx, {}, ['--force']);
    expect(r.code, r.out).toBe(0);
    expect(stubLog(fx)).toContain('pkill -9 -x MoteHelper');
    expect(fs.readFileSync(path.join(installed(), 'Contents/marker'), 'utf8')).toBe('v9');
  });

  test('unknown option -> refuses before downloading', async () => {
    const r = await run(fx, {}, ['--nope']);
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('unknown option --nope');
    expect(fx.requests).toEqual([]);
  });

  test('checksum mismatch -> aborts, nothing installed, nothing opened', async () => {
    const zip = fakeZip(fx.dir, 'tampered');
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip', zip);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip.sha256', Buffer.from(`${'0'.repeat(64)}  MoteHelper-macos-arm64.zip\n`));
    const r = await run(fx);
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('checksum mismatch');
    expect(r.out).toContain('Not installing.');
    expect(fs.existsSync(installed())).toBe(false);
    expect(fs.existsSync(path.join(fx.home, 'Applications'))).toBe(false);
    expect(stubLog(fx)).not.toContain('open ');
  });

  test('missing checksum file -> aborts, nothing installed', async () => {
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip', fakeZip(fx.dir, 'nosum'));
    const r = await run(fx);
    expect(r.code).not.toBe(0);
    expect(fs.existsSync(installed())).toBe(false);
  });

  test('archive without MoteHelper.app -> aborts, nothing installed', async () => {
    const zip = fakeZip(fx.dir, 'other', false);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip', zip);
    fx.files.set('/releases/latest/download/MoteHelper-macos-arm64.zip.sha256', Buffer.from(`${sha(zip)}  x\n`));
    const r = await run(fx);
    expect(r.code).not.toBe(0);
    expect(r.out).toContain("archive doesn't contain MoteHelper.app");
    expect(fs.existsSync(installed())).toBe(false);
  });

  test('unsupported CPU -> refuses before downloading', async () => {
    const r = await run(fx, { FAKE_UNAME_M: 'i386' });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('unsupported CPU: i386');
    expect(fx.requests).toEqual([]);
    expect(fs.existsSync(path.join(fx.home, 'Applications'))).toBe(false);
  });

  test('not macOS -> refuses before downloading', async () => {
    const r = await run(fx, { FAKE_UNAME_S: 'Linux', FAKE_UNAME_M: 'x86_64' });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('Mote Helper is a macOS app');
    expect(fx.requests).toEqual([]);
    expect(fs.existsSync(path.join(fx.home, 'Applications'))).toBe(false);
  });

  test('a missing curl, shasum or ditto -> exit 1 naming the tool, before downloading', async () => {
    for (const missing of ['curl', 'shasum', 'ditto']) {
      // PATH = the stubs plus symlinks to the other two tools only, so `command -v <missing>` fails.
      const tools = path.join(fx.dir, 'tools-without-' + missing);
      fs.mkdirSync(tools);
      for (const t of ['curl', 'shasum', 'ditto'].filter((t) => t !== missing)) fs.symlinkSync(`/usr/bin/${t}`, path.join(tools, t));
      const r = await run(fx, { PATH: `${fx.stubs}:${tools}` });
      expect(r.code, missing).toBe(1);
      expect(r.out).toContain(`${missing} is required`);
    }
    expect(fx.requests).toEqual([]);
    expect(fs.existsSync(path.join(fx.home, 'Applications'))).toBe(false);
  });

  test('the override only allows plain http for loopback', async () => {
    // curl refuses the protocol before any DNS lookup or connection, so this needs no network.
    for (const base of ['http://example.invalid/dl', 'http://localhost@example.invalid/dl']) {
      const r = await run(fx, { MOTE_RELEASE_URL: base });
      expect(r.code, base).not.toBe(0);
      expect(r.out).toMatch(/Protocol "?http"? not supported|disabled/);
      expect(fs.existsSync(installed())).toBe(false);
    }
  });

  test('default download URL is unchanged (GitHub latest release, https)', async () => {
    const s = fs.readFileSync(INSTALL, 'utf8');
    expect(s).toContain('BASE_URL="${MOTE_RELEASE_URL:-https://github.com/$REPO/releases/latest/download}"');
    expect(s).toContain('REPO="tusharpandey13/mote"');
  });
});
