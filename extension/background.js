// Mote Video Bridge service worker: one WebSocket to Mote Helper on 127.0.0.1, relays the active
// tab's main video state and runs the phone's commands in the right frame. Protocol:
// docs/labs/video-bridge.md section 2.
const DEFAULT_PORT = 8792;
const frames = new Map();    // tabId -> Map(frameId -> {score, state, at})
let lastPlaying = null;      // tabId that most recently reported a playing video
let focusedTab = null;       // active tab of the last focused window
let ws = null;
let authed = false;
let status = 'disconnected'; // disconnected | connecting | unpaired | connected | error: ...
let pendingCode = null;
let backoff = 1000;
let retry = 0;
const prevWindowState = new Map();

const store = (k) => chrome.storage.local.get(k);

async function hmacHex(tokenHex, nonce) {
  const key = await crypto.subtle.importKey('raw', new Uint8Array(tokenHex.match(/../g).map((h) => parseInt(h, 16))),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(nonce)));
  return [...sig].map((b) => b.toString(16).padStart(2, '0')).join('');
}

const send = (m) => { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(m)); };
const setStatus = (s) => { status = s; chrome.runtime.sendMessage({ type: 'status', status }).catch(() => {}); };

async function connect() {
  if (ws && ws.readyState <= WebSocket.OPEN) return;
  const { port = DEFAULT_PORT } = await store('port');
  setStatus('connecting');
  authed = false;
  const s = new WebSocket(`ws://127.0.0.1:${port}/bridge`);
  ws = s;
  s.onmessage = async (e) => {
    let m; try { m = JSON.parse(e.data); } catch { return; }
    if (m.t === 'hello') {
      s.nonce = m.nonce;
      const { id, token } = await store(['id', 'token']);
      if (pendingCode) { send({ t: 'labs.video.pair', code: pendingCode }); pendingCode = null; }
      else if (id && token) send({ t: 'labs.video.auth', id, mac: await hmacHex(token, m.nonce) });
      else setStatus('unpaired');
    } else if (m.t === 'labs.video.paired') {
      await chrome.storage.local.set({ id: m.id, token: m.token });
      send({ t: 'labs.video.auth', id: m.id, mac: await hmacHex(m.token, s.nonce) });
    } else if (m.t === 'labs.video.authed') {
      authed = true; backoff = 1000; setStatus('connected'); push(true);
    } else if (m.t === 'labs.video.cmd') {
      command(m.cmd, m.value);
    } else if (m.t === 'ping') {
      send({ t: 'pong', n: m.n });
    } else if (m.t === 'err') {
      if (m.code === 'auth' || m.code === 'revoked') { await chrome.storage.local.remove(['id', 'token']); setStatus('unpaired'); }
      else if (m.ref === 'labs.video.pair') setStatus(`error: ${m.msg || m.code}`);
    }
  };
  s.onclose = () => {
    if (ws === s) { ws = null; authed = false; }
    if (!status.startsWith('error') && status !== 'unpaired') setStatus('disconnected');
    clearTimeout(retry);
    retry = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30000);
  };
}

// Keepalive: messages every 20 s keep the MV3 worker (and the socket) alive; the alarm revives a
// worker that was stopped anyway.
setInterval(() => send({ t: 'ping', n: Date.now() % 1e6 }), 20000);
chrome.alarms.create('mote-keepalive', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(() => connect());
chrome.runtime.onStartup.addListener(() => connect());
chrome.runtime.onInstalled.addListener(() => connect());

// ---- which tab/frame is relayed

function bestFrame(tabId) {
  let best = null;
  for (const [frameId, f] of frames.get(tabId) || []) {
    if (f.state.active && (!best || f.score > best.score)) best = { frameId, ...f };
  }
  return best;
}

/** Focused tab with a video, else the tab that played last, else the tab that reported a video last. */
function target() {
  let t = focusedTab != null && bestFrame(focusedTab) ? focusedTab : lastPlaying != null && bestFrame(lastPlaying) ? lastPlaying : null;
  if (t == null) {
    let at = 0;
    for (const id of frames.keys()) { const f = bestFrame(id); if (f && f.at > at) { at = f.at; t = id; } }
  }
  return t == null ? null : { tabId: t, ...bestFrame(t) };
}

let lastPushed = '';
async function push(force = false) {
  if (!authed) return;
  const t = target();
  let m = { t: 'labs.video.state', active: false };
  if (t) {
    let tab = null;
    try { tab = await chrome.tabs.get(t.tabId); } catch { /* closed */ }
    let host = '';
    try { host = new URL(tab?.url || '').host; } catch { /* about:blank etc. */ }
    m = { t: 'labs.video.state', ...t.state, title: t.state.title || tab?.title || host, host };
  }
  const k = JSON.stringify(m);
  if (!force && k === lastPushed) return;
  lastPushed = k;
  send(m);
}

async function refreshFocus() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    focusedTab = tab?.id ?? null;
  } catch { focusedTab = null; }
  push();
}
chrome.tabs.onActivated.addListener(refreshFocus);
chrome.windows.onFocusChanged.addListener(refreshFocus);
chrome.tabs.onRemoved.addListener((id) => { frames.delete(id); if (lastPlaying === id) lastPlaying = null; refreshFocus(); });
chrome.tabs.onUpdated.addListener((id, info) => { if (info.status === 'loading') { frames.delete(id); push(); } });

async function command(cmd, value) {
  const t = target();
  if (!t) { send({ t: 'labs.video.result', cmd, ok: false, code: 'notfound' }); return; }
  let r;
  try {
    r = await chrome.tabs.sendMessage(t.tabId, { type: 'cmd', cmd, value }, { frameId: t.frameId });
  } catch {
    r = { ok: false, code: 'notfound' };
  }
  if (r?.window) setWindowFullscreen(t.tabId, true);
  if (!r?.ok) send({ t: 'labs.video.result', cmd, ok: false, code: r?.code || 'failed' });
}

async function setWindowFullscreen(tabId, on) {
  try {
    const tab = await chrome.tabs.get(tabId);
    const w = await chrome.windows.get(tab.windowId);
    if (on && w.state !== 'fullscreen') { prevWindowState.set(w.id, w.state); await chrome.windows.update(w.id, { state: 'fullscreen' }); }
    if (!on && w.state === 'fullscreen') { await chrome.windows.update(w.id, { state: prevWindowState.get(w.id) || 'normal' }); prevWindowState.delete(w.id); }
  } catch { /* window gone */ }
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (m?.type === 'state' && sender.tab) {
    const tabId = sender.tab.id;
    if (!frames.has(tabId)) frames.set(tabId, new Map());
    const f = frames.get(tabId);
    if (m.state.active) f.set(sender.frameId, { score: m.score, state: m.state, at: Date.now() });
    else f.delete(sender.frameId);
    if (m.state.active && !m.state.paused) lastPlaying = tabId;
    if (sender.tab.active && focusedTab == null) focusedTab = tabId;
    connect();
    push();
    return;
  }
  if (m?.type === 'window' && sender.tab) { setWindowFullscreen(sender.tab.id, !!m.fullscreen); return; }
  if (m?.type === 'getStatus') {
    store(['id', 'port']).then(({ id, port }) => reply({ status, paired: !!id, port: port || DEFAULT_PORT, target: target()?.state || null }));
    return true;
  }
  if (m?.type === 'pair') {
    // A new pairing replaces the stored credential; the code is sent once the next hello arrives.
    (async () => {
      if (m.port) await chrome.storage.local.set({ port: +m.port });
      await chrome.storage.local.remove(['id', 'token']);
      pendingCode = String(m.code).replace(/\D/g, '');
      backoff = 1000;
      if (ws) ws.close(); else connect();
      reply({ ok: true });
    })();
    return true;
  }
  if (m?.type === 'forget') {
    chrome.storage.local.remove(['id', 'token']).then(() => { ws?.close(); setStatus('unpaired'); reply({ ok: true }); });
    return true;
  }
});

refreshFocus();
connect();
