// Page-world helper for Netflix (world: MAIN). Netflix's <video> rejects `currentTime` writes
// (playback error M7375), so seeking goes through its undocumented player API. This can break at any
// time; when the API isn't found, content.js reports `seekable: false`.
(() => {
  const player = () => {
    try {
      const vp = window.netflix.appContext.state.playerApp.getAPI().videoPlayer;
      const id = vp.getAllPlayerSessionIds().find((s) => s.startsWith('watch')) || vp.getAllPlayerSessionIds()[0];
      return id ? vp.getVideoPlayerBySessionId(id) : null;
    } catch { return null; }
  };
  const mark = () => { document.documentElement.dataset.moteNfApi = player() ? '1' : '0'; };
  setInterval(mark, 2000);
  mark();
  window.addEventListener('message', (e) => {
    if (e.source !== window || e.data?.__moteNf !== 'seek') return;
    const p = player();
    if (p && Number.isFinite(e.data.ms)) p.seek(e.data.ms);
  });
})();
