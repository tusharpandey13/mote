// Mote Video Bridge content script: runs in every frame, finds this frame's main <video>, reports its
// state to the service worker and runs commands on it. The worker picks the best frame per tab.
(() => {
  if (window.__moteVideoBridge) return;
  window.__moteVideoBridge = true;

  const host = location.hostname;
  const adapter = (self.MOTE_ADAPTERS || []).find((a) => a.hosts.test(host)) || { site: 'generic' };
  const SKIP_TEXT = self.MOTE_SKIP_TEXT || {};
  const FS_ATTR = 'data-mote-fs';
  let pseudoFs = null; // the <video> we stretched over the viewport, if any
  let last = '';
  let lastSent = 0;
  let timer = 0;

  const q = (sel) => (sel ? document.querySelector(sel) : null);
  const visible = (el) => {
    if (!el || !el.isConnected) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && +s.opacity > 0.05;
  };
  const firstVisible = (sels) => (sels || []).map(q).find(visible) || null;

  /** Visible area of v inside the viewport. */
  function area(v) {
    const r = v.getBoundingClientRect();
    const w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
    const h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
    return w * h;
  }

  /** The main video: largest visible one, playing ones first. Score 0 = none. */
  function main() {
    let best = null, score = 0;
    for (const v of document.querySelectorAll('video')) {
      if (v.readyState === 0 && !v.currentSrc && !v.src) continue;
      const a = v === pseudoFs ? innerWidth * innerHeight : area(v);
      if (a < 64 * 36) continue;
      const s = a * (!v.paused && !v.ended ? 4 : 1);
      if (s > score) { best = v; score = s; }
    }
    return { v: best, score };
  }

  function genericSkip(kind) {
    const re = SKIP_TEXT[kind];
    if (!re) return null;
    const els = document.querySelectorAll('button, [role="button"]');
    for (let i = 0; i < els.length && i < 300; i++) {
      const t = (els[i].textContent || els[i].getAttribute('aria-label') || '').trim();
      if (t.length < 40 && re.test(t) && visible(els[i])) return els[i];
    }
    return null;
  }
  const skipButton = (kind) => firstVisible(adapter.skips?.[kind]) || genericSkip(kind);

  function captionTracks(v) {
    return [...(v.textTracks || [])].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
  }
  function captionsState(v) {
    const b = q(adapter.captions?.button);
    if (b && visible(b)) return adapter.captions.on ? (b.matches(adapter.captions.on) ? 'on' : 'off') : 'off';
    const tr = captionTracks(v);
    if (!tr.length) return 'none';
    return tr.some((t) => t.mode === 'showing') ? 'on' : 'off';
  }

  const nfApi = () => document.documentElement.dataset.moteNfApi === '1';

  function snapshot() {
    const { v, score } = main();
    if (!v) return { score: 0, state: { active: false } };
    const d = v.duration;
    const live = !!(adapter.live && visible(q(adapter.live))) || d === Infinity;
    const titleEl = q(adapter.title);
    return {
      score,
      state: {
        active: true,
        title: (titleEl?.textContent || '').trim().slice(0, 200) || undefined,
        site: adapter.site,
        duration: Number.isFinite(d) ? d : null,
        time: v.currentTime,
        paused: v.paused || v.ended,
        rate: v.playbackRate,
        muted: v.muted,
        volume: v.volume,
        fullscreen: !!document.fullscreenElement || pseudoFs === v,
        live,
        seekable: !live && Number.isFinite(d) && (!adapter.pageSeek || nfApi()),
        hasNext: !!firstVisible([adapter.next]),
        hasPrev: !!firstVisible([adapter.prev]),
        captions: captionsState(v),
        skips: ['ad', 'intro', 'recap', 'credits'].filter((k) => skipButton(k)),
      },
    };
  }

  /** Sends when anything but the clock changed, when the clock jumped, and every 5 s as a heartbeat. */
  function report() {
    timer = 0;
    let snap;
    try { snap = snapshot(); } catch { return; }
    const { time, ...rest } = snap.state;
    const key = JSON.stringify(rest) + '|' + Math.round((time || 0) - (performance.now() / 1000) * (rest.paused ? 0 : rest.rate || 1));
    const now = performance.now();
    if (key === last && now - lastSent < 5000) return;
    if (!snap.score && last === '' ) { last = key; return; } // never had a video: stay quiet
    last = key; lastSent = now;
    try { chrome.runtime.sendMessage({ type: 'state', score: snap.score, state: snap.state }).catch(() => {}); } catch { /* extension reloaded */ }
  }
  const schedule = () => { if (!timer) timer = setTimeout(report, 120); };

  for (const ev of ['play', 'pause', 'playing', 'ratechange', 'volumechange', 'seeked', 'durationchange', 'loadedmetadata', 'emptied', 'ended']) {
    document.addEventListener(ev, schedule, true);
  }
  document.addEventListener('fullscreenchange', schedule);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && pseudoFs) exitPseudo(); }, true);
  setInterval(schedule, 1000);
  schedule();

  // ---- commands

  function ensureStyle() {
    if (document.getElementById('mote-fs-style')) return;
    const s = document.createElement('style');
    s.id = 'mote-fs-style';
    s.textContent = `video[${FS_ATTR}]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;` +
      `max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}`;
    (document.head || document.documentElement).appendChild(s);
  }
  function exitPseudo() {
    pseudoFs?.removeAttribute(FS_ATTR);
    pseudoFs = null;
    chrome.runtime.sendMessage({ type: 'window', fullscreen: false }).catch(() => {});
    schedule();
  }

  async function fullscreen(v, want) {
    const on = !!document.fullscreenElement || !!pseudoFs;
    if (want === undefined) want = !on;
    if (!want) {
      if (document.fullscreenElement) await document.exitFullscreen().catch(() => {});
      if (pseudoFs) exitPseudo();
      return {};
    }
    if (on) return {};
    try {
      await v.requestFullscreen(); // needs a user gesture: usually rejected for remote commands
      return {};
    } catch {
      ensureStyle();
      v.setAttribute(FS_ATTR, '');
      pseudoFs = v;
      return { window: true };
    }
  }

  function seekTo(v, t) {
    const d = Number.isFinite(v.duration) ? v.duration : Infinity;
    t = Math.max(0, Math.min(t, d));
    if (adapter.pageSeek) {
      if (!nfApi()) throw Object.assign(new Error('no player API'), { code: 'unsupported' });
      window.postMessage({ __moteNf: 'seek', ms: Math.round(t * 1000) }, location.origin);
      return;
    }
    v.currentTime = t;
  }

  const click = (el) => { if (!el) throw Object.assign(new Error('not on this page'), { code: 'notfound' }); el.click(); };

  async function exec(cmd, value) {
    const { v } = main();
    if (!v) throw Object.assign(new Error('no video'), { code: 'notfound' });
    switch (cmd) {
      case 'play': await v.play(); break;
      case 'pause': v.pause(); break;
      case 'toggle': if (v.paused || v.ended) await v.play(); else v.pause(); break;
      case 'seek': seekTo(v, +value); break;
      case 'seekBy': seekTo(v, v.currentTime + +value); break;
      case 'rate': v.playbackRate = Math.max(0.25, Math.min(4, +value)); break;
      case 'volume': v.volume = Math.max(0, Math.min(1, +value)); v.muted = false; break;
      case 'mute': v.muted = value === undefined ? !v.muted : !!value; break;
      case 'fullscreen': return fullscreen(v, value);
      case 'next': click(firstVisible([adapter.next])); break;
      case 'prev': click(firstVisible([adapter.prev])); break;
      case 'captions': {
        const cur = captionsState(v);
        const want = value === undefined ? cur !== 'on' : !!value;
        if (cur === 'none') throw Object.assign(new Error('no captions'), { code: 'notfound' });
        if ((cur === 'on') === want) break;
        const b = q(adapter.captions?.button);
        if (b && visible(b)) { b.click(); break; }
        const tr = captionTracks(v);
        tr.forEach((t, i) => { t.mode = want && i === 0 ? 'showing' : 'disabled'; });
        break;
      }
      case 'skip': {
        const kinds = value ? [value] : ['ad', 'intro', 'recap', 'credits'];
        click(kinds.map(skipButton).find(Boolean));
        break;
      }
      default: throw Object.assign(new Error('unknown cmd'), { code: 'bad' });
    }
    return {};
  }

  chrome.runtime.onMessage.addListener((m, _sender, reply) => {
    if (m?.type !== 'cmd') return;
    exec(m.cmd, m.value).then(
      (r) => { schedule(); reply({ ok: true, ...r }); },
      (e) => { schedule(); reply({ ok: false, code: e.code || (e.name === 'NotAllowedError' ? 'blocked' : 'failed'), msg: String(e.message || e) }); },
    );
    return true;
  });
})();
