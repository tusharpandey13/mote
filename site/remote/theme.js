// Applies the saved theme before first paint (separate file: the helper's CSP allows no inline scripts).
(function () { try { var t = localStorage.getItem('mote.ui.theme') || 'system';
    if (t === 'dark' || (t === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)) document.documentElement.className = 'dark'; } catch (e) {} })();
