#!/usr/bin/env python3
"""Generates design/mote-v1/*.html (rev 7: light default; media.html is the dark variant). Static HTML is the artifact; this only removes chrome duplication."""
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
<script src="flicker.js"></script>
</body>
</html>
"""

STATUS = '<div class="statusbar" data-dc-id="statusbar"><span>9:41</span><span class="sys"><i data-i="wifiBars" class="i16"></i><i data-i="battery" class="i20"></i></span></div>'

# Wordmark (debossed Unbounded Black) sits right; the menu is a small engraved glyph beside it.
def wordmark(mark):
    if mark == "lit":
        return ('<span class="wordmark lit bulb" data-dc-id="wordmark"><span class="wm-spill" data-dc-id="wordmark-spill"></span>'
                '<span class="wm-glow" aria-hidden="true">MOTE</span><span class="wm-core">MOTE</span><span class="wm-spec" aria-hidden="true">MOTE</span></span>')
    return f'<span class="wordmark {mark}" data-dc-id="wordmark">MOTE</span>'

def brand(state="connected"):
    mark = {"connected": "lit", "searching": "pulse", "pairing": "", "off": ""}[state]
    return f"""<div class="brand" data-dc-id="brand">
      {wordmark(mark)}
      <div class="icon-btn" data-dc-id="menu"><i data-i="more" class="i20"></i></div>
    </div>"""
BRAND = brand("connected")

def topbar(state="connected"):
    led = {"connected": "led", "searching": "led amber", "pairing": "led amber"}[state]
    extra = {"connected": "", "searching": '<span class="state" data-dc-id="pill-state">Searching…</span>', "pairing": ""}[state]
    return f"""<header class="topbar" data-dc-id="topbar">
    <div class="pill raised" data-dc-id="connection-pill">
      <span class="{led}" data-dc-id="pill-led"></span>
      <span class="name" data-dc-id="pill-name">{DEVICE}</span>{extra}
      <i data-i="chevDown" class="i20 chev" data-dc-id="pill-chevron"></i>
    </div>
    {brand(state)}
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
import math
C = 118                       # centre of the 236dp D-pad
def _pt(r, deg):
    a = math.radians(deg - 90)
    return C + r * math.cos(a), C + r * math.sin(a)
def _arc(r, a0, a1):
    x0, y0 = _pt(r, a0); x1, y1 = _pt(r, a1)
    return f"M{x0:.2f} {y0:.2f}A{r} {r} 0 {1 if (a1 - a0) > 180 else 0} 1 {x1:.2f} {y1:.2f}"
def _sector(r0, r1, a0, a1):
    p0 = _pt(r1, a0); p1 = _pt(r1, a1); p2 = _pt(r0, a1); p3 = _pt(r0, a0)
    return (f"M{p0[0]:.2f} {p0[1]:.2f}A{r1} {r1} 0 0 1 {p1[0]:.2f} {p1[1]:.2f}"
            f"L{p2[0]:.2f} {p2[1]:.2f}A{r0} {r0} 0 0 0 {p3[0]:.2f} {p3[1]:.2f}Z")
ARM_R0, ARM_R1 = 82, 110     # arrow ring (28dp) - gave space to the stick in rev 7.1
GATE_OUT, WELL_R = 82, 77     # metal gate lip, recessed well the stick moves in
STICK_R, DISH_R = 52, 38      # raised cap, concave thumb dish
TRAVEL = 20                   # max centre offset; cap edge stays 5dp inside the well
WALL_H, SHADOW_Y = 7, 9       # side-wall height and cast-shadow drop at rest (dp)
POINTER = 'M6 3.8l12.4 6.9c.6.33.5 1.22-.15 1.42l-5.1 1.55a1 1 0 0 0-.62.53l-2.3 4.85c-.3.62-1.2.55-1.4-.1z'

def _knurl(cx, cy, r0, r1, n=40):
    out = []
    for k in range(n):
        a = 360 * k / n
        x0, y0 = cx + r0 * math.sin(math.radians(a)), cy - r0 * math.cos(math.radians(a))
        x1, y1 = cx + r1 * math.sin(math.radians(a)), cy - r1 * math.cos(math.radians(a))
        out.append(f"M{x0:.2f} {y0:.2f}L{x1:.2f} {y1:.2f}")
    return " ".join(out)

def dpad(state="rest", angle=45, d=0.75):
    """state: rest | deflect | pressed. angle: bearing (deg, 0 = up). d: 0..1 of TRAVEL."""
    arm = _sector(ARM_R0, ARM_R1, -45, 45)
    tri = f"M{C} {C - 96 - 5}l6.5 9h-13z"
    arms = "".join(
        '<g data-dc-id="dpad-%s"%s><path class="arm" d="%s"/><path class="tri" d="%s"/></g>'
        % (n, (f' transform="rotate({r} {C} {C})"') if r else "", arm, tri)
        for n, r in [("up", 0), ("right", 90), ("down", 180), ("left", 270)])
    divs = "".join('<line class="div" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (*_pt(ARM_R0 + 3, a), *_pt(ARM_R1 - 5, a)) for a in (45, 135, 225, 315))
    # engraved 8-direction hints in the well floor (chevrons N/E/S/W, dots on diagonals)
    hints = []
    for a in (0, 90, 180, 270):
        hints.append(f'<path d="M{C - 5} {C - 66}L{C} {C - 71}L{C + 5} {C - 66}" transform="rotate({a} {C} {C})"/>')
    for a in (45, 135, 225, 315):
        x, y = _pt(68, a); hints.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="1.4" class="dot"/>')
    hint_svg = "".join(hints)
    dx = dy = 0.0; glow = ""; pressed = state == "pressed"
    if state == "deflect":
        dx = TRAVEL * d * math.sin(math.radians(angle)); dy = -TRAVEL * d * math.cos(math.radians(angle))
        sweep = 30 + 60 * d; path = _arc(GATE_OUT - 2.5, angle - sweep / 2, angle + sweep / 2)
        glow = f'<g data-dc-id="dpad-stick-glow" opacity="{min(1, .35 + d):.2f}"><path class="glow-arc soft" d="{path}" filter="url(#glowblur)"/><path class="glow-arc" d="{path}"/></g>'
    elif pressed:
        glow = f'<g data-dc-id="dpad-stick-glow" opacity=".75"><circle class="glow-arc soft" cx="{C}" cy="{C}" r="{GATE_OUT - 2.5}" filter="url(#glowblur)"/><circle class="glow-arc" cx="{C}" cy="{C}" r="{GATE_OUT - 2.5}"/></g>'
    sx, sy = C + dx, C + dy
    wall = 2 if pressed else WALL_H
    lift = 2.5 if pressed else 0                     # cap sinks toward the well when clicked
    # shadow: falls below (light from top) and shifts opposite to the deflection (the shaft leans)
    shx, shy = sx - 0.45 * dx, sy + (3 if pressed else SHADOW_Y) - 0.45 * dy
    blur = 2.5 if pressed else 5
    wx, wy = sx - 0.22 * dx, sy + wall - 0.22 * dy  # wall base leans back toward the pivot
    tx, ty = sx, sy + lift
    knurl = _knurl(tx, ty, DISH_R + 3, STICK_R - 3)
    scale = f' transform="translate({tx:.2f} {ty:.2f}) scale(.97) translate({-tx:.2f} {-ty:.2f})"' if pressed else ""
    return f"""<div class="dpad-wrap js" data-dc-id="dpad-bezel"><svg class="dpad" viewBox="0 0 236 236" data-dc-id="dpad">
        <defs>
          <linearGradient id="dpk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--rub-top)"/><stop offset="1" style="stop-color:var(--rub-bot)"/></linearGradient>
          <radialGradient id="dome" cx=".42" cy=".30" r=".78"><stop offset="0" style="stop-color:var(--stick-hi)"/><stop offset=".55" style="stop-color:var(--rub-top)"/><stop offset="1" style="stop-color:var(--rub-bot)"/></radialGradient>
          <linearGradient id="wallg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--rub-bot)"/><stop offset="1" style="stop-color:var(--rub-wall)"/></linearGradient>
          <linearGradient id="dish" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--dish-top)"/><stop offset="1" style="stop-color:var(--dish-bot)"/></linearGradient>
          <radialGradient id="dishshade" cx=".5" cy=".30" r=".70"><stop offset=".45" style="stop-color:#000;stop-opacity:0"/><stop offset="1" style="stop-color:#000;stop-opacity:.30"/></radialGradient>
          <radialGradient id="wellg" cx=".5" cy=".62" r=".6"><stop offset="0" style="stop-color:var(--well)"/><stop offset="1" style="stop-color:var(--well-deep)"/></radialGradient>
          <linearGradient id="gate" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:var(--gate-0)"/><stop offset=".48" style="stop-color:var(--gate-1)"/><stop offset=".52" style="stop-color:var(--gate-2)"/><stop offset="1" style="stop-color:var(--gate-3)"/></linearGradient>
          <linearGradient id="bev" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:#fff;stop-opacity:.9"/><stop offset=".35" style="stop-color:#fff;stop-opacity:0"/><stop offset=".7" style="stop-color:#000;stop-opacity:0"/><stop offset="1" style="stop-color:#000;stop-opacity:.22"/></linearGradient>
          <linearGradient id="bevin" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:#000;stop-opacity:.55"/><stop offset=".45" style="stop-color:#000;stop-opacity:0"/><stop offset=".8" style="stop-color:#fff;stop-opacity:0"/><stop offset="1" style="stop-color:#fff;stop-opacity:.55"/></linearGradient>
          <filter id="gr" x="0" y="0" width="1" height="1"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed="7" stitchTiles="stitch"/><feColorMatrix values=".8 .8 .8 0 -.7 .8 .8 .8 0 -.7 .8 .8 .8 0 -.7 0 0 0 0 .9"/></filter>
          <filter id="soft" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="{blur}"/></filter>
          <filter id="glowblur" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="3"/></filter>
          <filter id="spec" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.5"/></filter>
          <clipPath id="cdisc"><circle cx="{C}" cy="{C}" r="110"/></clipPath>
          <clipPath id="cwell"><circle cx="{C}" cy="{C}" r="{WELL_R}"/></clipPath>
          <clipPath id="cdish"><circle cx="{tx:.2f}" cy="{ty:.2f}" r="{DISH_R}"/></clipPath>
        </defs>
        <circle class="housing" cx="{C}" cy="{C}" r="113"/>
        <circle class="contact" cx="{C}" cy="{C + 6}" r="106"/>
        <circle class="disc" cx="{C}" cy="{C}" r="110" fill="url(#dpk)" data-dc-id="dpad-rubber"/>
        <rect class="grain" x="8" y="8" width="220" height="220" filter="url(#gr)" clip-path="url(#cdisc)" opacity=".5"/>
        <circle class="bevel" cx="{C}" cy="{C}" r="109.25" stroke="url(#bev)" data-dc-id="dpad-bevel"/>
        {arms}
        <g>{divs}</g>
        <g data-dc-id="dpad-stick-gate">
          <circle cx="{C}" cy="{C}" r="{GATE_OUT}" fill="url(#gate)"/><circle class="gate-hi" cx="{C}" cy="{C}" r="{GATE_OUT - .5}"/>
          <circle cx="{C}" cy="{C}" r="{WELL_R}" fill="url(#wellg)" data-dc-id="dpad-stick-well"/>
          <circle cx="{C}" cy="{C}" r="{WELL_R - 1.5}" fill="none" stroke="url(#bevin)" stroke-width="3"/>
          <g class="well-hints" data-dc-id="dpad-stick-hints">{hint_svg}</g>
        </g>
        {glow}
        <g clip-path="url(#cwell)"><circle class="stick-shadow" cx="{shx:.2f}" cy="{shy:.2f}" r="{STICK_R}" filter="url(#soft)" data-dc-id="dpad-stick-shadow"/></g>
        <g data-dc-id="dpad-center"{scale}>
          <circle cx="{wx:.2f}" cy="{wy + lift:.2f}" r="{STICK_R}" fill="url(#wallg)" data-dc-id="dpad-stick-wall"/>
          <circle cx="{tx:.2f}" cy="{ty:.2f}" r="{STICK_R}" fill="url(#dome)" data-dc-id="dpad-stick-cap"/>
          <path d="{knurl}" class="knurl" data-dc-id="dpad-stick-knurl"/>
          <circle cx="{tx:.2f}" cy="{ty:.2f}" r="{STICK_R - .75}" fill="none" stroke="url(#bev)" stroke-width="1.5"/>
          <circle cx="{tx:.2f}" cy="{ty:.2f}" r="{DISH_R}" fill="url(#dish)" data-dc-id="dpad-stick-dish"/>
          <circle cx="{tx:.2f}" cy="{ty:.2f}" r="{DISH_R}" fill="url(#dishshade)"/>
          <rect x="{tx - DISH_R:.2f}" y="{ty - DISH_R:.2f}" width="{2 * DISH_R}" height="{2 * DISH_R}" filter="url(#gr)" clip-path="url(#cdish)" opacity=".55" style="mix-blend-mode:overlay"/>
          <circle cx="{tx:.2f}" cy="{ty:.2f}" r="{DISH_R}" fill="none" stroke="url(#bevin)" stroke-width="2"/>
          <ellipse cx="{tx - 16:.2f}" cy="{ty - 40:.2f}" rx="14" ry="4" class="spec" filter="url(#spec)" transform="rotate(-24 {tx - 16:.2f} {ty - 40:.2f})"/>
          <g data-dc-id="dpad-center-legend" transform="translate({tx - 8.5:.2f} {ty - 10:.2f}) scale(.75)"><path class="legend-engr" d="{POINTER}" style="stroke:var(--hi)" transform="translate(0 1.2)"/><path class="legend-engr" d="{POINTER}" style="stroke:var(--on-surface-muted)"/></g>
        </g>
      </svg></div>"""

MEDIA_CSS = """
  .media { display: flex; flex-direction: column; height: 100%; }
  .fav-head { display: flex; align-items: center; justify-content: space-between; height: 32px; color: var(--on-surface-subtle); }
  .fav-head span { display: inline-flex; align-items: center; gap: 6px; }
  .cap-row { display: flex; justify-content: space-between; align-items: center; height: 16px; margin-top: 4px; color: var(--on-surface-subtle); font: 500 11px/1 var(--font-ui); }
  .carousel { margin-top: 4px; }
  .favs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 8px; }
  .fav { height: 52px; border-radius: 16px; display: flex; flex-direction: row; align-items: center; justify-content: center; gap: 6px; position: relative; color: var(--on-surface); }
  .fav .lb { font: 600 13px/16px var(--font-ui); color: var(--on-surface-muted); white-space: nowrap; }
  .sec-row { margin-top: auto; padding-top: 12px; }
  .sec-row .pair.small .cap { height: 64px; border-radius: 17px; color: var(--on-surface) !important; }
  .sec-row .pair.small { border-radius: 22px; }
  .cluster { position: relative; height: 252px; margin-top: 14px; align-items: start; display: grid; grid-template-columns: 56px 236px 56px; justify-content: space-between; }
  .cluster .col { display: flex; flex-direction: column; align-items: center; gap: 8px; }
  .cluster .dpad-wrap { margin-top: 0; }
  .transport { margin-top: 14px; grid-template-columns: 76px 1fr 76px !important; }
  .transport .glass.sm, .transport .glass.play { height: 96px; border-radius: 24px; }
  .cluster .slider { height: 232px; }
  .dark .engrave { color: #4F535B; }
"""

DESKS = ["desk-a", "desk-b", "desk-c"]
WINS = [[(10, 10, 46, 30), (40, 22, 52, 34)], [(8, 8, 86, 40)], [(12, 14, 34, 26), (50, 8, 44, 40)]]
def carousel(dc, mode="bt", current=2, n=6):
    cards = []
    for k in range(1, n + 1):
        cur = k == current
        cls = "scard" + (" current" if cur else "")
        dot = '<span class="cur-dot"></span>' if cur else ""
        if mode == "bt" or k > 3:
            if mode != "bt":
                cards.append(f'<div class="scard empty" data-dc-id="{dc}-card-{k}"><span class="nm">Desktop {k}<br>Not visited yet</span></div>')
                continue
            cards.append(f'<div class="{cls} raised" data-dc-id="{dc}-card-{k}">{dot}<span class="num">{k}</span><span class="nm">Desktop {k}</span></div>')
        else:
            wins = "".join(f'<span class="win" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px"></span>' for x, y, w, h in WINS[k - 1])
            cards.append(f'<div class="{cls} thumb" data-dc-id="{dc}-card-{k}"><span class="shot {DESKS[k-1]}">{wins}</span>{dot}<span class="cap">Desktop {k}</span></div>')
    return f"""<div class="carousel well" data-dc-id="{dc}">
        <div class="rowc">{''.join(cards)}</div>
        <span class="fade-r"></span>
      </div>"""

def spaces_tile(dc, cls):
    return f"""<div class="{cls} gesture raised" data-dc-id="{dc}">
        <div class="slide"><i data-i="chevLeft" class="i20" data-dc-id="{dc}-prev"></i>
          <div class="mid"><i data-i="spaces"></i><span class="lb" style="color:var(--on-surface)">Spaces</span><span class="hint" data-dc-id="{dc}-hint">hold &amp; slide</span></div>
          <i data-i="chevRight" class="i20" data-dc-id="{dc}-next"></i></div>
      </div>"""

def slider(dc, label, top, bottom, thumb, base=""):
    return f"""<div class="col">
        <div class="slider well{' vol' if base else ''}" data-dc-id="{dc}">
          <span class="end" data-dc-id="{dc}-top">{top}</span>
          <span class="ticks"></span>
          <div class="thumb" data-dc-id="{dc}-thumb">{thumb}</div>
          <span class="end{' bot' if base else ''}" data-dc-id="{dc}-bottom">{bottom}</span>{base}
        </div>
        <span class="engrave" data-dc-id="{dc}-label">{label}</span>
      </div>"""

def media_inner(offline=False):
    live = " live" if offline else ""
    fav_html = '<div class="fav add" style="grid-column: span 4" data-dc-id="fav-add"><i data-i="plus" class="i20"></i><span class="lb">Add favourite</span></div>'
    grip = '<span class="grip"></span><span class="grip"></span><span class="grip"></span>'
    scroll = slider("scroll-lever", "Scroll", '<i data-i="triUp" class="i16"></i>', '<i data-i="triDown" class="i16"></i>', grip)
    volume = slider("volume-group", "Volume", "+", "−", '<i data-i="speaker" class="i20"></i>',
                    base='<div class="mutecap raised" data-dc-id="mute"><i data-i="mute" class="i20"></i></div>')
    return f"""<main class="content media{' offline' if offline else ''}" data-dc-id="media-content">
    <section class="fav-wrap{live}" data-dc-id="fav-strip">
      <div class="fav-head"><span class="t-caps"><i data-i="starFill" class="i16"></i>Favourites</span><span class="edit-chip raised" data-dc-id="fav-edit"><i data-i="edit" class="i16"></i>Edit</span></div>
      <div class="cap-row"><span>Spaces</span><span>swipe or tap a desktop</span></div>
      {carousel("fav-next-space", "bt")}
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
    </div>

    <div class="channel well transport{live}" data-dc-id="transport">
      <div class="mcap sm" data-dc-id="prev"><i data-i="prev" class="i28"></i></div>
      <div class="mcap play" data-dc-id="play-pause"><span class="ring" data-dc-id="play-ring"></span><i data-i="playPause" class="i32"></i></div>
      <div class="mcap sm" data-dc-id="next"><i data-i="next" class="i28"></i></div>
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
        <div class="slide"><div style="flex:1"><div class="nm">Spaces</div><div class="ch">tap to pick a desktop</div></div><i data-i="chevRight" class="i20"></i></div>
      </div>"""
    return f"""<main class="content macros live" data-dc-id="macros-content">
    <div class="mhead" data-dc-id="macros-header"><h1 class="t-headline">Macros</h1><div class="edit-btn raised" data-dc-id="macros-edit"><i data-i="edit" class="i20"></i>Edit</div></div>
    <p class="t-body-s subtle hint" data-dc-id="macros-hint">Tap to run. Spaces opens the desktop picker. Use Edit to change, star or reorder. Shortcuts are set for {DEVICE}.</p>
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

# ---------------------------------------------------------------- rev 6 artboards
SPACES_CSS = """
  .sheet { padding: 0 16px 28px; }
  .sh-head { display: flex; align-items: center; justify-content: space-between; height: 56px; margin-top: 2px; }
  .lvl { display: flex; align-items: center; justify-content: space-between; margin: 14px 2px 6px; color: var(--on-surface-subtle); }
  .hintcard { margin-top: 10px; border-radius: 16px; padding: 12px 14px; display: flex; gap: 10px; color: var(--on-surface-muted); font: 400 13px/18px var(--font-ui); }
  .hintcard b { color: var(--on-surface); font-weight: 600; }
  .hintcard .acts { display: flex; gap: 8px; margin-top: 8px; }
  .hintcard .acts span { height: 32px; padding: 0 12px; border-radius: 10px; display: inline-flex; align-items: center; font: 600 13px/1 var(--font-ui); color: var(--on-surface); }
  .scard { width: 112px; }
"""
def spaces_sheet():
    return f"""  <div class="scrim" data-dc-id="scrim"></div>
  <section class="sheet" data-dc-id="spaces-sheet">
    <div class="handle"></div>
    <div class="sh-head"><h2 class="t-headline">Spaces</h2><div class="icon-btn"><i data-i="close"></i></div></div>
    <p class="t-body-s muted">Swipe the row or tap a desktop to jump to it. The lit card is the desktop you are on.</p>

    <div class="lvl"><span class="t-caps">Over Bluetooth</span><span class="t-caps">numbered</span></div>
    {carousel("spaces-bt", "bt")}
    <div class="hintcard well" data-dc-id="spaces-shortcut-hint"><i data-i="info" class="i20"></i>
      <div><b>Jump straight to a desktop.</b> On the Mac, turn on Switch to Desktop 1-9 in System Settings, Keyboard Shortcuts, Mission Control. Until then Mote steps with ⌃← and ⌃→.
        <div class="acts"><span class="raised">Done</span><span>Remind me later</span></div></div></div>

    <div class="lvl"><span class="t-caps">With the Mote helper</span><span class="disp" data-dc-id="spaces-display"><span class="disp-dots" data-dc-id="spaces-display-dots"><span class="on"></span><span></span></span><span class="disp-chip raised" data-dc-id="spaces-display-chip"><i data-i="laptop" class="i16"></i>Built-in<i data-i="chevDown" class="i16"></i></span></span></div>
    {carousel("spaces-helper", "helper")}
    <p class="t-body-s subtle" style="margin-top:8px" data-dc-id="spaces-helper-note">Each display has its own desktops. The row follows the display you're using; tap the chip to pick another. The helper saves a picture of each desktop when you visit it (needs Screen Recording permission).</p>
  </section>"""

def dpad_press_page():
    def cell(dc, label, html):
        return (f'<div style="display:flex;flex-direction:column;align-items:center;gap:6px;width:200px">'
                f'<div style="width:236px;height:236px;transform:scale(.8);margin:-24px" data-dc-id="{dc}">{html}</div>'
                f'<span class="t-caps subtle" style="text-align:center">{label}</span></div>')
    body = f"""<div class="screen" style="height:560px;grid-template-rows:1fr;padding:16px 0" data-dc-id="screen">
  <div style="display:flex;flex-wrap:wrap;justify-content:space-around;align-content:space-around;row-gap:18px" data-dc-id="dpad-press-compare">
    {cell("dpad-rest", "Rest", dpad())}
    {cell("dpad-deflected", "Deflected up-right: cap offset, shadow shifts back", dpad("deflect", 45, .8))}
    {cell("dpad-pressed", "Pressed (click): cap sinks, wall and shadow shrink", dpad("pressed"))}
  </div>
</div>"""
    return page("D-pad joystick", body)

# ---------------------------------------------------------------- write
# ================================================================ user journeys (storyboards)
# Wide artboards: numbered steps left->right, each a real phone screen at 50% and/or Mac-side UI.
ARROW = '<div class="arrow"><svg viewBox="0 0 44 20"><path d="M2 10h36M30 3l8 7-8 7" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>'

def jpage(title, body, width_css=""):
    """A journey page: mote.css + journeys.css, light theme, flicker off (static storyboards)."""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Mote — {title}</title>
<link rel="stylesheet" href="mote.css">
<link rel="stylesheet" href="journeys.css">
<style>{MEDIA_CSS}{SHEET_CSS}{LOST_CSS}{SPACES_CSS}{MACROS_CSS}{KEYS_CSS}{PAD_CSS}{SUB_CSS_J}{width_css}</style>
</head>
<body>
{body}
<script src="icons.js"></script>
</body>
</html>
"""

def phone(screen_html, dc, taps=(), drags=(), dark=False):
    """screen_html: a full .screen div (412x915). taps: (x, y[, 'hold']) in screen dp. drags: (x, y, len, deg)."""
    marks = "".join(f'<span class="tap{" hold" if len(t) > 2 else ""}" style="left:{t[0]/2}px;top:{t[1]/2}px"></span>' for t in taps)
    marks += "".join(f'<span class="drag" style="left:{x/2}px;top:{y/2}px;width:{l/2}px;transform:rotate({a}deg)"></span>' for x, y, l, a in drags)
    return f'<div class="phone{" dark" if dark else ""}" data-dc-id="{dc}"><div class="vp">{screen_html}{marks}</div></div>'

def mac(inner, dc, menubar=True, items="", cap=""):
    mb = f'<div class="menubar"><span class="mi">{items}</span><span class="mi">Fri 9:41</span></div>' if menubar else ""
    capd = f'<div class="mac-cap">{cap}</div>' if cap else ""
    return f'<div class="mac" data-dc-id="{dc}">{mb}{inner}{capd}</div>'

def mote_menubar_icon(on=True, open_=False):
    dot = '<circle cx="8" cy="8" r="2.2" fill="currentColor"/>' if on else '<circle cx="8" cy="8" r="2" fill="none" stroke="currentColor" stroke-width="1.2"/>'
    svg = f'<svg width="16" height="16" viewBox="0 0 16 16">{dot}<path d="M4.6 4.6a4.8 4.8 0 0 0 0 6.8M11.4 4.6a4.8 4.8 0 0 1 0 6.8M2.6 2.6a7.6 7.6 0 0 0 0 10.8M13.4 2.6a7.6 7.6 0 0 1 0 10.8" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>'
    return f'<span class="mi{" on" if open_ else ""}" title="Mote Helper">{svg}</span>'

def step(n, dc, cap, small="", phones=(), macs=()):
    parts = "".join(phones) + "".join(macs)
    sm = f"<small>{small}</small>" if small else ""
    return f'<div class="step" data-dc-id="{dc}"><span class="num">{n}</span><div class="row">{parts}</div><p class="cap">{cap}{sm}</p></div>'

BADGES = {"shipped": '<span class="badge shipped" data-dc-id="status">Shipped</span>',
          "next": '<span class="badge next" data-dc-id="status">Next (0.6.0)</span>',
          "labs": '<span class="badge labs" data-dc-id="status">Preview (Labs)</span>',
          "legacy": '<span class="badge legacy" data-dc-id="status">Legacy (0.4.0, being replaced)</span>'}
def branch(dc, frm, q, steps, end=""):
    """A failure/alternative row: decision diamond, then its own steps, then an optional end note."""
    endh = f'<div class="end well">{end}</div>' if end else ""
    lab = f"from step {frm}" if str(frm)[0].isdigit() else frm
    dia = f'<div class="decision"><span class="from">{lab}</span><div class="dia"><span>IF</span></div><div class="q">{q}</div></div>'
    return f'<div class="branch" data-dc-id="{dc}">{dia}{ARROW}{ARROW.join(steps)}{endh}</div>'
def journey(jid, title, status, sub, steps, branches=()):
    flow = ARROW.join(steps)
    br = f'<div class="branches" data-dc-id="branches">{"".join(branches)}</div>' if branches else ""
    body = f"""<div class="jb" data-dc-id="{jid}">
  <div class="jb-head"><h1 data-dc-id="title">{title}</h1>{BADGES[status]}</div>
  <p class="jb-sub" data-dc-id="summary">{sub}</p>
  <div class="steps" data-dc-id="steps">{flow}</div>{br}
</div>"""
    return jpage(title, body)

import re as _re
def scr(inner, mode="media", state="connected", overlay=""):
    return screen(inner, mode, state, overlay)
def media_with_panel(panel):
    return _re.sub(r'<section class="fav-wrap.*?</section>', panel, media_inner(), count=1, flags=_re.S)
def jsheet(dc, inner):
    return f'<div class="jscrim"></div><section class="jsheet" data-dc-id="{dc}"><div class="handle"></div>{inner}</section>'
def pill(name, led="led", badge="", cls=""):
    b = f'<span class="tbadge {cls}">{badge}</span>' if badge else ""
    return f'<div class="pill raised" style="margin:0"><span class="{led}"></span><span class="name">{name}</span>{b}<i data-i="chevDown" class="i20 chev"></i></div>'
def topbar_with(pill_html, lit=True):
    return f'<header class="topbar" data-dc-id="topbar">{pill_html}{brand("connected" if lit else "off")}</header>'
def scr_badge(inner, mode, pill_html, overlay="", lit=True):
    return f"""<div class="screen" data-dc-id="screen">{STATUS}{topbar_with(pill_html, lit)}{inner}{rail(mode)}{overlay}</div>"""
def desk(inner="", dim=False):
    return f'<div class="desk{" dim" if dim else ""}">{inner}</div>'
def win(x, y, w, h, title, body, dc=""):
    return f'<div class="win" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px" data-dc-id="{dc}"><div class="tb"><i></i><i></i><i></i><span style="margin-left:6px">{title}</span></div><div class="bd" style="height:{h-20}px">{body}</div></div>'

# ---- 8. Instant pairing (Labs)
def j08():
    near = jsheet("j8-connect-sheet", f"""<div class="hero-dev raised"><i data-i="laptop"></i></div>
      <h2 class="t-headline ctr">Connect to MacBook Pro?</h2>
      <p class="t-body-s muted ctr" style="margin-top:6px">Found nearby with Bluetooth. The Mac will ask you to allow this phone.</p>
      <div class="btn-row"><div class="btn-secondary raised">Not now</div><div class="btn-primary accent-cap">Connect</div></div>""")
    s1 = scr_badge(media_inner(offline=True), "media", pill("No device", "led red"), near, lit=False)
    alert = desk('<div class="alert"><div class="ic">MOTE</div><b>Allow “Pixel 8” to control this Mac?</b><p>Mote Helper will accept keyboard, mouse and media input from this phone.</p><div class="btns"><span>Don’t Allow</span><span class="def">Allow</span></div></div>')
    s3 = scr_badge(media_inner(), "media", pill("MacBook Pro", "led", "Wi-Fi", "lan"))
    ts = jsheet("j8-trusted", """<div class="hero-dev raised"><i data-i="check"></i></div>
      <h2 class="t-headline ctr">Connected to MacBook Pro</h2>
      <p class="t-body-s muted ctr" style="margin-top:6px">Trusted via Tailscale: this phone and the Mac belong to the same tailnet owner, so no prompts were needed.</p>""")
    s4 = scr_badge(media_inner(), "media", pill("MacBook Pro", "led", "Tailscale", "ts"), ts)
    fall = f"""<div class="mac" data-dc-id="j8-fallback" style="width:370px;padding:18px;background:var(--surface-1);color:var(--on-surface)">
      <div class="t-caps subtle">Silent reconnect, best route first</div>
      <div style="display:flex;flex-direction:column;gap:12px;margin-top:14px">
        {pill("MacBook Pro", "led", "Wi-Fi", "lan")}<span class="t-body-s muted">1. Same Wi-Fi (LAN): fastest</span>
        {pill("MacBook Pro", "led amber", "Tailscale", "ts")}<span class="t-body-s muted">2. Away from home: tailnet</span>
        {pill("MacBook Pro", "led", "Bluetooth", "bt")}<span class="t-body-s muted">3. No network: Bluetooth fallback</span></div></div>"""
    steps = [
        step(1, "j8-s1", "Open Mote near the Mac", "A Connect sheet appears (BLE discovery)", [phone(s1, "j8-p1", taps=[(306, 760)])]),
        step(2, "j8-s2", "The Mac asks once", "Allow sets up trust; nothing to type", macs=[mac(alert, "j8-m2", items=mote_menubar_icon())]),
        step(3, "j8-s3", "Connected", "The wordmark lights; the pill shows the route", [phone(s3, "j8-p3")]),
        step(4, "j8-s4", "Variant: same Tailscale owner", "Zero prompts: trusted via Tailscale", [phone(s4, "j8-p4")]),
        step(5, "j8-s5", "Later: reconnects silently", "LAN, then tailnet, then Bluetooth", macs=[fall]),
    ]
    return journey("journey-instant-pairing", "8. Instant pairing", "labs",
        "Open the app near your Mac and connect with one tap and one Mac confirmation. A Tailscale owner match skips the prompt entirely; later reconnects pick the best route on their own.", steps)

# ---- 9. Smart remote (Labs)
def j09():
    nflx = f"""<section class="panel raised" data-dc-id="j9-netflix-panel">
      <div class="src"><span class="app" style="background:#E50914">N</span>Netflix in front<span class="preview-tag" style="margin-left:auto">Preview</span></div>
      <div class="ttl">Episode 3: The Long Night</div>
      <div class="scrub"><i style="width:22%"></i><b style="left:22%"></b></div><div class="times"><span>9:12</span><span>-41:30</span></div>
      <div class="chips2"><span class="raised acc"><i data-i="next" class="i16"></i>Skip intro</span><span class="raised">Fullscreen</span></div></section>"""
    yt = f"""<section class="panel raised" data-dc-id="j9-youtube-panel">
      <div class="src"><span class="app" style="background:#FF0000">▶</span>YouTube in front<span class="preview-tag" style="margin-left:auto">Preview</span></div>
      <div class="ttl">Building a desk lamp from scrap</div>
      <div class="scrub"><i style="width:58%"></i><b style="left:58%"></b></div><div class="times"><span>6:40</span><span>-4:51</span></div>
      <div class="chips2"><span class="raised">−10 s</span><span class="raised">+10 s</span><span class="raised">Next</span><span class="raised acc">CC</span></div></section>"""
    m1 = desk(win(20, 20, 300, 220, "Netflix", '<div style="position:absolute;inset:0;background:#111"></div><div style="position:absolute;right:12px;bottom:12px;padding:5px 9px;border:1px solid #fff;color:#fff;font-size:11px">Skip Intro</div>'))
    m2 = desk(win(20, 20, 300, 220, "YouTube", '<div style="position:absolute;inset:0;background:#0F0F0F"></div><div style="position:absolute;left:12px;right:12px;bottom:14px;height:3px;background:#555"><i style="display:block;width:58%;height:3px;background:#F00"></i></div>'))
    m3 = desk(win(20, 20, 300, 220, "Safari", '<div style="margin:30px 24px;height:28px;border-radius:7px;box-shadow:0 0 0 3px #2F7CF6;padding:6px 10px;font-size:12px;color:#888">Search…<span style="color:#000">|</span></div>'))
    kb = """<div class="autokb raised" data-dc-id="j9-autokb"><i data-i="modeKeys"></i><span><b>Keyboard opened</b> for a text field on the Mac</span><span class="x raised" data-dc-id="j9-stop-autokb">Stop auto-keyboard</span></div>"""
    p3 = scr(keys_inner(), "keys", overlay=kb)
    steps = [
        step(1, "j9-s1", "Netflix is in front", "Media adds Skip intro, Fullscreen and a scrubber", [phone(scr(media_with_panel(nflx), "media"), "j9-p1", taps=[(110, 300)])], [mac(m1, "j9-m1", items=mote_menubar_icon())]),
        step(2, "j9-s2", "Switch to YouTube", "Seek, Next and captions; the joystick stays", [phone(scr(media_with_panel(yt), "media"), "j9-p2")], [mac(m2, "j9-m2", items=mote_menubar_icon())]),
        step(3, "j9-s3", "A search field gets focus", "Keys opens by itself; one tap turns that off", [phone(p3, "j9-p3")], [mac(m3, "j9-m3", items=mote_menubar_icon())]),
    ]
    return journey("journey-smart-remote", "9. Smart remote", "labs",
        "Mote reads which app is in front (via the helper) and adds the controls that app needs, then gets out of the way.", steps)

# ---- 10. Open… + TV mode (Labs, spec in docs/labs/open-tv.md)
SITES = [("YouTube", "#FF0000", "▶"), ("Netflix", "#E50914", "N"), ("Prime Video", "#00A8E1", "P"), ("Disney+", "#113CCF", "D"), ("Twitch", "#9146FF", "T"), ("Spotify", "#1DB954", "S")]
def open_sheet(query="lofi beats", link=""):
    sites = "".join(f'<span class="raised"><i style="background:{c}">{g}</i>{n}</span>' for n, c, g in SITES)
    val = link or query
    return jsheet("j10-open-sheet", f"""<div style="display:flex;align-items:center;justify-content:space-between"><h2 class="t-headline">Open on Mac</h2><span class="preview-tag">Preview</span></div>
      <div class="field focus searchf" style="margin-top:12px"><i data-i="search" class="i20"></i><span class="t-body">{val}</span><i data-i="mic" class="i20" style="margin-left:auto"></i></div>
      <div class="btn-row" style="margin-top:10px"><div class="btn-secondary raised">Paste</div><div class="btn-primary accent-cap">Open</div></div>
      <p class="t-body-s subtle" style="margin-top:8px">Links open on the Mac; YouTube opens in TV mode. Tap the keyboard mic to speak.</p>
      <span class="t-caps subtle" style="display:block;margin-top:14px">Quick actions</span>
      <div class="qa"><span class="raised"><i data-i="close" class="i20"></i>Close this tab</span><span class="raised"><i data-i="windows" class="i20"></i>Exit fullscreen</span><span class="raised"><i data-i="playPause" class="i20"></i>Pause everything</span><span class="raised"><i data-i="tv" class="i20"></i>Open TV mode on the Mac</span></div>
      <span class="t-caps subtle" style="display:block;margin-top:14px">Pinned</span><div class="sites">{sites}</div>""")
def dpad_ok():
    """TV-remote D-pad: same rubber ring and arrows, a big raised OK cap in the centre (no stick)."""
    html = dpad()
    a = html.index('<g data-dc-id="dpad-stick-gate">'); b = html.index("</svg></div>")
    ok = (f'<g data-dc-id="dpad-center"><circle cx="{C}" cy="{C + 4}" r="64" style="fill:var(--rub-wall)"/>'
          f'<circle cx="{C}" cy="{C}" r="64" fill="url(#dome)"/><circle cx="{C}" cy="{C}" r="63.25" fill="none" stroke="url(#bev)" stroke-width="1.5"/>'
          f'<text x="{C}" y="{C + 8}" text-anchor="middle" class="ok" style="font-size:24px" data-dc-id="dpad-center-legend">OK</text></g>')
    return html[:a] + ok + html[b:]

def tv_remote_inner():
    return f"""<main class="content media" data-dc-id="media-content">
      <div style="display:flex;align-items:center;gap:8px;margin:4px 0 2px"><span class="t-caps">TV mode</span><span class="preview-tag">Preview</span><span class="edit-chip raised" style="margin-left:auto" data-dc-id="j10-exit-tv">Exit</span></div><p class="t-body-s muted" style="margin-bottom:12px">YouTube</p>
      <div class="tvpad" data-dc-id="j10-tv-remote">{dpad_ok()}
        <div class="tvkeys"><span class="raised">Back</span><span class="raised">Home</span><span class="raised"><i data-i="search" class="i20"></i>Search</span>
          <span class="raised"><i data-i="volDown" class="i20"></i></span><span class="raised"><i data-i="playPause" class="i20"></i></span><span class="raised"><i data-i="volUp" class="i20"></i></span></div></div></main>"""
def tv_window(kind="results"):
    if kind == "results":
        tiles = "".join(f'<div class="tile2{" focus" if k == 1 else ""}"></div>' for k in range(4))
        body = f'<div style="font:700 16px/1 var(--font-ui)"><span style="color:#F00">▶</span> YouTube</div><div class="rowt">Results for “lofi beats”</div><div class="tiles">{tiles}</div><div class="rowt">Live</div><div class="tiles">{"".join("<div class=tile2></div>" for _ in range(4))}</div>'
    else:
        body = '<div style="display:flex;justify-content:space-between;margin-bottom:16px"><span style="font:900 13px/1 Unbounded;letter-spacing:2px">MOTE</span><span style="font:600 11px/1 var(--font-ui);color:#999">TV mode · Preview</span></div><div class="launch">' + "".join(f'<span style="background:{c}">{g}</span>' for n, c, g in SITES[:4]) + '</div><div class="launch" style="margin-top:10px">' + "".join(f'<span style="background:{c}">{g}</span>' for n, c, g in SITES[4:]) + '</div><div style="position:absolute;left:18px;bottom:14px;font:500 10px/1 var(--font-ui);color:#888">Arrows move · OK opens · Back on YouTube goes back · Exit TV mode from the phone or the helper menu</div>'
    return f'<div class="tv" data-dc-id="j10-tv-{kind}">{body}</div>'
def share_sheet():
    apps = "".join(f'<span class="raised" style="flex-direction:column;height:72px;justify-content:center;gap:6px"><i style="background:{c}">{g}</i>{n}</span>' for n, c, g in [("Open on Mac", "#26282C", "M"), ("Messages", "#34C759", "✉"), ("Drive", "#1A73E8", "D")])
    return jsheet("j10-share", f"""<span class="t-label">Share link</span><p class="t-body-s muted" style="margin-top:4px">youtube.com/watch?v=dQw4w9WgXcQ</p><div class="sites" style="margin-top:14px">{apps}</div>""")
def j10():
    steps = [
        step(1, "j10-s1", "Open on Mac (top-bar search icon)", "Search YouTube, paste a link, a pin or a quick action", [phone(scr(media_inner(), "media", overlay=open_sheet()), "j10-p1", taps=[(300, 300)])]),
        step(2, "j10-s2", "The Mac opens TV mode", "YouTube’s leanback UI, fullscreen on the active display", macs=[mac(tv_window("results"), "j10-m2", menubar=False, cap="TV mode: borderless fullscreen window (helper), youtube.com/tv")]),
        step(3, "j10-s3", "Media becomes a TV remote", "D-pad, Back, Home, Search, play and volume", [phone(scr(tv_remote_inner(), "media"), "j10-p3")]),
        step(4, "j10-s4", "Home shows the launcher", "“Mote TV” tiles; Netflix, Prime Video and Disney+ open in the browser", macs=[mac(tv_window("launcher"), "j10-m4", menubar=False, cap="Exit from the phone, or Labs › Exit TV mode in the helper menu")]),
        step(5, "j10-s5", "Share a link from any app", "“Open on Mac” only pre-fills the sheet; nothing is sent until you tap Open", [phone(scr(media_inner(), "media", overlay=share_sheet()), "j10-p5", taps=[(76, 790)]), phone(scr(media_inner(), "media", overlay=open_sheet(link="youtube.com/watch?v=dQw4w9WgXcQ")), "j10-p6", taps=[(300, 296)])]),
    ]
    return journey("journey-open-tv", "10. Open on Mac and TV mode", "labs",
        "Pick what to watch from the phone and steer it like a TV. Off by default on both sides: Settings › Labs › Open &amp; TV mode (preview) on Android, Labs › TV mode (preview) in the helper menu. On main today.", steps)

# ---- 11. Browser video bridge (Labs)
def j11():
    ext = desk(win(16, 14, 308, 250, "Extensions", """<div style="padding:10px 12px;font-size:11.5px"><div style="display:flex;justify-content:space-between;align-items:center"><b>Extensions</b><span style="font-size:10.5px">Developer mode <span style="display:inline-block;width:22px;height:12px;border-radius:6px;background:#2F7CF6;vertical-align:-2px"></span></span></div>
      <div style="margin-top:10px;display:flex;gap:6px"><span style="padding:4px 8px;border-radius:5px;box-shadow:0 0 0 2px #2F7CF6;font-size:10.5px">Load unpacked</span><span style="padding:4px 8px;border-radius:5px;background:#EEE;font-size:10.5px">Pack extension</span></div>
      <div style="margin-top:14px;padding:10px;border-radius:8px;box-shadow:0 0 0 1px #DDD;display:flex;gap:10px;align-items:center"><span style="width:28px;height:28px;border-radius:7px;background:#26282C;color:#A8DEFF;display:grid;place-items:center;font:900 7px/1 Unbounded">MOTE</span><div><b>Mote video bridge</b><div style="color:#777">Lets Mote control videos in this browser</div></div></div></div>""", "j11-ext"))
    popup = desk(win(16, 14, 308, 250, "YouTube", '<div style="position:absolute;inset:0;background:#0F0F0F"></div>' + """<div style="position:absolute;right:10px;top:8px;width:170px;padding:12px;border-radius:10px;background:#fff;box-shadow:0 8px 20px rgba(0,0,0,.4);font-size:11.5px">
      <b>Pair with Mote Helper</b><div style="color:#777;margin-top:3px">Enter the code from the menu bar</div>
      <div style="margin-top:8px;height:28px;border-radius:6px;box-shadow:0 0 0 2px #2F7CF6;display:grid;place-items:center;font:700 16px/1 var(--font-ui);letter-spacing:3px">482 913</div>
      <div style="margin-top:8px;height:24px;border-radius:6px;background:#2F7CF6;color:#fff;display:grid;place-items:center">Pair</div></div>""", "j11-popup"))
    web = f"""<section class="panel raised" data-dc-id="j11-web-panel">
      <div class="src"><span class="app" style="background:#26282C">⧉</span>Web video, Chrome<span class="preview-tag" style="margin-left:auto">Preview</span></div>
      <div class="ttl">How a lathe works, in 12 minutes</div>
      <div class="scrub"><i style="width:34%"></i><b style="left:34%"></b></div><div class="times"><span>4:05</span><span>-7:55</span></div>
      <div class="chips2"><span class="raised">1.25×</span><span class="raised acc">CC</span><span class="raised">Skip ad</span><span class="raised">Skip intro</span></div></section>"""
    play = desk(win(16, 14, 308, 250, "YouTube", '<div style="position:absolute;inset:0;background:#0F0F0F"></div><div style="position:absolute;left:12px;right:12px;bottom:16px;height:3px;background:#555"><i style="display:block;width:34%;height:3px;background:#F00"></i></div><div style="position:absolute;left:12px;top:10px;padding:3px 7px;border-radius:5px;background:rgba(0,0,0,.6);color:#A8DEFF;font-size:10.5px">Controlled by Mote</div>', "j11-playing"))
    steps = [
        step(1, "j11-s1", "Install the extension", "Chrome › Extensions › Developer mode › Load unpacked", macs=[mac(ext, "j11-m1", items=mote_menubar_icon())]),
        step(2, "j11-s2", "Pair it with the helper", "Same 6-digit code as phones, from the menu bar", macs=[mac(popup, "j11-m2", items=mote_menubar_icon())]),
        step(3, "j11-s3", "The phone gets a Web video panel", "Scrubber, speed, captions, skip ad and intro", [phone(scr(media_with_panel(web), "media"), "j11-p3", taps=[(70, 308)])]),
        step(4, "j11-s4", "The browser video follows", "Seeking and speed are exact, not key presses", macs=[mac(play, "j11-m4", items=mote_menubar_icon())]),
    ]
    return journey("journey-video-bridge", "11. Browser video bridge", "labs",
        "A small browser extension tells the helper what the page’s video is doing, so the phone controls the video itself instead of guessing with keys.", steps)

# ---- 12. Live screen (Labs)
def j12():
    warn = jsheet("j12-lan-warning", """<h2 class="t-headline">Show the Mac’s screen?</h2>
      <p class="t-body-s muted" style="margin-top:8px">The screen is streamed to this phone over your local network. Use it only on networks you trust. The Mac shows “Sharing screen” in the menu bar while it’s on.</p>
      <div class="btn-row"><div class="btn-secondary raised">Cancel</div><div class="btn-primary accent-cap">Start</div></div>""")
    live = """<div class="livev" data-dc-id="j12-live"><div class="scr"><div style="position:absolute;left:60px;top:40px;width:220px;height:150px;border-radius:5px;background:#fff;box-shadow:0 6px 16px rgba(0,0,0,.35)"></div></div>
      <svg class="cursor" style="left:200px;top:210px" viewBox="0 0 24 24"><path fill="currentColor" stroke="#000" stroke-width="1.2" d="M6 3.8l12.4 6.9c.6.33.5 1.22-.15 1.42l-5.1 1.55a1 1 0 0 0-.62.53l-2.3 4.85c-.3.62-1.2.55-1.4-.1z"/></svg>
      <span class="pinch">Pinch to zoom · 2.0×</span></div>"""
    close = """<div class="livev" data-dc-id="j12-live-close" style="background:#111"><span class="pinch" style="left:16px;right:auto">Tap to click</span><div class="icon-btn raised" style="position:absolute;right:14px;top:10px;color:#fff;background:#333">✕</div></div>"""
    sharing = desk('', dim=False)
    menu = """<div class="menu"><div class="it"><b>Mote Helper</b></div><div class="sep"></div><div class="it"><span><span class="rec-dot"></span>&nbsp; Sharing screen with Pixel 8</span></div><div class="it hl">Stop sharing</div><div class="sep"></div><div class="it dis">Labs</div></div>"""
    steps = [
        step(1, "j12-s1", "Open Screen", "First use explains it streams over the LAN", [phone(scr(media_inner(), "media", overlay=warn), "j12-p1", taps=[(306, 838)])]),
        step(2, "j12-s2", "See the Mac live", "Pinch to zoom; tap to click where you point", [phone(scr(media_inner(), "mouse", overlay=live), "j12-p2", taps=[(214, 448)])]),
        step(3, "j12-s3", "The Mac shows it’s shared", "A red dot in the menu bar for as long as it streams", macs=[mac(desk(menu), "j12-m3", items='<span class="mi on"><span class="rec-dot"></span>&nbsp;Sharing screen</span>' + mote_menubar_icon())]),
        step(4, "j12-s4", "Close the view", "The stream stops and the indicator goes away", [phone(scr(media_inner(), "mouse", overlay=close), "j12-p4", taps=[(384, 104)])], [mac(desk(), "j12-m4", items=mote_menubar_icon(), cap="No indicator: nothing is shared")]),
    ]
    return journey("journey-live-screen", "12. Live screen", "labs",
        "See the Mac’s screen on the phone and click by tapping on it. Explicit, visible and it stops the moment you close it.", steps)

# ---------------- system UI helpers for exhaustive journeys (illustrative OS chrome; Mote copy verbatim)
def tcc(title, body, buttons, icon="lock"):
    btns = "".join(f'<span class="{"def" if i == 0 else ""}">{b}</span>' for i, b in enumerate(buttons))
    return f'<div class="tcc"><div class="ic"><i data-i="{icon}" class="i20"></i></div><b>{title}</b><p>{body}</p><div class="btns">{btns}</div></div>'
def sysset(pane, title, rows, side=("Wi-Fi", "Bluetooth", "Notifications", "Privacy & Security", "General")):
    sd = "".join(f'<span class="{"on" if x == pane else ""}">{x}</span>' for x in side)
    def _tg(on):
        if on is True: return '<span class="tg on"></span>'
        if on is False: return '<span class="tg"></span>'
        return f'<span style="color:#888">{on}</span>'
    li = "".join(f'<div class="li"><span>{n}</span>{_tg(on)}</div>' for n, on in rows)
    return f'<div class="sysset"><div class="side">{sd}</div><div class="main"><h4>{title}</h4>{li}</div></div>'
def mnote(title, body, act=""):
    a = f'<span class="act">{act}</span>' if act else ""
    return f'<div class="note-banner"><span class="ic"><i data-i="modeMedia" class="i16"></i></span><div><b>{title}</b>{body}</div>{a}</div>'
def browser(url, inner):
    return f'<div class="browser"><div class="url">{url}</div><div class="pg">{inner}</div></div>'
def asys(title, inner):
    return f'<div class="asys"><h3>{title}</h3>{inner}</div>'
def anote(title, body, acts=()):
    a = "".join(f"<span>{x}</span>" for x in acts)
    return f'<div class="anote"><b>{title}</b><br>{body}{f"<div class=acts>{a}</div>" if acts else ""}</div>'
def toast(t):
    return f'<div class="toast">{t}</div>'
def asw(on): return f'<span class="sw{" on" if on else ""}"></span>'
def aphone(inner, dc, taps=()):
    """Android system screen (not Mote): status bar + content."""
    return phone(f'<div class="screen" style="grid-template-rows:var(--status-h) 1fr var(--nav-h);background:#F3F4F9" data-dc-id="screen">{STATUS}<div style="position:relative">{inner}</div><div class="navbar" style="background:#F3F4F9"></div></div>', dc, taps)

# ---------------- shipped journeys (strings verbatim from android/ + helper/, v0.4.0)
def media_shipped(offline=False, stick=None):
    m = media_inner(offline)
    m = m.replace('<div class="fav add" style="grid-column: span 4" data-dc-id="fav-add"><i data-i="plus" class="i20"></i><span class="lb">Add favourite</span></div>',
                  '<div class="fav raised" data-dc-id="fav-return"><i data-i="ret" class="i20"></i><span class="lb">Return</span></div><div class="fav add" style="grid-column: span 3" data-dc-id="fav-add"><i data-i="plus" class="i20"></i><span class="lb">Add favourite</span></div>')
    if stick:
        m = m.replace(dpad(), dpad(*stick))
    return m
def bare(inner, overlay="", top=None):
    """Screen without the mode rail (gate / full-screen flows)."""
    return f'<div class="screen" style="grid-template-rows:var(--status-h) var(--top-h) 1fr var(--nav-h)" data-dc-id="screen">{STATUS}{top or topbar_with(pill("Choose a device", "led red"), lit=False)}{inner}<div class="navbar"></div>{overlay}</div>'
def sysdlg(dc, title, body, buttons, extra=""):
    btns = "".join(f'<span style="font:600 15px/1 var(--font-ui);color:#1D6FB8">{b}</span>' for b in buttons)
    return f'''<div class="jscrim"></div><div data-dc-id="{dc}" style="position:absolute;left:28px;right:28px;top:300px;z-index:7;padding:24px 24px 18px;border-radius:28px;background:#ECEEF3;color:#1C1D20;box-shadow:0 20px 40px rgba(0,0,0,.35)">
      <div style="font:500 19px/26px var(--font-ui)">{title}</div><div style="margin-top:10px;font:400 14px/20px var(--font-ui);color:#45474D">{body}</div>{extra}
      <div style="display:flex;justify-content:flex-end;gap:28px;margin-top:20px">{btns}</div></div>'''
def gate(state="perm"):
    body = {"perm": ("Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on.", "Connect with Bluetooth"),
            "off": ("Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue.", "Turn on Bluetooth")}[state]
    return f'''<main class="content" data-dc-id="bt-gate" style="display:flex;flex-direction:column;justify-content:center;gap:14px;padding:0 28px">
      <div class="hero-dev raised" style="width:96px;height:96px;margin:0 auto 6px"><i data-i="bluetooth"></i></div>
      <h1 class="t-headline ctr">How do you want to connect?</h1>
      <p class="t-body muted ctr">{body[0]}</p>
      <div class="btn-primary accent-cap" style="margin-top:12px" data-dc-id="gate-primary">{body[1]}</div>
      <div class="btn-secondary raised" data-dc-id="gate-wifi">Use Wi-Fi with Mote Helper</div>
      <p class="t-body-s subtle ctr">Needs the free Mote Helper app on the Mac (tusharpandey13.github.io/mote). Adds Unicode typing, precise brightness and click-at-point.</p></main>'''
def badges(*rs, active=None):
    return "".join(f'<span class="tbadge{" lan" if r == active else ""}" style="{"box-shadow:inset 0 0 0 1px var(--accent);color:var(--accent);" if r == active else ""}">{r.upper()}</span>' for r in rs)
def devrow(dc, icon, name, status, rbadges="", sel=False, extra=""):
    return f'''<div class="row-item {'raised' if sel else 'flat'}" data-dc-id="{dc}"><i data-i="{icon}"></i><div class="tx"><div class="t-label">{name}</div><div class="sub">{status}</div></div><span style="display:flex;gap:4px">{rbadges}</span>{extra}</div>'''
def devices(note="Mote reconnects to the selected device while the app is open.", rows="", hl=None):
    def b(lbl, dc):
        return f'<div class="btn-secondary raised" style="height:48px{";box-shadow:0 0 0 2px var(--accent)" if hl == dc else ""}" data-dc-id="{dc}">{lbl}</div>'
    return jsheet("devices-sheet", f'''<h2 class="t-headline">Devices</h2><p class="t-body-s muted" style="margin:6px 0 12px">{note}</p>
      <div class="list" style="display:flex;flex-direction:column;gap:8px">{rows}</div>
      <div style="display:flex;flex-direction:column;gap:8px;margin-top:14px">{b("Add Mac over Wi-Fi", "dev-add-wifi")}{b("Enter address", "dev-enter-address")}{b("Pair new Bluetooth device", "dev-pair-bt")}</div>
      <p class="t-body-s subtle" style="margin-top:8px">Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code.</p>''')
def mac_bt_settings(code=None):
    rows = '<div style="display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #E3E3E7"><span>AirPods Pro</span><span style="color:#888">Not connected</span></div>'
    rows += '<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 0"><span><b>Pixel 8</b></span><span style="padding:2px 9px;border-radius:5px;background:#2F7CF6;color:#fff;font-size:11px">Connect</span></div>'
    alert = f'<div class="alert"><b>Bluetooth pairing request</b><p>Confirm that “Pixel 8” shows this code:</p><div style="font:700 22px/30px var(--font-ui);letter-spacing:3px;margin-top:4px">{code}</div><div class="btns"><span>Cancel</span><span class="def">Connect</span></div></div>' if code else ""
    return desk(win(16, 14, 308, 250, "Bluetooth", f'<div style="padding:10px 12px;font-size:11.5px"><b>Bluetooth</b> <span style="float:right">On</span><div style="color:#888;margin:10px 0 4px">Nearby Devices</div>{rows}</div>', "mac-bt-settings") + alert)
def helper_menu(pairing=False, connected=False):
    items = ['<div class="it"><b>Mote Helper: Ready</b></div>']
    if pairing:
        items += ['<div class="it">Pairing code: 482 913</div>', '<div class="it">Show pairing code and QR…</div>', '<div class="it">Stop pairing</div>']
    else:
        items += ['<div class="it hl">Pair new device… <span>⌘N</span></div>']
    items += ['<div class="sep"></div>', '<div class="it">Wi-Fi remote: http://macbook-pro.local:8790</div>', '<div class="it dis">&nbsp;&nbsp;&nbsp;&nbsp; or http://192.168.1.20:8790</div>',
              '<div class="sep"></div>', '<div class="it">Allow Tailscale connections</div>', '<div class="it dis">Tailscale: not detected (is it running?)</div>', '<div class="sep"></div>']
    items += ['<div class="it">Connected devices:</div>', '<div class="it dis">&nbsp;&nbsp; Pixel 8 · Wi-Fi</div>'] if connected else ['<div class="it dis">No connected devices</div>']
    items += ['<div class="it dis">Paired devices: 1</div>' if connected else '<div class="it dis">Paired devices: 0</div>', '<div class="sep"></div>', '<div class="it">Labs <span>▸</span></div>', '<div class="it">Pause input <span>⌘P</span></div>', '<div class="it">Quit Mote Helper <span>⌘Q</span></div>']
    return f'<div class="menu" style="width:262px">{"".join(items)}</div>'
def pairing_window():
    return desk(win(30, 8, 280, 284, "Pair a device with Mote", '''<div style="padding:12px;text-align:center;font-size:11.5px"><div style="color:#555">Enter this code in the Mote remote</div>
      <div style="font:700 30px/38px var(--font-ui);letter-spacing:4px">482 913</div><div class="qr"></div>
      <div style="color:#777;margin-top:8px">Or scan with the phone camera (same Wi-Fi) to open http://…</div>
      <div style="color:#999;margin-top:6px;font-size:10.5px">Pairing closes after 5 minutes or once a device pairs. Only pair devices you own.</div></div>''', "mac-pair-window"))
def wifi_add(found=True):
    row = devrow("wifi-found", "laptop", "MacBook Pro", "192.168.1.20", badges("Wi-Fi")) if found else '<p class="t-body-s muted">Looking for Macs on this Wi-Fi…</p>'
    return f'''<main class="content" data-dc-id="add-wifi" style="display:flex;flex-direction:column;gap:12px">
      <h1 class="t-headline">Add Mac over Wi-Fi</h1>
      <p class="t-body-s muted">On the Mac, open the Mote Helper menu and choose Pair new device. Then pick the Mac here, or scan its QR with the camera and tap Open in the Mote app.</p>
      {row}<div class="btn-row" style="margin-top:4px"><div class="btn-secondary raised">Scan again</div><div class="btn-secondary raised">Enter address</div></div>
      <p class="t-body-s subtle" style="margin-top:auto">No Mote Helper yet? Get it at tusharpandey13.github.io/mote</p></main>'''
def code_screen(code="482913", note=""):
    return f'''<main class="content" data-dc-id="pair-code-screen" style="display:flex;flex-direction:column;gap:14px">
      <h1 class="t-headline">Pair with MacBook Pro</h1>
      <p class="t-body-s muted">Enter the 6-digit code shown in the Mote Helper pairing window on the Mac.</p>
      <div class="field focus"><span class="t-title" style="letter-spacing:4px">{code}</span></div>{note}
      <div class="btn-primary accent-cap">Pair</div></main>'''
def scr_route(inner, route, overlay="", lit=True, mode="media", led="led"):
    return scr_badge(inner, mode, pill("MacBook Pro", led, route.upper() if route else "", "lan"), overlay, lit)

def j01(): pass
def j02(): pass
def j03():
    rows = devrow("dev-ts", "laptop", "MacBook Pro", "Paired", badges("Tailscale"), sel=True)
    ts = jsheet("tailscale-off", '''<div class="hero-dev raised" style="width:80px;height:80px"><i data-i="globe"></i></div><h2 class="t-headline ctr">Tailscale is off</h2>
      <p class="t-body-s muted ctr" style="margin-top:6px">Turn it on to reach MacBook Pro.</p>
      <div class="btn-row"><div class="btn-secondary raised">Not now</div><div class="btn-primary accent-cap" data-dc-id="ts-turn-on">Turn on Tailscale</div></div>''')
    ts2 = jsheet("tailscale-waiting", '''<div class="hero-dev raised" style="width:80px;height:80px"><i data-i="globe"></i></div><h2 class="t-headline ctr">Tailscale is off</h2>
      <p class="t-body-s muted ctr" style="margin-top:6px">Turn it on to reach MacBook Pro.</p>
      <p class="t-body-s ctr" style="margin-top:12px;color:var(--link-connecting)">Finish in the Tailscale app, then come back. Mote connects as soon as Tailscale is on.</p>''')
    steps = [
        step(1, "j3-s1", "Pick a Tailscale host", "Away from home, Tailscale off", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber"), devices(rows=rows), lit=False), "j3-p1", taps=[(206, 228)])]),
        step(2, "j3-s2", "“Tailscale is off”", "Turn on Tailscale asks the Tailscale app to connect", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber"), ts, lit=False), "j3-p2", taps=[(306, 820)])]),
        step(3, "j3-s3", "Finish in Tailscale", "Opens the Tailscale app if it isn’t up after 6 s", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber"), ts2, lit=False), "j3-p3")]),
        step(4, "j3-s4", "Auto-connect", "When a tailnet address appears the sheet closes and Mote connects", [phone(scr_route(media_shipped(), "Tailscale"), "j3-p4")]),
    ]
    return journey("journey-tailscale", "3. Tailscale", "shipped",
        "Reach the Mac from anywhere on your tailnet. Mote notices Tailscale is off, turns it on, and finishes the connection by itself.", steps)

def j04():
    srch = scr_badge(disconnected_inner(), "media", pill("MacBook Pro", "led amber"), lit=False).replace("brand(", "brand(")
    notpaired_pill = pill("Not paired: pair again", "led red")
    rows_np = devrow("dev-np", "laptop", "MacBook Pro", "Not paired: pair again", badges("BT"), sel=True)
    np_sheet = devices("The Mac no longer knows this phone. Long-press it to forget, then pair again.", rows_np)
    forget = sysdlg("forget", "Forget MacBook Pro on this phone?", "", ["Cancel", "Forget"])
    rows_pick = (devrow("dev-mbp", "laptop", "MacBook Pro", "Searching…", badges("Wi-Fi", "BT"), sel=True)
                 + devrow("dev-mini", "desktop", "Mac mini", "Paired over Bluetooth · Mote Helper found", badges("BT")))
    steps = [
        step(1, "j4-s1", "Signal lost: auto-search", "“Searching for MacBook Pro…”; nothing is sent meanwhile", [phone(srch, "j4-p1")]),
        step(2, "j4-s2", "Choose a device", "The pill opens Devices; pick another or add one", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber"), devices(rows=rows_pick), lit=False), "j4-p2", taps=[(206, 334)])]),
        step(3, "j4-s3", "Not paired: pair again", "The Mac forgot this phone; Mote never auto-connects it", [phone(scr_badge(media_shipped(True), "media", notpaired_pill, np_sheet, lit=False), "j4-p3", taps=[(206, 230, "hold")])]),
        step(4, "j4-s4", "Long-press to forget", "Then pair again from Devices", [phone(scr_badge(media_shipped(True), "media", notpaired_pill, forget, lit=False), "j4-p4", taps=[(348, 470)])]),
    ]
    return journey("journey-reconnect", "4. Reconnect and disconnected states", "shipped",
        "While the app is open Mote keeps looking for the selected Mac. The pill always says what is going on and opens the device picker.", steps)

def j05():
    m = media_shipped()
    mac_desk = lambda n: desk(f'<div style="position:absolute;left:50%;top:40%;transform:translate(-50%,-50%);color:#fff;font:700 28px/1 var(--font-ui);text-shadow:0 2px 8px rgba(0,0,0,.4)">Desktop {n}</div>')
    chooser = '''<div class="jscrim" style="background:transparent"></div><div data-dc-id="display-menu" style="position:absolute;right:20px;top:680px;z-index:8;width:220px;padding:6px;border-radius:14px;background:var(--surface-1);box-shadow:0 12px 30px rgba(0,0,0,.3)">
      <div class="row-item flat" style="min-height:44px">Built-in (active) ✓</div><div class="row-item" style="min-height:44px">LG UltraFine</div></div>'''
    steps = [
        step(1, "j5-s1", "Tap a desktop", "Over Bluetooth: ⌃N, or ⌃←/⌃→ steps every 300 ms", [phone(scr(m, "media"), "j5-p1", taps=[(290, 196)])]),
        step(2, "j5-s2", "The Mac switches", "The lit card follows the desktop you are on", macs=[mac(mac_desk(3), "j5-m2", items=mote_menubar_icon(False))]),
        step(3, "j5-s3", "One-time ⌃N hint", "Done hides it for good; Remind me later for this sheet", [phone(scr(m, "media", overlay=spaces_sheet()), "j5-p3", taps=[(176, 690)])]),
        step(4, "j5-s4", "With the helper: thumbnails", "Unvisited desktops show a dashed placeholder", [phone(scr(m, "media", overlay=spaces_sheet()), "j5-p4", taps=[(330, 656)])]),
        step(5, "j5-s5", "Two displays: the chip", "Pick a display; cards fade out 120 ms, in 200 ms", [phone(scr(m, "media", overlay=spaces_sheet() + chooser), "j5-p5", taps=[(320, 744)])]),
    ]
    return journey("journey-spaces", "5. Spaces", "shipped",
        "Jump between desktops from the Media screen or the Spaces sheet. Over Bluetooth the cards are numbered; with the helper they are pictures, per display.", steps)

def editor_shipped(click=False):
    mods = "".join(f'<span class="{"raised" if on else "flat"}" style="height:40px;padding:0 12px;border-radius:12px;display:inline-flex;align-items:center;font:600 14px/1 var(--font-ui);{"color:var(--accent);box-shadow:0 0 0 2px var(--accent)" if on else ""}">{g} {n}</span>' for g, n, on in [("⌘", "cmd", True), ("⇧", "shift", True), ("⌥", "opt", False), ("⌃", "ctrl", False)])
    if click:
        body = '''<span class="t-caps lbl">Click at point</span>
          <div class="well" data-dc-id="click-picker" style="position:relative;height:200px;border-radius:12px;background:linear-gradient(160deg,#7EA4D1,#2E4A72)"><span style="position:absolute;left:72%;top:18%;width:22px;height:22px;margin:-11px;border-radius:50%;box-shadow:0 0 0 3px #fff,0 0 8px var(--accent)"></span></div>
          <p class="t-body-s muted" style="margin-top:8px">Tap or drag where to click on the main display: 72% across, 18% down.</p>'''
        name = "Join call"
    else:
        body = f'''<span class="t-caps lbl">Shortcut</span><div class="field focus"><span class="t-title">⌘ ⇧ 4</span></div>
          <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:8px">{mods}</div>
          <div class="field" style="margin-top:8px"><span class="t-body-s muted">Type a key (letter, digit, symbol)</span></div>'''
        name = "Screenshot area"
    return jsheet("macro-editor-shipped", f'''<h2 class="t-headline">New macro</h2>
      <span class="t-caps lbl">Name</span><div class="field"><span class="t-title">{name}</span></div>
      <span class="t-caps lbl">Action</span><div class="seg-ctl well"><div class="{"" if click else "on raised"}">Shortcut</div><div class="{"on raised" if click else ""}">Click at point</div></div>
      {body}
      <div class="trow"><div class="txt t-label">★ Favourite</div><div class="switch on"></div></div>
      <div class="trow"><div class="txt t-label">Confirm before run</div><div class="switch"></div></div>
      <div class="btn-primary accent-cap" style="margin-top:14px">Save</div>''')
def j06():
    lock_armed = macros_inner().replace('Tap again</div><div class="ch">Lock screen', 'Tap again</div><div class="ch">Lock')
    fav = media_shipped().replace('<div class="fav add" style="grid-column: span 3" data-dc-id="fav-add"><i data-i="plus" class="i20"></i><span class="lb">Add favourite</span></div>',
        '<div class="fav raised" style="box-shadow:inset 0 1px 0 var(--hi),0 0 0 2px var(--accent),var(--drop)" data-dc-id="fav-new"><i data-i="camera" class="i20"></i><span class="lb">Screenshot area</span></div><div class="fav add" style="grid-column: span 2" data-dc-id="fav-add"><i data-i="plus" class="i20"></i><span class="lb">Add favourite</span></div>')
    lockmac = '<div class="desk dim"><div style="position:absolute;left:50%;top:44%;transform:translate(-50%,-50%);text-align:center;color:#fff"><div style="font:600 44px/1 var(--font-ui)">9:41</div><div style="margin-top:8px;font-size:12px;opacity:.8">Screen locked</div></div></div>'
    steps = [
        step(1, "j6-s1", "Macros › New macro", "Edit / Done to change or star", [phone(scr(macros_inner(), "macros"), "j6-p1", taps=[(76, 748)])]),
        step(2, "j6-s2", "Record a shortcut", "Modifiers + a key; ★ Favourite on", [phone(scr(macros_inner(), "macros", overlay=editor_shipped()), "j6-p2")]),
        step(3, "j6-s3", "Or click at point (helper)", "Skipped on Bluetooth hosts", [phone(scr(macros_inner(), "macros", overlay=editor_shipped(True)), "j6-p3", taps=[(296, 446)])]),
        step(4, "j6-s4", "It appears on Media", "Under FAVOURITES (4 slots)", [phone(scr(fav, "media"), "j6-p4", taps=[(254, 296)])]),
        step(5, "j6-s5", "Run with confirm: Lock", "First tap arms (“Tap again”), second within 2 s runs", [phone(scr(lock_armed, "macros"), "j6-p5", taps=[(340, 640)])], [mac(lockmac, "j6-m5", menubar=False)]),
    ]
    return journey("journey-macros", "6. Macros", "shipped",
        "Make a one-tap shortcut, star it onto the Media screen, and guard the risky ones with a second tap.", steps)

def j07():
    cursor = lambda x, y: f'<svg style="position:absolute;left:{x}px;top:{y}px;width:18px;height:18px;color:#fff;filter:drop-shadow(0 1px 2px rgba(0,0,0,.6))" viewBox="0 0 24 24"><path fill="currentColor" stroke="#000" stroke-width="1.2" d="M6 3.8l12.4 6.9c.6.33.5 1.22-.15 1.42l-5.1 1.55a1 1 0 0 0-.62.53l-2.3 4.85c-.3.62-1.2.55-1.4-.1z"/></svg>'
    move = desk(cursor(220, 90) + '<div style="position:absolute;left:120px;top:150px;width:100px;height:2px;background:rgba(255,255,255,.5);transform:rotate(-31deg);transform-origin:0 0"></div>')
    ctx = desk(cursor(150, 120) + '<div class="menu" style="left:160px;right:auto;top:132px;width:150px"><div class="it">Open</div><div class="it">Get Info</div><div class="it hl">Copy</div><div class="it">Share…</div></div>')
    seek = desk(win(16, 14, 308, 250, "Music", '<div style="position:absolute;inset:0;background:#1C1C1E;color:#fff;padding:20px;font-size:12px">Fast-forwarding ▶▶<div style="margin-top:16px;height:3px;background:#555"><i style="display:block;width:64%;height:3px;background:#fff"></i></div></div>'))
    vol = desk('<div style="position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:150px;height:150px;border-radius:24px;background:rgba(40,40,44,.75);color:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;font-size:30px">🔊<div style="width:110px;height:6px;border-radius:3px;background:#666"><i style="display:block;width:70%;height:6px;border-radius:3px;background:#fff"></i></div></div>')
    steps = [
        step(1, "j7-s1", "Drag the stick: the pointer moves", "Speed grows with distance (deadzone + curve)", [phone(scr(media_shipped(stick=("deflect", 45, .8)), "media"), "j7-p1", drags=[(206, 532, 70, -45)])], [mac(move, "j7-m1", menubar=False)]),
        step(2, "j7-s2", "Tap = click, hold 450 ms = right click", "The ring sends arrow keys", [phone(scr(media_shipped(), "media"), "j7-p2", taps=[(206, 532, "hold")])], [mac(ctx, "j7-m2", menubar=False)]),
        step(3, "j7-s3", "Hold next to fast-forward", "Tap skips; past 400 ms it seeks until you lift", [phone(scr(media_shipped(), "media"), "j7-p3", taps=[(350, 738, "hold")])], [mac(seek, "j7-m3", menubar=False)]),
        step(4, "j7-s4", "Scroll and volume levers", "Pull further for faster; tap an end for one notch", [phone(scr(media_shipped(), "media"), "j7-p4", drags=[(368, 520, 60, -90), (44, 520, 60, 90)])], [mac(vol, "j7-m4", menubar=False)]),
    ]
    return journey("journey-media", "7. Media controls", "shipped",
        "The Media screen is a whole remote: pointer stick, arrows, levers for scroll and volume, and transport keys that seek when held.", steps)

# ---------------- exhaustive journeys (A–G). Copy verbatim from code where it exists; OS chrome illustrative.
SUB_CSS_J = SUB_CSS.replace("  .screen { grid-template-rows: var(--status-h) var(--top-h) 1fr var(--nav-h); }\n", "")
def sub_screen(title, inner, dc="sub"):
    return (f'<div class="screen" style="grid-template-rows:var(--status-h) var(--top-h) 1fr var(--nav-h)" data-dc-id="screen">{STATUS}'
            f'<header class="topbar"><div class="back"><div class="icon-btn"><i data-i="chevLeft"></i></div><span class="t-title">{title}</span></div>{brand("connected")}</header>'
            f'<main class="content sub" data-dc-id="{dc}">{inner}</main><div class="navbar"></div></div>')
def term(lines):
    return '<div class="term">' + "<br>".join(lines) + '</div>'
SITE = '<b style="font:900 13px/1 Unbounded;letter-spacing:2px">MOTE</b><div style="margin-top:8px;font-size:15px;font-weight:650">Your Android phone as a remote for your Mac</div>'
def site_page(copied=False):
    btn = '<span style="color:#7EC699">Copied</span>' if copied else '<span style="color:#9CC3EA">Copy</span>'
    return desk(win(14, 12, 312, 300, "tusharpandey13.github.io/mote", browser("tusharpandey13.github.io/mote", SITE +
        f'<div style="margin-top:10px;display:flex;gap:6px"><span style="padding:5px 10px;border-radius:6px;background:#26282C;color:#fff;font-size:11px">Download APK</span><span style="padding:5px 10px;border-radius:6px;background:#EEE;font-size:11px">Install Mac helper</span></div>'
        f'<div class="code"><span>curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash</span>{btn}</div>'
        '<div style="margin-top:8px;color:#777;font-size:11px">Read the script first. No sudo; installs into ~/Applications.</div>'), "site"))
INSTALL_OK = ['<span class="p">~ %</span> curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash', '<span class="dim">This will:</span>',
    '<span class="dim">    download  …/MoteHelper-macos-arm64.zip</span>', '<span class="dim">    verify    its SHA-256 against MoteHelper-macos-arm64.zip.sha256 from the same release</span>',
    '<span class="dim">    install   ~/Applications/MoteHelper.app</span>', '<span class="dim">    clear the quarantine flag on that app only, then open it</span>',
    '==&gt; Downloading MoteHelper-macos-arm64.zip', '==&gt; Verifying SHA-256', '    ok 9f2c…e41a', '==&gt; Installing to ~/Applications/MoteHelper.app', '==&gt; Opening Mote Helper',
    '', 'Done. Next steps:', '  1. Allow Accessibility when macOS asks (System Settings &gt; Privacy &amp; Security &gt; Accessibility).',
    '  4. The menu-bar icon has Pause input, Revoke all devices, Launch at login and Quit.', '', 'Uninstall: quit it from the menu, then  rm -rf "~/Applications/MoteHelper.app"']
def ax_prompt():
    return desk(tcc("“MoteHelper.app” would like to control this computer using accessibility features.", "Grant access to this application in Privacy &amp; Security settings, located in System Settings.", ["Open System Settings", "Deny"]))
def ax_pane(on=True, title="Accessibility"):
    return desk(sysset("Privacy &amp; Security", title, [("MoteHelper", on), ("Terminal", False)]))
def mac_menu(items):
    return '<div class="menu" style="width:262px">' + "".join(items) + '</div>'
def mi(t, cls="", r=""): return f'<div class="it {cls}">{t}<span>{r}</span></div>'
SEP = '<div class="sep"></div>'

def jA():
    bt = desk(tcc("“MoteHelper.app” would like to use Bluetooth.", "Mote Helper lets your phone's browser connect over Bluetooth to control this Mac.", ["OK", "Don’t Allow"], "bluetooth"))
    ln = desk(tcc("Allow “MoteHelper.app” to find devices on local networks?", "Mote Helper serves the Mote web remote to your phone on this network.", ["Allow", "Don’t Allow"], "wifiBars"))
    sr = desk(sysset("Privacy &amp; Security", "Screen &amp; System Audio Recording", [("MoteHelper", True)]))
    auto = desk(tcc("“MoteHelper.app” wants access to control “Safari”.", "Mote Helper reads the active tab of your browser so the Mote remote can show the right controls (Labs, off by default).", ["Allow", "Don’t Allow"], "apps"))
    menu = desk(mac_menu([mi("<b>Mote Helper: Ready</b>"), mi("Pair new device…", "", "⌘N"), SEP, mi("Wi-Fi remote: http://macbook-pro.local:8790"), SEP,
        mi("Allow Tailscale connections", "hl", "✓"), mi("Tailscale: http://macbook-pro.tail1234.ts.net:8790", "dis"), SEP, mi("Space thumbnails: grant…"), mi("Share Space thumbnails", "", "✓"),
        SEP, mi("No connected devices", "dis"), mi("Paired devices: 0", "dis"), SEP, mi("Labs", "", "▸"), mi("Pause input", "", "⌘P"), mi("Revoke all devices…"), mi("Launch at login", "", "✓"), mi("Quit Mote Helper", "", "⌘Q")]))
    menu_ax = desk(mac_menu([mi("<b>Needs Accessibility permission</b>"), mi("Grant Accessibility…", "hl"), SEP, mi("Pair new device…", "", "⌘N"), SEP, mi("Quit Mote Helper", "", "⌘Q")]))
    steps = [
        step(1, "jA-s1", "Find Mote", "Website or README › Install Mac helper", macs=[mac(site_page(), "jA-m1", items=mote_menubar_icon(False))]),
        step(2, "jA-s2", "Copy the install line", "curl … install.sh | bash; no sudo", macs=[mac(site_page(True), "jA-m2", items=mote_menubar_icon(False))]),
        step(3, "jA-s3", "Terminal: download, verify, install", "Quits a running helper first; clears quarantine on this app only", macs=[mac(term(INSTALL_OK), "jA-m3", items=mote_menubar_icon(False))]),
        step(4, "jA-s4", "Launch: Accessibility prompt", "Asked at launch; needed to type and click", macs=[mac(ax_prompt(), "jA-m4", items=mote_menubar_icon(False))]),
        step(5, "jA-s5", "Turn on Mote Helper", "System Settings › Privacy &amp; Security › Accessibility", macs=[mac(ax_pane(), "jA-m5", items=mote_menubar_icon(False))]),
        step(6, "jA-s6", "Local network and Bluetooth", "When the server starts and BLE starts advertising", macs=[mac(ln, "jA-m6a", items=mote_menubar_icon(False)), mac(bt, "jA-m6b", items=mote_menubar_icon(False))]),
        step(7, "jA-s7", "The menu-bar tour", "Pair, Wi-Fi address, Tailscale, thumbnails, Labs, Pause, Revoke, Launch at login", macs=[mac(menu, "jA-m7", items=mote_menubar_icon(False, True))]),
    ]
    branches = [
        branch("jA-b1", 3, "Not a Mac, checksum mismatch, or bad archive", [step("3a", "jA-b1s", "The script stops; nothing installed", "", macs=[mac(term(['<span class="p">~ %</span> curl … | bash', '==&gt; Verifying SHA-256', '<span style="color:#FF6B66">error:</span> checksum mismatch (expected 9f2c…, got 1b07…). Not installing.', '', '<span style="color:#FF6B66">error:</span> Mote Helper is a macOS app. On other systems use the Android app or the web remote.', '<span style="color:#FF6B66">error:</span> unsupported CPU: …']), "jA-b1m", items=mote_menubar_icon(False))])], "Re-run later, or download the zip from GitHub Releases"),
        branch("jA-b2", 4, "Accessibility denied or skipped", [step("4a", "jA-b2s", "Menu says so; input is ignored", "“Needs Accessibility permission” › Grant Accessibility…", macs=[mac(menu_ax, "jA-b2m", items=mote_menubar_icon(False, True))]),
                                                              step("4b", "jA-b2s2", "Grant it in System Settings", "The phone shows “Mote Helper needs Accessibility on the Mac”", macs=[mac(ax_pane(), "jA-b2m2", items=mote_menubar_icon(False))])]),
        branch("jA-b3", 7, "First use of Space thumbnails or Live screen", [step("7a", "jA-b3s", "Screen Recording, only then", "Menu: “Space thumbnails: grant…”; Live screen: “Allow Screen Recording for Mote Helper in System Settings”", macs=[mac(sr, "jA-b3m", items=mote_menubar_icon(False))])]),
        branch("jA-b4", 7, "First use of Smart remote (Labs)", [step("7b", "jA-b4s", "Automation, per browser", "Plus “Allow JavaScript from Apple Events” in the browser’s Develop menu", macs=[mac(auto, "jA-b4m", items=mote_menubar_icon(False))])], "No macOS notification prompt: the helper sends none today"),
    ]
    return journey("journey-setup-mac", "A. Mac setup", "shipped",
        "Install the menu-bar helper and grant macOS permissions in the order they actually appear: Accessibility at launch, Local network and Bluetooth when they start, Screen Recording and Automation only when a feature first needs them.", steps, branches)

def jA2():
    menu_login = desk(mac_menu([mi("<b>Mote Helper: Ready</b>"), SEP, mi("Allow Tailscale connections", "hl", "✓"), mi("Tailscale: not detected (is it running?)", "dis"), SEP, mi("Launch at login", "hl", "✓"), mi("Quit Mote Helper", "", "⌘Q")]))
    reinst = term(['<span class="p">~ %</span> curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash', '==&gt; Quitting the running Mote Helper', '==&gt; Installing to ~/Applications/MoteHelper.app', '==&gt; Opening Mote Helper',
        '     After an update you may have to switch it off and on', '     again there (the app is ad-hoc signed, so each version has a new signature).'])
    offon = desk(sysset("Privacy &amp; Security", "Accessibility", [("MoteHelper  (off, then on again)", False)]))
    quit_ = desk(mac_menu([mi("<b>Mote Helper: Ready</b>"), SEP, mi("Launch at login", "", ""), mi("Quit Mote Helper", "hl", "⌘Q")]))
    rm = term(['<span class="p">~ %</span> rm -rf ~/Applications/MoteHelper.app', '<span class="dim"># optional: remove it from Accessibility and Screen Recording in System Settings</span>'])
    steps = [
        step(1, "jA2-s1", "Launch at login, Tailscale", "Menu checkboxes; Tailscale shows its MagicDNS URL when detected", macs=[mac(menu_login, "jA2-m1", items=mote_menubar_icon(False, True))]),
        step(2, "jA2-s2", "Update: run the same line", "It quits the old helper and replaces it", macs=[mac(reinst, "jA2-m2", items=mote_menubar_icon(False))]),
        step(3, "jA2-s3", "Re-grant Accessibility", "Ad-hoc signed: switch it off and on (or remove and re-add); click Always Allow on the Keychain prompt", macs=[mac(offon, "jA2-m3a", items=mote_menubar_icon(False)), mac(ax_pane(True), "jA2-m3b", items=mote_menubar_icon(False))]),
        step(4, "jA2-s4", "Uninstall: quit", "Turn off Launch at login first", macs=[mac(quit_, "jA2-m4", items=mote_menubar_icon(False, True))]),
        step(5, "jA2-s5", "Delete the app", "From the installer’s own instructions", macs=[mac(rm, "jA2-m5", menubar=False)]),
    ]
    branches = [branch("jA2-b1", 3, "Skipped the re-grant", [step("3a", "jA2-b1s", "Phone keeps the link but nothing moves", "Devices row: “Mote Helper needs Accessibility on the Mac”", [phone(scr_badge(media_shipped(), "media", pill("MacBook Pro", "led amber", "Wi-Fi", "lan"), devices(rows=devrow("d", "laptop", "MacBook Pro", "Mote Helper needs Accessibility on the Mac", badges("Wi-Fi"), sel=True)), lit=False), "jA2-b1p")])], "Fix: step 3")]
    return journey("journey-mac-update", "A2. Mac: login, Tailscale, update and uninstall", "shipped",
        "Keeping the helper around: start it at login, allow Tailscale, update it (and re-grant Accessibility, because every ad-hoc-signed build is a new app to macOS), remove it.", steps, branches)

def jB():
    rel = '<div style="padding:8px 4px;font-size:14px"><b>Releases · tusharpandey13/mote</b><div style="margin-top:10px;padding:10px;border-radius:12px;background:#fff">v0.4.0 · Latest<div style="margin-top:8px;color:#1D6FB8">mote-v0.4.0.apk</div><div style="color:#1D6FB8">mote-v0.4.0.apk.sha256</div><div style="color:#888;margin-top:4px">MoteHelper-macos-arm64.zip …</div></div></div>'
    dl = anote("Download complete", "mote-v0.4.0.apk", ("Open",))
    unk = asys("Install unknown apps", '<div class="li">Chrome<span></span></div><div class="li">Allow from this source' + asw(True) + '</div><p style="font-size:13px;color:#555;margin-top:10px">Your phone and personal data are more vulnerable to attack by unknown apps.</p>')
    inst = asys("Mote", '<p style="font-size:16px">Do you want to install this app?</p><div style="display:flex;justify-content:flex-end;gap:8px;margin-top:24px"><span class="btnt">Cancel</span><span class="btnp">Install</span></div>')
    perm = sysdlg("b-nearby", "Allow Mote to find, connect to and determine the relative position of nearby devices?", "", ["Don’t allow", "Allow"])
    notif = sysdlg("b-notif", "Allow Mote to send you notifications?", "Used for the ongoing link notification: “Connected to …”, “Searching for …”.", ["Don’t allow", "Allow"])
    steps = [
        step(1, "jB-s1", "Get the APK", "README › Download APK (GitHub Releases); .sha256 alongside", [aphone(asys("github.com", rel), "jB-p1", taps=[(170, 250)])]),
        step(2, "jB-s2", "Open the download", "", [aphone(dl, "jB-p2", taps=[(70, 110)])]),
        step(3, "jB-s3", "Allow unknown sources", "Once per browser or file app", [aphone(unk, "jB-p3", taps=[(360, 236)])]),
        step(4, "jB-s4", "Install", "Android’s package installer", [aphone(inst, "jB-p4", taps=[(350, 240)])]),
        step(5, "jB-s5", "First launch: the gate", "Nearby devices is asked only when you choose Bluetooth", [phone(bare(gate("perm")), "jB-p5", taps=[(206, 560)]), phone(bare(gate("perm"), overlay=perm), "jB-p5b", taps=[(336, 470)])]),
        step(6, "jB-s6", "Notifications (Android 13+)", "Asked together with Nearby devices on the gate button, then Bluetooth on", [phone(bare(gate("off"), overlay=notif), "jB-p6", taps=[(336, 490)])]),
    ]
    branches = [
        branch("jB-b1", 5, "Nearby devices denied twice", [step("5a", "jB-b1s", "Gate switches to Open settings", "“…Allow Nearby devices in Settings, then come back.”", [phone(bare(gate("perm").replace("Connect with Bluetooth", "Open settings").replace("so it can pair and connect, then turn Bluetooth on.", "in Settings, then come back.")), "jB-b1p")])], "Or: Use Wi-Fi with Mote Helper (no Bluetooth permission needed)"),
        branch("jB-b2", 5, "Phone has no usable Bluetooth", [step("5b", "jB-b2s", "Wi-Fi only", "“This phone has no Bluetooth adapter Mote can use.”", [phone(bare(gate("perm").replace('<div class="btn-primary accent-cap" style="margin-top:12px" data-dc-id="gate-primary">Connect with Bluetooth</div>', '').replace("Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on.", "This phone has no Bluetooth adapter Mote can use.")), "jB-b2p")])]),
    ]
    return journey("journey-setup-phone", "B. Phone setup", "shipped",
        "Sideload the APK, then let Mote ask for permissions only when they matter. There is no separate onboarding: the Bluetooth gate is the first screen.", steps, branches)

def jC():
    nomac = bare(wifi_add(False).replace("Looking for Macs on this Wi-Fi…", "No Macs found. Guest and hotel Wi-Fi often block this. Try Enter address, Tailscale or Bluetooth."), top=topbar_with(pill("Choose a device", "led red"), False))
    addr = bare('''<main class="content" style="display:flex;flex-direction:column;gap:14px"><h1 class="t-headline">Enter address</h1>
      <p class="t-body-s muted">For Tailscale, use the Mac's MagicDNS name or its 100.x address, and turn on Allow Tailscale connections in the Mote Helper menu. A LAN IP or name.local works too.</p>
      <div class="field focus"><span class="t-body">192.168.1.20</span></div><div class="btn-primary accent-cap">Connect</div></main>''', top=topbar_with(pill("Choose a device", "led red"), False))
    err = lambda msg: bare(code_screen("482910", f'<p class="t-body-s" style="color:var(--danger)">{msg}</p>'), top=topbar_with(pill("MacBook Pro", "led amber"), False))
    steps = [
        step(1, "jC-s1", "Use Wi-Fi with Mote Helper", "From the gate or Devices › Add Mac over Wi-Fi", [phone(bare(gate("off")), "jC-p1", taps=[(206, 594)])]),
        step(2, "jC-s2", "Bonjour (NSD) looks for Macs", "“Looking for Macs on this Wi-Fi…”", [phone(bare(wifi_add(False), top=topbar_with(pill("Choose a device", "led red"), False)), "jC-p2")]),
        step(3, "jC-s3", "Found: pick the Mac", "Row with name, IP and a WI-FI badge", [phone(bare(wifi_add(), top=topbar_with(pill("Choose a device", "led red"), False)), "jC-p3", taps=[(206, 376)])]),
        step(4, "jC-s4", "Mac: Pair new device…", "The pairing window shows the code", macs=[mac(pairing_window(), "jC-m4", items=mote_menubar_icon(False))]),
        step(5, "jC-s5", "Enter the code", "Pair", [phone(bare(code_screen(), top=topbar_with(pill("MacBook Pro", "led amber"), False)), "jC-p5", taps=[(206, 360)])]),
        step(6, "jC-s6", "Connected", "", [phone(scr_route(media_shipped(), "Wi-Fi"), "jC-p6")]),
    ]
    branches = [
        branch("jC-b1", 2, "Multicast blocked: no Macs found", [step("2a", "jC-b1s", "“No Macs found…”", "Guest, hotel and many office networks", [phone(nomac, "jC-b1p", taps=[(300, 300)])]),
                                                             step("2b", "jC-b1s2", "Enter address", "IP from the helper menu: Wi-Fi remote: …", [phone(addr, "jC-b1p2", taps=[(206, 340)])])], "Then step 4"),
        branch("jC-b2", 5, "Code rejected", [step("5a", "jC-b2s", "Wrong code", "“Wrong code. Check the code in the Mote Helper window.”", [phone(err("Wrong code. Check the code in the Mote Helper window."), "jC-b2p")]),
                                             step("5b", "jC-b2s2", "Pairing window closed", "“Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu.”", [phone(err("Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu."), "jC-b2p2")]),
                                             step("5c", "jC-b2s3", "Too many tries / no answer", "“Too many attempts. Try again in 30 s.” · “The Mac didn't answer. Is Mote Helper running?”", [phone(err("Too many attempts. Try again in 30 s."), "jC-b2p3")])]),
    ]
    return journey("journey-connect-now", "C. Code pairing (legacy 0.4.0, being replaced)", "legacy",
        "The honest current path, including the network that hides the Mac and every code error. Bluetooth-only first connect is journey 1.", steps, branches)

def jC2():
    ksa = desk(win(30, 30, 280, 200, "Keyboard Setup Assistant", '<div style="padding:16px;font-size:12px"><b>Your keyboard cannot be identified and will not be usable until it is identified.</b><p style="color:#666;margin-top:6px">To identify your keyboard, click Continue.</p><div style="display:flex;justify-content:flex-end;gap:8px;margin-top:18px"><span style="padding:4px 10px;border-radius:6px;background:#E3E3E7">Quit</span><span style="padding:4px 10px;border-radius:6px;background:#2F7CF6;color:#fff">Continue</span></div></div>', "ksa"))
    forget_mac = desk(sysset("Bluetooth", "Bluetooth", [("Pixel 8  ⓘ  › Forget This Device…", "Connected"), ("AirPods Pro", "Not connected")]))
    stale = scr_route(media_shipped(), "BT")
    steps = [
        step(1, "jC2-s1", "After pairing: Keyboard Setup Assistant", "macOS asks to identify the “keyboard”; Quit is fine (ANSI default)", macs=[mac(ksa, "jC2-m1", items=mote_menubar_icon(False))]),
        step(2, "jC2-s2", "Connected over BT", "Works at the login screen too", [phone(stale, "jC2-p2")]),
    ]
    branches = [branch("jC2-b1", 2, "Shows Connected but buttons do nothing", [
        step("2a", "jC2-b1s", "Stale HID pairing", "The Mac kept an old key for this phone", [phone(stale, "jC2-b1p", taps=[(206, 532)])]),
        step("2b", "jC2-b1s2", "Forget on the phone", "Devices › long-press › Forget", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber", "BT", "bt"), sysdlg("f", "Forget MacBook Pro on this phone?", "", ["Cancel", "Forget"]), lit=False), "jC2-b1p2", taps=[(348, 470)])]),
        step("2c", "jC2-b1s3", "Forget on the Mac", "System Settings › Bluetooth › ⓘ › Forget This Device…", macs=[mac(forget_mac, "jC2-b1m", items=mote_menubar_icon(False))]),
        step("2d", "jC2-b1s4", "Pair again", "Journey 1, Bluetooth-only branch (1b)", [phone(scr(media_shipped(), "media"), "jC2-b1p3")])], "No in-app detection of this state yet")]
    return journey("journey-bt-quirks", "C2. Bluetooth first connect: macOS quirks", "shipped",
        "What macOS does after Mote pairs as a keyboard, and the recovery when a stale pairing makes the remote look connected but dead.", steps, branches)

def connect_sheet(name="MacBook Pro", sub="Nearby · Wi-Fi", via=""):
    v = f'<p class="t-body-s ctr" style="color:var(--accent);margin-top:6px">{via}</p>' if via else ""
    return jsheet("jD-connect", f'''<div class="hero-dev raised"><i data-i="laptop"></i></div><h2 class="t-headline ctr">Connect to {name}</h2>
      <p class="t-body-s muted ctr" style="margin-top:6px">{sub}</p>{v}<div class="btn-row"><div class="btn-secondary raised">Not now</div><div class="btn-primary accent-cap">Connect</div></div>''')
def discovering():
    return jsheet("jD-discover", '''<div class="scan" style="margin:6px auto 12px"><i data-i="bluetooth" class="i28"></i></div><h2 class="t-headline ctr">Looking for your Mac…</h2><p class="t-body-s muted ctr" style="margin-top:4px">Searching this Wi-Fi, Bluetooth and Tailscale.</p>
      <div style="display:flex;flex-direction:column;gap:6px;margin-top:14px">''' + "".join(f'<div class="row-item flat" style="min-height:40px"><span class="led{" amber" if i else ""}"></span><span class="t-body-s">{t}</span></div>' for i, t in enumerate(["Known Macs", "Bluetooth LE nearby", "This Wi-Fi (sweep)", "Bonjour"])) + '</div>')
def jD():
    note = desk(mnote("Mote", "Pixel 8 connected to Mote.<br>via Bluetooth proximity", "Block"))
    wifioff = jsheet("jD-wifi-off", '<h2 class="t-headline ctr">Turn on Wi-Fi</h2><p class="t-body-s muted ctr" style="margin-top:6px">Wi-Fi is off. Connect to the same Wi-Fi as your Mac, or turn on Tailscale.</p><div class="btn-row"><div class="btn-secondary raised">Enter address</div><div class="btn-primary accent-cap">Turn on Wi-Fi</div></div>')
    nohelper = jsheet("jD-no-helper", '<h2 class="t-headline ctr">Mote Helper not found on this network</h2><p class="t-body-s muted ctr" style="margin-top:6px">Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:</p><div class="field" style="margin-top:8px"><span class="t-body-s">curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash</span></div><p class="t-body-s subtle ctr" style="margin-top:6px">or download it from tusharpandey13.github.io/mote</p><div class="btn-row"><div class="btn-secondary raised">Enter address</div><div class="btn-primary accent-cap">Retry</div></div>')
    far = jsheet("jD-far", '<h2 class="t-headline ctr">Connect to MacBook Pro</h2><p class="t-body-s ctr" style="margin-top:6px;color:var(--danger)">MacBook Pro couldn’t confirm this phone is nearby. Bring it next to the Mac with Bluetooth on, or use the same Tailscale account.</p><div class="btn-row"><div class="btn-primary accent-cap">Try again</div></div>')
    approval = desk('<div class="alert"><div class="ic">MOTE</div><b>Allow “Pixel 8” to control this Mac?</b><p>It will be able to type, click and move the pointer, like a keyboard and mouse.<br>Via Wi-Fi (192.168.1.44) · Key 3f2a 9c01 77de 0b41<br>Deny if you didn’t just tap Connect on this phone.</p><div class="btns"><span>Deny</span><span class="def">Allow</span></div></div>')
    req = desk(mac_menu([mi("<b>Mote Helper: Ready</b>"), SEP, mi("Trusted phones: 1", "dis"), mi("This Mac's key: 9a1c 44f0 …", "dis"), mi("Require approval for new devices", "hl", "✓"), SEP, mi("Connected devices:"), mi("&nbsp;&nbsp; Pixel 8 · Wi-Fi", "dis")]))
    steps = [
        step(1, "jD-s1", "Open Mote: “Connect to your Mac”", "No permission asked first. Known Macs, BLE (if allowed), /24 sweep, Bonjour in parallel", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), discovering(), lit=False), "jD-p1")]),
        step(2, "jD-s2", "“Connect to MacBook Pro”", "“Mote uses Bluetooth once to check this phone is next to your Mac.” › Allow and connect", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), connect_sheet(), lit=False), "jD-p2", taps=[(306, 790)])]),
        step(3, "jD-s3", "One tap: connected", "No code, nothing to click on the Mac", [phone(scr_route(media_shipped(), "Wi-Fi"), "jD-p3")]),
        step(4, "jD-s4", "Mac: a non-blocking notice", "Floats top right, closes after 15 s; never for already-trusted phones", macs=[mac(note, "jD-m4", items=mote_menubar_icon(True))]),
    ]
    branches = [
        branch("jD-b1", 1, "Wi-Fi off on the phone", [step("1a", "jD-b1s", "Turn on Wi-Fi", "Or fall back to Bluetooth HID", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led red"), wifioff, lit=False), "jD-b1p", taps=[(306, 790)])])], "Then step 2"),
        branch("jD-b2", 1, "No helper answers within 5 s", [step("1b", "jD-b2s", "Install link, Try again, Enter address", "", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led red"), nohelper, lit=False), "jD-b2p")])], "See journey A"),
        branch("jD-b3", 2, "Same Tailscale owner, away from home", [step("2a", "jD-b3s", "Zero prompts, over the internet", "“Connected. Trusted via Tailscale”", [phone(scr_badge(media_shipped(), "media", pill("MacBook Pro", "led", "Tailscale", "ts"), connect_sheet(sub="Over Tailscale", via="Connected. Trusted via Tailscale")), "jD-b3p")])]),
        branch("jD-b4", 3, "On this LAN but out of Bluetooth range", [step("3a", "jD-b4s", "Rejected with a reason", "Proximity check", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led red"), far, lit=False), "jD-b4p")])]),
        branch("jD-b5", 3, "“Require approval for new devices” is on", [step("3b", "jD-b5s", "Mac setting", "", macs=[mac(desk(req), "jD-b5m0", items=mote_menubar_icon(True, True))]), step("3c", "jD-b5s2", "The Mac asks", "Phone: “Click Allow on MacBook Pro. It asks before trusting new devices.”", macs=[mac(approval, "jD-b5m", items=mote_menubar_icon(True))])]),
    ]
    return journey("journey-connect-next", "D. Connect: every branch (0.6.0)", "next",
        "Proposed: one tap from the phone, nothing on the Mac. Discovery runs in parallel; the Mac only tells you it happened and lets you Block. From docs/pairing.md and the in-flight connection branch.", steps, branches)

def jE():
    rows2 = devrow("e-mbp", "laptop", "MacBook Pro", "Connected · Wi-Fi", badges("Wi-Fi", "BT", active="Wi-Fi"), sel=True) + devrow("e-mini", "desktop", "Mac mini", "Paired", badges("Wi-Fi"))
    asleep = scr_badge(disconnected_inner(), "media", pill("MacBook Pro", "led amber"), lit=False)
    helperdown = scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber"), devices(rows=devrow("d", "laptop", "MacBook Pro", "Searching…", badges("Wi-Fi"), sel=True)) + toast("The Mac didn't answer. Is Mote Helper running?"), lit=False)
    steps = [
        step(1, "jE-s1", "Launch: reconnects to the selected Mac", "Notification: “Searching for MacBook Pro…”", [phone(scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led amber"), anote("Mote", "Searching for MacBook Pro…"), lit=False), "jE-p1")]),
        step(2, "jE-s2", "Best route wins", "Bonjour › LAN › name.local › tailnet › manual. Wi-Fi stops 30 s after the app leaves the screen", [phone(scr_route(media_shipped(), "Wi-Fi"), "jE-p2"), phone(scr_route(media_shipped(), "Tailscale"), "jE-p2b")]),
        step(3, "jE-s3", "Switch Macs", "Pill › Devices › tap the other Mac", [phone(scr_badge(media_shipped(), "media", pill("MacBook Pro", "led", "Wi-Fi", "lan"), devices(rows=rows2)), "jE-p3", taps=[(206, 300)])]),
        step(4, "jE-s4", "Connected to the other Mac", "One active Mac at a time; the other stays paired", [phone(scr_badge(media_shipped(), "media", pill("Mac mini", "led", "Wi-Fi", "lan")), "jE-p4")]),
    ]
    branches = [
        branch("jE-b1", 1, "The Mac is asleep", [step("1a", "jE-b1s", "Keeps searching", "Only “Searching…” / “Connecting…”: no Mac-asleep copy", [phone(asleep, "jE-b1p")])], "Reconnects on wake"),
        branch("jE-b2", 1, "Helper not running", [step("1b", "jE-b2s", "No answer", "Generic failure copy; start Mote Helper or turn on Launch at login", [phone(helperdown, "jE-b2p")])]),
        branch("jE-b3", 2, "Input paused on the Mac", [step("2a", "jE-b3s", "“Paused on the Mac”", "Menu › Pause input; keys are ignored, not queued", [phone(scr_badge(media_shipped(), "media", pill("MacBook Pro", "led amber", "Wi-Fi", "lan"), devices(rows=devrow("p", "laptop", "MacBook Pro", "Paused on the Mac", badges("Wi-Fi"), sel=True))), "jE-b3p")])]),
    ]
    return journey("journey-daily", "E. Daily use and reconnect", "shipped",
        "Opening Mote on an ordinary day: it finds the Mac it used last, picks the best route, and says what it is doing when it can’t.", steps, branches)

def jF():
    revoke = desk('<div class="alert"><div class="ic">MOTE</div><b>Revoke all devices?</b><p>Every paired phone and browser is disconnected and has to pair again with a new code.</p><div class="btns"><span>Cancel</span><span class="def">Revoke All</span></div></div>')
    np = scr_badge(media_shipped(True), "media", pill("Not paired: pair again", "led red"), devices("The Mac no longer knows this phone. Long-press it to forget, then pair again.", devrow("r", "laptop", "MacBook Pro", "Not paired: pair again", badges("Wi-Fi"), sel=True)), lit=False)
    block = desk(mnote("Mote", "Pixel 8 connected to Mote.<br>via Bluetooth proximity", "Block"))
    fresh = bare(gate("perm"))
    sig = aphone(asys("Mote", '<p style="font-size:16px">App not installed as package conflicts with an existing package.</p><div style="display:flex;justify-content:flex-end;margin-top:24px"><span class="btnt">OK</span></div>'), "jF-p5")
    btoff = scr_badge(media_shipped(True), "media", pill("MacBook Pro", "led red", "BT", "bt"), toast("Connecting…"), lit=False)
    permgone = bare(gate("perm"))
    axgone = scr_badge(media_shipped(), "media", pill("MacBook Pro", "led amber", "Wi-Fi", "lan"), devices(rows=devrow("a", "laptop", "MacBook Pro", "Mote Helper needs Accessibility on the Mac", badges("Wi-Fi"), sel=True)), lit=False)
    steps = [
        step(1, "jF-s1", "Lost phone: Revoke all devices…", "Helper menu; also clears Instant pairing trust", macs=[mac(revoke, "jF-m1", items=mote_menubar_icon(True))]),
        step(2, "jF-s2", "Every phone: Not paired", "Pair again from Devices", [phone(np, "jF-p2")]),
        step(3, "jF-s3", "Block one phone (0.6.0)", "From the connect notice: forget its key, refuse it from now on", macs=[mac(block, "jF-m3", items=mote_menubar_icon(True))]),
        step(4, "jF-s4", "Reinstalled Mote or cleared data", "No special copy: back to the gate with no Macs; the Mac still lists the old phone until revoked", [phone(fresh, "jF-p4")]),
        step(5, "jF-s5", "Lost signing key (maintainers)", "Nothing user-facing in Mote: Android refuses the update; uninstall loses pairings", [sig]),
    ]
    branches = [
        branch("jF-b1", "any time", "Nearby devices revoked mid-use", [step("5a", "jF-b1s", "Back to the gate", "Bluetooth link drops; Wi-Fi keeps working", [phone(permgone, "jF-b1p")])]),
        branch("jF-b2", "any time", "Bluetooth turned off mid-session", [step("5b", "jF-b2s", "The gate returns", "“…Bluetooth is off; turn it on to continue.” (unless Wi-Fi was chosen)", [phone(bare(gate("off")), "jF-b2p")])]),
        branch("jF-b3", "any time", "Accessibility revoked on the Mac", [step("5c", "jF-b3s", "Connected, but input ignored", "“Mote Helper needs Accessibility on the Mac”", [phone(axgone, "jF-b3p")])], "Fix: journey A2, step 3"),
    ]
    return journey("journey-recovery", "F. Recovery and management", "shipped",
        "Taking control back: revoke or block phones, pause input, and what users see when data, keys, permissions or Bluetooth disappear.", steps, branches)

def jG():
    pointer = ''.join([
        '<span class="t-caps grp-h">Pointer</span><section class="grp raised">',
        setrow("g-speed", "Pointer speed", "1x (default). Higher moves farther per swipe.", ""), '<div class="hslider well"><span class="ticks"></span><span class="fill" style="width:55%"></span><span class="thumb" style="left:57%"></span></div>',
        setrow("g-scroll", "Scroll speed", None, ""), '<div class="hslider well"><span class="ticks"></span><span class="fill" style="width:40%"></span><span class="thumb" style="left:42%"></span></div>',
        setrow("g-natural", "Natural scrolling", "Content follows your fingers", SW_ON), setrow("g-rc", "Right-click", None, '<div class="seg-ctl well" style="width:180px"><div class="on raised">Hold</div><div>2-finger tap</div></div>'), '</section>',
        '<span class="t-caps grp-h">Keyboard</span><section class="grp raised">', setrow("g-clear", "Clear modifiers on mode switch", None, SW_OFF), setrow("g-idle", "Release modifiers after 30 s idle", None, SW_ON), '</section>'])
    desks = ''.join(['<span class="t-caps grp-h">Spaces</span><section class="grp raised">',
        setrow("g-desks", "Desktops", "6 on the Mac. Sets the cards in the Spaces carousel over Bluetooth.", '<span class="t-label">6</span>'), setrow("g-ctrln", "Use ⌃1–⌃9 shortcuts", "Turn on Switch to Desktop 1-9 on the Mac (System Settings, Keyboard Shortcuts, Mission Control). Off: Mote steps with ⌃← and ⌃→.", SW_ON), '</section>',
        '<span class="t-caps grp-h">Screen</span><section class="grp raised">', setrow("g-awake", "Keep screen on while connected", None, SW_ON), setrow("g-dim", "Extra dim", "For dark rooms and presentations", SW_OFF), '</section>'])
    labs = ''.join(['<span class="t-caps grp-h">Labs</span><section class="grp raised">'] + [setrow(f"g-lab{i}", n, d, SW_OFF) for i, (n, d) in enumerate([
        ("Instant pairing (preview)", "Find Macs over Bluetooth and connect without a code. Needs Mote Helper with Labs › Instant pairing on."),
        ("Smart remote (preview)", "Adapts Media to what is on the Mac: now playing with a scrubber, app buttons, keyboard on text fields."),
        ("Open &amp; TV mode (preview)", "An Open… sheet that drives the Mac, and a TV remote for YouTube."),
        ("Live screen (preview)", "See the Mac's screen and tap to click."),
        ("Browser video control (preview)", "Control the video in the Mac's browser tab with the Mote Video Bridge extension.")])] + ['</section>'])
    ks = desk(sysset("Keyboard", "Keyboard Shortcuts › Mission Control", [("Mission Control", True), ("Switch to Desktop 1  ⌃1", True), ("Switch to Desktop 2  ⌃2", True), ("Switch to Desktop 3  ⌃3", False)], side=("General", "Desktop &amp; Dock", "Keyboard", "Mouse")))
    helper_labs = desk(mac_menu([mi("<b>Mote Helper: Ready</b>"), SEP, mi("Labs", "hl", "▸")]) + '<div class="menu" style="right:auto;left:20px;top:60px;width:220px">' + "".join(mi(x, "", "✓" if i == 0 else "") for i, x in enumerate(["Instant pairing (preview)", "Context &amp; now playing (preview)", "TV mode (preview)", "Live screen (preview)", "Browser extension bridge"])) + '</div>')
    steps = [
        step(1, "jG-s1", "⋯ menu", "Theme, Manage devices, Haptics, Settings, About Mote", [phone(scr(media_shipped(), "media", overlay=menu_sheet()), "jG-p1", taps=[(206, 790)])]),
        step(2, "jG-s2", "Pointer and keyboard", "Speeds, natural scrolling, right-click, modifiers", [phone(sub_screen("Settings", pointer), "jG-p2", taps=[(230, 330)])]),
        step(3, "jG-s3", "Desktops: ⌃1–⌃9", "Mote can’t turn this on for you", [phone(sub_screen("Settings", desks), "jG-p3", taps=[(366, 236)])]),
        step(4, "jG-s4", "On the Mac: enable the shortcuts", "System Settings › Keyboard › Keyboard Shortcuts › Mission Control", macs=[mac(ks, "jG-m4", items=mote_menubar_icon(True))]),
        step(5, "jG-s5", "Haptics", "Strength and which events tick", [phone(body_of(haptics_page()).replace('<div class="screen"', '<div class="screen" style="grid-template-rows:var(--status-h) var(--top-h) 1fr var(--nav-h)"', 1), "jG-p5", taps=[(250, 318)])]),
        step(6, "jG-s6", "Labs: both sides", "Each preview needs the phone toggle and the helper’s Labs item", [phone(sub_screen("Settings", labs), "jG-p6", taps=[(366, 220)])], [mac(helper_labs, "jG-m6", items=mote_menubar_icon(True, True))]),
    ]
    return journey("journey-settings", "G. Settings and customisation", "shipped",
        "Everything a user can tune, including the one macOS setting Mote depends on for direct desktop jumps.", steps)
def body_of(html):
    a = html.index('<div class="screen"'); b = html.rindex('<script src="icons.js">')
    return html[a:b]
def setup_screen():
    return '''<main class="content" data-dc-id="bt-gate" style="display:flex;flex-direction:column;justify-content:center;gap:14px;padding:0 28px">
      <div class="hero-dev raised" style="width:96px;height:96px;margin:0 auto 6px"><i data-i="laptop"></i></div>
      <h1 class="t-headline ctr">Set up Mote</h1>
      <p class="t-body muted ctr">Mote connects to your Mac over Wi-Fi or Tailscale through the free Mote Helper, and pairs Bluetooth as a backup that also works at the login screen.</p>
      <div class="btn-primary accent-cap" style="margin-top:12px" data-dc-id="gate-primary">Set up Mote</div>
      <div class="btn-secondary raised" data-dc-id="gate-bt-only">Use Bluetooth only (limited)</div>
      <p class="t-body-s subtle ctr">No helper yet? tusharpandey13.github.io/mote · one line in Terminal</p></main>'''
def also_bt(checked=True):
    box = '<span style="flex:none;width:24px;height:24px;border-radius:7px;display:grid;place-items:center;color:#fff;background:var(--accent)">✓</span>' if checked else '<span style="flex:none;width:24px;height:24px;border-radius:7px;box-shadow:inset 0 0 0 2px var(--outline)"></span>'
    return jsheet("j1-also-bt", f'''<div class="hero-dev raised" style="width:80px;height:80px"><i data-i="bluetooth"></i></div>
      <h2 class="t-headline ctr">Also pair Bluetooth</h2>
      <p class="t-body-s muted ctr" style="margin-top:6px">Recommended. Works at the Mac’s login screen and as a backup when Wi-Fi drops. Mote does the pairing; the Mac accepts it through Mote Helper.</p>
      <div class="row-item raised" style="margin-top:14px" data-dc-id="j1-bt-check">{box}<div class="tx"><div class="t-label">Pair Bluetooth too</div><div class="sub">Keyboard, mouse and media keys over Bluetooth</div></div></div>
      <div class="btn-row"><div class="btn-secondary raised" data-dc-id="j1-bt-skip">Skip</div><div class="btn-primary accent-cap" data-dc-id="j1-bt-continue">Continue</div></div>''')
def both_routes():
    return scr_badge(media_shipped(), "media", f'<div class="pill raised" style="margin:0"><span class="led"></span><span class="name">MacBook Pro</span><span class="tbadge lan">WI-FI</span><span class="tbadge bt">BT</span><i data-i="chevDown" class="i20 chev"></i></div>')
def j01():
    pair = sysdlg("android-pair-code", "Pair with MacBook Pro?", "Bluetooth pairing code", ["Cancel", "Pair"], '<div style="font:600 26px/34px var(--font-ui);letter-spacing:3px;margin-top:6px">482 913</div>')
    auto = desk(mnote("Mote", "Pixel 8 paired over Bluetooth.<br>Accepted by Mote Helper", "Block"))
    steps = [
        step(1, "j1-s1", "Open Mote: Set up Mote", "Wi-Fi/Tailscale and Bluetooth by default", [phone(bare(setup_screen()), "j1-p1", taps=[(206, 560)])]),
        step(2, "j1-s2", "Mote finds the Mac", "Known Macs, BLE, Wi-Fi sweep, Bonjour in parallel", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), discovering(), lit=False), "j1-p2")]),
        step(3, "j1-s3", "One tap: Connect", "No code; nothing to click on the Mac", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), connect_sheet(), lit=False), "j1-p3", taps=[(306, 790)])]),
        step(4, "j1-s4", "Connected over Wi-Fi, then: Also pair Bluetooth", "Pre-checked, skippable", [phone(scr_route(media_shipped(), "Wi-Fi", overlay=also_bt()), "j1-p4", taps=[(306, 830)])]),
        step(5, "j1-s5", "Android confirms the Bluetooth pair", "The helper accepts it on the Mac: a notice, not a prompt", [phone(scr_route(media_shipped(), "Wi-Fi", overlay=pair), "j1-p5", taps=[(342, 500)])], [mac(auto, "j1-m5", items=mote_menubar_icon(True))]),
        step(6, "j1-s6", "Set up: Wi-Fi and Bluetooth", "Rich features via the helper; BT for the login screen and as a fallback", [phone(both_routes(), "j1-p6")]),
    ]
    vis = sysdlg("android-visible", "Mote wants to make your phone visible to other Bluetooth devices for 120 seconds.", "", ["Deny", "Allow"])
    branches = [
        branch("j1-b1", 5, "The helper can’t accept the Bluetooth pair", [step("5a", "j1-b1s", "One macOS Bluetooth confirm", "Click Connect once; Mote says so on the phone", macs=[mac(mac_bt_settings("482 913"), "j1-b1m", items=mote_menubar_icon(True))])], "Then step 6"),
        branch("j1-b2", 4, "Skip Bluetooth", [step("4a", "j1-b2s", "Wi-Fi only", "Add it later: Devices › Pair Bluetooth", [phone(scr_route(media_shipped(), "Wi-Fi"), "j1-b2p")])]),
        branch("j1-b3", 1, "Use Bluetooth only (limited)", [
            step("1a", "j1-b3s1", "Nearby devices + Bluetooth on", "Android’s prompts", [phone(bare(gate("perm"), overlay=sysdlg("android-perm", "Allow Mote to find, connect to and determine the relative position of nearby devices?", "", ["Don’t allow", "Allow"])), "j1-b3p1", taps=[(336, 470)])]),
            step("1b", "j1-b3s2", "Pair from the Mac’s Bluetooth settings", "Phone visible 120 s; Connect on the Mac, confirm the code", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), vis, lit=False), "j1-b3p2", taps=[(336, 470)])], [mac(mac_bt_settings(), "j1-b3m2", items=mote_menubar_icon(False))]),
            step("1c", "j1-b3s3", "Connected [BT]", "No Unicode typing, click-at-point, thumbnails or Labs", [phone(scr_route(media_shipped(), "BT"), "j1-b3p3")])], "Upgrade any time: Set up Mote"),
    ]
    return journey("journey-first-run-bt", "1. First run (Wi-Fi + Bluetooth)", "next",
        "The intended first run: one Set up Mote flow connects over Wi-Fi/Tailscale through Mote Helper, then pairs Bluetooth as a recommended backup. No codes, no Mac-side pairing. Failures of the connect step: journey D.", steps, branches)

def j02():
    term = '''<div class="term"><span class="p">~ %</span> curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash<br>==&gt; Downloading MoteHelper-macos-arm64.zip<br>==&gt; Verifying SHA-256<br>==&gt; Installing to ~/Applications/MoteHelper.app<br>==&gt; Opening Mote Helper</div>'''
    note = desk(mnote("Mote", "Pixel 8 connected to Mote.<br>via Bluetooth proximity", "Block"))
    tsnote = desk(mnote("Mote", "Pixel 8 connected to Mote.<br>via Tailscale", "Block"))
    steps = [
        step(1, "j2-s1", "Install Mote Helper", "One line in Terminal (journey A for permissions)", macs=[mac(term, "j2-m1", items=mote_menubar_icon(False))]),
        step(2, "j2-s4", "The phone finds it", "Same Wi-Fi: Bonjour or sweep; nearby: BLE", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), discovering(), lit=False), "j2-p4")]),
        step(3, "j2-s3c", "One tap: Connect to MacBook Pro", "Trust by Bluetooth proximity: no code", [phone(scr_badge(media_shipped(True), "media", pill("Choose a device", "led amber"), connect_sheet(), lit=False), "j2-p5", taps=[(306, 790)])]),
        step(4, "j2-s6", "Connected over Wi-Fi", "The Mac only shows a notice with Block", [phone(scr_route(media_shipped(), "Wi-Fi"), "j2-p6")], [mac(note, "j2-m6", items=mote_menubar_icon(True))]),
    ]
    branches = [branch("j2-b1", 3, "Away from home, same Tailscale account", [step("3a", "j2-b1s", "Zero prompts over the internet", "“Connected. Trusted via Tailscale”", [phone(scr_badge(media_shipped(), "media", pill("MacBook Pro", "led", "Tailscale", "ts"), connect_sheet(sub="Over Tailscale", via="Connected. Trusted via Tailscale")), "j2-b1p")], [mac(tsnote, "j2-b1m", items=mote_menubar_icon(True))])], "Every other failure: journey D")]
    return journey("journey-wifi-helper", "2. Wi-Fi / Tailscale connect", "next",
        "Starting from the Mac: install the helper, and the phone does the rest. The app triggers pairing; the Mac trusts it by Bluetooth proximity or the same Tailscale owner and only shows a Block notice.", steps, branches)

# ---- index
JOURNEYS = [  # (id, title, status, phase, one line)
    ("journey-setup-mac", "A. Mac setup", "shipped", "setup", "Find Mote, install line, Terminal, Accessibility, Local network, Bluetooth, menu tour; Screen Recording and Automation on first use."),
    ("journey-mac-update", "A2. Mac: login, Tailscale, update, uninstall", "shipped", "setup", "Launch at login, Allow Tailscale connections, re-grant Accessibility after updates, rm -rf."),
    ("journey-setup-phone", "B. Phone setup", "shipped", "setup", "APK from Releases, unknown sources, install, gate, Nearby devices, Notifications."),
    ("journey-first-run-bt", "1. First run (Wi-Fi + Bluetooth)", "next", "connect", "Set up Mote › find › one tap › Wi-Fi, then Also pair Bluetooth; Bluetooth-only branch."),
    ("journey-bt-quirks", "C2. Bluetooth quirks", "shipped", "connect", "Keyboard Setup Assistant; connected-but-dead stale pairing → forget both sides."),
    ("journey-wifi-helper", "2. Wi-Fi / Tailscale connect", "next", "connect", "Helper installed › the phone finds it › one tap › Block notice only."),
    ("journey-connect-now", "C. Code pairing (legacy 0.4.0)", "legacy", "connect", "Being replaced by 1, 2 and D: NSD, Enter address, 6-digit code and its errors."),
    ("journey-tailscale", "3. Tailscale", "shipped", "connect", "Tailscale is off › Turn on Tailscale › auto-connect."),
    ("journey-connect-next", "D. Connect: every branch", "next", "connect", "Parallel discovery, one tap, Block notice; Wi-Fi off, no helper, Tailscale owner, out of range, Require approval."),
    ("journey-instant-pairing", "8. Instant pairing (Labs)", "labs", "connect", "Connect sheet, Mac allow prompt, Tailscale trust, route fallback."),
    ("journey-daily", "E. Daily use and reconnect", "shipped", "use", "Silent reconnect, best route, Mac asleep, helper down, paused, switch Macs."),
    ("journey-media", "7. Media controls", "shipped", "use", "Stick, right click, hold-to-seek, levers."),
    ("journey-spaces", "5. Spaces", "shipped", "use", "Carousel, ⌃N hint, thumbnails, display chip."),
    ("journey-macros", "6. Macros", "shipped", "use", "Record, click at point, favourite, confirm."),
    ("journey-settings", "G. Settings and customisation", "shipped", "use", "Speeds, ⌃1–⌃9 + macOS shortcut setup, haptics, theme, Labs on both sides."),
    ("journey-smart-remote", "9. Smart remote (Labs)", "labs", "use", "Per-app panels, auto keyboard."),
    ("journey-open-tv", "10. Open on Mac and TV mode (Labs)", "labs", "use", "Open sheet, TV window, OK-centre TV remote, share a link."),
    ("journey-video-bridge", "11. Browser video bridge (Labs)", "labs", "use", "Extension, pairing, Web video panel."),
    ("journey-live-screen", "12. Live screen (Labs)", "labs", "use", "LAN warning, live view, tap to click, indicator."),
    ("journey-reconnect", "4. Disconnected states", "shipped", "recover", "Searching, Devices, Not paired: pair again, Forget."),
    ("journey-recovery", "F. Recovery and management", "shipped", "recover", "Revoke all, Block, reinstall, lost key, permissions or Bluetooth lost mid-use."),
]
PHASES = [("setup", "1 · Set up"), ("connect", "2 · Connect"), ("use", "3 · Use"), ("recover", "4 · Recover")]
def index_page():
    def card(jid, t, st, ph, d):
        b = BADGES[st].replace(' data-dc-id="status"', "")
        return f'<a class="jcard raised" href="{jid}.html" style="color:inherit;text-decoration:none" data-dc-id="idx-{jid}"><div>{b}</div><div class="t">{t}</div><div class="d">{d}</div><div class="id">{jid}</div></a>'
    cols = "".join(f'<div class="jphase" data-dc-id="phase-{k}"><div class="jgroup" style="margin-top:0">{lbl}</div>' + "".join(card(*j) for j in JOURNEYS if j[3] == k) + "</div>" for k, lbl in PHASES)
    body = f'''<div class="jb" data-dc-id="journeys-index">
  <div class="jb-head"><h1 data-dc-id="title">Mote user journeys</h1></div>
  <p class="jb-sub" data-dc-id="summary">Every path a user takes, in order: set up the Mac and the phone, connect, use, recover. Each storyboard reads left to right; rows under a yellow IF diamond are the failure and alternative branches. Blue dots are taps, dashed rings are holds.</p>
  <div class="legend">{BADGES["shipped"]}{BADGES["next"]}{BADGES["labs"]}{BADGES["legacy"]}</div>
  <div class="jcols" data-dc-id="journey-map">{cols}</div>
</div>'''
    return jpage("User journeys", body)

JFILES = {j[0]: None for j in JOURNEYS}
JFILES.update({"journey-first-run-bt": j01, "journey-wifi-helper": j02, "journey-tailscale": j03, "journey-reconnect": j04,
          "journey-spaces": j05, "journey-macros": j06, "journey-media": j07, "journey-instant-pairing": j08,
          "journey-smart-remote": j09, "journey-open-tv": j10, "journey-video-bridge": j11, "journey-live-screen": j12,
          "journey-setup-mac": jA, "journey-mac-update": jA2, "journey-setup-phone": jB, "journey-connect-now": jC,
          "journey-bt-quirks": jC2, "journey-connect-next": jD, "journey-daily": jE, "journey-recovery": jF, "journey-settings": jG})

# Gallery index (served at /mote/design/): grouped like design.json, lazy scaled iframe thumbnails, #<id> deep links.
import json, html as _h
GROUP_TITLES = {"media": "Media", "pad": "Mouse", "keys": "Keys", "macros": "Macros", "connection": "Connection",
                "journeys-shipped": "User journeys", "journeys-labs": "User journeys (Labs)"}
def gallery_page():
    d = json.loads((OUT / "design.json").read_text())
    groups = {}
    for a in d["artboards"]:
        groups.setdefault(a.get("group", "misc"), []).append(a)
    secs = []
    for g, boards in groups.items():
        labs = g.endswith("labs")
        badge = '<span class="bd labs">Preview (Labs)</span>' if labs else '<span class="bd">Shipped</span>'
        tw = 480 if boards[0]["width"] > 600 else 220
        cards = []
        for a in boards:
            w, h = a["width"], a.get("height", 915)
            sc = tw / w
            cards.append(
                f'<a class="card" id="{a["id"]}" href="{a["file"]}" style="width:{tw}px">'
                f'<div class="thumb" style="height:{round(h*sc)}px"><iframe src="{a["file"]}" loading="lazy" tabindex="-1" title="{_h.escape(a["title"])}" '
                f'style="width:{w}px;height:{h}px;transform:scale({sc:.5f})"></iframe></div>'
                f'<div class="cap"><b>{_h.escape(a["title"])}</b><code>#{a["id"]}</code></div></a>')
        secs.append(f'<section id="g-{g}"><h2>{GROUP_TITLES.get(g, g)} {badge}</h2><div class="row">{"".join(cards)}</div></section>')
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mote design</title>
<style>
@font-face{{font-family:Unbounded;font-weight:900;src:url(fonts/Unbounded-Black.woff2) format("woff2")}}
:root{{--bg:#E3E5E8;--s1:#E9EAED;--ink:#1C1D20;--mut:#50535A;--acc:#1D6FB8;--edge:#C3C6CB}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}}
header,section{{max-width:1500px;margin:0 auto;padding:24px 28px}}
.wm{{font:900 22px Unbounded,system-ui;letter-spacing:.12em;color:var(--ink);text-decoration:none}}
h1{{margin:.4em 0 .2em;font-size:28px}} p{{color:var(--mut);max-width:70ch;margin:.2em 0}} a{{color:var(--acc)}}
h2{{font-size:18px;border-top:1px solid var(--edge);padding-top:18px;display:flex;gap:10px;align-items:center}}
.bd{{font:600 11px system-ui;letter-spacing:.04em;text-transform:uppercase;padding:2px 8px;border-radius:99px;background:#D3E3F3;color:#14578F}}
.bd.labs{{background:#FFE9A8;color:#6B4E00}}
.row{{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-start}}
.card{{display:block;text-decoration:none;color:inherit;background:var(--s1);border:1px solid var(--edge);border-radius:14px;overflow:hidden;box-shadow:0 1px 0 #fff inset,0 4px 10px rgba(20,28,40,.08)}}
.card:hover,.card:target{{outline:2px solid var(--acc)}}
.thumb{{overflow:hidden;position:relative;background:#D2D5D9}}
.thumb iframe{{border:0;transform-origin:0 0;pointer-events:none;position:absolute;left:0;top:0}}
.cap{{padding:8px 12px;display:flex;justify-content:space-between;gap:8px;font-size:13px}}
.cap code{{color:var(--mut);font-size:12px}}
</style>
</head>
<body>
<header><a class="wm" href="../">MOTE</a><h1>Design</h1>
<p>{_h.escape(d["description"].split(" Tokens in")[0])} Click a board to open it full size; link to one with <code>#&lt;id&gt;</code>. PNG copies live in the <a href="https://github.com/tusharpandey13/mote/tree/main/design/exports">repo</a>.</p></header>
{"".join(secs)}
</body>
</html>
"""

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
    "spaces-carousel.html": page("Spaces carousel", screen(media_inner(), "media", overlay=spaces_sheet()), MEDIA_CSS + SPACES_CSS),
    "dpad-press.html": dpad_press_page(),
    "haptics.html": haptics_page(),
}
files["journeys-index.html"] = index_page()
files["index.html"] = gallery_page()
for jid, fn in JFILES.items():
    files[jid + ".html"] = fn()
for name, html in files.items():
    (OUT / name).write_text(html)
print("wrote", len(files))
