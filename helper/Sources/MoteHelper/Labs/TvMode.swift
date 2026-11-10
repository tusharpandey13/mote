import AppKit
import ApplicationServices
import WebKit

// Labs (preview): "Open…" and TV mode. Messages `labs.open.*` / `labs.tv.*`, documented in
// docs/labs/open-tv.md (not part of docs/protocol.md). Off by default: menu › Labs › TV mode.

/// URL checks shared by every labs message. Only http(s) with a host; nothing else ever reaches
/// NSWorkspace or the TV window (no file:, javascript:, data:, custom schemes, no user:pass@).
enum LabsURL {
    static let maxLength = 2048

    static func validate(_ raw: String) throws -> URL {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty, s.utf8.count <= maxLength else { throw ProtoError("bad", "url must be 1-\(maxLength) bytes") }
        guard s.unicodeScalars.allSatisfy({ $0.value > 0x20 && $0.value != 0x7F }) else { throw ProtoError("bad", "url has spaces or control characters") }
        guard let u = URLComponents(string: s), let scheme = u.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            throw ProtoError("bad", "only http and https links can be opened")
        }
        guard let host = u.host, !host.isEmpty else { throw ProtoError("bad", "url needs a host") }
        guard u.user == nil, u.password == nil else { throw ProtoError("bad", "urls with a user name or password are refused") }
        guard let url = u.url else { throw ProtoError("bad", "malformed url") }
        return url
    }

    static func isYouTube(_ url: URL) -> Bool {
        guard let h = url.host?.lowercased() else { return false }
        return h == "youtu.be" || h == "youtube.com" || h.hasSuffix(".youtube.com")
    }

    private static func videoID(_ s: String?) -> String? {
        guard let s, s.count == 11, s.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        return s
    }

    /// The leanback (youtube.com/tv) equivalent of a YouTube link: watch / shorts / live / youtu.be and
    /// search results map to `#/watch?v=` and `#/search?q=`; anything else opens the TV home.
    static func tvURL(_ url: URL) -> URL {
        let home = URL(string: "https://www.youtube.com/tv")!
        guard isYouTube(url), let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        let q = c.queryItems ?? []
        let path = c.path.split(separator: "/").map(String.init)
        if c.path.hasPrefix("/tv") { return url }
        var id: String?
        if url.host?.lowercased() == "youtu.be" { id = videoID(path.first) }
        else if path.first == "watch" { id = videoID(q.first { $0.name == "v" }?.value) }
        else if ["shorts", "live", "embed"].contains(path.first ?? ""), path.count > 1 { id = videoID(path[1]) }
        if let id { return URL(string: "https://www.youtube.com/tv#/watch?v=\(id)") ?? home }
        if path.first == "results", let term = q.first(where: { $0.name == "search_query" })?.value, !term.isEmpty {
            var s = CharacterSet.alphanumerics; s.insert(charactersIn: "-._~")
            return URL(string: "https://www.youtube.com/tv#/search?q=\(term.addingPercentEncoding(withAllowedCharacters: s) ?? "")") ?? home
        }
        return home
    }
}

/// TV mode: a helper-owned fullscreen WKWebView on the active display (YouTube leanback, or the
/// bundled 10-foot launcher), driven by `labs.tv.key`. Plus `labs.open.url` / `labs.open.action`.
/// Main-queue only. With --dry-run nothing opens: every action is logged as `DRY labs ...`.
final class TvMode: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, NSWindowDelegate {
    // YouTube serves its leanback UI to Cobalt (the TV app runtime); a desktop UA is redirected to youtube.com.
    static let cobaltUA = "Mozilla/5.0 (X11; Linux armv7l) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
    static let safariUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    static let keys: Set<String> = ["up", "down", "left", "right", "ok", "back", "home", "playpause", "search"]
    static let maxPins = 12

    private let dryRun: Bool
    private let injector: Injector
    private let defaults: UserDefaults?
    private let forced: Bool
    /// CGDirectDisplayID of the active display (Spaces), if known.
    var activeDisplay: () -> CGDirectDisplayID? = { nil }
    /// TV state or the Labs toggle changed: push `labs.tv.state`, refresh the menu.
    var onChange: (() -> Void)?

    /// Menu › Labs › TV mode. Off by default; `--labs-tv` forces it on (tests).
    var enabled: Bool {
        didSet {
            guard enabled != oldValue else { return }
            defaults?.set(enabled, forKey: "labs.tvMode")
            log("labs: TV mode \(enabled ? "enabled" : "disabled")")
            ev(.labs, "toggle", ["lab": "tvMode", "on": enabled])
            if !enabled && on { exit() } else { onChange?() }
        }
    }

    private(set) var on = false
    private var page = "home"            // home | youtube | web
    private var current: String?
    private var pins: [[String: String]] = TvMode.defaultPins
    private var window: TvWindow?
    private var web: WKWebView?

    static let defaultPins: [[String: String]] = [
        ["title": "YouTube", "url": "https://www.youtube.com/tv"],
        ["title": "Netflix", "url": "https://www.netflix.com/browse"],
        ["title": "Prime Video", "url": "https://www.primevideo.com"],
        ["title": "Disney+", "url": "https://www.disneyplus.com"],
        ["title": "Twitch", "url": "https://www.twitch.tv"],
        ["title": "Spotify", "url": "https://open.spotify.com"],
    ]

    init(config: Config, injector: Injector) {
        dryRun = config.dryRun
        self.injector = injector
        defaults = config.memoryStore ? nil : UserDefaults.standard
        forced = config.labsTv
        enabled = config.labsTv || (defaults?.bool(forKey: "labs.tvMode") ?? false)
        super.init()
    }

    func state() -> [String: Any] {
        var m: [String: Any] = ["t": "labs.tv.state", "enabled": enabled, "on": on, "page": page]
        if on, let current { m["url"] = current }
        return m
    }

    /// TV mode is on screen and focused: plain `key` arrows / return / esc go to the web view.
    var frontmost: Bool { on && (dryRun || (window?.isKeyWindow == true && NSApp.isActive)) }

    // MARK: messages

    func handle(_ m: [String: Any]) throws {
        let t = try m.str("t")
        guard enabled else { throw ProtoError("disabled", "Turn on Labs › TV mode in the Mote Helper menu") }
        switch t {
        case "labs.open.url":
            let url = try LabsURL.validate(m.str("url"))
            let mode = try m.str("mode", "browser")
            switch mode {
            case "browser": openInBrowser(url, fullscreen: (m["fullscreen"] as? Bool) ?? false)
            case "tv": open(url: url)
            default: throw ProtoError("bad", "mode must be browser or tv")
            }
        case "labs.open.action":
            try action(m.str("a"))
        case "labs.tv.open":
            if let raw = m["pins"] { pins = try parsePins(raw) }
            if let s = try m.optStr("url") { open(url: try LabsURL.validate(s)) } else { open(url: nil) }
        case "labs.tv.key":
            let k = try m.str("k")
            guard TvMode.keys.contains(k) else { throw ProtoError("bad", "unknown TV key '\(k)'") }
            guard on else { throw ProtoError("tv-off", "TV mode is not open") }
            key(k)
        case "labs.tv.exit":
            exit()
        default:
            throw ProtoError("unknown", "unknown message '\(t)'")
        }
    }

    /// Hub hook: while TV mode is frontmost, D-pad `key` taps go to the web view, not global injection.
    func intercept(_ m: [String: Any]) -> Bool {
        guard frontmost, m["t"] as? String == "key", (try? m.int("mods", 0)) == 0 else { return false }
        let map: [Int: String] = [0x52: "up", 0x51: "down", 0x50: "left", 0x4F: "right", 0x28: "ok", 0x29: "back"]
        guard let usage = try? m.int("usage"), let k = map[usage] else { return false }
        let action = (try? m.str("action", "tap")) ?? "tap"
        if action != "up" { key(k) }   // tap / down (and its repeats) press once each
        return true
    }

    private func parsePins(_ raw: Any) throws -> [[String: String]] {
        guard let a = raw as? [[String: Any]], a.count <= TvMode.maxPins else { throw ProtoError("bad", "pins: up to \(TvMode.maxPins) {title, url}") }
        return try a.map { p in
            let title = String(((p["title"] as? String) ?? "").prefix(40))
            let url = try LabsURL.validate((p["url"] as? String) ?? "")
            return ["title": title.isEmpty ? (url.host ?? "Site") : title, "url": url.absoluteString]
        }
    }

    // MARK: browser

    private func openInBrowser(_ url: URL, fullscreen: Bool) {
        if dryRun { log("DRY labs.open.url mode=browser fullscreen=\(fullscreen) url=\(url.absoluteString)"); return }
        if on { exit() }
        log("labs: open \(url.host ?? "") in the default browser")
        let cfg = NSWorkspace.OpenConfiguration()
        cfg.activates = true
        NSWorkspace.shared.open(url, configuration: cfg) { app, err in
            if let err { log("labs: open failed: \(err.localizedDescription)"); return }
            guard fullscreen, let pid = app?.processIdentifier else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { _ = TvMode.setFullscreen(true, pid: pid) }
        }
    }

    /// Sets AXFullScreen on the focused window of [pid]. Returns its previous value (nil = no window / no AX).
    @discardableResult
    static func setFullscreen(_ value: Bool, pid: pid_t) -> Bool? {
        let app = AXUIElementCreateApplication(pid)
        var w: CFTypeRef?
        guard AXUIElementCopyAttributeValue(app, kAXFocusedWindowAttribute as CFString, &w) == .success, let win = w,
              CFGetTypeID(win) == AXUIElementGetTypeID() else { return nil }
        let el = win as! AXUIElement
        var cur: CFTypeRef?
        let was = AXUIElementCopyAttributeValue(el, "AXFullScreen" as CFString, &cur) == .success && (cur as? Bool) == true
        if was != value { AXUIElementSetAttributeValue(el, "AXFullScreen" as CFString, value as CFBoolean) }
        return was
    }

    private func action(_ a: String) throws {
        guard ["closeTab", "exitFullscreen", "pauseAll"].contains(a) else { throw ProtoError("bad", "unknown action '\(a)'") }
        if dryRun { log("DRY labs.open.action a=\(a) tv=\(on)"); if a != "pauseAll" && frontmost { exit() }; return }
        switch a {
        case "closeTab", "exitFullscreen" where frontmost:
            exit()
        case "closeTab":
            try injector.key(usage: 0x1A, mods: 8, action: "tap")            // ⌘W
        case "exitFullscreen":
            let pid = NSWorkspace.shared.frontmostApplication?.processIdentifier
            if pid.flatMap({ TvMode.setFullscreen(false, pid: $0) }) != true {
                try injector.key(usage: 0x29, mods: 0, action: "tap")        // Esc: leaves HTML5 video fullscreen
            }
        default: // pauseAll
            if on, let web {
                web.evaluateJavaScript("document.querySelectorAll('video,audio').forEach(function(v){v.pause()})")
                web.pauseAllMediaPlayback(completionHandler: nil)
            } else {
                try injector.consumer("play")   // one play/pause to whatever is Now Playing
            }
        }
    }

    // MARK: TV window

    /// Opens (or reuses) the TV window. nil = the launcher; YouTube links go to leanback.
    func open(url: URL?) {
        let target = url.map { LabsURL.isYouTube($0) ? LabsURL.tvURL($0) : $0 }
        let wasOn = on
        on = true
        page = target == nil ? "home" : LabsURL.isYouTube(target!) ? "youtube" : "web"
        current = target?.absoluteString
        if dryRun {
            log("DRY labs.tv open page=\(page) url=\(current ?? "home")")
            onChange?(); return
        }
        if !wasOn { log("labs: TV mode on") }
        let w = window ?? makeWindow()
        place(w)
        load(target)
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
        if let web { w.makeFirstResponder(web) }
        onChange?()
    }

    func exit() {
        guard on else { onChange?(); return }
        on = false; page = "home"; current = nil
        if dryRun { log("DRY labs.tv exit"); onChange?(); return }
        log("labs: TV mode off")
        web?.pauseAllMediaPlayback(completionHandler: nil)
        web?.stopLoading()
        NSApp.presentationOptions = []
        if let w = window { window = nil; w.delegate = nil; w.close() }
        web = nil
        onChange?()
    }

    private func makeWindow() -> TvWindow {
        let cfg = WKWebViewConfiguration()
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.websiteDataStore = .default()   // sign-ins and "Watch as guest" survive between sessions
        cfg.userContentController.add(self, name: "mote")
        let wv = WKWebView(frame: .zero, configuration: cfg)
        wv.navigationDelegate = self
        wv.uiDelegate = self
        wv.allowsBackForwardNavigationGestures = false
        let w = TvWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 720), styleMask: [.borderless], backing: .buffered, defer: false)
        w.isReleasedWhenClosed = false
        w.backgroundColor = .black
        w.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        w.contentView = wv
        w.delegate = self
        window = w; web = wv
        return w
    }

    /// Covers the active display (Spaces' notion: frontmost window, else the cursor), else the main screen.
    private func place(_ w: NSWindow) {
        let id = activeDisplay()
        let screen = NSScreen.screens.first { ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value == id }
            ?? NSScreen.main ?? NSScreen.screens.first
        if let screen { w.setFrame(screen.frame, display: true) }
    }

    private func load(_ url: URL?) {
        guard let web else { return }
        web.pageZoom = 1
        guard let url else {
            web.customUserAgent = TvMode.safariUA
            web.loadHTMLString(TvMode.launcherHTML(pins), baseURL: nil)
            return
        }
        web.customUserAgent = LabsURL.isYouTube(url) ? TvMode.cobaltUA : TvMode.safariUA
        web.load(URLRequest(url: url))
    }

    // MARK: keys

    private func key(_ k: String) {
        if dryRun { log("DRY labs.tv key \(k)"); return }
        guard let w = window, let web else { return }
        switch k {
        case "home": open(url: nil); return
        case "search": open(url: URL(string: "https://www.youtube.com/tv#/search")); return
        case "playpause":
            web.evaluateJavaScript("(function(){var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();return true}return false})()") { [weak self] r, _ in
                if (r as? Bool) != true { self?.key("ok") }   // no <video> yet: OK toggles the leanback player
            }
            return
        default: break
        }
        let (code, chars): (UInt16, String) = switch k {
        case "up": (126, String(UnicodeScalar(NSUpArrowFunctionKey)!))
        case "down": (125, String(UnicodeScalar(NSDownArrowFunctionKey)!))
        case "left": (123, String(UnicodeScalar(NSLeftArrowFunctionKey)!))
        case "right": (124, String(UnicodeScalar(NSRightArrowFunctionKey)!))
        case "ok": (36, "\r")
        default: (53, "\u{1b}")   // back = Escape (leanback treats it as Back)
        }
        let flags: NSEvent.ModifierFlags = code >= 123 ? [.numericPad, .function] : []
        w.makeFirstResponder(web)
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            if let e = NSEvent.keyEvent(with: type, location: .zero, modifierFlags: flags, timestamp: ProcessInfo.processInfo.systemUptime,
                                        windowNumber: w.windowNumber, context: nil, characters: chars, charactersIgnoringModifiers: chars,
                                        isARepeat: false, keyCode: code) {
                w.sendEvent(e)
            }
        }
    }

    // MARK: WebKit

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let scheme = action.request.url?.scheme?.lowercased() ?? ""
        // The launcher is loadHTMLString (about:blank); pages may only go to http(s) / about / blob / data frames.
        let ok = ["http", "https", "about", "blob"].contains(scheme) || (scheme == "data" && action.targetFrame?.isMainFrame == false)
        if !ok { log("labs: TV blocked navigation to a \(scheme): url") }
        decisionHandler(ok ? .allow : .cancel)
    }

    /// target=_blank / window.open: load in the same view instead of a second window.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let u = action.request.url, (try? LabsURL.validate(u.absoluteString)) != nil { webView.load(action.request) }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let u = webView.url, on else { return }
        // Fallback: if YouTube ever redirects the TV UA to the desktop site, make that readable from the couch.
        if LabsURL.isYouTube(u), !u.path.hasPrefix("/tv") { webView.pageZoom = 1.75; page = "youtube-desktop" }
        if u.scheme?.hasPrefix("http") == true, u.absoluteString != current { current = u.absoluteString; onChange?() }
    }

    /// Launcher tiles post `{url}`. Only honoured while the launcher (about:blank) is showing.
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        guard page == "home", message.frameInfo.isMainFrame, web?.url == nil || web?.url?.scheme == "about",
              let s = message.body as? String, let url = try? LabsURL.validate(s) else { return }
        if LabsURL.isYouTube(url) { open(url: url) }
        // DRM sites (Netflix, Prime, Disney+) are not expected to play in a WKWebView: hand them to the browser.
        else { openInBrowser(url, fullscreen: true) }
    }

    func windowDidBecomeKey(_ n: Notification) {
        if window?.screen == NSScreen.screens.first { NSApp.presentationOptions = [.autoHideMenuBar, .autoHideDock] }
    }
    func windowDidResignKey(_ n: Notification) { NSApp.presentationOptions = [] }

    // MARK: launcher

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "'", with: "&#39;")
    }

    /// 10-foot home: big focusable tiles, arrows move focus, return opens. Titanium (dark) tokens from
    /// design/mote-v1/mote.css; self-contained (no network, no file access).
    static func launcherHTML(_ pins: [[String: String]]) -> String {
        let tiles = pins.enumerated().map { i, p in
            let title = esc(p["title"] ?? ""), url = esc(p["url"] ?? "")
            let host = esc(URL(string: p["url"] ?? "")?.host?.replacingOccurrences(of: "www.", with: "") ?? "")
            return "<button class=\"tile\" data-url=\"\(url)\"\(i == 0 ? " autofocus" : "")><span class=\"name\">\(title)</span><span class=\"host\">\(host)</span></button>"
        }.joined()
        return """
        <!doctype html><html class="dark"><head><meta charset="utf-8"><title>Mote TV</title>
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>
        :root{--chassis-top:#2C2E33;--chassis-bot:#1C1D21;--key-top:#3E4148;--key-bot:#31343A;--key-edge:#15161A;--ink:#E9EAEC;--muted:#A9ACB2;--subtle:#8F939A;--accent:#6CC4FF;--glow:rgba(108,196,255,.55);--well:#141518}
        *{box-sizing:border-box}html,body{margin:0;height:100%;cursor:none}
        body{background:linear-gradient(180deg,var(--chassis-top),var(--chassis-bot));color:var(--ink);font:500 2.2vh/1.4 Inter,-apple-system,system-ui,sans-serif;display:flex;flex-direction:column;padding:7vh 7vw}
        header{display:flex;align-items:baseline;gap:2vw;margin-bottom:6vh}
        .mark{font-weight:900;font-size:5vh;letter-spacing:.08em;color:var(--ink);text-shadow:0 -1px 0 rgba(0,0,0,.6)}
        .preview{font-size:1.6vh;font-weight:600;letter-spacing:.14em;text-transform:uppercase;color:var(--accent);border:1px solid var(--accent);border-radius:99px;padding:.3vh 1.2vh}
        .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:3.5vh 2.5vw;flex:1;align-content:start}
        .tile{appearance:none;border:0;border-radius:3.2vh;min-height:24vh;padding:3vh 2.5vw;text-align:left;display:flex;flex-direction:column;justify-content:flex-end;gap:.6vh;color:var(--ink);
          background:linear-gradient(180deg,var(--key-top),var(--key-bot));box-shadow:0 1px 0 var(--key-edge),0 2px 2px rgba(0,0,0,.45),0 6px 12px -4px rgba(0,0,0,.55),inset 0 1px 0 rgba(255,255,255,.10);
          transition:transform 150ms cubic-bezier(.2,0,0,1),box-shadow 150ms;font:inherit;outline:none}
        .tile:focus{transform:scale(1.05);box-shadow:0 0 0 .5vh var(--accent),0 0 4vh var(--glow),0 6px 12px -4px rgba(0,0,0,.55)}
        .name{font-size:4.2vh;font-weight:700}.host{font-size:2vh;color:var(--muted)}
        footer{color:var(--subtle);font-size:1.9vh;margin-top:4vh}
        </style></head><body>
        <header><span class="mark">MOTE</span><span class="preview">TV mode · Preview</span></header>
        <main class="grid">\(tiles)</main>
        <footer>Arrows move · OK opens · Back on YouTube goes back · Exit TV mode from the phone or the helper menu</footer>
        <script>
        (function(){var t=[].slice.call(document.querySelectorAll('.tile'));var cols=3;
        function f(i){if(t[i])t[i].focus()}f(0);
        t.forEach(function(b){b.addEventListener('click',function(){window.webkit.messageHandlers.mote.postMessage(b.dataset.url)})});
        document.addEventListener('keydown',function(e){var i=t.indexOf(document.activeElement);if(i<0){f(0);return}
        var d={ArrowLeft:-1,ArrowRight:1,ArrowUp:-cols,ArrowDown:cols}[e.key];
        if(d!==undefined){e.preventDefault();var n=i+d;if(n>=0&&n<t.length)f(n)}else if(e.key==='Enter'){e.preventDefault();t[i].click()}});})();
        </script></body></html>
        """
    }
}

/// Borderless windows can't become key by default; the TV window must, to get key events.
final class TvWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
    override func cancelOperation(_ sender: Any?) {}   // Esc is the page Back; no beep
}
