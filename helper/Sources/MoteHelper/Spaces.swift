import AppKit
import CoreGraphics
import ImageIO
import ScreenCaptureKit

/// macOS Spaces on the main display (protocol 1.3, docs/protocol.md "spaces"): current index and
/// count from private CGS calls, thumbnails captured with ScreenCaptureKit shortly after each
/// Space change, and `goto` via the Switch-to-Desktop shortcut or ⌃←/⌃→ steps.
/// Thumbnails stay in memory only. Main-queue only.
final class Spaces {
    static let maxDesktops = 16
    static let maxMessageBytes = 60 * 1024   // a `spaces.thumb` message stays under the 64 KiB cap
    static let thumbWidth = 320
    static let captureDelay = 0.6            // after the Space slide animation

    struct Thumb { let v: Int; let w: Int; let h: Int; let jpeg: Data }

    private let dryRun: Bool
    private let fake: Int?                   // --fake-spaces N (dry-run only): no CGS, no capture
    private let settings: Settings
    private let injector: Injector
    private var fakeCurrent = 1
    private var desktops: [UInt64] = []      // desktop Space ids, in Mission Control order
    private var all: [UInt64] = []           // desktops and full-screen Spaces, in order (⌃←/⌃→ walk this)
    private var currentID: UInt64 = 0
    private var thumbs: [UInt64: Thumb] = [:]
    private var version = 0
    private var armed = false                // a client subscribed this run: capture on every visit
    private var pending: DispatchWorkItem?
    private var lastPermission: Bool
    /// State changed (index, count, a thumbnail, toggle, permission): broadcast to subscribers.
    var onChange: (() -> Void)?

    init(config: Config, settings: Settings, injector: Injector) {
        dryRun = config.dryRun
        fake = config.dryRun ? config.fakeSpaces : nil
        self.settings = settings
        self.injector = injector
        lastPermission = false
        lastPermission = permitted
        refresh()
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.spaceChanged()
        }
    }

    var count: Int { desktops.count }
    /// 1-based index of the current desktop; 0 if unknown or a full-screen Space is showing.
    var current: Int { desktops.firstIndex(of: currentID).map { $0 + 1 } ?? 0 }

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

    /// Asks macOS for Screen Recording (shows the system prompt once). Lazy: first subscription or the menu.
    func requestPermission() {
        guard fake == nil, !dryRun, !permitted else { return }
        log("spaces: requesting Screen Recording permission")
        _ = CGRequestScreenCaptureAccess()
    }

    // MARK: state

    /// Re-reads the Spaces layout. Returns true if index or count changed.
    @discardableResult
    func refresh() -> Bool {
        let before = (desktops, currentID)
        if let n = fake {
            desktops = (1...max(1, n)).map { UInt64($0) }
            all = desktops
            currentID = UInt64(fakeCurrent)
        } else if let s = CGSSpaces.read() {
            desktops = Array(s.desktops.prefix(Spaces.maxDesktops)); all = s.all; currentID = s.current
        } else {
            desktops = []; all = []; currentID = 0
        }
        let live = Set(all)
        thumbs = thumbs.filter { live.contains($0.key) }
        return before.0 != desktops || before.1 != currentID
    }

    func state() -> [String: Any] {
        ["t": "spaces", "count": count, "current": current, "thumbs": desktops.map { thumbs[$0]?.v ?? 0 }, "capture": captureStatus]
    }

    /// A client sent `spaces.sub on:true`: arm capturing, ask for permission, grab the current Space.
    func subscribed() {
        refresh()
        if !armed {
            armed = true
            requestPermission()
        }
        if thumbs[currentID] == nil { scheduleCapture(after: 0.1) }
    }

    /// Called every 2 s by the hub: picks up added/removed desktops and a newly granted permission.
    func poll() {
        let p = permitted
        let permChanged = p != lastPermission
        lastPermission = p
        if refresh() || permChanged {
            if permChanged && p && armed { scheduleCapture(after: 0.1) }
            onChange?()
        }
    }

    private func spaceChanged() {
        refresh()
        onChange?()
        if armed { scheduleCapture(after: Spaces.captureDelay) }
    }

    /// `spaces.thumb` reply for desktop `i` (1-based), or nil if none is cached.
    func thumbMessage(_ i: Int) -> [String: Any]? {
        guard (1...max(1, count)).contains(i), i <= count, let t = thumbs[desktops[i - 1]] else { return nil }
        return ["t": "spaces.thumb", "i": i, "v": t.v, "w": t.w, "h": t.h, "mime": "image/jpeg", "data": t.jpeg.base64EncodedString()]
    }

    /// Menu "Clear Space thumbnails", toggle off, revoke all.
    func clear() {
        pending?.cancel(); pending = nil
        guard !thumbs.isEmpty else { return }
        thumbs.removeAll()
        log("spaces: thumbnails cleared")
        onChange?()
    }

    func shareToggled() {
        if !settings.shareSpaceThumbnails { thumbs.removeAll(); pending?.cancel() }
        else if armed { requestPermission(); scheduleCapture(after: 0.1) }
        onChange?()
    }

    // MARK: capture

    private func scheduleCapture(after delay: Double) {
        pending?.cancel()
        guard capturing else { return }
        let target = currentID
        let w = DispatchWorkItem { [weak self] in self?.capture(target) }
        pending = w
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: w)
    }

    private func capture(_ space: UInt64) {
        refresh()
        guard capturing, space != 0, space == currentID else { return }
        let done: (CGImage?) -> Void = { [weak self] img in
            DispatchQueue.main.async {
                // The user may have moved on (or turned sharing off) while the frame was taken.
                guard let self, self.capturing, space == self.currentID, let img, let jpeg = Spaces.jpeg(img) else { return }
                self.version += 1
                self.thumbs[space] = Thumb(v: self.version, w: img.width, h: img.height, jpeg: jpeg)
                self.onChange?()
            }
        }
        if fake != nil {
            done(Spaces.solid(index: current, of: count))
        } else if #available(macOS 14.0, *) {
            Spaces.captureMainDisplay(done)
        }
    }

    @available(macOS 14.0, *)
    private static func captureMainDisplay(_ done: @escaping (CGImage?) -> Void) {
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { content, err in
            guard let content, let d = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else {
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

    /// --fake-spaces: a solid colour per desktop (hue by index), 320x200.
    static func solid(index: Int, of n: Int) -> CGImage? {
        let w = thumbWidth, h = 200
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        let c = NSColor(hue: CGFloat(max(0, index - 1)) / CGFloat(max(1, n)), saturation: 0.6, brightness: 0.8, alpha: 1)
        ctx.setFillColor(c.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()
    }

    // MARK: goto

    /// `spaces.goto`: the user's Switch-to-Desktop shortcut if enabled, else ⌃←/⌃→ steps from the current index.
    func goto(_ i: Int) throws {
        refresh()
        let upper = count > 0 ? count : Spaces.maxDesktops
        guard (1...upper).contains(i) else { throw ProtoError("bad", "desktop index must be 1-\(upper)") }
        // Fake spaces always step, so tests don't depend on this Mac's shortcut settings.
        if fake == nil, let hk = SymbolicHotkeys.desktop(i) {
            if dryRun { log("DRY spaces goto=\(i) from=\(current) via=shortcut") } else { log("spaces: goto \(i) via shortcut") }
            try injector.key(usage: hk.usage, mods: hk.mods, action: "tap")
            return
        }
        guard currentID != 0, let from = all.firstIndex(of: currentID) else {
            throw ProtoError("unsupported", "current Space unknown; enable Switch to Desktop \(i) in Keyboard Shortcuts")
        }
        let to = all.firstIndex(of: desktops[i - 1]) ?? from
        let steps = to - from
        if dryRun { log("DRY spaces goto=\(i) from=\(current) via=step") } else { log("spaces: goto \(i) via \(abs(steps)) steps") }
        let usage = steps > 0 ? 79 : 80   // HID Right / Left arrow
        for k in 0..<abs(steps) {
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(80 * k)) { [weak self] in
                try? self?.injector.key(usage: usage, mods: 1, action: "tap")
            }
        }
        if fake != nil {
            // Simulate the Space change the real keys would cause.
            fakeCurrent = i
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(80 * abs(steps))) { [weak self] in self?.spaceChanged() }
        }
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

    struct Layout { let desktops: [UInt64]; let all: [UInt64]; let current: UInt64 }

    /// Spaces of the main display ("Main" when displays share Spaces, else the main display's UUID).
    static func read() -> Layout? {
        guard let mainConnection, let copyManaged else { return nil }
        let cid = mainConnection()
        guard let displays = copyManaged(cid)?.takeRetainedValue() as? [[String: Any]], !displays.isEmpty else { return nil }
        let mainUUID = CGDisplayCreateUUIDFromDisplayID(CGMainDisplayID()).map { CFUUIDCreateString(nil, $0.takeRetainedValue()) as String }
        let disp = displays.first { d in
            guard let id = d["Display Identifier"] as? String else { return false }
            return id == "Main" || (mainUUID.map { id.caseInsensitiveCompare($0) == .orderedSame } ?? false)
        } ?? displays[0]
        guard let spaces = disp["Spaces"] as? [[String: Any]] else { return nil }
        func id(_ s: [String: Any]) -> UInt64? { ((s["ManagedSpaceID"] ?? s["id64"]) as? NSNumber)?.uint64Value }
        let all = spaces.compactMap(id)
        let desktops = spaces.filter { (($0["type"] as? NSNumber)?.intValue ?? 0) == 0 }.compactMap(id)
        var current = (disp["Current Space"] as? [String: Any]).flatMap(id) ?? 0
        if current == 0, let activeSpace { current = activeSpace(cid) }
        return Layout(desktops: desktops, all: all, current: current)
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
