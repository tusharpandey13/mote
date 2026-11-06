// Inline icon set (stand-in for Material Symbols Rounded, weight 500). Usage: <i data-i="play" class="i28"></i>
(function () {
  var S = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  var F = 'fill="currentColor"';
  var I = {
    play: '<path ' + F + ' d="M8 5.5v13a1 1 0 0 0 1.5.86l10.4-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z"/>',
    pause: '<rect ' + F + ' x="6" y="5" width="4" height="14" rx="1.2"/><rect ' + F + ' x="14" y="5" width="4" height="14" rx="1.2"/>',
    prev: '<path ' + F + ' d="M18 6.5v11a1 1 0 0 1-1.55.83L9 13.2a1.4 1.4 0 0 1 0-2.4l7.45-5.13A1 1 0 0 1 18 6.5z"/><rect ' + F + ' x="5" y="6" width="2.6" height="12" rx="1.2"/>',
    next: '<path ' + F + ' d="M6 6.5v11a1 1 0 0 0 1.55.83L15 13.2a1.4 1.4 0 0 0 0-2.4L7.55 5.67A1 1 0 0 0 6 6.5z"/><rect ' + F + ' x="16.4" y="6" width="2.6" height="12" rx="1.2"/>',
    minus: '<path ' + S + ' d="M6 12h12"/>',
    plus: '<path ' + S + ' d="M12 6v12M6 12h12"/>',
    speaker: '<path ' + S + ' d="M4 9.5h3l4.5-4v13L7 14.5H4z"/><path ' + S + ' d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/>',
    mute: '<path ' + S + ' d="M4 9.5h3l4.5-4v13L7 14.5H4z"/><path ' + S + ' d="M16 9.5l5 5M21 9.5l-5 5"/>',
    sunMinus: '<circle ' + S + ' cx="12" cy="12" r="3.2"/><path ' + S + ' d="M12 4.5v1.2M12 18.3v1.2M4.5 12h1.2M18.3 12h1.2M6.7 6.7l.85.85M16.45 16.45l.85.85M6.7 17.3l.85-.85M16.45 7.55l.85-.85"/>',
    sunPlus: '<circle ' + F + ' cx="12" cy="12" r="4"/><path ' + S + ' d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.5 1.5M17.2 17.2l1.5 1.5M5.3 18.7l1.5-1.5M17.2 6.8l1.5-1.5"/>',
    more: '<circle ' + F + ' cx="12" cy="5.5" r="1.8"/><circle ' + F + ' cx="12" cy="12" r="1.8"/><circle ' + F + ' cx="12" cy="18.5" r="1.8"/>',
    modeMedia: '<rect ' + S + ' x="3.5" y="3.5" width="17" height="17" rx="8.5"/><path ' + F + ' d="M10 8.6v6.8a.6.6 0 0 0 .9.5l5.3-3.4a.6.6 0 0 0 0-1L10.9 8.1a.6.6 0 0 0-.9.5z"/>',
    modePad: '<rect ' + S + ' x="3.5" y="4.5" width="17" height="15" rx="3.5"/><path ' + S + ' d="M12 15.5v4"/>',
    modeKeys: '<rect ' + S + ' x="2.5" y="6" width="19" height="12" rx="3"/><path ' + S + ' d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M8 14h8"/>',
    modeMacros: '<rect ' + S + ' x="3.5" y="3.5" width="7" height="7" rx="2"/><rect ' + S + ' x="13.5" y="3.5" width="7" height="7" rx="2"/><rect ' + S + ' x="3.5" y="13.5" width="7" height="7" rx="2"/><path ' + S + ' d="M17 13.5v7M13.5 17h7"/>',
    lock: '<rect ' + S + ' x="5" y="10.5" width="14" height="10" rx="2.5"/><path ' + S + ' d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>',
    unlock: '<rect ' + S + ' x="5" y="10.5" width="14" height="10" rx="2.5"/><path ' + S + ' d="M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2"/>',
    bluetooth: '<path ' + S + ' d="M7 7.5l10 9-5 4.5v-18l5 4.5-10 9"/>',
    laptop: '<rect ' + S + ' x="4.5" y="5" width="15" height="10.5" rx="1.8"/><path ' + S + ' d="M2.5 19h19"/>',
    desktop: '<rect ' + S + ' x="3" y="4" width="18" height="12" rx="2"/><path ' + S + ' d="M9 20h6M12 16v4"/>',
    check: '<path ' + S + ' d="M5 12.5l4.5 4.5L19 7.5"/>',
    close: '<path ' + S + ' d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    refresh: '<path ' + S + ' d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4"/>',
    backspace: '<path ' + S + ' d="M9 5.5h10a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H9L3.5 12z"/><path ' + S + ' d="M11.5 9.5l5 5M16.5 9.5l-5 5"/>',
    ret: '<path ' + S + ' d="M19 5.5v6a2.5 2.5 0 0 1-2.5 2.5H5.5M9.5 10l-4 4 4 4"/>',
    chevDown: '<path ' + S + ' d="M7 10l5 5 5-5"/>',
    chevUp: '<path ' + S + ' d="M7 14l5-5 5 5"/>',
    camera: '<path ' + S + ' d="M4 8.5A1.5 1.5 0 0 1 5.5 7h2l1.5-2h6l1.5 2h2A1.5 1.5 0 0 1 20 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z"/><circle ' + S + ' cx="12" cy="12.5" r="3.5"/>',
    windows: '<rect ' + S + ' x="3" y="5" width="8" height="6" rx="1.5"/><rect ' + S + ' x="13" y="5" width="8" height="6" rx="1.5"/><rect ' + S + ' x="3" y="13" width="8" height="6" rx="1.5"/><rect ' + S + ' x="13" y="13" width="8" height="6" rx="1.5"/>',
    search: '<circle ' + S + ' cx="10.5" cy="10.5" r="6"/><path ' + S + ' d="M15 15l5 5"/>',
    mic: '<rect ' + S + ' x="9" y="3.5" width="6" height="11" rx="3"/><path ' + S + ' d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>',
    present: '<rect ' + S + ' x="3" y="4.5" width="18" height="12" rx="2"/><path ' + F + ' d="M10 8.2v4.6a.5.5 0 0 0 .78.42l3.5-2.3a.5.5 0 0 0 0-.84l-3.5-2.3a.5.5 0 0 0-.78.42z"/><path ' + S + ' d="M9 20.5l3-4 3 4"/>',
    power: '<path ' + S + ' d="M12 3.5v8M7.2 6.8a7 7 0 1 0 9.6 0"/>',
    text: '<path ' + S + ' d="M5 6.5h14M12 6.5v12M9 18.5h6"/>',
    apps: '<rect ' + S + ' x="4" y="4" width="16" height="16" rx="4.5"/><path ' + S + ' d="M9 15l3-7 3 7M10 13h4"/>',
    spaces: '<rect ' + S + ' x="2.5" y="6" width="8" height="12" rx="2"/><rect ' + S + ' x="13.5" y="6" width="8" height="12" rx="2"/><path ' + S + ' d="M10.5 12h3"/>',
    trash: '<path ' + S + ' d="M5 7h14M10 7V5h4v2M7 7l1 12.5h8L17 7"/>',
    edit: '<path ' + S + ' d="M14.5 5.5l4 4L9 19H5v-4z"/>',
    finger1: '<circle ' + F + ' cx="12" cy="12" r="3.2"/>',
    finger2: '<circle ' + F + ' cx="8" cy="12" r="3"/><circle ' + F + ' cx="16" cy="12" r="3"/>',
    finger3: '<circle ' + F + ' cx="5.5" cy="12" r="2.6"/><circle ' + F + ' cx="12" cy="12" r="2.6"/><circle ' + F + ' cx="18.5" cy="12" r="2.6"/>',
    hand: '<path ' + S + ' d="M8 13V6.5a1.5 1.5 0 0 1 3 0V12M11 11V5a1.5 1.5 0 0 1 3 0v6M14 11V6.5a1.5 1.5 0 0 1 3 0V14a6 6 0 0 1-6 6h-.6a5 5 0 0 1-3.9-1.9L4 13.7a1.4 1.4 0 0 1 2.1-1.8L8 14"/>',
    wifiBars: '<path ' + F + ' d="M3 19h3v-3H3zM8 19h3v-6H8zM13 19h3v-9h-3zM18 19h3V7h-3z"/>',
    battery: '<rect ' + S + ' x="3" y="7.5" width="16" height="9" rx="2"/><rect ' + F + ' x="5" y="9.5" width="10" height="5" rx="1"/><path ' + S + ' d="M21.5 10.5v3"/>'
  };
  document.querySelectorAll('i[data-i]').forEach(function (el) {
    var name = el.getAttribute('data-i');
    if (!I[name]) return;
    var svg = '<svg viewBox="0 0 24 24" aria-hidden="true" class="i ' + (el.className || '') + '"' +
      (el.getAttribute('data-dc-id') ? ' data-dc-id="' + el.getAttribute('data-dc-id') + '"' : '') + '>' + I[name] + '</svg>';
    el.outerHTML = svg;
  });
})();
