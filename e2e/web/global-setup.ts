// Builds the helper if it is missing or older than its sources, and assembles the web root from
// site/remote + design assets (the same layout helper/build.sh bundles), so site edits are tested
// without rebuilding the helper. The helper is started with --web-root pointing at it.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { HELPER_BIN, REPO } from './lib/helper';

function newest(dir: string): number {
  let t = 0;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    t = Math.max(t, e.isDirectory() ? newest(p) : fs.statSync(p).mtimeMs);
  }
  return t;
}

export default function globalSetup() {
  if (!process.env.MOTE_HELPER) {
    const stale = !fs.existsSync(HELPER_BIN) || fs.statSync(HELPER_BIN).mtimeMs < newest(path.join(REPO, 'helper/Sources'));
    if (stale) {
      console.log('[e2e] building the helper (helper/build.sh, native arch)…');
      execFileSync('bash', [path.join(REPO, 'helper/build.sh')], { stdio: 'inherit', env: { ...process.env, ARCHS: os.arch() === 'arm64' ? 'arm64' : 'x86_64' } });
    }
  }
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-e2e-web-'));
  fs.cpSync(path.join(REPO, 'site/remote'), root, { recursive: true });
  const design = path.join(root, 'design');
  fs.mkdirSync(path.join(design, 'fonts'), { recursive: true });
  for (const f of ['mote.css', 'icons.js']) fs.copyFileSync(path.join(REPO, 'design/mote-v1', f), path.join(design, f));
  for (const f of fs.readdirSync(path.join(REPO, 'design/mote-v1/fonts'))) {
    fs.copyFileSync(path.join(REPO, 'design/mote-v1/fonts', f), path.join(design, 'fonts', f));
  }
  process.env.MOTE_WEB_ROOT = root;
  return () => fs.rmSync(root, { recursive: true, force: true });
}
