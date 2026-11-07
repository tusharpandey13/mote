const $ = (id) => document.getElementById(id);
const LABELS = {
  connected: 'Connected to Mote Helper', connecting: 'Connecting…', disconnected: 'Mote Helper not reachable. Is Labs › Browser extension bridge on?',
  unpaired: 'Not paired yet',
};

function render(s) {
  $('status').textContent = LABELS[s.status] || s.status;
  $('status').dataset.status = s.status;
  if (s.port) $('port').placeholder = String(s.port);
  const now = $('now');
  if (s.target) { now.style.display = 'block'; now.textContent = `Now relaying: ${s.target.title || s.target.site || 'video'}`; }
  else now.style.display = 'none';
  $('forget').style.display = s.paired ? 'block' : 'none';
}
const refresh = () => chrome.runtime.sendMessage({ type: 'getStatus' }).then(render);

$('pair').addEventListener('submit', async (e) => {
  e.preventDefault();
  await chrome.runtime.sendMessage({ type: 'pair', code: $('code').value, port: $('port').value || undefined });
  $('code').value = '';
  setTimeout(refresh, 300);
});
$('forget').addEventListener('click', () => chrome.runtime.sendMessage({ type: 'forget' }).then(refresh));
chrome.runtime.onMessage.addListener((m) => { if (m?.type === 'status') refresh(); });
refresh();
setInterval(refresh, 1000);
