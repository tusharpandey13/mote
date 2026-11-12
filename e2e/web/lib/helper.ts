// Mote Helper process fixture. Always --dry-run: the helper logs input instead of posting events,
// and those log lines ("[mote] DRY key usage=80 mods=1 action=tap") are the tests' oracle.
import { spawn, ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const REPO = path.resolve(__dirname, '../../..');
export const HELPER_BIN =
  process.env.MOTE_HELPER || path.join(REPO, 'helper/build/MoteHelper.app/Contents/MacOS/MoteHelper');

const SAFE_ARGS = ['--dry-run', '--headless', '--no-ble', '--memory-store', '--stdin-control'];

export type Cred = { id: string; token: string };

export class Helper {
  readonly lines: string[] = [];
  port = 0;
  host = '';
  private proc!: ChildProcess;
  private waiters = new Set<() => void>();
  private exited: Promise<void> = Promise.resolve();
  private extra: string[] = [];
  /** The helper's structured diag events (`--events FILE`, docs/protocol.md "Event object"), one `area/event {fields json}` per entry. */
  readonly events: string[] = [];
  private evFile = path.join(os.tmpdir(), `mote-e2e-events-${process.pid}-${Math.random().toString(36).slice(2)}.jsonl`);
  private evRead = 0;
  private evTimer?: NodeJS.Timeout;

  /** Starts a helper. port 0 = any free port (the helper logs the real one). */
  /** `args`: extra flags (e.g. Labs toggles); the safe flags above are always set. */
  static async start(opts: { port?: number; args?: string[] } = {}): Promise<Helper> {
    const h = new Helper();
    h.extra = opts.args ?? [];
    await h.spawn(opts.port ?? 0);
    return h;
  }

  private async spawn(port: number) {
    fs.writeFileSync(this.evFile, '');
    this.evRead = 0;
    const args = [...SAFE_ARGS, '--events', this.evFile, ...this.extra, '--port', String(port)];
    if (process.env.MOTE_WEB_ROOT) args.push('--web-root', process.env.MOTE_WEB_ROOT);
    if (!args.includes('--dry-run')) throw new Error('refusing to start the helper without --dry-run');
    const p = spawn(HELPER_BIN, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    this.proc = p;
    this.exited = new Promise((res) => p.once('exit', () => { this.pollEvents(); res(); }));
    clearInterval(this.evTimer); this.evTimer = setInterval(() => this.pollEvents(), 50);
    for (const s of [p.stdout!, p.stderr!]) {
      let buf = '';
      s.setEncoding('utf8');
      s.on('data', (d: string) => {
        buf += d;
        let i;
        while ((i = buf.indexOf('\n')) >= 0) {
          this.lines.push(buf.slice(0, i));
          buf = buf.slice(i + 1);
        }
        this.waiters.forEach((w) => w());
      });
    }
    const m = await this.waitFor(/wifi: listening on :(\d+)/, { from: this.lines.length });
    this.port = +m[1];
  }

  private pollEvents() {
    let buf: Buffer;
    try { buf = fs.readFileSync(this.evFile); } catch { return; }
    const text = buf.subarray(this.evRead).toString('utf8');
    const end = text.lastIndexOf('\n');
    if (end < 0) return;
    this.evRead += Buffer.byteLength(text.slice(0, end + 1));
    for (const l of text.slice(0, end).split('\n').filter(Boolean)) {
      try { const e = JSON.parse(l); this.events.push(`${e.area}/${e.event} ${JSON.stringify(e.fields ?? {})}`); } catch { /* partial line */ }
    }
    this.waiters.forEach((w) => w());
  }

  /** Index of the next diag event; pass to waitEvent to only look at what happens after now. */
  eventMark() { this.pollEvents(); return this.events.length; }

  /**
   * Waits for the helper's diag event `area/name` (the "Detected by" column of docs/journeys), optionally with its fields JSON
   * matching `fields`, logged since `from` (eventMark()). Assert the event next to the UI, never only the text.
   */
  waitEvent(area: string, name: string, opts: { fields?: RegExp; from?: number; timeout?: number } = {}): Promise<string> {
    const { fields, from = 0, timeout = 10_000 } = opts;
    const head = `${area}/${name} `;
    return new Promise((res, rej) => {
      const check = () => {
        const hit = this.events.slice(from).find((e) => e.startsWith(head) && (!fields || fields.test(e.slice(head.length))));
        if (hit) { done(); res(hit); return true; }
        return false;
      };
      const t = setTimeout(() => { done(); rej(new Error(`timed out waiting for event ${head}${fields ?? ''} since #${from}. Last events:\n` + this.events.slice(-15).join('\n'))); }, timeout);
      const done = () => { clearTimeout(t); this.waiters.delete(w); };
      const w = () => { check(); };
      if (!check()) this.waiters.add(w);
    });
  }

  get url() { return `http://127.0.0.1:${this.port}`; }

  /** Index of the next log line; pass to waitFor/since to only look at what happens after now. */
  mark() { return this.lines.length; }

  /** Lines matching re from index `from` (default 0). */
  since(from: number, re: RegExp): RegExpMatchArray[] {
    return this.lines.slice(from).map((l) => l.match(re)).filter((m): m is RegExpMatchArray => !!m);
  }

  /** Waits until `count` lines matching re have been logged since `from`; returns the last match. */
  waitFor(re: RegExp, opts: { from?: number; count?: number; timeout?: number } = {}): Promise<RegExpMatchArray> {
    const { from = 0, count = 1, timeout = 10_000 } = opts;
    return new Promise((res, rej) => {
      const check = () => {
        const ms = this.since(from, re);
        if (ms.length >= count) { done(); res(ms[count - 1]); return true; }
        return false;
      };
      const t = setTimeout(() => {
        done();
        rej(new Error(`timed out waiting for ${count}x ${re} since line ${from}. Log tail:\n` + this.lines.slice(-30).join('\n')));
      }, timeout);
      const done = () => { clearTimeout(t); this.waiters.delete(w); };
      const w = () => { check(); };
      if (!check()) this.waiters.add(w);
    });
  }

  /** Test-only stand-ins for the menu-bar actions (--stdin-control). */
  cmd(c: 'pair' | 'revoke' | 'pause' | 'resume' | `bridge ${'pair' | 'on' | 'off' | 'forget'}`) { this.proc.stdin!.write(c + '\n'); }

  /** Opens a pairing window ("Pair new device") and returns the 6-digit code the helper prints. */
  async openPairing(): Promise<string> {
    const from = this.mark();
    this.cmd('pair');
    return (await this.waitFor(/PAIRING CODE: (\d{6})/, { from }))[1];
  }

  /** Pairs a device over the protocol (no UI), for tests that start from an already-paired browser. */
  async pairDevice(name = 'e2e seed'): Promise<Cred> {
    const code = await this.openPairing();
    const ws = new WebSocket(`ws://127.0.0.1:${this.port}/ws`);
    const q: any[] = [], ws8: ((m: any) => void)[] = [];
    ws.onmessage = (e) => { const m = JSON.parse(String(e.data)); const w = ws8.shift(); w ? w(m) : q.push(m); };
    const next = () => new Promise<any>((r) => { const m = q.shift(); m ? r(m) : ws8.push(r); });
    await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
    const hello = await next();
    this.host = hello.host;
    ws.send(JSON.stringify({ t: 'pair', code, name }));
    const p = await next();
    if (p.t !== 'paired') throw new Error('seed pairing failed: ' + JSON.stringify(p));
    ws.close();
    return { id: p.id, token: p.token };
  }

  /** SIGKILL, like a crash. */
  async kill() { this.proc.kill('SIGKILL'); await this.exited; }
  async stop() { clearInterval(this.evTimer); if (this.proc.exitCode === null && this.proc.signalCode === null) { this.proc.kill('SIGTERM'); } await this.exited; }
  /** Starts the helper again on the same port (fresh process, empty memory store). */
  async restart() { await this.stop(); await this.spawn(this.port); }
}

