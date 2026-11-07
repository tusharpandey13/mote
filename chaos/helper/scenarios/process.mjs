// Process chaos: SIGSTOP/SIGCONT mid-session, SIGKILL + restart on the same port, and (opt-in,
// --keychain) SIGKILL during Keychain token writes followed by a restart that must still accept
// the stored token. The helper has no file store: persistence is the login Keychain only.
import { execFile } from 'node:child_process';
import { Checker, PEERS, invariants } from '../lib/check.mjs';
import { startHelper, sleep } from '../lib/helper.mjs';
import { authed, open, pair, hmac } from '../lib/ws.mjs';

export const name = 'process';
export const weight = 0.15;

export async function run({ r, deadline, keychain, seed }) {
  const chk = new Checker(name);
  await stopCont(chk);
  await killRestart(chk, r);
  if (keychain) await keychainKill(chk, r, seed, deadline);
  else chk.note('Keychain persistence skipped (pass --keychain; uses a throwaway Keychain item). --memory-store loses tokens on restart by design; there is no file store.');
  return chk;
}

async function stopCont(chk) {
  const h = await startHelper();
  const cred = await pair(h);
  const c = await authed(h, cred, { host: PEERS[0] });
  chk.ok((await c.ping(1)) !== null, 'STOP/CONT: session works before');
  h.kill('SIGSTOP');
  for (let i = 0; i < 10; i++) c.send({ t: 'text', s: `MKS-${i}` });
  const pending = open({ port: h.port, host: PEERS[1], timeout: 8000 }).catch((e) => e);
  const frozen = await c.ping(2, 1500);
  chk.ok(frozen === null, 'STOP/CONT: no reply while stopped (really stopped)');
  await sleep(1000);
  h.kill('SIGCONT');
  const thawed = await c.until((m) => m.t === 'pong' && m.n === 2, 3000);
  chk.ok(!!thawed, 'STOP/CONT: queued ping answered after SIGCONT');
  const p = await pending;
  chk.ok(p?.hello?.t === 'hello', 'STOP/CONT: client that connected while stopped gets hello', p?.message);
  p?.close?.();
  await sleep(300);
  const counts = Array.from({ length: 10 }, (_, i) => h.markers.get(`MKS-${i}`) || 0);
  chk.ok(counts.every((n) => n === 1), 'STOP/CONT: input sent while stopped executed exactly once', counts);
  c.close();
  chk.cases += 12;
  await invariants(h, chk, {});
  await h.stop();
}

async function killRestart(chk, r) {
  let h = await startHelper();
  const port = h.port;
  let cred = await pair(h);
  for (let round = 0; round < 3; round++) {
    const c = await authed(h, cred, { host: PEERS[round % 3] });
    let i = 0, streaming = true;
    const stream = (async () => { while (streaming && !c.closed) { c.send({ t: 'text', s: `MKK-${round}-${i++}` }); await sleep(2); } })();
    await sleep(r.int(50, 400));
    h.kill('SIGKILL');
    await h.waitExit(3000);
    chk.ok(await c.waitClosed(3000), `SIGKILL #${round}: client sees the connection close`);
    streaming = false; await stream;
    const dup = [...h.markers].filter(([, v]) => v > 1);
    chk.ok(dup.length === 0, `SIGKILL #${round}: nothing executed twice before the kill`, dup.slice(0, 3));
    // Restart on the same port; a client with backoff reconnects.
    const t0 = Date.now();
    h = await startHelper({ port });
    let back = null;
    for (let k = 0; k < 20 && !back; k++) { try { back = await open({ port, host: PEERS[0] }); } catch { await sleep(100); } }
    chk.ok(back?.hello?.t === 'hello', `SIGKILL #${round}: helper restarts on the same port, client reconnects (${Date.now() - t0} ms)`);
    const a = await back?.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, back.hello.nonce) });
    chk.ok(a?.code === 'auth', `SIGKILL #${round}: --memory-store forgets tokens (expected), old token rejected cleanly`, a);
    back?.close();
    cred = await pair(h);
    chk.cases += 1;
  }
  await invariants(h, chk, {});
  await h.stop();
}

function security(args) { return new Promise((res) => execFile('security', args, (e, out, err) => res({ ok: !e, out: out + err }))); }

async function keychainKill(chk, r, seed, deadline) {
  const service = `dev.mote.helper.chaos.${seed}`;
  const store = { keychainService: service };
  try {
    let h = await startHelper({ store });
    const port = h.port;
    const cred = await pair(h);
    let rounds = 0, recovered = 0;
    while (rounds < 8 && Date.now() < deadline - 5000) {
      rounds++;
      // Every successful auth rewrites the Keychain item (lastSeen). Kill in the middle of a storm of them.
      let storming = true, auths = 0;
      const storm = Promise.all(PEERS.map(async (host) => {
        while (storming) { try { const c = await authed(h, cred, { host }); auths++; c.destroy(); } catch { await sleep(5); } }
      }));
      await sleep(r.int(20, 600));
      h.kill('SIGKILL');
      await h.waitExit(3000);
      storming = false; await storm;
      h = await startHelper({ store, port, openPairing: false });
      let ok = false;
      try { const c = await authed(h, cred, { host: PEERS[0] }); ok = true; c.close(); } catch (e) { chk.note(`keychain round ${rounds}: ${e.message}; log: ${h.log.slice(-3).join(' / ')}`); }
      if (ok) recovered++;
      chk.ok(!h.log.some((l) => /keychain (read|write) failed/.test(l)), `keychain round ${rounds}: no Keychain read/write errors after kill`, h.log.filter((l) => /keychain/.test(l)));
    }
    chk.ok(recovered === rounds, `keychain: token survives SIGKILL during writes (${recovered}/${rounds})`);
    chk.cases += rounds;
    await h.stop();
  } finally {
    await security(['delete-generic-password', '-s', service, '-a', 'secrets']);   // the one Vault item (helper Vault.swift)
  }
}
