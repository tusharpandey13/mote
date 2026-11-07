// Trust (chaos/RUBRIC.md I6): the instant-pairing handshake (docs/pairing.md) under hostile input.
// Forged / replayed / cross-host signatures, field fuzz, the approval prompt (fake Allow / Deny, busy,
// cool-down), Tailscale auto-trust with a fake whois (same owner, other owner, CLI timeout), revocation
// mid-session, a spoofed Mac (another key behind the same info), and, once the one-tap rework lands
// (feature-detected by --fake-proximity), BLE proximity tokens: replayed, expired, forged, skew +-1
// window, and notification-Block revocation mid-session.
import { Checker, PEERS, invariants } from '../lib/check.mjs';
import { hasFlag, startHelper, sleep } from '../lib/helper.mjs';
import { clientProof, fp, helperProof, keypair, labsHello, sign, verify } from '../lib/labs.mjs';
import { open } from '../lib/ws.mjs';

export const name = 'trust';
export const weight = 0.06;

const isOk = (v) => v?.t === 'labs.pair.ok';

export async function run({ r }) {
  const chk = new Checker(name);
  const oneTap = await hasFlag('--fake-proximity');      // the one-tap rework: proximity tokens, no prompt by default
  const approval = oneTap ? ['--require-approval'] : []; // keep exercising the Allow / Deny prompt path
  const flags = (...f) => ['--labs-pairing', '--stdin-control', ...f];

  const A = await startHelper({ stdin: true, extraArgs: flags('--fake-approve', ...approval) });
  const base = { baseFds: (await A.fds()).all, baseFp: await A.footprint() };

  // 1. Pre-handshake: info is public, input is not.
  {
    const c = await open({ port: A.port, host: PEERS[0] });
    const info = await c.req({ t: 'labs.pair.info' });
    chk.ok(info?.t === 'labs.pair.info' && info.id === c.hello.hostId && /^[0-9a-f]{32}$/.test(info.fp ?? ''), 'labs.pair.info before auth: id + fp', info, 'S2');
    const e = await c.req({ t: 'text', s: 'MKtrust-pre' });
    chk.ok(e?.t === 'err' && e.code === 'unauth', 'pre-handshake input refused (unauth)', e, 'S1 I1');
    c.destroy();
  }

  // 2. Forgeries (all from PEERS[2], which the limiter then blocks): signature by another key, a hello
  //    replayed from another connection, a proof for another hostId, malformed fields.
  const k1 = keypair(), k2 = keypair();
  {
    const bad = [];
    const one = async (label, mutate) => {
      const c = await open({ port: A.port, host: PEERS[2] });
      const { verdict } = await labsHello(c, k1, { mutate: (m) => mutate(m, c), waitMs: 1500 });
      bad.push([label, verdict]);
      c.destroy();
    };
    await one('signed by another key', (m, c) => { m.sig = sign(k2, clientProof(c.hello.hostId, c.hello.nonce, k1.pub)); });
    const c0 = await open({ port: A.port, host: PEERS[2] });
    const replayed = { t: 'labs.pair.hello', pub: k1.pub, name: 'x', cn: 'a'.repeat(32), sig: sign(k1, clientProof(c0.hello.hostId, c0.hello.nonce, k1.pub)) };
    c0.destroy();
    await one('hello replayed from another connection', (m) => Object.assign(m, replayed));
    await one('proof for another hostId', (m, c) => { m.sig = sign(k1, clientProof('0123456789abcdef', c.hello.nonce, k1.pub)); });
    const mut = [(m) => { m.pub = m.pub.slice(2); }, (m) => { m.sig = m.sig.slice(1); }, (m) => { m.cn = 'zz'.repeat(16); }, (m) => { delete m.cn; },
      (m) => { m.pub = 'G'.repeat(64); }, (m) => { m.sig = 0; }, (m) => { m.pub = '00'.repeat(32); }, (m) => { m.sig = 'zz' + m.sig.slice(2); }];
    for (const f of r.shuffle ? r.shuffle(mut) : mut) await one('malformed', f);
    const wrong = bad.filter(([, v]) => isOk(v) || !(v?.t === 'err' && ['bad', 'rate'].includes(v.code)));
    chk.ok(wrong.length === 0, `${bad.length} forged / malformed hellos: err bad or rate, never trusted`, wrong.slice(0, 3), 'S1 I6');
    chk.ok(bad.some(([, v]) => v?.code === 'rate'), 'bad signatures count towards the per-peer limiter (err rate)', bad.map(([, v]) => v?.code), 'S2');
    chk.ok(A.alive(), 'helper alive after forged hellos', A.exit, 'S1 I10');
  }

  // 3. A genuine new phone: pending (fake Allow), ok approved, helper proof verifies; reconnect is
  //    trusted with no prompt; input executes exactly once.
  {
    const c = await open({ port: A.port, host: PEERS[0] });
    const { hi, verdict, helperSigOk } = await labsHello(c, k1, { waitMs: 3000 });
    chk.ok(hi && helperSigOk && hi.fp === fp(hi.pub), 'labs.pair.hi signed by the helper key over our cn', hi, 'S1 I6');
    chk.ok(isOk(verdict) && verdict.via === 'approved', 'new key + Allow: ok via approved', verdict, 'S2 I6');
    const authed = await c.until((m) => m.t === 'authed', 1000);
    chk.ok(authed?.t === 'authed', 'authed follows labs.pair.ok', authed, 'S2');
    c.send({ t: 'text', s: 'MKtrust-1' });
    await c.ping();
    c.destroy();
    const c2 = await open({ port: A.port, host: PEERS[0] });
    const t0 = Date.now();
    const again = await labsHello(c2, k1, { waitMs: 2000 });
    chk.ok(isOk(again.verdict) && again.verdict.via === 'trusted', 'known key reconnects: ok via trusted, no prompt', again.verdict, 'S2 I6');
    chk.budget('trust.trustedReconnectMs', Date.now() - t0, 500);
    c2.destroy();
    await sleep(200);
    chk.ok(A.markers.get('MKtrust-1') === 1 && !A.markers.has('MKtrust-pre'), 'labs-authed input executed exactly once, pre-auth never', Object.fromEntries(A.markers), 'S1 I2');
  }

  // 4. Two new phones at once: one prompt, the other `denied busy`; nothing trusted twice.
  {
    const [ka, kb] = [keypair(), keypair()];
    const [ca, cb] = await Promise.all([open({ port: A.port, host: PEERS[0] }), open({ port: A.port, host: PEERS[1] })]);
    const [va, vb] = await Promise.all([labsHello(ca, ka, { waitMs: 3000 }), labsHello(cb, kb, { waitMs: 3000 })]);
    const v = [va.verdict, vb.verdict];
    chk.ok(v.filter(isOk).length >= 1 && v.every((x) => isOk(x) || (x?.t === 'labs.pair.denied' && x.reason === 'busy')), 'two phones pairing at once: one approved, the other denied busy (or approved after)', v, 'S2 I6');
    ca.destroy(); cb.destroy();
  }

  // 5. Revocation mid-session ("Revoke all devices"): the session drops, later input never executes,
  //    the key is no longer trusted.
  {
    const c = await open({ port: A.port, host: PEERS[1] });
    await labsHello(c, k1, { waitMs: 2000 });
    await c.until((m) => m.t === 'authed', 1000);
    c.send({ t: 'text', s: 'MKrev-before' }); await c.ping();
    const t0 = Date.now();
    A.stdin('revoke\n');
    const gone = await c.until((m) => m.t === 'err' && m.code === 'revoked', 1500);
    for (let i = 0; i < 5; i++) c.send({ t: 'text', s: 'MKrev-after' });
    await c.waitClosed(1500);
    chk.ok(gone || c.closed, 'revoke mid-session: err revoked or closed', gone, 'S1 I6');
    chk.budget('trust.revokeToDropMs', Date.now() - t0, 1500, { tag: 'S2 I4' });
    await sleep(200);
    chk.ok(!A.markers.has('MKrev-after') && A.markers.get('MKrev-before') === 1, 'no input executes after revocation', Object.fromEntries(A.markers), 'S1 I1');
    const c2 = await open({ port: A.port, host: PEERS[1] });
    const again = await labsHello(c2, k1, { waitMs: 3000 });
    chk.ok(!(isOk(again.verdict) && again.verdict.via === 'trusted'), 'after revoke the key is not trusted', again.verdict, 'S1 I6');
    c2.destroy();
  }

  // 6. Spoofed Mac: another helper (other key) answering for this Mac. Its hi must not verify under the
  //    pinned key, so a pinning phone rejects it (the phone side: HandshakeTest "helper-key", LabsPairingIntegrationTest).
  {
    const B = await startHelper({ extraArgs: flags('--fake-approve', ...approval) });
    const ca = await open({ port: A.port, host: PEERS[0] });
    const pinned = (await ca.req({ t: 'labs.pair.info' })).fp;
    const pinnedPub = (await labsHello(ca, keypair(), { waitMs: 500 })).hi?.pub;
    ca.destroy();
    const cb = await open({ port: B.port, host: PEERS[0] });
    const kb = keypair();
    const { hi, cn } = await labsHello(cb, kb, { waitMs: 500 });
    const forged = hi && verify(pinnedPub, helperProof(cb.hello.hostId, cb.hello.nonce, cn, kb.pub), hi.sig);
    chk.ok(hi && hi.fp !== pinned && !forged, 'spoofed Mac: different fp and its proof fails under the pinned key', { pinned, got: hi?.fp }, 'S1 I6');
    cb.destroy();
    await B.stop();
  }
  await invariants(A, chk, base);
  await A.stop();

  // 7. Deny + cool-down: denied, then denied again at once without a prompt; nothing trusted.
  {
    const D = await startHelper({ extraArgs: flags('--fake-approve-deny', ...approval) });
    const k = keypair();
    const c = await open({ port: D.port, host: PEERS[0] });
    const first = await labsHello(c, k, { waitMs: 3000 });
    c.destroy();
    const c2 = await open({ port: D.port, host: PEERS[0] });
    const t0 = Date.now();
    const second = await labsHello(c2, k, { waitMs: 3000 });
    const e = await c2.req({ t: 'text', s: 'MKdeny' }, 1000);
    chk.ok(first.verdict?.reason === 'denied' && second.verdict?.reason === 'denied' && Date.now() - t0 < 1000, 'Deny: denied, then denied at once during the cool-down', [first.verdict, second.verdict], 'S1 I6');
    chk.ok(e?.code === 'unauth' && !D.markers.has('MKdeny'), 'denied phone cannot send input', e, 'S1 I1');
    c2.destroy();
    await D.stop();
  }

  // 8. Tailscale auto-trust (fake whois): same owner -> ok via tailscale without a prompt; another owner
  //    or a hung CLI (3 s) -> never auto-trusted (prompt on main, denied "far" after the rework).
  for (const [owner, expectTs] of [['self', true], ['other', false], ['timeout', false]]) {
    if (owner === 'timeout' && !(await hasFlag('ID|timeout'))) { chk.pending('whois timeout', 'helper has no --fake-tailscale-owner timeout'); continue; }
    const T = await startHelper({ extraArgs: flags('--fake-tailscale-owner', owner, '--fake-approve-deny') });
    const c = await open({ port: T.port, host: PEERS[0] });
    const t0 = Date.now();
    c.send({ t: 'labs.pair.hello' }); // malformed first: must not short-circuit the real decision
    await c.until((m) => m.t === 'err', 1000);
    const { verdict } = await labsHello(c, keypair(), { waitMs: 6000 });
    const ms = Date.now() - t0;
    if (expectTs) chk.ok(isOk(verdict) && verdict.via === 'tailscale', 'whois same owner: ok via tailscale, no prompt', verdict, 'S2 I6');
    else chk.ok(!isOk(verdict), `whois ${owner}: never auto-trusted`, verdict, 'S1 I6');
    if (owner === 'timeout') chk.ok(ms >= 2800 && verdict !== null, 'whois timeout: falls through after the 3 s CLI timeout with a visible verdict', { ms, verdict }, 'S3 I8');
    c.destroy();
    await T.stop();
  }

  // 9. One-tap rework: BLE proximity tokens and Block (feature-detected).
  if (!oneTap) {
    chk.pending('proximity tokens (replay, expired, forged, skew +-1)', 'helper has no --fake-proximity (one-tap rework not merged)');
    chk.pending('notification Block mid-session', 'helper has no `block` stdin command (one-tap rework not merged)');
  } else {
    const P = await startHelper({ stdin: true, extraArgs: flags('--fake-proximity') });
    // Stay clear of a 30 s window boundary so dw maps to the window we mean.
    const into = (Date.now() / 1000) % 30;
    if (into > 25) await sleep((31 - into) * 1000);
    const tok = async (c, dw) => (await c.req({ t: 'labs.pair.prox', dw }))?.v;
    const tryTok = async (peer, prox) => {
      const c = await open({ port: P.port, host: peer });
      const res = await labsHello(c, keypair(), { mutate: (m) => { if (prox !== undefined) m.prox = prox; }, waitMs: 4000 });
      return { c, v: res.verdict };
    };
    const src = await open({ port: P.port, host: PEERS[0] });
    const t0 = await tok(src, 0), tm1 = await tok(src, -1), tp1 = await tok(src, 1), tm2 = await tok(src, -2), tp2 = await tok(src, 2);
    src.destroy();
    const cases = [
      ['fresh token', t0, 'ok'], ['replayed token', t0, 'stale'], ['skew -1 window', tm1, 'ok'], ['skew +1 window', tp1, 'ok'],
      ['expired (-2 windows)', tm2, 'stale'], ['future (+2 windows)', tp2, 'stale'],
      ['forged (one hex digit flipped)', t0 && t0.slice(0, -1) + (t0.at(-1) === '0' ? '1' : '0'), 'bad-proximity'],
      ['malformed', 'x:' + 'zz'.repeat(16), 'bad-proximity'], ['no token (far away)', undefined, 'far'],
    ];
    const bad = [];
    for (const [k, [label, prox, want]] of cases.entries()) {
      const { c, v } = await tryTok(PEERS[k % 2], prox);
      const got = isOk(v) ? 'ok' : v?.reason ?? v?.code ?? 'none';
      if (got !== want) bad.push({ label, want, got });
      c.destroy();
    }
    chk.ok(bad.length === 0, `proximity tokens: ${cases.length} cases (replay, expiry, forgery, skew +-1)`, bad, 'S1 I6');
    // Block mid-session: a nearby-trusted phone is dropped, input after Block never executes, then denied blocked.
    const src2 = await open({ port: P.port, host: PEERS[1] });
    const tk = await tok(src2, 0); src2.destroy();
    const kb = keypair();
    const c = await open({ port: P.port, host: PEERS[1] });
    const first = await labsHello(c, kb, { mutate: (m) => { m.prox = tk; }, waitMs: 3000 });
    if (isOk(first.verdict)) {
      await c.until((m) => m.t === 'authed', 1000);
      P.stdin('block\n');
      await c.waitClosed(1500);
      for (let i = 0; i < 3; i++) c.send({ t: 'text', s: 'MKblocked' });
      await sleep(300);
      chk.ok(c.closed && !P.markers.has('MKblocked'), 'Block mid-session: dropped, no input after', undefined, 'S1 I6');
      const c2 = await open({ port: P.port, host: PEERS[1] });
      const again = await labsHello(c2, kb, { waitMs: 2000 });
      chk.ok(again.verdict?.reason === 'blocked', 'blocked key is refused (denied blocked)', again.verdict, 'S1 I6');
      c2.destroy();
    } else chk.ok(false, 'nearby phone trusted before Block', first.verdict, 'S2 I6');
    c.destroy();
    await P.stop();
  }
  return chk;
}
