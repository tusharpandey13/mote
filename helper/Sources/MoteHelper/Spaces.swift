import AppKit
import CoreGraphics
import ImageIO
import ScreenCaptureKit

/// macOS Spaces per display (protocol 1.4, docs/protocol.md "spaces"): index and count per display
/// from private CGS calls, the active display (frontmost window, else the cursor), thumbnails
/// captured per display with ScreenCaptureKit shortly after each Space change, and `goto` via the
/// Switch-to-Desktop shortcut or ⌃←/⌃→ steps. The 1.3 top-level fields mirror the active display.
/// Thumbnails stay in memory only. Main-queue only.
final class Spaces {
    static let maxDesktops = 16
    static let maxDisplays = 8
    static let maxMessageBytes = 60 * 1024   // a `spaces.thumb` message stays under the 64 KiB cap
    static let thumbWidth = 320
    static let captureDelay = 0.6            // after the Space slide animation
    static let debounce = 0.15               // coalesces bursts of Space / app / screen notifications
    /// After the last stepping key, how long a new goto still trusts the keys over the Space macOS reports (the slide).
    static let settle = 0.4

    struct Thumb { let v: Int; let w: Int; let h: Int; let jpeg: Data }

    /// One display's Spaces. `id` is the CGS "Display Identifier" (a UUID, or "Main" when displays share Spaces).
    struct Display: Equatable {
        let id: String
        let name: String
        let cgID: CGDirectDisplayID
        let desktops: [UInt64]               // desktop Space ids, in Mission Control order
        let all: [UInt64]                    // desktops and full-screen Spaces, in order (⌃←/⌃→ walk this)
        let current: UInt64
        /// 1-based index of the current desktop; 0 if unknown or a full-screen Space is showing.
        var index: Int { desktops.firstIndex(of: current).map { $0 + 1 } ?? 0 }
    }

    private let dryRun: Bool
    private var fake: [Int]?                 // --fake-spaces N[,M...] (dry-run only): no CGS, no capture
    private let settings: Settings
    private let injector: Injector
    private var fakeCurrent: [Int]           // per fake display, 1-based
    private var fakeActive = 0               // 0-based fake display that is active
    private(set) var displays: [Display] = []
    private(set) var activeID = ""
    private(set) var shared = false          // "Displays have separate Spaces" is off: one list for all screens
    private var thumbs: [UInt64: Thumb] = [:]   // Space ids are unique across displays
    private var version = 0
    private var armed = false                // a client subscribed this run: capture on every visit
    private var pending: DispatchWorkItem?
    private var pendingDisplays = Set<String>()
    private var kickItem: DispatchWorkItem?
    // goto stepping in flight: the arrow keys still scheduled, and the position (index into `all`) the
    // keys delivered so far have reached on that display. A goto during the slide starts from there.
    private var stepItems: [DispatchWorkItem] = []
    private var stepPos: (display: String, pos: Int, until: Date)?
    private var lastPermission: Bool
    /// State changed (index, count, active display, a thumbnail, toggle, permission): broadcast to subscribers.
    var onChange: (() -> Void)?
    /// Whether any session is subscribed; the 1 s poll does nothing otherwise.
    var isSubscribed: (() -> Bool)?

    init(config: Config, settings: Settings, injector: Injector) {
        dryRun = config.dryRun
        fake = config.dryRun ? config.fakeSpaces : nil
        fakeCurrent = Array(repeating: 1, count: fake?.count ?? 0)
        self.settings = settings
        self.injector = injector
        lastPermission = false
        lastPermission = permitted
        refresh()
        let ws = NSWorkspace.shared.notificationCenter
        for n in [NSWorkspace.activeSpaceDidChangeNotification, NSWorkspace.didActivateApplicationNotification] {
            ws.addObserver(forName: n, object: nil, queue: .main) { [weak self] _ in self?.kick() }
        }
        NotificationCenter.default.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            self?.kick()
        }
        // The active display also changes with clicks and cursor moves, which post nothing: poll while subscribed.
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self, self.isSubscribed?() == true else { return }
            self.poll()
        }
    }

    var active: Display? { displays.first { $0.id == activeID } ?? displays.first }
    var count: Int { active?.desktops.count ?? 0 }
    var current: Int { active?.index ?? 0 }

    // MARK: permission and capture status

    /// Screen Recording granted (always true for fake spaces; never checked in a plain dry run).
    var permitted: Bool {
        if fake != nil { return true }
        if dryRun { return false }
        return CGPreflightScreenCaptureAccess()
    }

    /// `capture` field: on | off | denied | unavailable.
    var captureStatus: String {
        if !settings.shareSpaceThumbnails { return "off" }
        if fake != nil { return "on" }
        guard !dryRun, #available(macOS 14.0, *) else { return "unavailable" }   // dry run never captures the real screen
        return permitted ? "on" : "denied"
    }

    var capturing: Bool { captureStatus == "on" }

    /// Asks macOS for Screen Recording (shows the system prompt once). Only from a click: the Setup
    /// window or the "Share Space thumbnails" toggle; never from a phone subscribing or a Space switch.
    func requestPermission() {
        guard fake == nil, !dryRun, !permitted else { return }
        log("spaces: requesting Screen Recording permission")
        _ = CGRequestScreenCaptureAccess()
    }

    // MARK: state

    /// Re-reads the Spaces layout and the active display. Returns true if anything changed.
    @discardableResult
    func refresh() -> Bool {
        let before = (displays, activeID, shared)
        if let f = fake {
            displays = f.enumerated().map { d, n in
                let ids = (1...n).map { UInt64((d + 1) * 100 + $0) }
                return Display(id: "fake-\(d + 1)", name: "Fake Display \(d + 1)", cgID: 0, desktops: ids, all: ids, current: ids[min(fakeCurrent[d], n) - 1])
            }
            shared = false
            activeID = displays[min(fakeActive, displays.count - 1)].id
        } else if let raw = CGSSpaces.read() {
            shared = raw.count == 1 && raw[0].id == "Main"
            let ids = CGSSpaces.onlineDisplays()
            displays = raw.prefix(Spaces.maxDisplays).map { r in
                let cg = r.id == "Main" ? CGMainDisplayID() : (ids.first { CGSSpaces.uuid($0)?.caseInsensitiveCompare(r.id) == .orderedSame } ?? 0)
                return Display(id: r.id, name: shared ? "All displays" : Spaces.screenName(cg), cgID: cg,
                               desktops: Array(r.desktops.prefix(Spaces.maxDesktops)), all: r.all, current: r.current)
            }
            if shared {
                activeID = displays[0].id
            } else {
                let focus = Spaces.frontWindowDisplay() ?? Spaces.cursorDisplay()
                activeID = (displays.first { $0.cgID == focus } ?? displays.first { $0.cgID == CGMainDisplayID() } ?? displays[0]).id
            }
        } else {
            displays = []; activeID = ""; shared = false
        }
        let live = Set(displays.flatMap(\.all))
        thumbs = thumbs.filter { live.contains($0.key) }
        return before.0 != displays || before.1 != activeID || before.2 != shared
    }

    private func versions(_ d: Display) -> [Int] { d.desktops.map { thumbs[$0]?.v ?? 0 } }

    func state() -> [String: Any] {
        let list: [[String: Any]] = displays.map { ["id": $0.id, "name": $0.name, "count": $0.desktops.count, "current": $0.index, "thumbs": versions($0)] }
        var s: [String: Any] = ["t": "spaces", "count": count, "current": current, "thumbs": active.map(versions) ?? [],
                                "capture": captureStatus, "displays": list, "shared": shared]
        if !activeID.isEmpty { s["display"] = activeID }
        return s
    }

    /// A client sent `spaces.sub on:true`: arm capturing, grab each display's current Space (if permitted).
    func subscribed() {
        refresh()
        armed = true
        let missing = displays.filter { $0.current != 0 && thumbs[$0.current] == nil }.map(\.id)
        if !missing.isEmpty { scheduleCapture(missing, after: 0.1) }
    }

    /// Every second while subscribed: picks up the active display, added/removed desktops and displays,
    /// missed Space changes and a newly granted permission.
    func poll() {
        let p = permitted
        let permChanged = p != lastPermission
        lastPermission = p
        if permChanged && p && armed { scheduleCapture(displays.map(\.id), after: 0.1) }
        if !update(captureAfter: Spaces.captureDelay) && permChanged { onChange?() }
    }

    /// Debounced re-read after a notification (Space change, app activation, screen change).
    private func kick() {
        kickItem?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.update(captureAfter: Spaces.captureDelay - Spaces.debounce) }
        kickItem = w
        DispatchQueue.main.asyncAfter(deadline: .now() + Spaces.debounce, execute: w)
    }

    /// Re-reads; on a change broadcasts and captures every display whose current Space moved. True if changed.
    @discardableResult
    private func update(captureAfter delay: Double) -> Bool {
        let prev = Dictionary(displays.map { ($0.id, $0.current) }, uniquingKeysWith: { a, _ in a })
        guard refresh() else { return false }
        onChange?()
        let moved = displays.filter { $0.current != 0 && prev[$0.id] != $0.current }.map(\.id)
        if armed && !moved.isEmpty { scheduleCapture(moved, after: delay) }
        return true
    }

    /// Test-only (`--stdin-control` `display N`): make fake display N (1-based) the active one.
    func fakeActivate(_ n: Int) {
        guard let f = fake, (1...f.count).contains(n) else { log("spaces: display \(n) needs --fake-spaces with that many displays"); return }
        fakeActive = n - 1
        log("spaces: fake active display \(n)")
        kick()
    }

    /// Test-only (`--stdin-control` `displays N,M,...`): a simulated display hot-plug to a new fake layout.
    func fakeReplug(_ spec: String) {
        guard fake != nil else { log("spaces: displays needs --fake-spaces"); return }
        let ns = spec.split(separator: ",").compactMap { Int($0) }.prefix(Spaces.maxDisplays).map { min(max($0, 1), Spaces.maxDesktops) }
        guard !ns.isEmpty else { log("spaces: bad displays '\(spec)'"); return }
        fakeCurrent = ns.indices.map { k in min(k < fakeCurrent.count ? fakeCurrent[k] : 1, ns[k]) }
        fakeActive = min(fakeActive, ns.count - 1)
        fake = Array(ns)
        log("spaces: fake displays \(ns.map(String.init).joined(separator: ","))")
        kick()
    }

    /// `spaces.thumb` reply for desktop `i` (1-based) of `display` (default: the active one), or nil if none is cached.
    func thumbMessage(_ i: Int, display: String? = nil) -> [String: Any]? {
        guard let d = display.map({ id in displays.first { $0.id == id } }) ?? active,
              (1...max(1, d.desktops.count)).contains(i), i <= d.desktops.count, let t = thumbs[d.desktops[i - 1]] else { return nil }
        return ["t": "spaces.thumb", "i": i, "display": d.id, "v": t.v, "w": t.w, "h": t.h, "mime": "image/jpeg", "data": t.jpeg.base64EncodedString()]
    }

    /// Menu "Clear Space thumbnails", toggle off, revoke all.
    func clear() {
        pending?.cancel(); pending = nil; pendingDisplays.removeAll()
        guard !thumbs.isEmpty else { return }
        thumbs.removeAll()
        log("spaces: thumbnails cleared")
        onChange?()
    }

    func shareToggled() {
        if !settings.shareSpaceThumbnails { thumbs.removeAll(); pending?.cancel(); pendingDisplays.removeAll() }
        else if armed { scheduleCapture(displays.map(\.id), after: 0.1) }
        onChange?()
    }

    // MARK: capture

    /// Captures the current Space of each display in `ids` after `delay` (merged with any capture already pending).
    private func scheduleCapture(_ ids: [String], after delay: Double) {
        pending?.cancel()
        guard capturing else { pendingDisplays.removeAll(); return }
        pendingDisplays.formUnion(ids)
        let w = DispatchWorkItem { [weak self] in
            guard let self else { return }
            let targets = self.pendingDisplays
            self.pendingDisplays.removeAll()
            self.refresh()
            for id in self.displays.map(\.id) where targets.contains(id) { self.capture(id) }
        }
        pending = w
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0, delay), execute: w)
    }

    private func capture(_ displayID: String) {
        guard capturing, let d = displays.first(where: { $0.id == displayID }), d.current != 0 else { return }
        let space = d.current
        let done: (CGImage?) -> Void = { [weak self] img in
            DispatchQueue.main.async {
                // The user may have moved on (or turned sharing off) while the frame was taken.
                guard let self, self.capturing, self.displays.first(where: { $0.id == displayID })?.current == space,
                      let img, let jpeg = Spaces.jpeg(img) else { return }
                self.version += 1
                self.thumbs[space] = Thumb(v: self.version, w: img.width, h: img.height, jpeg: jpeg)
                self.onChange?()
            }
        }
        if fake != nil {
            let k = displays.firstIndex(of: d) ?? 0
            done(Spaces.solid(index: d.index, of: d.desktops.count, display: k))
        } else if #available(macOS 14.0, *) {
            Spaces.captureDisplay(d.cgID, done)
        }
    }

    /// One frame of display `id` (the SCDisplay with that CGDirectDisplayID; 0 or unknown = main display).
    @available(macOS 14.0, *)
    private static func captureDisplay(_ id: CGDirectDisplayID, _ done: @escaping (CGImage?) -> Void) {
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { content, err in
            guard let content, let d = content.displays.first(where: { $0.displayID == (id == 0 ? CGMainDisplayID() : id) })
                    ?? content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else {
                if let err { log("spaces: capture unavailable: \(err.localizedDescription)") }
                done(nil); return
            }
            let c = SCStreamConfiguration()
            c.width = thumbWidth
            c.height = max(1, Int((Double(thumbWidth) * Double(d.height) / Double(max(1, d.width))).rounded()))
            c.showsCursor = false
            SCScreenshotManager.captureImage(contentFilter: SCContentFilter(display: d, excludingWindows: []), configuration: c) { img, err in
                if let err { log("spaces: capture failed: \(err.localizedDescription)") }
                done(img)
            }
        }
    }

    /// JPEG at quality 0.6, stepped down until the base64 message fits [maxMessageBytes].
    static func jpeg(_ img: CGImage) -> Data? {
        for q in [0.6, 0.4, 0.25] {
            let d = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(d, "public.jpeg" as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImage(dest, img, [kCGImageDestinationLossyCompressionQuality: q] as CFDictionary)
            guard CGImageDestinationFinalize(dest) else { return nil }
            if (d.length + 2) / 3 * 4 + 200 <= maxMessageBytes { return d as Data }
        }
        return nil
    }

    /// --fake-spaces: a solid colour per desktop (hue by index, brightness by display), 320x200.
    static func solid(index: Int, of n: Int, display: Int = 0) -> CGImage? {
        let w = thumbWidth, h = 200
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        let c = NSColor(hue: CGFloat(max(0, index - 1)) / CGFloat(max(1, n)), saturation: 0.6, brightness: 0.8 - 0.2 * CGFloat(display % 3), alpha: 1)
        ctx.setFillColor(c.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()
    }

    // MARK: display helpers

    /// The display holding the frontmost app's frontmost normal window (keyboard focus), or nil if it has none.
    /// Window bounds and owners need no Screen Recording permission.
    static func frontWindowDisplay() -> CGDirectDisplayID? {
        guard let pid = NSWorkspace.shared.frontmostApplication?.processIdentifier,
              let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return nil }
        for w in list where (w[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value == pid && (w[kCGWindowLayer as String] as? NSNumber)?.intValue == 0 {
            guard let b = w[kCGWindowBounds as String] as? NSDictionary, let r = CGRect(dictionaryRepresentation: b), r.width > 1, r.height > 1 else { continue }
            if let d = display(at: CGPoint(x: r.midX, y: r.midY)) { return d }
        }
        return nil
    }

    /// The display under the mouse cursor.
    static func cursorDisplay() -> CGDirectDisplayID? {
        guard let p = CGEvent(source: nil)?.location else { return nil }
        return display(at: p)
    }

    private static func display(at p: CGPoint) -> CGDirectDisplayID? {
        var id: CGDirectDisplayID = 0, n: UInt32 = 0
        guard CGGetDisplaysWithPoint(p, 1, &id, &n) == .success, n > 0 else { return nil }
        return id
    }

    /// NSScreen.localizedName for a display, else "Display".
    static func screenName(_ id: CGDirectDisplayID) -> String {
        let key = NSDeviceDescriptionKey("NSScreenNumber")
        return NSScreen.screens.first { ($0.deviceDescription[key] as? NSNumber)?.uint32Value == id }?.localizedName ?? "Display"
    }

    // MARK: goto

    /// `spaces.goto` desktop `i` of `display` (default: the active one): the user's Switch-to-Desktop
    /// shortcut if enabled (numbered across displays in macOS order), else ⌃←/⌃→ steps on that display,
    /// after moving the cursor onto it (steps act on the display under the cursor).
    func goto(_ i: Int, display: String? = nil) throws {
        // update, not a bare refresh: a Space change this read picks up must still reach subscribers
        // (a silent refresh here swallowed it, and the carousel kept showing the old desktop).
        update(captureAfter: Spaces.captureDelay)
        var target = active
        if let display {
            guard let d = displays.first(where: { $0.id == display }) else { throw ProtoError("notfound", "no display \(display)") }
            target = d
        }
        let n = target?.desktops.count ?? 0
        let upper = n > 0 ? n : Spaces.maxDesktops
        guard (1...upper).contains(i) else { throw ProtoError("bad", "desktop index must be 1-\(upper)") }
        let tag = target.map { " display=\($0.id)" } ?? ""
        // "Switch to Desktop N" numbers desktops globally: display 1's desktops, then display 2's, ...
        let offset = target.map { t in displays.prefix { $0.id != t.id }.reduce(0) { $0 + $1.desktops.count } } ?? 0
        // Fake spaces always step, so tests don't depend on this Mac's shortcut settings.
        if fake == nil, let hk = SymbolicHotkeys.desktop(offset + i) {
            if dryRun { log("DRY spaces goto=\(i) from=\(target?.index ?? 0) via=shortcut n=\(offset + i)\(tag)") } else { log("spaces: goto \(i) via shortcut \(offset + i)\(tag)") }
            try injector.key(usage: hk.usage, mods: hk.mods, action: "tap")
            return
        }
        guard let d = target, d.current != 0, var from = d.all.firstIndex(of: d.current) else {
            throw ProtoError("unsupported", "current Space unknown; enable Switch to Desktop \(offset + i) in Keyboard Shortcuts")
        }
        // A goto while the previous one is still stepping (keys queued, or the slide not settled):
        // macOS hasn't reported the new Space yet, so start from where the keys sent so far lead,
        // and drop the keys the old goto hasn't sent. Otherwise both key runs land and overshoot.
        if let p = stepPos, p.display == d.id, Date() < p.until { from = p.pos }
        for w in stepItems { w.cancel() }
        stepItems = []
        let to = d.all.firstIndex(of: d.desktops[i - 1]) ?? from
        let steps = to - from
        if dryRun { log("DRY spaces goto=\(i) from=\(d.index) via=step\(tag)") } else { log("spaces: goto \(i) via \(abs(steps)) steps\(tag)") }
        let warp = steps != 0 && !shared && (fake != nil ? d.id != activeID : Spaces.cursorDisplay() != d.cgID)
        if warp { warpCursor(to: d) }
        let lead = warp ? 100 : 0
        let usage = steps > 0 ? 79 : 80   // HID Right / Left arrow
        let dir = steps > 0 ? 1 : -1
        stepPos = (d.id, from, Date().addingTimeInterval(Double(lead + 80 * abs(steps)) / 1000 + Spaces.settle))
        for k in 0..<abs(steps) {
            let w = DispatchWorkItem { [weak self] in
                guard let self else { return }
                try? self.injector.key(usage: usage, mods: 1, action: "tap")
                if let p = self.stepPos, p.display == d.id { self.stepPos = (p.display, p.pos + dir, p.until) }
            }
            stepItems.append(w)
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(lead + 80 * k), execute: w)
        }
        if fake != nil, let k = displays.firstIndex(of: d) {
            // Simulate the Space change the real keys would cause (and the focus the warp would move).
            fakeCurrent[k] = i
            if warp { fakeActive = k }
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(lead + 80 * abs(steps))) { [weak self] in self?.update(captureAfter: Spaces.captureDelay) }
        }
    }

    /// Moves the cursor (no click) to the centre of `d`, so ⌃←/⌃→ act on it. Logged only in --dry-run.
    private func warpCursor(to d: Display) {
        if dryRun { log("DRY spaces warp display=\(d.id)"); return }
        let r = CGDisplayBounds(d.cgID == 0 ? CGMainDisplayID() : d.cgID)
        CGWarpMouseCursorPosition(CGPoint(x: r.midX, y: r.midY))
        CGAssociateMouseAndMouseCursorPosition(1)
        log("spaces: cursor moved to display \(d.id)")
    }
}

/// Private CoreGraphics / SkyLight Space calls, looked up at run time. Any missing symbol -> nil (unknown).
enum CGSSpaces {
    private typealias MainConnection = @convention(c) () -> Int32
    private typealias CopyManagedDisplaySpaces = @convention(c) (Int32) -> Unmanaged<CFArray>?
    private typealias GetActiveSpace = @convention(c) (Int32) -> UInt64

    private static let handles: [UnsafeMutableRawPointer] = ([
        "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight",
        "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
    ] as [String]).compactMap { dlopen($0, RTLD_LAZY) }

    private static func sym<T>(_ name: String, _ type: T.Type) -> T? {
        for h in handles { if let p = dlsym(h, name) { return unsafeBitCast(p, to: type) } }
        return nil
    }

    private static let mainConnection = sym("CGSMainConnectionID", MainConnection.self) ?? sym("_CGSDefaultConnection", MainConnection.self)
    private static let copyManaged = sym("CGSCopyManagedDisplaySpaces", CopyManagedDisplaySpaces.self)
    private static let activeSpace = sym("CGSGetActiveSpace", GetActiveSpace.self)
    static var available: Bool { mainConnection != nil && copyManaged != nil }

    struct Layout { let id: String; let desktops: [UInt64]; let all: [UInt64]; let current: UInt64 }

    /// Spaces per display, in macOS order. One entry with id "Main" when displays share Spaces.
    static func read() -> [Layout]? {
        guard let mainConnection, let copyManaged else { return nil }
        let cid = mainConnection()
        guard let displays = copyManaged(cid)?.takeRetainedValue() as? [[String: Any]], !displays.isEmpty else { return nil }
        func id(_ s: [String: Any]) -> UInt64? { ((s["ManagedSpaceID"] ?? s["id64"]) as? NSNumber)?.uint64Value }
        let out: [Layout] = displays.compactMap { disp in
            guard let did = disp["Display Identifier"] as? String, let spaces = disp["Spaces"] as? [[String: Any]] else { return nil }
            let all = spaces.compactMap(id)
            let desktops = spaces.filter { (($0["type"] as? NSNumber)?.intValue ?? 0) == 0 }.compactMap(id)
            var current = (disp["Current Space"] as? [String: Any]).flatMap(id) ?? 0
            if current == 0, displays.count == 1, let activeSpace { current = activeSpace(cid) }
            return Layout(id: did, desktops: desktops, all: all, current: current)
        }
        return out.isEmpty ? nil : out
    }

    /// Online displays (CGDirectDisplayID).
    static func onlineDisplays() -> [CGDirectDisplayID] {
        var n: UInt32 = 0
        guard CGGetOnlineDisplayList(0, nil, &n) == .success, n > 0 else { return [] }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(n))
        guard CGGetOnlineDisplayList(n, &ids, &n) == .success else { return [] }
        return Array(ids.prefix(Int(n)))
    }

    static func uuid(_ id: CGDirectDisplayID) -> String? {
        CGDisplayCreateUUIDFromDisplayID(id).map { CFUUIDCreateString(nil, $0.takeRetainedValue()) as String }
    }
}

/// "Switch to Desktop N" (System Settings > Keyboard Shortcuts > Mission Control), keys 118-126.
enum SymbolicHotkeys {
    /// The enabled shortcut for desktop `n` (1-9) as a HID usage + protocol modifier bits, or nil.
    static func desktop(_ n: Int) -> (usage: Int, mods: Int)? {
        guard (1...9).contains(n) else { return nil }
        let domain = "com.apple.symbolichotkeys" as CFString
        CFPreferencesAppSynchronize(domain)
        guard let all = CFPreferencesCopyAppValue("AppleSymbolicHotKeys" as CFString, domain) as? [String: Any],
              let e = all[String(117 + n)] as? [String: Any], (e["enabled"] as? NSNumber)?.boolValue == true,
              let v = e["value"] as? [String: Any], let p = v["parameters"] as? [NSNumber], p.count >= 3 else { return nil }
        let vk = p[1].intValue, flags = p[2].intValue
        guard let usage = KeyMap.vk.first(where: { Int($0.value) == vk })?.key else { return nil }
        var mods = 0
        if flags & 0x40000 != 0 { mods |= 1 }    // control
        if flags & 0x20000 != 0 { mods |= 2 }    // shift
        if flags & 0x80000 != 0 { mods |= 4 }    // option
        if flags & 0x100000 != 0 { mods |= 8 }   // command
        return (usage, mods)
    }
}
