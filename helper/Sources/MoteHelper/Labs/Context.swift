import AppKit
import ApplicationServices
import Foundation

// Context (frontmost app / browser tab; Labs, off by default) and now playing (core, ON by default; menu: "Share now playing").
// Messages `labs.ctx.*` and `labs.np.*`, documented in docs/labs/context.md (not part of protocol.md).

/// Persisted Labs toggles. With --memory-store nothing is read or written (tests get the defaults).
final class LabsContextSettings {
    private let d: UserDefaults?
    var enabled: Bool { didSet { d?.set(enabled, forKey: "labs.context.enabled") } }
    /// Now playing (title/artist/position of what plays on the Mac) is a core feature: on unless the user turned it off
    /// (menu "Share now playing"). Independent of the context lab above.
    var npEnabled: Bool { didSet { d?.set(npEnabled, forKey: "nowPlaying.enabled") } }
    /// Privacy: off (default) sends only the host of a browser tab and no window titles.
    var fullDetails: Bool { didSet { d?.set(fullDetails, forKey: "labs.context.fullDetails") } }

    init(config: Config) {
        d = config.memoryStore ? nil : UserDefaults.standard
        enabled = d?.bool(forKey: "labs.context.enabled") ?? false
        npEnabled = (d?.object(forKey: "nowPlaying.enabled") as? Bool) ?? true
        fullDetails = d?.bool(forKey: "labs.context.fullDetails") ?? false
    }
}

/// What is in front on the Mac.
struct AppContext: Equatable {
    var app = ""
    var bundle = ""
    var title: String?
    var url: String?
    var browser = false
    var text = false        // the focused element takes text input
    var secure = false      // ... and it is a password field
    var automation = "n/a"  // browsers: granted | ask | denied | n/a

    var host: String? { url.flatMap { URL(string: $0)?.host?.lowercased() } }

    /// The `labs.ctx` fields. Without [full] the URL becomes its host and titles are dropped.
    func message(full: Bool) -> [String: Any] {
        var m: [String: Any] = ["t": "labs.ctx", "enabled": true, "app": app, "bundle": bundle, "browser": browser,
                                "text": text, "secure": secure, "automation": automation, "full": full]
        if let host { m["host"] = host }
        if full, let url { m["url"] = url }
        if full, let title { m["title"] = title }
        return m
    }
}

/// Browsers whose active tab can be read with AppleScript (Firefox has no tab scripting: title from AX only).
enum Browsers {
    static let chromium: Set<String> = ["com.google.Chrome", "com.google.Chrome.beta", "com.google.Chrome.canary",
                                        "company.thebrowser.Browser", "com.microsoft.edgemac", "com.brave.Browser", "com.vivaldi.Vivaldi"]
    static let safari: Set<String> = ["com.apple.Safari", "com.apple.SafariTechnologyPreview"]
    static let firefox: Set<String> = ["org.mozilla.firefox", "org.mozilla.firefoxdeveloperedition", "org.mozilla.nightly"]

    static func isBrowser(_ b: String) -> Bool { chromium.contains(b) || safari.contains(b) || firefox.contains(b) }
    static func scriptable(_ b: String) -> Bool { chromium.contains(b) || safari.contains(b) }

    static func tabScript(_ b: String) -> String? {
        if chromium.contains(b) { return "tell application id \"\(b)\" to if (count of windows) > 0 then return {URL, title} of active tab of front window" }
        if safari.contains(b) { return "tell application id \"\(b)\" to if (count of windows) > 0 then return {URL, name} of current tab of front window" }
        return nil
    }

    /// [js] must not contain double quotes or backslashes (it is embedded in an AppleScript string).
    static func jsScript(_ b: String, _ js: String) -> String? {
        if chromium.contains(b) { return "tell application id \"\(b)\" to execute active tab of front window javascript \"\(js)\"" }
        if safari.contains(b) { return "tell application id \"\(b)\" to do JavaScript \"\(js)\" in current tab of front window" }
        return nil
    }

    /// Automation (Apple Events) permission for [bundle]; [ask] shows the macOS prompt once. Blocks while asking.
    static func automation(_ bundle: String, ask: Bool) -> String {
        let target = NSAppleEventDescriptor(bundleIdentifier: bundle)
        guard let desc = target.aeDesc else { return "n/a" }
        switch AEDeterminePermissionToAutomateTarget(desc, typeWildCard, typeWildCard, ask) {
        case noErr: return "granted"
        case OSStatus(errAEEventWouldRequireUserConsent): return "ask"
        case OSStatus(errAEEventNotPermitted): return "denied"
        default: return "n/a" // not running (procNotFound) or unknown
        }
    }
}

/// Accessibility reads (needs the Accessibility permission the helper already has).
enum AXRead {
    static let textRoles: Set<String> = ["AXTextField", "AXTextArea", "AXComboBox", "AXSearchField"]

    static func attr(_ e: AXUIElement, _ a: String) -> CFTypeRef? {
        var v: CFTypeRef?
        return AXUIElementCopyAttributeValue(e, a as CFString, &v) == .success ? v : nil
    }

    /// Whether the focused element takes text: text roles, or editable web content (contenteditable).
    static func focusedText() -> (text: Bool, secure: Bool) {
        let sys = AXUIElementCreateSystemWide()
        AXUIElementSetMessagingTimeout(sys, 0.3)
        guard let f = attr(sys, kAXFocusedUIElementAttribute), CFGetTypeID(f) == AXUIElementGetTypeID() else { return (false, false) }
        let e = f as! AXUIElement
        let role = attr(e, kAXRoleAttribute) as? String ?? ""
        let sub = attr(e, kAXSubroleAttribute) as? String ?? ""
        if textRoles.contains(role) || sub == "AXSearchField" || sub == "AXSecureTextField" { return (true, sub == "AXSecureTextField") }
        if attr(e, "AXEditableAncestor") != nil { return (true, false) }
        var settable: DarwinBoolean = false
        if role == "AXWebArea" || role == "AXGroup", AXUIElementIsAttributeSettable(e, kAXValueAttribute as CFString, &settable) == .success, settable.boolValue {
            return (true, false)
        }
        return (false, false)
    }

    static func windowTitle(_ pid: pid_t) -> String? {
        let app = AXUIElementCreateApplication(pid)
        AXUIElementSetMessagingTimeout(app, 0.3)
        guard let w = attr(app, kAXFocusedWindowAttribute), CFGetTypeID(w) == AXUIElementGetTypeID() else { return nil }
        return (attr(w as! AXUIElement, kAXTitleAttribute) as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Chromium and Electron only build their web accessibility tree when asked to.
    static func enableWebAX(_ pid: pid_t) {
        AXUIElementSetAttributeValue(AXUIElementCreateApplication(pid), "AXManualAccessibility" as CFString, kCFBooleanTrue)
    }
}

private final class WeakSession { weak var s: Session?; init(_ s: Session) { self.s = s } }

/// The Labs module: context watcher + now playing. Main-queue only, except the AppleScript queue.
final class LabsContext: NSObject, LabsModule {
    let prefixes = ["labs.ctx.", "labs.np."]
    private unowned let hub: Hub
    let settings: LabsContextSettings
    private let fake: Bool
    private var ctxSubs: [ObjectIdentifier: WeakSession] = [:]
    private var npSubs: [ObjectIdentifier: WeakSession] = [:]

    // context
    private var pollTimer: Timer?
    private var debounce: DispatchWorkItem?
    private var pending: AppContext?
    private(set) var last: AppContext?
    private var tabCache: [String: (url: String?, title: String?)] = [:]
    private var automation: [String: String] = [:]
    private var scriptBusy = false
    private var lastScript = Date.distantPast
    private var webAX: Set<pid_t> = []
    private let scriptQ = DispatchQueue(label: "mote.labs.applescript")
    private var fakeMtime: Date?
    /// Most recent browser and its automation state, for the menu.
    private var menuBrowser: (name: String, state: String)?

    // now playing
    let nowPlaying: NowPlaying

    static let debounceMs = 250

    init(hub: Hub) {
        self.hub = hub
        settings = LabsContextSettings(config: hub.config)
        let fakeCtx = hub.config.dryRun && hub.config.fakeContext != nil
        let fakeNp = hub.config.dryRun && hub.config.fakeNowPlaying
        fake = fakeCtx
        nowPlaying = NowPlaying(fake: fakeNp, dryRun: hub.config.dryRun, reader: hub.config.dryRun ? hub.config.fakeNowPlayingReader : nil)
        super.init()
        if (hub.config.fakeContext != nil || hub.config.fakeNowPlaying) && !hub.config.dryRun { log("labs: --fake-context / --fake-nowplaying need --dry-run; ignoring them") }
        if fakeCtx || fakeNp { if fakeCtx { settings.enabled = true }; if fakeNp { settings.npEnabled = true }; log("labs: context & now playing on (fake\(fakeCtx ? " context" : "")\(fakeNp ? " now-playing" : ""))") }
        nowPlaying.onUpdate = { [weak self] in self?.broadcastNp() }
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(appActivated), name: NSWorkspace.didActivateApplicationNotification, object: nil)
        // Housekeeping: drop closed sessions, stop work nobody listens to.
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.prune() }
    }

    // MARK: messages

    func handle(_ t: String, _ m: [String: Any], from s: Session) {
        switch t {
        case "labs.ctx.sub":
            let on = (m["on"] as? Bool) ?? true
            if on { ctxSubs[ObjectIdentifier(s)] = WeakSession(s) } else { ctxSubs[ObjectIdentifier(s)] = nil }
            ev(.labs, "subscribe", ["lab": "context", "on": on])
            updateRunning()
            if on { s.send(ctxState()) }
        case "labs.np.sub":
            let on = (m["on"] as? Bool) ?? true
            if on { npSubs[ObjectIdentifier(s)] = WeakSession(s) } else { npSubs[ObjectIdentifier(s)] = nil }
            ev(.labs, "subscribe", ["lab": "nowPlaying", "on": on])
            updateRunning()
            if on { s.send(npState()) }
        case "labs.np.seek": seek(m, from: s)
        default: s.send(["t": "err", "code": "unknown", "msg": "unknown message", "ref": t])
        }
    }

    private func ctxState() -> [String: Any] {
        guard settings.enabled else { return ["t": "labs.ctx", "enabled": false] }
        return (last ?? AppContext()).message(full: settings.fullDetails)
    }

    private func npState() -> [String: Any] {
        guard settings.npEnabled else { return ["t": "labs.np", "enabled": false] }
        // `now` (Mac clock, ms) lets clients extrapolate from `np.at` without trusting the phone's clock.
        var m: [String: Any] = ["t": "labs.np", "enabled": true, "source": nowPlaying.status, "seek": seekPaths(),
                                "now": Int64(Date().timeIntervalSince1970 * 1000)]
        m["np"] = nowPlaying.info ?? NSNull()
        return m
    }

    /// Seek paths that could work right now, best first.
    private func seekPaths() -> [String] {
        var p: [String] = []
        if nowPlaying.canSeek { p.append(nowPlaying.status == "fake" ? "fake" : "mediaremote") }
        if let c = last, Browsers.scriptable(c.bundle) { p.append("js") }
        p.append("keys")
        return p
    }

    private func live(_ subs: inout [ObjectIdentifier: WeakSession]) -> [Session] {
        let authed = Set(hub.authedSessions.map(ObjectIdentifier.init))
        subs = subs.filter { $0.value.s != nil && authed.contains($0.key) }
        return subs.values.compactMap(\.s)
    }

    /// Diagnostics: is [s] subscribed to context / now playing.
    func subscription(_ s: Session) -> (ctx: Bool, np: Bool) { (ctxSubs[ObjectIdentifier(s)]?.s != nil, npSubs[ObjectIdentifier(s)]?.s != nil) }
    private func broadcastCtx() { let st = ctxState(); let ss = live(&ctxSubs); ev(.labs, "push", coalesce: "ctx", ["lab": "context", "sessions": ss.count]); for s in ss { s.send(st) } }
    private func broadcastNp() { let st = npState(); let ss = live(&npSubs); ev(.labs, "push", coalesce: "np", ["lab": "nowPlaying", "sessions": ss.count]); for s in ss { s.send(st) } }

    private func prune() {
        let before = ctxSubs.count + npSubs.count
        _ = live(&ctxSubs); _ = live(&npSubs)
        if ctxSubs.count + npSubs.count != before { updateRunning() }
    }

    /// Starts or stops the watcher and the now-playing reader to match the toggle and the subscribers.
    private func updateRunning() {
        let wantCtx = settings.enabled && !ctxSubs.isEmpty
        if wantCtx && pollTimer == nil {
            pollTimer = Timer.scheduledTimer(withTimeInterval: fake ? 0.1 : 0.4, repeats: true) { [weak self] _ in self?.tick() }
            tick()
        } else if !wantCtx, let t = pollTimer {
            t.invalidate(); pollTimer = nil
        }
        // Context drives the seek fallbacks, so now playing also keeps the context fresh.
        if settings.npEnabled && !npSubs.isEmpty { nowPlaying.start() } else { nowPlaying.stop() }
    }

    // MARK: context

    @objc private func appActivated() { if pollTimer != nil { tick() } }

    private func tick() {
        if fake { readFake(); return }
        guard let front = NSWorkspace.shared.frontmostApplication else { return }
        var c = AppContext()
        c.bundle = front.bundleIdentifier ?? ""
        c.app = front.localizedName ?? c.bundle
        c.browser = Browsers.isBrowser(c.bundle)
        if hub.axTrusted {
            if Browsers.chromium.contains(c.bundle), !webAX.contains(front.processIdentifier) {
                webAX.insert(front.processIdentifier); AXRead.enableWebAX(front.processIdentifier)
            }
            (c.text, c.secure) = AXRead.focusedText()
            c.title = AXRead.windowTitle(front.processIdentifier)
        }
        if Browsers.scriptable(c.bundle) {
            c.automation = automation[c.bundle] ?? "ask"
            if let tab = tabCache[c.bundle] { c.url = tab.url; c.title = tab.title ?? c.title }
            readTab(c.bundle)
            menuBrowser = (c.app, c.automation)
        } else if c.browser {
            c.automation = "n/a"
        }
        propose(c)
    }

    /// Reads the active tab off the main queue, at most once a second. Never asks for Automation:
    /// that only happens on a click (turning the lab on, "Allow … tabs", or the Setup window).
    private func readTab(_ bundle: String) {
        guard !scriptBusy, Date().timeIntervalSince(lastScript) > 1 else { return }
        scriptBusy = true
        scriptQ.async { [weak self] in
            let state = Browsers.automation(bundle, ask: false)
            var url: String?, title: String?
            if state == "granted", let src = Browsers.tabScript(bundle), let script = NSAppleScript(source: src) {
                var err: NSDictionary?
                let r = script.executeAndReturnError(&err)
                if err == nil, r.numberOfItems >= 2 {
                    url = r.atIndex(1)?.stringValue; title = r.atIndex(2)?.stringValue
                }
            }
            DispatchQueue.main.async {
                guard let self else { return }
                self.scriptBusy = false; self.lastScript = Date()
                let changed = self.automation[bundle] != state
                self.automation[bundle] = state
                self.tabCache[bundle] = (url, title)
                if changed { self.hub.onChange?() }
                if self.pollTimer != nil, NSWorkspace.shared.frontmostApplication?.bundleIdentifier == bundle { self.tick() }
            }
        }
    }

    private func readFake() {
        guard let path = hub.config.fakeContext else { return }
        let mtime = (try? FileManager.default.attributesOfItem(atPath: path))?[.modificationDate] as? Date
        guard mtime != fakeMtime || pending == nil else { return }
        fakeMtime = mtime
        guard let d = FileManager.default.contents(atPath: path),
              let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return }
        var c = AppContext()
        c.bundle = j["bundle"] as? String ?? ""
        c.app = j["app"] as? String ?? c.bundle
        c.url = j["url"] as? String
        c.title = j["title"] as? String
        c.browser = j["browser"] as? Bool ?? Browsers.isBrowser(c.bundle)
        c.text = j["text"] as? Bool ?? false
        c.secure = j["secure"] as? Bool ?? false
        c.automation = j["automation"] as? String ?? (Browsers.scriptable(c.bundle) ? "granted" : "n/a")
        propose(c)
    }

    /// Debounced push: a context has to stay the same for 250 ms before phones hear about it.
    private func propose(_ c: AppContext) {
        if c == pending { return }
        pending = c
        debounce?.cancel()
        let w = DispatchWorkItem { [weak self] in
            guard let self, let p = self.pending, p != self.last else { return }
            self.last = p
            self.broadcastCtx()
            if !self.npSubs.isEmpty { self.broadcastNp() } // seek paths depend on the front app
        }
        debounce = w
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Self.debounceMs), execute: w)
    }

    // MARK: seek

    private func seek(_ m: [String: Any], from s: Session) {
        func err(_ code: String, _ msg: String) { s.send(["t": "err", "code": code, "msg": msg, "ref": "labs.np.seek"]) }
        guard settings.npEnabled else { err("unsupported", "Turn on Share now playing in the Mote Helper menu"); return }
        guard !hub.paused else { err("paused", "input is paused on the Mac"); return }
        guard hub.inputReady else { err("ax", "Accessibility permission not granted to Mote Helper"); return }
        let to = (m["to"] as? NSNumber)?.doubleValue
        let by = (m["by"] as? NSNumber)?.doubleValue
        guard to != nil || by != nil, (to ?? 0).isFinite, (by ?? 0).isFinite, abs(by ?? 0) <= 3600 else { err("bad", "labs.np.seek needs a finite 'to' or 'by' (seconds)"); return }
        let ctx = last
        let cur = nowPlaying.elapsedNow()
        func reply(_ via: String, _ target: Double?, note: String? = nil) {
            var r: [String: Any] = ["t": "labs.np.seek", "via": via]
            if let target { r["to"] = target }
            if let note { r["note"] = note }
            s.send(r)
        }
        // 1. MediaRemote (or the fake session) when something is playing and it knows the position.
        if nowPlaying.canSeek, let cur {
            var target = to ?? (cur + (by ?? 0))
            target = max(0, nowPlaying.duration.map { min(target, max(0, $0 - 1)) } ?? target)
            nowPlaying.seek(target)
            reply(nowPlaying.status == "fake" ? "fake" : "mediaremote", target)
            return
        }
        // 2. Browser video via JavaScript from Apple Events (not Netflix: its player rejects currentTime).
        if let c = ctx, Browsers.scriptable(c.bundle), !(c.host ?? "").hasSuffix("netflix.com") {
            let js = to.map { "v.currentTime=\(max(0, $0))" } ?? "v.currentTime=Math.max(0,v.currentTime+(\(by ?? 0)))"
            let src = "(function(){var vs=Array.prototype.slice.call(document.querySelectorAll('video'));"
                + "vs.sort(function(a,b){return b.offsetWidth*b.offsetHeight-a.offsetWidth*a.offsetHeight});"
                + "var v=vs[0];if(!v)return 'none';\(js);return 'ok'})()"
            if hub.config.dryRun {
                log("DRY labs seek via=js bundle=\(c.bundle) \(to.map { "to=\($0)" } ?? "by=\(by ?? 0)")")
                reply("js", to); return
            }
            guard let script = Browsers.jsScript(c.bundle, src) else { return }
            scriptQ.async { [weak self] in
                var e: NSDictionary?
                let r = NSAppleScript(source: script)?.executeAndReturnError(&e)
                let result = r?.stringValue
                DispatchQueue.main.async {
                    guard let self else { return }
                    if e == nil, result == "ok" { reply("js", to); return }
                    let msg = (e?[NSAppleScript.errorMessage] as? String) ?? ""
                    let note: String
                    if msg.localizedCaseInsensitiveContains("javascript") {
                        note = Browsers.safari.contains(c.bundle)
                            ? "Safari: turn on Develop > Allow JavaScript from Apple Events (Settings > Advanced > Show features for web developers)."
                            : "\(c.app): turn on View > Developer > Allow JavaScript from Apple Events."
                    } else if result == "none" { note = "No video on this page." } else { note = msg.isEmpty ? "JavaScript seek failed." : msg }
                    self.stepKeys(to: to, by: by, cur: cur, reply: { via, t in reply(via, t, note: note) })
                }
            }
            return
        }
        // 3. Arrow keys: most web players step 5 s per press.
        stepKeys(to: to, by: by, cur: cur, reply: { via, t in reply(via, t) })
    }

    private func stepKeys(to: Double?, by: Double?, cur: Double?, reply: (String, Double?) -> Void) {
        let delta = by ?? (to.flatMap { t in cur.map { t - $0 } } ?? 0)
        let steps = min(24, Int((abs(delta) / 5).rounded()))
        guard steps > 0 else { reply("keys", nil); return }
        let usage = delta > 0 ? 79 : 80 // Right / Left
        for i in 0..<steps {
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(i * 40)) { [weak self] in
                guard let self, !self.hub.paused else { return }
                try? self.hub.injector.exec(["t": "key", "usage": usage, "mods": 0, "action": "tap"])
            }
        }
        reply("keys", nil)
    }

    // MARK: menu

    func menuItems() -> [NSMenuItem] {
        func item(_ title: String, _ action: Selector?, state: Bool? = nil) -> NSMenuItem {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: "")
            i.target = self
            i.isEnabled = action != nil
            if let state { i.state = state ? .on : .off }
            return i
        }
        var out = [item("Context (frontmost app, preview)", #selector(toggleEnabled), state: settings.enabled)]
        guard settings.enabled else { return out }
        out.append(item("    Share full URLs and window titles", #selector(toggleFull), state: settings.fullDetails))
        if let b = menuBrowser {
            switch b.state {
            case "granted": out.append(item("    \(b.name) tabs: allowed", nil))
            case "denied": out.append(item("    \(b.name) tabs: denied, open Settings…", #selector(openAutomation)))
            default: out.append(item("    Allow \(b.name) tabs…", #selector(askAutomation)))
            }
        } else {
            out.append(item("    Browser tabs: bring a browser to the front, then allow it here", nil))
        }
        return out
    }

    /// Core (not Labs): Advanced › "Share now playing" and its status line.
    func nowPlayingMenuItems() -> [NSMenuItem] {
        let sw = NSMenuItem(title: "Share now playing", action: #selector(toggleNowPlaying), keyEquivalent: "")
        sw.target = self
        sw.state = settings.npEnabled ? .on : .off
        guard settings.npEnabled else { return [sw] }
        let st = NSMenuItem(title: "     \(nowPlayingMenuLine)", action: nil, keyEquivalent: "")
        st.isEnabled = false
        return [sw, st]
    }

    private var nowPlayingMenuLine: String {
        let np: String
        switch nowPlaying.status {
        case "mediaremote": np = "reading (MediaRemote via /usr/bin/perl)"
        case "fake": np = "fake (dry run)"
        case "unavailable": np = "unavailable on this macOS"
        case "starting": np = "starting…"
        default: np = "idle (no phone listening)"
        }
        return "Now playing: \(np)"
    }

    @objc private func toggleNowPlaying() { setNowPlayingEnabled(!settings.npEnabled) }

    @objc private func toggleEnabled() { setEnabled(!settings.enabled) }

    var enabled: Bool { settings.enabled }
    var nowPlayingEnabled: Bool { settings.npEnabled }
    var nowPlayingStatus: String { nowPlaying.status }

    /// A click (menu or Setup window): turning it on asks the running browsers for Automation, in context.
    func setEnabled(_ on: Bool) {
        guard on != settings.enabled else { return }
        settings.enabled = on
        if on && !hub.config.dryRun { askAutomation() }
        log("labs: context \(settings.enabled ? "on" : "off")")
        ev(.labs, "toggle", ["lab": "context", "on": settings.enabled])
        if !settings.enabled { last = nil; pending = nil }
        updateRunning()
        broadcastCtx(); broadcastNp()
        hub.onChange?()
    }

    func setNowPlayingEnabled(_ on: Bool) {
        guard on != settings.npEnabled else { return }
        settings.npEnabled = on
        log("now playing \(on ? "shared" : "not shared")")
        ev(.lifecycle, "setting", ["key": "nowPlaying", "on": on])
        updateRunning()
        broadcastNp()
        hub.onChange?()
    }

    @objc private func toggleFull() { settings.fullDetails.toggle(); broadcastCtx(); hub.onChange?() }

    /// Shows the macOS Automation prompt for every running scriptable browser not yet decided (user action only).
    @objc func askAutomation() {
        let bundles = NSWorkspace.shared.runningApplications.compactMap(\.bundleIdentifier).filter(Browsers.scriptable)
        if bundles.isEmpty || bundles.allSatisfy({ Browsers.automation($0, ask: false) == "denied" }) { openAutomation(); return }
        scriptQ.async { [weak self] in
            var got: [String: String] = [:]
            for b in bundles { got[b] = Browsers.automation(b, ask: true) }
            DispatchQueue.main.async {
                guard let self else { return }
                for (b, st) in got { self.automation[b] = st }
                self.hub.onChange?()
            }
        }
    }

    @objc func openAutomation() {
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation")!)
    }
}

// MARK: - Now playing

/// Now-playing info. macOS 15.4+ answers MediaRemote only for Apple-signed processes, so the reader runs
/// as `/usr/bin/perl mote-nowplaying.pl MoteNowPlaying.dylib` (helper/LabsNowPlaying/), one JSON line per change.
final class NowPlaying {
    var onUpdate: (() -> Void)?
    /// off | starting | mediaremote | unavailable | fake
    private(set) var status = "off"
    private(set) var info: [String: Any]?
    private(set) var canSeek = false
    private let fake: Bool
    private let dryRun: Bool
    private var proc: Process?
    private var stdin: FileHandle?
    private var buf = Data()
    private var failures: [Date] = []
    private var wanted = false
    private var fakeTimer: Timer?
    private let reader: String?   // chaos (--fake-nowplaying-reader, dry-run only): stands in for perl + the dylib
    /// A reader line longer than this is dropped (a reader that never sends a newline can't grow memory).
    static let maxLine = 256 * 1024

    init(fake: Bool, dryRun: Bool, reader: String? = nil) { self.fake = fake; self.dryRun = dryRun; self.reader = reader }

    var duration: Double? { (info?["duration"] as? NSNumber)?.doubleValue }

    /// Elapsed seconds now, extrapolated from `elapsed` at `at` with `rate`.
    func elapsedNow() -> Double? {
        guard let i = info, let e = (i["elapsed"] as? NSNumber)?.doubleValue else { return nil }
        let rate = (i["rate"] as? NSNumber)?.doubleValue ?? 0
        let at = (i["at"] as? NSNumber)?.doubleValue ?? Date().timeIntervalSince1970 * 1000
        return e + max(0, Date().timeIntervalSince1970 * 1000 - at) / 1000 * rate
    }

    func start() {
        guard !wanted else { return }
        wanted = true
        if fake { startFake(); return }
        launch()
    }

    func stop() {
        guard wanted else { return }
        wanted = false
        fakeTimer?.invalidate(); fakeTimer = nil
        proc?.terminate(); proc = nil; stdin = nil
        if status != "unavailable" { status = "off" }
        info = nil; canSeek = false
    }

    func seek(_ to: Double) {
        if fake {
            info?["elapsed"] = to; info?["at"] = Int64(Date().timeIntervalSince1970 * 1000)
            log("DRY labs seek via=fake to=\(String(format: "%.1f", to))")
            onUpdate?(); return
        }
        stdin?.write("seek \(to)\n".data(using: .utf8)!)
    }

    private func startFake() {
        status = "fake"; canSeek = true
        info = ["title": "Fake Song", "artist": "Mote Labs", "album": "Dry Run", "app": "Music", "bundle": "com.apple.Music",
                "duration": 300.0, "elapsed": 42.0, "rate": 1.0, "playing": true, "at": Int64(Date().timeIntervalSince1970 * 1000)]
        onUpdate?()
    }

    private func resources() -> (script: String, dylib: String)? {
        let env = ProcessInfo.processInfo.environment
        let dir = env["MOTE_NP_DIR"].map { URL(fileURLWithPath: $0) } ?? Bundle.main.resourceURL?.appendingPathComponent("labs")
        guard let dir else { return nil }
        let s = dir.appendingPathComponent("mote-nowplaying.pl").path, d = dir.appendingPathComponent("MoteNowPlaying.dylib").path
        guard FileManager.default.fileExists(atPath: s), FileManager.default.fileExists(atPath: d) else { return nil }
        return (s, d)
    }

    private func launch() {
        guard wanted, proc == nil else { return }
        // Dry run never touches the real media session (MediaRemote seek would be real input); use --fake-nowplaying.
        let p = Process()
        if let reader {
            p.executableURL = URL(fileURLWithPath: reader)
        } else {
            if dryRun { status = "unavailable"; log("labs: now-playing reader off in dry run"); onUpdate?(); return }
            guard let r = resources(), FileManager.default.isExecutableFile(atPath: "/usr/bin/perl") else {
                status = "unavailable"; log("labs: now-playing reader not bundled"); onUpdate?(); return
            }
            p.executableURL = URL(fileURLWithPath: "/usr/bin/perl")
            p.arguments = [r.script, r.dylib]
        }
        status = "starting"
        buf = Data()
        let out = Pipe(), inp = Pipe()
        p.standardOutput = out; p.standardInput = inp; p.standardError = FileHandle.nullDevice
        out.fileHandleForReading.readabilityHandler = { [weak self] h in
            let d = h.availableData
            DispatchQueue.main.async { self?.consume(d) }
        }
        p.terminationHandler = { [weak self] _ in DispatchQueue.main.async { self?.exited(p) } }
        do { try p.run() } catch { status = "unavailable"; log("labs: perl failed: \(error)"); onUpdate?(); return }
        proc = p; stdin = inp.fileHandleForWriting
    }

    private func consume(_ d: Data) {
        buf.append(d)
        while let nl = buf.firstIndex(of: 0x0A) {
            let line = buf.subdata(in: buf.startIndex..<nl)
            buf.removeSubrange(buf.startIndex...nl)
            guard line.count <= Self.maxLine else { log("labs: now-playing line too long (\(line.count) B), dropped"); continue }
            guard let j = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any] else { continue }
            if let ok = j["ok"] as? Bool {
                status = ok ? "mediaremote" : "unavailable"
                log("labs: now playing \(ok ? "via MediaRemote (perl)" : "unavailable")")
                onUpdate?()
            } else if j.keys.contains("np") {
                info = Self.normalise(j["np"] as? [String: Any])
                canSeek = info?["elapsed"] != nil && (duration ?? 0) > 0
                onUpdate?()
            }
        }
        // No newline yet and already over the cap: discard until the next newline.
        if buf.count > Self.maxLine { log("labs: now-playing reader sent \(buf.count) B without a newline, dropped"); buf = Data() }
    }

    /// Booleans arrive as 0/1 from the adapter; keep the message small and typed.
    static func normalise(_ np: [String: Any]?) -> [String: Any]? {
        guard var np, !np.isEmpty else { return nil }
        np["playing"] = ((np["rate"] as? NSNumber)?.doubleValue ?? 0) > 0
        for k in ["title", "artist", "album", "app"] { if let s = np[k] as? String { np[k] = String(s.prefix(200)) } }
        return np
    }

    private func exited(_ p: Process) {
        guard proc === p else { return }
        proc = nil; stdin = nil
        guard wanted else { return }
        let now = Date()
        failures = failures.filter { now.timeIntervalSince($0) < 60 } + [now]
        if failures.count >= 4 { status = "unavailable"; info = nil; canSeek = false; log("labs: now-playing reader keeps exiting; giving up"); onUpdate?(); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(failures.count)) { [weak self] in self?.launch() }
    }
}
