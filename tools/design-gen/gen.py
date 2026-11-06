#!/usr/bin/env python3
"""Generates design/mote-v1/*.html (rev 4: light default; media.html is the dark variant). Static HTML is the artifact; this only removes chrome duplication."""
import sys, pathlib
OUT = pathlib.Path(sys.argv[1])

DEVICE = "MacBook Pro"

def page(title, body, css="", dark=False):
    cls = ' class="dark"' if dark else ""
    return f"""<!doctype html>
<html lang="en"{cls}>
<head>
<meta charset="utf-8">
<title>Mote — {title}</title>
<link rel="stylesheet" href="mote.css">
<style>{css}</style>
</head>
<body>
{body}
<script src="icons.js"></script>
</body>
</html>
"""

STATUS = '<div class="statusbar" data-dc-id="statusbar"><span>9:41</span><span class="sys"><i data-i="wifiBars" class="i16"></i><i data-i="battery" class="i20"></i></span></div>'

# Wordmark (debossed Unbounded Black) sits right; the menu is a small engraved glyph beside it.
BRAND = """<div class="brand" data-dc-id="brand">
      <span class="wordmark" data-dc-id="wordmark">MOTE</span>
      <div class="icon-btn" data-dc-id="menu"><i data-i="more" class="i20"></i></div>
    </div>"""

def topbar(state="connected"):
    led = {"connected": "led", "searching": "led amber", "pairing": "led amber"}[state]
    extra = {"connected": "", "searching": '<span class="state" data-dc-id="pill-state">Searching…</span>', "pairing": ""}[state]
    return f"""<header class="topbar" data-dc-id="topbar">
    <div class="pill raised" data-dc-id="connection-pill">
      <span class="{led}" data-dc-id="pill-led"></span>
      <span class="name" data-dc-id="pill-name">{DEVICE}</span>{extra}
      <i data-i="chevDown" class="i20 chev" data-dc-id="pill-chevron"></i>
    </div>
    {BRAND}
  </header>"""

MODES = [("media", "rail-media", "modeMedia", "Media"), ("mouse", "rail-pad", "modeMouse", "Mouse"),
         ("keys", "rail-keys", "modeKeys", "Keys"), ("macros", "rail-macros", "modeMacros", "Macros")]

def rail(on):
    segs = "\n      ".join(
        f'<div class="seg{" on raised" if m == on else ""}" data-dc-id="{i}"><i data-i="{ic}"></i><span class="lb">{lb}</span></div>'
        for m, i, ic, lb in MODES)
    return f"""<nav class="rail" data-dc-id="mode-rail">
    <div class="track well" data-dc-id="rail-track">
      {segs}
    </div>
  </nav>
  <div class="navbar"></div>"""

def screen(inner, on, state="connected", overlay=""):
    return f"""<div class="screen" data-dc-id="screen">
  {STATUS}
  {topbar(state)}
  {inner}
  {rail(on)}
{overlay}
</div>"""

# ---------------------------------------------------------------- media
DPAD_ARM = 'M30.46 30.46A104 104 0 0 1 177.54 30.46L132.28 75.72A40 40 0 0 0 75.72 75.72Z'
TRI = 'M104 27l7 9.5h-14z'
def dpad():
    arms = "".join(
        '<g data-dc-id="dpad-%s"%s><path class="arm" d="%s"/><path class="tri" d="%s"/></g>'
        % (n, (' transform="rotate(%d 104 104)"' % r) if r else "", DPAD_ARM, TRI)
        for n, r in [("up", 0), ("right", 90), ("down", 180), ("left", 270)])
    return f"""<svg class="dpad" viewBox="0 0 208 208" data-dc-id="dpad">
        <defs><linearGradient id="dpk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--rub-top)"/><stop offset="1" style="stop-color:var(--rub-bot)"/></linearGradient><linearGradient id="bev" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:#fff;stop-opacity:.9"/><stop offset=".35" style="stop-color:#fff;stop-opacity:0"/><stop offset=".7" style="stop-color:#000;stop-opacity:0"/><stop offset="1" style="stop-color:#000;stop-opacity:.22"/></linearGradient></defs>
        <circle class="housing" cx="104" cy="104" r="104"/>
        <circle class="contact" cx="104" cy="110" r="92"/>
        <circle class="disc" cx="104" cy="104" r="96" fill="url(#dpk)" data-dc-id="dpad-rubber"/>
        <circle class="bevel" cx="104" cy="104" r="95.25" stroke="url(#bev)" data-dc-id="dpad-bevel"/>
        {arms}
        <g><line class="div" x1="73" y1="73" x2="47" y2="47"/><line class="div" x1="135" y1="73" x2="161" y2="47"/><line class="div" x1="73" y1="135" x2="47" y2="161"/><line class="div" x1="135" y1="135" x2="161" y2="161"/></g>
        <g data-dc-id="dpad-center"><circle class="ring" cx="104" cy="104" r="42"/><circle class="cap" cx="104" cy="104" r="35" fill="url(#dpk)"/><circle class="bevel" cx="104" cy="104" r="34.25" stroke="url(#bev)"/><text class="ok" x="104" y="109.5" text-anchor="middle">OK</text></g>
      </svg>"""

MEDIA_CSS = """
  .media { display: flex; flex-direction: column; height: 100%; }
  .fav-head { display: flex; align-items: center; justify-content: space-between; height: 40px; color: var(--on-surface-subtle); }
  .fav-head span { display: inline-flex; align-items: center; gap: 6px; }
  .favs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 6px; }
  .fav { height: 64px; border-radius: 16px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; position: relative; color: var(--on-surface); }
  .fav .lb { font: 600 12px/14px var(--font-ui); color: var(--on-surface-muted); white-space: nowrap; }
  .fav.gesture { grid-column: span 2; flex-direction: row; padding: 0 8px; }
  .sec-row { margin-top: auto; }
  .sec-row .cap { color: var(--on-surface) !important; }
  .cluster { position: relative; height: 256px; margin-top: 24px; align-items: center; display: grid; grid-template-columns: 56px 208px 56px; justify-content: space-between; }
  .cluster .col { display: flex; flex-direction: column; align-items: center; gap: 8px; }
  .cluster .dpad { margin-top: 0; }
  .cluster .mute { position: absolute; right: 66px; bottom: 6px; width: 48px; height: 48px; border-radius: 50%; display: grid; place-items: center; color: var(--on-surface); }
  .transport { margin-top: 20px; }
  .transport .glass.sm { height: 72px; }
  .transport .glass.play { height: 96px; border-radius: 24px; }
  .cluster .slider { height: 232px; }
  .dark .engrave { color: #4F535B; }
"""

def spaces_tile(dc, cls):
    return f"""<div class="{cls} gesture raised" data-dc-id="{dc}">
        <div class="slide"><i data-i="chevLeft" class="i20" data-dc-id="{dc}-prev"></i>
          <div class="mid"><i data-i="spaces"></i><span class="lb" style="color:var(--on-surface)">Spaces</span><span class="hint" data-dc-id="{dc}-hint">hold &amp; slide</span></div>
          <i data-i="chevRight" class="i20" data-dc-id="{dc}-next"></i></div>
      </div>"""

def slider(dc, label, top, bottom, thumb):
    return f"""<div class="col">
        <div class="slider well" data-dc-id="{dc}">
          <span class="end" data-dc-id="{dc}-top">{top}</span>
          <span class="ticks"></span>
          <div class="thumb" data-dc-id="{dc}-thumb">{thumb}</div>
          <span class="end" data-dc-id="{dc}-bottom">{bottom}</span>
        </div>
        <span class="engrave" data-dc-id="{dc}-label">{label}</span>
      </div>"""

def media_inner(offline=False):
    live = " live" if offline else ""
    fav_html = spaces_tile("fav-next-space", "fav") + '<div class="fav add" style="grid-column: span 2" data-dc-id="fav-add"><i data-i="plus" class="i20"></i><span class="lb">Add favourite</span></div>'
    grip = '<span class="grip"></span><span class="grip"></span><span class="grip"></span>'
    scroll = slider("scroll-lever", "Scroll", '<i data-i="triUp" class="i16"></i>', '<i data-i="triDown" class="i16"></i>', grip)
    volume = slider("volume-group", "Volume", "+", "−", '<i data-i="speaker" class="i20"></i>')
    return f"""<main class="content media{' offline' if offline else ''}" data-dc-id="media-content">
    <section class="fav-wrap{live}" data-dc-id="fav-strip">
      <div class="fav-head"><span class="t-caps"><i data-i="starFill" class="i16"></i>Favourites</span><span class="fav-edit-btn raised" data-dc-id="fav-edit"><i data-i="edit" class="i16"></i>Edit</span></div>
      <div class="favs">{fav_html}</div>
    </section>

    <div class="sec-row{live}" data-dc-id="brightness-row">
      <div class="pair small well" data-dc-id="brightness-group">
        <div class="cap raised" data-dc-id="brightness-down"><span class="arrowed"><i data-i="sunDown"></i><i data-i="arrowDown" class="i20" data-dc-id="brightness-down-arrow"></i></span></div>
        <div class="cap raised" data-dc-id="brightness-up"><span class="arrowed"><i data-i="sunUp"></i><i data-i="arrowUp" class="i20" data-dc-id="brightness-up-arrow"></i></span></div>
      </div>
    </div>

    <div class="cluster{live}" data-dc-id="dpad-row">
      {scroll}
      {dpad()}
      {volume}
      <div class="mute raised" data-dc-id="mute"><i data-i="mute" class="i20"></i></div>
    </div>

    <div class="channel well transport{live}" data-dc-id="transport">
      <div class="glass sm" data-dc-id="prev"><i data-i="prev" class="i28"></i></div>
      <div class="glass play" data-dc-id="play-pause"><i data-i="playPause" class="i32"></i></div>
      <div class="glass sm" data-dc-id="next"><i data-i="next" class="i28"></i></div>
    </div>
  </main>"""

# ---------------------------------------------------------------- disconnected / device picker
LOST_CSS = """
  .lost { position: absolute; left: 16px; right: 16px; top: 50%; transform: translateY(-50%); z-index: 2; border-radius: 24px; padding: 24px 20px 20px; text-align: center; display: flex; flex-direction: column; align-items: center; }
  .lost .t-title { margin-top: 16px; }
  .lost .t-body-s { color: var(--on-surface-muted); margin-top: 6px; max-width: 300px; }
  .lost .acts { display: flex; gap: 8px; margin-top: 18px; width: 100%; }
  .lost .acts > * { flex: 1; }
"""
def lost_card():
    return f"""<section class="lost raised" data-dc-id="disconnected-card">
      <div class="scan" data-dc-id="scan-indicator"><i data-i="bluetooth" class="i28"></i></div>
      <h2 class="t-title" data-dc-id="disconnected-title">Searching for {DEVICE}…</h2>
      <p class="t-body-s" data-dc-id="disconnected-body">Mote reconnects on its own while it's open. Wake {DEVICE} or bring it in range. Nothing you press is sent until then.</p>
      <div class="acts"><div class="btn-secondary raised" data-dc-id="pair-another"><i data-i="devices"></i>Choose device</div></div>
    </section>"""

def disconnected_inner():
    return media_inner(offline=True).replace('<section class="fav-wrap', lost_card() + '\n    <section class="fav-wrap', 1)

SHEET_CSS = """
  .sheet { padding: 0 16px 36px; }
  .sh-head { display: flex; align-items: center; justify-content: space-between; height: 60px; margin-top: 4px; }
  .list { display: flex; flex-direction: column; gap: 8px; }
  .scrim, .sheet { z-index: 3; }
"""
def device_sheet():
    rows = [("known-macbook", "laptop", DEVICE, "Searching…", "led amber", True),
            ("known-macmini", "desktop", "Mac mini", "Paired, not in range", "", False),
            ("known-ipad", "tablet", "iPad Air", "Paired, not in range", "", False),
            ("known-tv", "tv", "Living room TV", "Paired, not in range", "", False)]
    items = "".join(f"""
        <div class="row-item {'raised' if on else 'flat'}" data-dc-id="{i}">
          <i data-i="{ic}"></i>
          <div class="tx"><div class="t-label">{n}</div><div class="sub">{led and f'<span class="{led}" style="display:inline-block;margin-right:6px;vertical-align:1px;width:7px;height:7px"></span>'}{s}</div></div>
          <span class="radio{' on' if on else ''}"></span>
          <div class="icon-btn"><i data-i="more"></i></div>
        </div>""" for i, ic, n, s, led, on in rows)
    return f"""  <div class="scrim" data-dc-id="scrim"></div>
  <section class="sheet" data-dc-id="device-picker-sheet">
    <div class="handle"></div>
    <div class="sh-head"><h2 class="t-headline">Devices</h2><div class="icon-btn" data-dc-id="picker-close"><i data-i="close"></i></div></div>
    <p class="t-body-s muted" style="margin-bottom:14px">Mote connects to the selected device and keeps looking for it while the app is open.</p>
    <div class="list" data-dc-id="device-list">{items}
    </div>
    <div class="btn-primary accent-cap" style="margin-top:18px" data-dc-id="pair-new"><i data-i="plus"></i>Pair new device</div>
  </section>"""

def menu_sheet():
    rows = [("menu-devices", "devices", "Manage devices", f"{DEVICE} and 3 others"),
            ("menu-haptics", "haptic", "Haptics", "Standard"),
            ("menu-settings", "gear", "Settings", "Pointer speed, scrolling, keyboard"),
            ("menu-about", "info", "About Mote", "Version 0.1")]
    items = "".join(f"""
        <div class="row-item" data-dc-id="{i}"><i data-i="{ic}"></i><div class="tx"><div class="t-label">{n}</div><div class="sub">{s}</div></div><i data-i="chevRight" class="i20 subtle"></i></div>""" for i, ic, n, s in rows)
    return f"""  <div class="scrim" data-dc-id="scrim"></div>
  <section class="sheet" data-dc-id="menu-sheet">
    <div class="handle"></div>
    <div class="sh-head"><h2 class="t-headline">Mote</h2><div class="icon-btn"><i data-i="close"></i></div></div>
    <div class="row-item" data-dc-id="menu-theme" style="margin-bottom:4px"><i data-i="theme"></i><div class="tx"><div class="t-label">Theme</div></div>
      <div class="seg-ctl well" style="width:216px"><div>Dark</div><div class="on raised">Light</div><div>System</div></div></div>
    <div class="list" style="gap:2px">{items}
    </div>
  </section>"""

# ---------------------------------------------------------------- mouse
PAD_CSS = """
  .pad-wrap { display: flex; flex-direction: column; height: 100%; gap: 12px; }
  .touchpad { flex: 1; }
  .hints { position: absolute; left: 24px; right: 24px; bottom: 28px; display: grid; grid-template-columns: 36px 1fr; row-gap: 14px; column-gap: 8px; align-items: center; color: var(--on-surface-subtle); }
  .hints .f { display: flex; justify-content: center; opacity: .85; }
  .hints .h { font: 400 14px/20px var(--font-ui); }
  .hints .h b { font-weight: 600; color: var(--on-surface-muted); }
  .hints-hide { position: absolute; right: 12px; top: 12px; height: 32px; padding: 0 12px 0 8px; border-radius: var(--r-full); display: inline-flex; align-items: center; gap: 4px; color: var(--on-surface-muted); font: 600 12px/1 var(--font-ui); }
  .strip { height: 60px; display: grid; grid-template-columns: 1fr 72px 1fr; gap: 8px; padding: 0; }
  .strip > div { border-radius: 18px; display: grid; place-items: center; color: var(--on-surface-muted); font: 600 14px/1 var(--font-ui); }
  .banner { position: absolute; left: 12px; right: 12px; top: 12px; border-radius: 20px; padding: 14px 14px 14px 18px; display: flex; gap: 12px; align-items: center; z-index: 2; }
  .banner .bar { position: absolute; left: 0; top: 14px; bottom: 14px; width: 3px; border-radius: 0 3px 3px 0; background: var(--accent); box-shadow: 0 0 8px var(--accent); }
  .banner .tx { flex: 1; }
  .banner .esc { height: 44px; padding: 0 14px; border-radius: 14px; display: grid; place-items: center; font: 600 14px/1 var(--font-ui); }
  .toast { position: absolute; left: 50%; transform: translateX(-50%); bottom: 18px; height: 36px; padding: 0 14px; border-radius: 18px; display: inline-flex; align-items: center; gap: 8px; font: 500 13px/1 var(--font-ui); color: var(--on-surface-muted); white-space: nowrap; }
  .sel { position: absolute; left: 70px; top: 250px; width: 210px; height: 150px; border: 1.5px dashed var(--on-surface-muted); border-radius: 4px; background: rgba(255,138,61,.06); }
"""
def pad_inner(running=False):
    if running:
        over = """<div class="banner raised" data-dc-id="macro-banner"><span class="bar"></span>
          <div class="tx"><div class="t-label">Screenshot area</div><div class="t-body-s muted" data-dc-id="macro-hint">Drag to select, tap to capture</div></div>
          <div class="esc raised" data-dc-id="macro-cancel">Esc</div></div>
        <span class="sel" data-dc-id="selection-preview"></span>
        <span class="glow" style="left: 280px; top: 400px" data-dc-id="touch-glow"></span>
        <div class="toast well" data-dc-id="mode-toast"><i data-i="modeMouse" class="i16"></i>Switched to Mouse. Back to Macros when done.</div>"""
    else:
        over = """<span class="chip-last" data-dc-id="last-gesture">Click</span>
      <span class="glow" style="left: 248px; top: 214px" data-dc-id="touch-glow"></span>
      <div class="hints-hide raised" data-dc-id="hints-hide"><i data-i="close" class="i16"></i>Hints</div>
      <div class="hints" data-dc-id="gesture-hints">
        <div class="f"><i data-i="finger1"></i></div><div class="h"><b>Drag</b> to move, <b>tap</b> to click</div>
        <div class="f"><i data-i="hold"></i></div><div class="h"><b>Hold</b> to right-click</div>
        <div class="f"><i data-i="finger2"></i></div><div class="h"><b>Two fingers</b> to scroll or pinch to zoom</div>
        <div class="f"><i data-i="finger3"></i></div><div class="h"><b>Three fingers</b> sideways to switch desktops, up for overview</div>
      </div>"""
    return f"""<main class="content pad-wrap live" data-dc-id="pad-content">
    <div class="touchpad" data-dc-id="touchpad">
      {over}
    </div>
    <div class="strip" data-dc-id="button-strip">
      <div class="raised" data-dc-id="left-click">Left</div>
      <div class="raised" data-dc-id="drag-lock"><i data-i="unlock"></i></div>
      <div class="raised" data-dc-id="right-click">Right</div>
    </div>
  </main>"""

# ---------------------------------------------------------------- keys
KEYS_CSS = """
  .keys { display: flex; flex-direction: column; height: 100%; padding-left: 0; padding-right: 0; }
  .row { display: flex; gap: 4px; }
  .row > * { flex: 1 1 0; min-width: 0; }
  .g15 { flex-grow: 1.5 !important; } .g2 { flex-grow: 2 !important; } .g5 { flex-grow: 5 !important; }
  .upper { display: flex; flex-direction: column; gap: 6px; padding: 0 8px; }
  .upper .key { height: 58px; border-radius: 10px; }
  .sec-head { display: flex; align-items: center; justify-content: space-between; height: 28px; padding: 0 8px; color: var(--on-surface-subtle); }
  .sec-head .tog { display: inline-flex; align-items: center; gap: 4px; }
  .navblock { display: grid; grid-template-columns: 1fr 1fr 12px 1fr 1fr 1fr; grid-template-rows: 58px 58px; gap: 6px; margin-top: 4px; }
  .navblock .gap { visibility: hidden; }
  .lower { margin-top: auto; display: flex; flex-direction: column; gap: 8px; }
  .chord { height: 44px; margin: 0 8px; border-radius: 14px; display: flex; align-items: center; gap: 8px; padding: 0 6px 0 10px; }
  .chord .mini { height: 28px; min-width: 28px; padding: 0 7px; border-radius: 8px; display: grid; place-items: center; background: linear-gradient(180deg, var(--modifier-top), var(--modifier-bot)); color: var(--on-modifier); font: 600 15px/1 var(--font-ui); }
  .chord .mini.lk { box-shadow: 0 0 0 2px var(--well), 0 0 0 3.5px var(--modifier-armed); }
  .chord .plus { font: 500 13px/16px var(--font-ui); color: var(--on-surface-muted); }
  .chord .slot { width: 28px; height: 28px; border-radius: 8px; border: 1px dashed var(--on-surface-subtle); }
  .chord .clr { margin-left: auto; height: 32px; padding: 0 12px; border-radius: 10px; display: inline-flex; align-items: center; font: 600 13px/1 var(--font-ui); color: var(--on-surface-muted); }
  .deck { padding: 6px 4px 6px; border-radius: 16px 16px 0 0; display: flex; flex-direction: column; gap: 4px; }
  .deck .key { height: 50px; }
  .modrow { display: flex; gap: 4px; }
  .modrow .mod { flex: 1; min-width: 0; }
  .key.mod { height: 50px; min-width: 0; border-radius: 7px; }
  .key.mod .g { font-size: 18px; line-height: 20px; }
  .key.mod .n { font-size: 10px; line-height: 12px; }
"""
def keyrow(chars, dc, subs=None):
    out = []
    for i, c in enumerate(chars):
        sub = f'<span class="sub">{subs[i]}</span>' if subs else ""
        out.append(f'<div class="key">{sub}{c}</div>')
    return out

def keys_inner():
    nums = keyrow(list("1234567890"), "", list("!@#$%^&*()"))
    q = keyrow(list("QWERTYUIOP"), "")
    a = keyrow(list("ASDFGHJKL"), "")
    z = keyrow(list("ZXCVBNM"), "")
    return f"""<main class="content keys live" data-dc-id="keys-content">
    <div class="sec-head"><span class="t-caps">Function and navigation</span><span class="tog t-caps" data-dc-id="fn-collapse">Hide <i data-i="chevUp" class="i16"></i></span></div>
    <section class="upper" data-dc-id="upper-keys">
      <div class="row" data-dc-id="f-row-1">{''.join(f'<div class="key fn">{k}</div>' for k in ['esc','F1','F2','F3','F4','F5','F6'])}</div>
      <div class="row" data-dc-id="f-row-2">{''.join(f'<div class="key fn">{k}</div>' for k in ['F7','F8','F9','F10','F11','F12','del'])}</div>
      <div class="navblock" data-dc-id="nav-row">
        <div class="key fn">home</div><div class="key fn">pg up</div><span class="gap"></span><span class="gap"></span><div class="key" data-dc-id="arrow-up">↑</div><span class="gap"></span>
        <div class="key fn">end</div><div class="key fn">pg dn</div><span class="gap"></span><div class="key" data-dc-id="arrow-left">←</div><div class="key" data-dc-id="arrow-down">↓</div><div class="key" data-dc-id="arrow-right">→</div>
      </div>
    </section>

    <section class="lower" data-dc-id="keyboard">
      <div class="chord well" data-dc-id="chord-preview">
        <span class="mini lk" data-dc-id="chord-shift">⇧</span><span class="mini" data-dc-id="chord-cmd">⌘</span>
        <span class="plus">+</span><span class="slot"></span><span class="plus">next key</span>
        <span class="clr raised" data-dc-id="chord-clear">Clear</span>
      </div>
      <div class="deck well" data-dc-id="key-deck">
        <div class="row" data-dc-id="row-numbers">{''.join(nums)}</div>
        <div class="row" data-dc-id="row-q">{''.join(q)}</div>
        <div class="row" data-dc-id="row-a">{''.join(a)}<div class="key wide g15" data-dc-id="key-return"><i data-i="ret"></i></div></div>
        <div class="row" data-dc-id="row-z">
          <div class="key mod locked g15" data-dc-id="mod-shift"><span class="g">⇧</span><span class="n">shift</span></div>
          {''.join(z)}
          <div class="key wide g15" data-dc-id="key-backspace"><i data-i="backspace"></i></div>
        </div>
        <div class="row" data-dc-id="row-bottom">
          <div class="key wide g15" data-dc-id="key-symbols">#+=</div>
          <div class="key wide" data-dc-id="key-tab">tab</div>
          <div class="key">,</div>
          <div class="key g5" data-dc-id="key-space"></div>
          <div class="key">.</div>
          <div class="key"><span class="sub">?</span>/</div>
        </div>
        <div class="modrow" data-dc-id="modifier-row">
          <div class="mod disabled" data-dc-id="mod-fn"><span class="g">fn</span><span class="n">not sendable</span></div>
          <div class="mod" data-dc-id="mod-ctrl"><span class="g">⌃</span><span class="n">control</span></div>
          <div class="mod" data-dc-id="mod-opt"><span class="g">⌥</span><span class="n">option</span></div>
          <div class="mod armed" data-dc-id="mod-cmd"><span class="g">⌘</span><span class="n">command</span></div>
        </div>
      </div>
    </section>
  </main>"""

# ---------------------------------------------------------------- macros
MACROS_CSS = """
  .macros { display: flex; flex-direction: column; height: 100%; }
  .mhead { display: flex; align-items: center; justify-content: space-between; padding-top: 8px; }
  .hint { margin-top: 4px; }
  .edit-btn { height: 40px; padding: 0 14px 0 10px; border-radius: 13px; display: inline-flex; align-items: center; gap: 6px; font: 600 14px/1 var(--font-ui); color: var(--on-surface); }
  .grid { margin-top: auto; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
"""
TILES = [("macro-spotlight", "search", "Spotlight", "⌘ space", "slate", False),
         ("macro-esc", "escape", "Escape", "esc", "slate", False),
         ("macro-mission", "windows", "Mission Control", "⌃↑", "indigo", False),
         ("macro-screenshot", "camera", "Screenshot area", "⌘⇧4", "blue", False),
         ("macro-open-safari", "apps", "Open Safari", "3 steps", "cyan", False)]
TILES2 = [("macro-force-quit", "power", "Force quit", "⌥⌘esc", "magenta", False),
          ("macro-hide-others", "windows", "Hide others", "⌥⌘H", "slate", False)]
def _tiles(lst):
    return "".join(f"""
      <div class="tile" data-tint="{t}" data-dc-id="{i}"><i data-i="{'starFill' if fav else 'star'}" class="i16 star{' on' if fav else ''}" data-dc-id="{i}-star"></i><i data-i="{ic}" class="ic"></i><div><div class="nm">{n}</div><div class="ch">{c}</div></div></div>""" for i, ic, n, c, t, fav in lst)
def macros_inner():
    spaces = f"""
      <div class="tile gesture" data-tint="indigo" data-dc-id="macro-next-space" style="grid-column: span 2">
        <i data-i="starFill" class="i16 star on" data-dc-id="macro-next-space-star"></i>
        <i data-i="spaces" class="ic"></i>
        <div class="slide"><i data-i="chevLeft" class="i20"></i><div style="flex:1;padding-left:4px"><div class="nm">Spaces</div><div class="ch">hold &amp; slide ⌃← ⌃→, tap ⌃↑</div></div><i data-i="chevRight" class="i20"></i></div>
      </div>"""
    return f"""<main class="content macros live" data-dc-id="macros-content">
    <div class="mhead" data-dc-id="macros-header"><h1 class="t-headline">Macros</h1><div class="edit-btn raised" data-dc-id="macros-edit"><i data-i="edit" class="i20"></i>Edit</div></div>
    <p class="t-body-s subtle hint" data-dc-id="macros-hint">Tap to run. Spaces is a gesture tile: hold and slide. Use Edit to change, star or reorder. Shortcuts are set for {DEVICE}.</p>
    <div class="grid" data-dc-id="macro-grid">{spaces}{_tiles(TILES)}
      <div class="tile confirm" data-dc-id="macro-lock-confirm"><i data-i="lock" class="ic"></i><div><div class="nm">Tap again</div><div class="ch">Lock screen</div></div></div>{_tiles(TILES2)}
      <div class="tile add" data-dc-id="macro-add"><i data-i="plus" class="i28"></i><span class="t-label" style="margin-top:6px">New macro</span></div>
    </div>
  </main>"""

EDITOR_CSS = """
  .sheet { height: 742px; padding: 0 16px 28px; display: flex; flex-direction: column; }
  .sh-head { display: flex; align-items: center; justify-content: space-between; height: 56px; margin-top: 2px; }
  .sh-head .acts { display: flex; margin-right: -12px; }
  .sh-head .fav { color: var(--accent); }
  .sh-head .del { color: var(--danger); }
  .lbl { color: var(--on-surface-subtle); margin: 12px 0 6px; display: block; }
  .look { display: flex; align-items: center; gap: 14px; }
  .look .prev { width: 52px; height: 52px; border-radius: 14px; display: grid; place-items: center; flex: none; color: var(--on-surface); }
  .sw { display: flex; gap: 8px; }
  .sw span { width: 26px; height: 26px; border-radius: 50%; box-shadow: inset 0 1px 0 rgba(255,255,255,.35), 0 1px 2px rgba(0,0,0,.4); }
  .sw .sel { box-shadow: 0 0 0 2px var(--surface-1), 0 0 0 4px var(--on-surface); }
  .xy { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
  .xy .field { flex-direction: row; align-items: center; gap: 10px; }
  .xy .ax { font: 600 13px/1 var(--font-ui); color: var(--on-surface-subtle); }
  .xy .val { font: 600 20px/24px var(--font-ui); }
  .xy .unit { margin-left: auto; font: 500 13px/1 var(--font-ui); color: var(--on-surface-subtle); }
  .capture { margin-top: 8px; height: 52px; }
  .pick-hint { margin-top: 8px; }
  .caveat { margin-top: 8px; display: flex; gap: 8px; color: var(--on-surface-subtle); font: 400 13px/18px var(--font-ui); }
  .caveat svg { margin-top: 1px; }
  .trow { display: flex; align-items: center; gap: 16px; margin-top: 14px; }
  .trow .txt { flex: 1; }
  .sheet .btn-primary { margin-top: auto; }
"""
def editor_sheet():
    sw = "".join(f'<span class="{"sel" if c=="teal" else ""}" style="background:var(--tint)" data-tint="{c}"></span>' for c in ["blue","indigo","violet","magenta","teal","cyan","slate","sand"])
    return f"""  <div class="scrim" data-dc-id="scrim"></div>
  <section class="sheet" data-dc-id="macro-editor-sheet">
    <div class="handle" data-dc-id="sheet-handle"></div>
    <div class="sh-head">
      <h2 class="t-headline" data-dc-id="editor-title">Edit macro</h2>
      <div class="acts"><div class="icon-btn fav" data-dc-id="editor-favourite"><i data-i="starFill"></i></div><div class="icon-btn del" data-dc-id="editor-delete"><i data-i="trash"></i></div></div>
    </div>
    <span class="t-caps lbl">Name</span>
    <div class="field" data-dc-id="field-name"><span class="t-title">Click Join button</span></div>
    <span class="t-caps lbl">Look</span>
    <div class="look" data-dc-id="field-look">
      <div class="prev raised" data-tint="teal" data-dc-id="icon-picker"><i data-i="click"></i></div>
      <div class="sw" data-dc-id="tint-swatches">{sw}</div>
    </div>
    <span class="t-caps lbl">Action</span>
    <div class="seg-ctl well" data-dc-id="action-type"><div>Shortcut</div><div>Text</div><div>Sequence</div><div class="on raised" data-dc-id="action-click">Click</div></div>

    <div class="xy" data-dc-id="click-point">
      <div class="field focus" data-dc-id="click-x"><span class="ax">X</span><span class="val">1184</span><span class="unit">px</span></div>
      <div class="field" data-dc-id="click-y"><span class="ax">Y</span><span class="val">742</span><span class="unit">px</span></div>
    </div>
    <div class="btn-secondary raised capture" data-dc-id="click-capture"><i data-i="target"></i>Capture pointer position</div>
    <p class="t-body-s muted pick-hint" data-dc-id="click-pick-hint">Move the pointer to the spot on {DEVICE}, then tap Capture. Or type the coordinates.</p>
    <p class="caveat" data-dc-id="click-caveat"><i data-i="info" class="i16"></i><span>Approximate. Mote moves the pointer to the top-left corner first, then by this offset, so pointer acceleration can shift it a little. The optional Mac helper makes it exact.</span></p>

    <div class="trow" data-dc-id="confirm-toggle">
      <div class="txt"><div class="t-label">Confirm before run</div><div class="t-body-s subtle">First tap arms, second tap runs</div></div>
      <div class="switch" data-dc-id="confirm-switch"></div>
    </div>
    <div class="btn-primary accent-cap" data-dc-id="editor-save"><i data-i="check"></i>Save macro</div>
  </section>"""

# ---------------------------------------------------------------- pairing
PAIR_CSS = """
  .screen { grid-template-rows: var(--status-h) var(--top-h) 1fr var(--nav-h); }
  .pair-c { display: flex; flex-direction: column; height: 100%; padding-top: 4px; }
  .back { display: flex; align-items: center; gap: 4px; }
  .back .icon-btn { margin-left: -12px; color: var(--on-surface); }
  .vis { border-radius: 24px; padding: 22px 20px; margin-top: 18px; display: flex; gap: 18px; align-items: center; }
  .vis .tx { flex: 1; }
  .vis .count { font: 650 28px/32px var(--font-ui); letter-spacing: -.3px; margin-top: 4px; }
  ol.steps { list-style: none; margin-top: 24px; display: flex; flex-direction: column; gap: 16px; }
  ol.steps li { display: grid; grid-template-columns: 30px 1fr; gap: 12px; align-items: start; }
  ol.steps .n { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; font: 600 14px/1 var(--font-ui); color: var(--on-surface-muted); }
  ol.steps .t-body { color: var(--on-surface); }
  ol.steps .t-body-s { color: var(--on-surface-muted); margin-top: 2px; }
  .foot { margin-top: auto; }
"""
def pairing_page():
    body = f"""<div class="screen" data-dc-id="screen">
  {STATUS}
  <header class="topbar" data-dc-id="topbar">
    <div class="back"><div class="icon-btn" data-dc-id="back"><i data-i="close"></i></div><span class="t-title">Pair new device</span></div>
    <div class="pill raised" data-dc-id="connection-pill"><span class="led amber"></span><span class="name">Visible</span></div>
  </header>
  <main class="content pair-c" data-dc-id="pairing-content">
    <h1 class="t-headline" data-dc-id="pair-title">Pair from the other device</h1>
    <section class="vis raised" data-dc-id="pair-code">
      <div class="scan"><i data-i="bluetooth" class="i28"></i></div>
      <div class="tx"><div class="t-body-s muted">This phone is visible as</div><div class="t-title">Pixel 8</div></div>
      <div style="text-align:right"><div class="t-body-s muted">for</div><div class="count" data-dc-id="visible-timer">1:52</div></div>
    </section>
    <ol class="steps" data-dc-id="pair-steps">
      <li><span class="n well">1</span><div><div class="t-body">Open Bluetooth settings on the other device.</div><div class="t-body-s">On a Mac: System Settings, then Bluetooth.</div></div></li>
      <li><span class="n well">2</span><div><div class="t-body">Choose Pixel 8 in its list of devices.</div></div></li>
      <li><span class="n well">3</span><div><div class="t-body">Confirm the code in the Android prompt that appears on this phone.</div><div class="t-body-s">Check it matches the code on the other device.</div></div></li>
    </ol>
    <div class="foot"><div class="btn-secondary raised" data-dc-id="cancel-pairing">Stop being visible</div></div>
  </main>
  <div class="navbar"></div>
</div>"""
    return page("Pairing", body, PAIR_CSS)

# ---------------------------------------------------------------- landscape
LAND_CSS = """
  .screen.land { width: 915px; height: 412px; grid-template-rows: none; grid-template-columns: 88px 1fr; }
  .rail.v { border-top: 0; border-right: 1px solid var(--outline); padding: 12px 10px 14px; display: flex; flex-direction: column; gap: 10px; }
  .rail.v .dev { height: 64px; border-radius: 18px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; position: relative; }
  .rail.v .dev .led { position: absolute; top: 9px; right: 11px; width: 7px; height: 7px; }
  .rail.v .dev .nm { font: 600 11px/12px var(--font-ui); color: var(--on-surface-muted); }
  .rail.v .track { flex: 1; height: auto; grid-template-columns: none; grid-template-rows: repeat(4, 1fr); }
  .land-main { display: grid; grid-template-columns: 1fr 112px; gap: 12px; padding: 16px 24px 16px 16px; min-height: 0; }
  .side { display: grid; grid-template-rows: 1fr 60px 1fr; gap: 8px; }
  .side > div { border-radius: 20px; display: grid; place-items: center; color: var(--on-surface-muted); font: 600 14px/1 var(--font-ui); }
  .side .on { color: var(--accent); }
  .menu-fab { position: absolute; top: 10px; right: 10px; z-index: 2; }
"""
def landscape_page():
    segs = "".join(f'<div class="seg{" on raised" if m == "mouse" else ""}" data-dc-id="{i}"><i data-i="{ic}"></i><span class="lb">{lb}</span></div>' for m, i, ic, lb in MODES)
    body = f"""<div class="screen land" data-dc-id="screen">
  <nav class="rail v" data-dc-id="mode-rail">
    <div class="dev raised" data-dc-id="connection-pill"><span class="led"></span><i data-i="laptop"></i><span class="nm">MacBook…</span></div>
    <div class="track well" data-dc-id="rail-track">{segs}</div>
  </nav>
  <div class="land-main live" data-dc-id="topbar">
    <div class="touchpad locked" data-dc-id="touchpad">
      <span class="chip-last" style="color:var(--accent)" data-dc-id="last-gesture">Drag locked</span>
      <div class="icon-btn menu-fab" data-dc-id="menu"><i data-i="more"></i></div>
      <span class="glow" style="left: 61%; top: 48%" data-dc-id="touch-glow"></span>
    </div>
    <div class="side" data-dc-id="button-strip">
      <div class="raised" data-dc-id="left-click">Left</div>
      <div class="pressed on" data-dc-id="drag-lock"><i data-i="lock"></i></div>
      <div class="raised" data-dc-id="right-click">Right</div>
    </div>
  </div>
</div>"""
    return page("Mouse (landscape)", body, LAND_CSS)

# ---------------------------------------------------------------- menu sub-screens (rev 4, #53)
SUB_CSS = """
  .screen { grid-template-rows: var(--status-h) var(--top-h) 1fr var(--nav-h); }
  .back { display: flex; align-items: center; gap: 4px; }
  .back .icon-btn { margin-left: -12px; color: var(--on-surface); }
  .sub { display: flex; flex-direction: column; gap: 8px; overflow: hidden; }
  .grp { border-radius: 20px; padding: 6px 14px; }
  .grp-h { color: var(--on-surface-subtle); margin: 14px 4px 2px; }
  .set { display: flex; align-items: center; gap: 12px; min-height: 60px; }
  .set + .set { border-top: 1px solid var(--outline); }
  .set .tx { flex: 1; }
  .set .tx .sub2 { font: 400 13px/18px var(--font-ui); color: var(--on-surface-muted); }
  .hslider { position: relative; height: 40px; border-radius: 20px; margin: 4px 0 12px; }
  .hslider .ticks { position: absolute; left: 18px; right: 18px; top: 6px; bottom: 6px; background: repeating-linear-gradient(90deg, var(--on-surface-subtle) 0 1.5px, transparent 1.5px 10%) top / 100% 5px no-repeat, repeating-linear-gradient(90deg, var(--on-surface-subtle) 0 1.5px, transparent 1.5px 10%) bottom / 100% 5px no-repeat; opacity: .5; }
  .hslider .fill { position: absolute; left: 6px; top: 17px; height: 6px; border-radius: 3px; background: var(--accent); box-shadow: 0 0 8px var(--accent-glow); }
  .hslider .thumb { position: absolute; top: 4px; width: 48px; height: 32px; border-radius: 10px; transform: translateX(-50%);
    background: repeating-linear-gradient(90deg, rgba(255,255,255,.10) 0 1px, rgba(0,0,0,.06) 1px 2px), var(--metal);
    box-shadow: inset 0 1px 0 rgba(255,255,255,.9), 0 0 0 1px var(--metal-edge), 0 3px 4px var(--dshadow); }
  .chips { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; margin: 4px 0 12px; }
  .chips > div { height: 48px; border-radius: 14px; display: grid; place-items: center; font: 600 13px/1 var(--font-ui); color: var(--on-surface); }
  .warn { display: flex; gap: 10px; padding: 14px; border-radius: 16px; color: var(--on-surface-muted); font: 400 14px/20px var(--font-ui); }
"""
def sub_page(title, dc, inner):
    body = f"""<div class="screen" data-dc-id="screen">
  {STATUS}
  <header class="topbar" data-dc-id="topbar">
    <div class="back"><div class="icon-btn" data-dc-id="back"><i data-i="chevLeft"></i></div><span class="t-title">{title}</span></div>
    {BRAND}
  </header>
  <main class="content sub" data-dc-id="{dc}">{inner}
  </main>
  <div class="navbar"></div>
</div>"""
    return page(title, body, SUB_CSS)

def setrow(dc, title, sub, ctl):
    sub_html = f'<div class="sub2">{sub}</div>' if sub else ""
    return f'<div class="set" data-dc-id="{dc}"><div class="tx"><div class="t-label">{title}</div>{sub_html}</div>{ctl}</div>'
SW_ON = '<div class="switch on"></div>'
SW_OFF = '<div class="switch"></div>'

def settings_page():
    inner = f"""
    <span class="t-caps grp-h">Pointer</span>
    <section class="grp raised" data-dc-id="settings-pointer">
      {setrow("set-speed", "Pointer speed", "6 of 10. Higher moves farther per swipe.", "")}
      <div class="hslider well" data-dc-id="set-speed-slider"><span class="ticks"></span><span class="fill" style="width:55%"></span><span class="thumb" style="left:57%"></span></div>
      {setrow("set-natural", "Natural scrolling", "Content follows your fingers", SW_ON)}
      {setrow("set-rightclick", "Right-click", None, '<div class="seg-ctl well" style="width:190px"><div class="on raised">Hold</div><div>2-finger tap</div></div>')}
    </section>
    <span class="t-caps grp-h">Keyboard</span>
    <section class="grp raised" data-dc-id="settings-keyboard">
      {setrow("set-clear-mods", "Clear modifiers on mode switch", None, SW_OFF)}
      {setrow("set-idle-release", "Release modifiers after 30 s idle", None, SW_ON)}
    </section>
    <span class="t-caps grp-h">Screen</span>
    <section class="grp raised" data-dc-id="settings-screen">
      {setrow("set-awake", "Keep screen on while connected", None, SW_ON)}
      {setrow("set-dim", "Extra dim", "For dark rooms and presentations", SW_OFF)}
    </section>"""
    return sub_page("Settings", "settings-content", inner)

def haptics_page():
    inner = f"""
    <span class="t-caps grp-h">Strength</span>
    <div class="seg-ctl well" data-dc-id="haptic-strength"><div>Off</div><div>Light</div><div class="on raised">Standard</div><div>Strong</div></div>
    <span class="t-caps grp-h">Try them</span>
    <div class="chips" data-dc-id="haptic-preview"><div class="raised">Tick</div><div class="raised">Click</div><div class="raised">Heavy</div><div class="raised">Lock</div><div class="raised">Reject</div></div>
    <section class="grp raised" data-dc-id="haptics-options">
      {setrow("hap-keys", "Key presses", "Tick on every key and D-pad step", SW_ON)}
      {setrow("hap-sliders", "Slider detents", "Ticks as scroll and volume speed change", SW_ON)}
      {setrow("hap-link", "Connect and disconnect", "Distinct pulses when the link changes", SW_ON)}
    </section>
    <div class="warn well" data-dc-id="haptic-warning"><i data-i="info" class="i20"></i><span>Haptics follow Android's touch feedback setting. If you feel nothing, turn on Settings, then Sound &amp; vibration, then Touch feedback.</span></div>"""
    return sub_page("Haptics", "haptics-content", inner)

# ---------------------------------------------------------------- write
files = {
    "media.html": page("Media (dark)", screen(media_inner(), "media"), MEDIA_CSS, dark=True),
    "media-light.html": page("Media", screen(media_inner(), "media"), MEDIA_CSS),
    "menu-sheet.html": page("Menu", screen(media_inner(), "media", overlay=menu_sheet()), MEDIA_CSS + SHEET_CSS),
    "pad.html": page("Mouse", screen(pad_inner(), "mouse"), PAD_CSS),
    "macro-running.html": page("Macro running", screen(pad_inner(running=True), "mouse"), PAD_CSS),
    "pad-landscape.html": landscape_page(),
    "keys.html": page("Keys", screen(keys_inner(), "keys"), KEYS_CSS),
    "macros.html": page("Macros", screen(macros_inner(), "macros"), MACROS_CSS),
    "macro-editor.html": page("Macro editor", screen(macros_inner(), "macros", overlay=editor_sheet()), MACROS_CSS + EDITOR_CSS),
    "disconnected.html": page("Disconnected", screen(disconnected_inner(), "media", state="searching"), MEDIA_CSS + LOST_CSS),
    "device-picker.html": page("Device picker", screen(disconnected_inner(), "media", state="searching", overlay=device_sheet()), MEDIA_CSS + LOST_CSS + SHEET_CSS),
    "pairing.html": pairing_page(),
    "settings.html": settings_page(),
    "haptics.html": haptics_page(),
}
for name, html in files.items():
    (OUT / name).write_text(html)
print("wrote", len(files))
