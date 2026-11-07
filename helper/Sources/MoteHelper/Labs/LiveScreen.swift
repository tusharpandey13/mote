import AppKit
import CoreImage
import CoreMedia
import CoreText
import ImageIO
import ScreenCaptureKit

/// Labs › Live screen (preview, docs/labs/live-screen.md): a low-rate JPEG stream of the active display to
/// one authenticated Wi-Fi / Tailscale viewer, and tap-to-click on it.
///
/// Flow control: every frame costs one credit and the viewer returns it with `labs.screen.ack`; with no
/// credit (or a socket backlog) the newest image just waits and is replaced by the next one, so frames are
/// dropped, never queued. In-flight bytes stay under `maxCredits * maxFrameBytes`, well below the 1 MiB
/// unread cap. The stream stops when the viewer says `stop`, disconnects, or goes quiet for `lease` s.
/// Frames live in memory only. Main-queue only, except the capture and encode queues noted below.
final class LiveScreen {
    static let minFps = 2.0, maxFps = 15.0
    static let minWidth = 480, maxWidth = 960
    static let maxCredits = 3, defaultCredits = 2
    static let maxFrameBytes = 240 * 1024     // a whole `labs.screen.frame` message, base64 included
    static let maxBacklog = 384 * 1024        // unsent socket bytes above which a frame is skipped
    static let lease = 2.0                    // the viewer sends something (ack) at least this often
    static let qualities = (min: 0.3, start: 0.55, max: 0.7)

    private let settings: Settings
    private let injector: Injector
    private let fake: Bool                    // --fake-screen (dry run): synthetic frames, never the real screen
    private let dryRun: Bool

    /// The one session being streamed to (a second `start` takes over).
    private(set) var viewer: Session?
    private(set) var viewerName = ""
    var onChange: (() -> Void)?

    /// Menu › Labs › "Pause screen sharing": stops any stream and refuses new ones until resumed.
    var paused = false {
        didSet {
            guard paused != oldValue else { return }
            log("screen: sharing \(paused ? "paused" : "resumed")")
            if paused { stop("paused") }
            onChange?()
        }
    }

    var streaming: Bool { viewer != nil }

    // Stream state (reset on start).
    private var seq = 0
    private var inflight: [Int: Double] = [:]   // seq -> send time
    private var credits = defaultCredits, maxCreditsReq = defaultCredits
    private var fps = 8.0, fpsCap = maxFps
    private var quality = qualities.start
    private var width = 800, widthCap = maxWidth
    private var rtt = 0.0                       // ms, EWMA of send -> ack
    private var lastHeard = 0.0
    private var tick: Timer?
    private var adaptTimer: Timer?
    private var starved = 0, dropped = 0, sent = 0, bytes = 0, encoding = false
    private var latest: (img: CGImage, at: Double, cursor: CGPoint?)?
    private var current: ScreenMath.Geometry?
    private var geometries: [String: ScreenMath.Geometry] = [:]   // display id -> geometry of frames sent recently
    private let capture = Capture()
    private let encodeQ = DispatchQueue(label: "mote.screen.encode", qos: .userInitiated)
    private var fakeN = 0

    init(config: Config, settings: Settings, injector: Injector) {
        self.settings = settings
        self.injector = injector
        dryRun = config.dryRun
        fake = config.dryRun && config.fakeScreen
        if config.fakeScreen && !config.dryRun { log("--fake-screen needs --dry-run; ignoring it") }
        if fake { log("fake screen: \(ScreenMath.fakeDisplay.id) \(Int(ScreenMath.fakeDisplay.bounds.width))x\(Int(ScreenMath.fakeDisplay.bounds.height)) pt at \(ScreenMath.fakeDisplay.bounds.origin) scale \(ScreenMath.fakeDisplay.scale)") }
        capture.onFrame = { [weak self] img, at in
            DispatchQueue.main.async { self?.captured(img, at: at) }
        }
    }

    private static var now: Double { Date().timeIntervalSince1970 }

    // MARK: messages

    /// `labs.screen.*` from an authenticated session. Returns a reply, or nil.
    func handle(_ t: String, _ m: [String: Any], from s: Session, input: () throws -> Void) throws -> [String: Any]? {
        switch t {
        case "labs.screen.start": return try start(m, from: s)
        case "labs.screen.stop":
            if viewer === s { stop("stop") }
            return nil
        case "labs.screen.ack":
            guard viewer === s else { return nil }
            lastHeard = LiveScreen.now
            if let n = m["seq"] as? Int, let sentAt = inflight.removeValue(forKey: n) {
                credits = min(maxCreditsReq, credits + 1)
                let r = (LiveScreen.now - sentAt) * 1000
                rtt = rtt == 0 ? r : rtt * 0.8 + r * 0.2
            }
            return nil
        case "labs.screen.click":
            guard viewer === s else { throw ProtoError("notstreaming", "start the live screen first") }
            lastHeard = LiveScreen.now
            try input()   // pause / Accessibility checks
            try click(m)
            return nil
        default: throw ProtoError("unknown", "unknown message '\(t)'")
        }
    }

    private func start(_ m: [String: Any], from s: Session) throws -> [String: Any] {
        guard s.transport != "BLE" else { throw ProtoError("unsupported", "Live screen needs Wi-Fi or Tailscale") }
        guard settings.labsLiveScreen else { throw ProtoError("labs-off", "Turn on Labs › Live screen in the Mote Helper menu on the Mac") }
        guard !paused else { throw ProtoError("sharing-paused", "Screen sharing is paused in the Mote Helper menu") }
        if !fake {
            guard !dryRun else { throw ProtoError("unavailable", "Live screen is off in a dry run (use --fake-screen)") }
            guard CGPreflightScreenCaptureAccess() else {
                _ = CGRequestScreenCaptureAccess()
                throw ProtoError("denied", "Allow Screen Recording for Mote Helper in System Settings")
            }
        }
        if let old = viewer, old !== s { stop("taken") }
        let reqW = (m["w"] as? Int) ?? 800, reqF = (m["fps"] as? Double) ?? Double((m["fps"] as? Int) ?? 10)
        let reqC = (m["credits"] as? Int) ?? LiveScreen.defaultCredits
        widthCap = min(max(reqW, LiveScreen.minWidth), LiveScreen.maxWidth)
        fpsCap = min(max(reqF, LiveScreen.minFps), LiveScreen.maxFps)
        maxCreditsReq = min(max(reqC, 1), LiveScreen.maxCredits)
        let restart = viewer === s
        viewer = s; viewerName = s.deviceName
        seq = 0; inflight = [:]; credits = maxCreditsReq
        fps = min(8, fpsCap); quality = LiveScreen.qualities.start; width = widthCap
        rtt = 0; starved = 0; dropped = 0; sent = 0; bytes = 0; latest = nil
        lastHeard = LiveScreen.now
        retarget(force: true)
        schedule()
        adaptTimer?.invalidate()
        adaptTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.adapt() }
        if !restart { log("screen: streaming to \(s.deviceName) via \(s.transport)") }
        onChange?()
        var r: [String: Any] = ["t": "labs.screen.started", "fps": fps, "w": width, "credits": maxCreditsReq,
                                "encrypted": s.transport == "Tailscale", "fake": fake]
        if let g = current { r["display"] = g.id; r["h"] = g.height(forWidth: width) }
        return r
    }

    /// Ends the stream (if any) and tells the viewer why: stop | paused | off | taken | timeout | gone | error.
    func stop(_ reason: String) {
        guard let v = viewer else { return }
        viewer = nil
        tick?.invalidate(); tick = nil
        adaptTimer?.invalidate(); adaptTimer = nil
        capture.stop()
        latest = nil; inflight = [:]; geometries = [:]; current = nil
        if reason != "gone" { v.send(["t": "labs.screen.stopped", "reason": reason]) }
        log("screen: stopped (\(reason)) after \(sent) frames, \(bytes / 1024) KiB, \(dropped) dropped")
        onChange?()
    }

    /// The session went away (closed, revoked, transport dropped).
    func sessionGone(_ s: Session) { if viewer === s { stop("gone") } }

    /// Labs toggle turned off.
    func labToggled() { if !settings.labsLiveScreen { stop("off") } }

    private func click(_ m: [String: Any]) throws {
        let x = try m.dbl("x"), y = try m.dbl("y")
        guard x.isFinite, y.isFinite, (0...1).contains(x), (0...1).contains(y) else { throw ProtoError("bad", "x and y must be 0..1") }
        let b = try m.str("button", "left")
        guard b == "left" || b == "right" else { throw ProtoError("bad", "button must be left or right") }
        let count = try m.int("count", 1)
        guard (1...2).contains(count) else { throw ProtoError("bad", "count must be 1 or 2") }
        let id = try m.optStr("display")
        guard let g = id.flatMap({ geometries[$0] }) ?? current else { throw ProtoError("notfound", "no frame shown yet") }
        let p = ScreenMath.point(nx: x, ny: y, in: g)
        try injector.clickAt(x: p.x, y: p.y, unit: "pt", display: 0, b: b, count: count)
    }

    // MARK: capture and send

    /// Picks the active display (frontmost window, else the cursor) and (re)starts capture on it if it moved.
    private func retarget(force: Bool = false) {
        let g: ScreenMath.Geometry
        if fake {
            g = ScreenMath.fakeDisplay
        } else {
            let id = Spaces.frontWindowDisplay() ?? Spaces.cursorDisplay() ?? CGMainDisplayID()
            let b = CGDisplayBounds(id)
            let px = CGFloat(CGDisplayPixelsWide(id))
            g = ScreenMath.Geometry(id: String(id), cgID: id, bounds: b, scale: b.width > 0 ? max(1, px / b.width) : 1)
        }
        guard force || g != current else { return }
        current = g
        geometries[g.id] = g
        if geometries.count > 4 { geometries = [g.id: g] }
        latest = nil
        if !fake { capture.start(g.cgID, width: width, height: g.height(forWidth: width), fps: fpsCap) }
    }

    private func schedule() {
        tick?.invalidate()
        tick = Timer.scheduledTimer(withTimeInterval: 1 / fps, repeats: true) { [weak self] _ in self?.step() }
        tick?.tolerance = 0.005
    }

    private func captured(_ img: CGImage, at: Double) {
        guard viewer != nil else { return }
        latest = (img, at, cursor())
    }

    private func cursor() -> CGPoint? {
        guard let g = current else { return nil }
        if fake { return CGPoint(x: 0.5 + 0.3 * cos(Double(fakeN) / 20), y: 0.5 + 0.3 * sin(Double(fakeN) / 20)) }
        guard let p = CGEvent(source: nil)?.location else { return nil }
        return ScreenMath.norm(p, in: g)
    }

    /// One frame period: drop the stream on a dead lease, else send the newest image if there is credit.
    private func step() {
        guard let v = viewer else { return }
        if LiveScreen.now - lastHeard > LiveScreen.lease { stop("timeout"); return }
        if fake {
            fakeN += 1
            if let g = current, let img = ScreenMath.fakeFrame(n: fakeN, width: width, height: g.height(forWidth: width)) {
                latest = (img, LiveScreen.now, cursor())
            }
        }
        guard let frame = latest, let g = current else { return }
        guard credits > 0, !encoding, v.backlog() < LiveScreen.maxBacklog else { starved += 1; dropped += 1; return }
        latest = nil
        encoding = true
        credits -= 1
        seq += 1
        let n = seq, q = quality, w = width, h = g.height(forWidth: width), rttNow = Int(rtt.rounded()), fpsNow = fps
        encodeQ.async { [weak self] in
            let jpeg = ScreenMath.jpeg(frame.img, width: w, height: h, quality: q, budget: LiveScreen.maxFrameBytes - 400)
            DispatchQueue.main.async {
                guard let self else { return }
                self.encoding = false
                guard self.viewer === v, let jpeg else { self.credits = min(self.maxCreditsReq, self.credits + 1); return }
                var m: [String: Any] = ["t": "labs.screen.frame", "seq": n, "display": g.id, "w": w, "h": h,
                                        "ts": Int(frame.at * 1000), "q": (jpeg.q * 100).rounded() / 100, "fps": (fpsNow * 10).rounded() / 10,
                                        "rtt": rttNow, "data": jpeg.data.base64EncodedString()]
                if let c = frame.cursor, (0...1).contains(c.x), (0...1).contains(c.y) { m["cx"] = (c.x * 1e4).rounded() / 1e4; m["cy"] = (c.y * 1e4).rounded() / 1e4 }
                self.inflight[n] = LiveScreen.now
                self.sent += 1; self.bytes += jpeg.data.count
                v.send(m)
            }
        }
    }

    /// Once a second: slow down / lower quality when frames were dropped or acks are slow, speed up when idle-fast.
    private func adapt() {
        guard viewer != nil else { return }
        retarget()
        let next = ScreenMath.adapt(fps: fps, quality: quality, width: width, starved: starved, rttMs: rtt, fpsCap: fpsCap, widthCap: widthCap)
        starved = 0
        let fpsChanged = next.fps != fps, widthChanged = next.width != width
        fps = next.fps; quality = next.quality; width = next.width
        if fpsChanged { schedule() }
        if widthChanged, !fake, let g = current { capture.update(width: width, height: g.height(forWidth: width), fps: fpsCap) }
        // Stale in-flight frames (a lost ack) give their credit back after 2 s, so the stream can't wedge.
        let cutoff = LiveScreen.now - 2
        for (k, t) in inflight where t < cutoff { inflight[k] = nil; credits = min(maxCreditsReq, credits + 1) }
    }
}

/// Pure math for the live screen (unit-tested in helper/tests/MoteHelperTests).
enum ScreenMath {
    /// A display in global CoreGraphics points (origin = top-left of the main display; y grows down).
    /// `scale` is backing pixels per point (2 on Retina). Frames are scaled images of `bounds`.
    struct Geometry: Equatable {
        let id: String
        var cgID: CGDirectDisplayID = 0
        let bounds: CGRect
        let scale: CGFloat
        func height(forWidth w: Int) -> Int { max(1, Int((Double(w) * Double(bounds.height) / Double(max(1, bounds.width))).rounded())) }
    }

    /// --fake-screen: a Retina display left of and above the main one (negative origin), 1512x982 pt.
    static let fakeDisplay = Geometry(id: "fake-screen", bounds: CGRect(x: -1512, y: -200, width: 1512, height: 982), scale: 2)

    /// Normalised frame point (0..1, top-left origin) -> global point on the display. Independent of the
    /// frame's pixel size and the Retina scale: frames always cover the whole display. Stays inside it.
    static func point(nx: Double, ny: Double, in g: Geometry) -> CGPoint {
        let x = min(max(nx, 0), 1), y = min(max(ny, 0), 1)
        return CGPoint(x: (g.bounds.minX + x * g.bounds.width).rounded(.down).clamped(g.bounds.minX, g.bounds.maxX - 1),
                       y: (g.bounds.minY + y * g.bounds.height).rounded(.down).clamped(g.bounds.minY, g.bounds.maxY - 1))
    }

    /// A frame pixel (in a frame `frameWidth` wide) -> global point. Retina-proof: pixels are scaled to points.
    static func point(px: Double, py: Double, frameWidth: Int, in g: Geometry) -> CGPoint {
        let h = Double(g.height(forWidth: frameWidth))
        return point(nx: px / Double(max(1, frameWidth)), ny: py / max(1, h), in: g)
    }

    /// Global point -> normalised frame point, or nil when it is off this display.
    static func norm(_ p: CGPoint, in g: Geometry) -> CGPoint? {
        guard g.bounds.contains(p) else { return nil }
        return CGPoint(x: (p.x - g.bounds.minX) / g.bounds.width, y: (p.y - g.bounds.minY) / g.bounds.height)
    }

    struct Rate: Equatable { let fps: Double; let quality: Double; let width: Int }

    /// Adaptive rate: dropped frames or acks slower than 250 ms step fps down (x0.75), then quality, then
    /// width; acks under 120 ms with nothing dropped step back up (fps +2, quality +0.05, width +160).
    static func adapt(fps: Double, quality: Double, width: Int, starved: Int, rttMs: Double, fpsCap: Double, widthCap: Int) -> Rate {
        let q = LiveScreen.qualities
        if starved > 0 || rttMs > 250 {
            if fps > LiveScreen.minFps { return Rate(fps: max(LiveScreen.minFps, (fps * 0.75 * 10).rounded() / 10), quality: quality, width: width) }
            if quality > q.min + 0.001 { return Rate(fps: fps, quality: max(q.min, quality - 0.1), width: width) }
            return Rate(fps: fps, quality: quality, width: max(LiveScreen.minWidth, width - 160))
        }
        if rttMs < 120 {
            if width < widthCap { return Rate(fps: fps, quality: quality, width: min(widthCap, width + 160)) }
            return Rate(fps: min(fpsCap, fps + 2), quality: min(q.max, quality + 0.05), width: width)
        }
        return Rate(fps: fps, quality: quality, width: width)
    }

    /// JPEG of `img` scaled to width x height, at `quality` stepped down until it fits `budget` bytes of base64.
    static func jpeg(_ img: CGImage, width: Int, height: Int, quality: Double, budget: Int) -> (data: Data, q: Double)? {
        var src = img
        if img.width != width || img.height != height {
            guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
            ctx.interpolationQuality = .medium
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: width, height: height))
            guard let s = ctx.makeImage() else { return nil }
            src = s
        }
        var q = quality
        while q >= 0.1 {
            let d = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(d, "public.jpeg" as CFString, 1, nil) else { return nil }
            CGImageDestinationAddImage(dest, src, [kCGImageDestinationLossyCompressionQuality: q] as CFDictionary)
            guard CGImageDestinationFinalize(dest) else { return nil }
            if (d.length + 2) / 3 * 4 <= budget { return (d as Data, q) }
            q -= 0.15
        }
        return nil
    }

    /// --fake-screen frame `n`: a diagonal gradient whose hue drifts, a sweeping bar, and the frame counter.
    static func fakeFrame(n: Int, width: Int, height: Int) -> CGImage? {
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        let h = CGFloat(n % 120) / 120
        let a = NSColor(hue: h, saturation: 0.55, brightness: 0.85, alpha: 1).cgColor
        let b = NSColor(hue: (h + 0.4).truncatingRemainder(dividingBy: 1), saturation: 0.6, brightness: 0.35, alpha: 1).cgColor
        if let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [a, b] as CFArray, locations: [0, 1]) {
            ctx.drawLinearGradient(grad, start: .zero, end: CGPoint(x: width, y: height), options: [])
        }
        let barX = CGFloat((n * 12) % max(1, width))
        ctx.setFillColor(NSColor.white.withAlphaComponent(0.7).cgColor)
        ctx.fill(CGRect(x: barX, y: 0, width: 10, height: CGFloat(height)))
        let font = CTFontCreateWithName("Menlo-Bold" as CFString, CGFloat(height) / 6, nil)
        let text = NSAttributedString(string: "#\(n)", attributes: [.font: font, .foregroundColor: NSColor.white])
        let line = CTLineCreateWithAttributedString(text)
        ctx.textPosition = CGPoint(x: CGFloat(width) * 0.06, y: CGFloat(height) * 0.12)
        CTLineDraw(line, ctx)
        return ctx.makeImage()
    }
}

private extension CGFloat {
    func clamped(_ lo: CGFloat, _ hi: CGFloat) -> CGFloat { Swift.min(Swift.max(self, lo), Swift.max(lo, hi)) }
}

/// One SCStream on one display. Frames arrive on `q`; only complete (changed) frames are passed on,
/// so a still screen sends nothing. Generation-guarded: a stop during the async start wins.
private final class Capture: NSObject, SCStreamOutput, SCStreamDelegate {
    var onFrame: ((CGImage, Double) -> Void)?
    private var stream: SCStream?
    private var gen = 0
    private let q = DispatchQueue(label: "mote.screen.capture", qos: .userInitiated)
    private let ci = CIContext(options: [.cacheIntermediates: false])

    private func config(width: Int, height: Int, fps: Double) -> SCStreamConfiguration {
        let c = SCStreamConfiguration()
        c.width = width; c.height = height
        c.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, fps.rounded())))
        c.queueDepth = 3
        c.showsCursor = false                 // the phone draws the cursor from `cx`/`cy`
        c.pixelFormat = kCVPixelFormatType_32BGRA
        return c
    }

    func start(_ id: CGDirectDisplayID, width: Int, height: Int, fps: Double) {
        stop()
        gen += 1
        let g = gen
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { [weak self] content, err in
            DispatchQueue.main.async {
                guard let self, g == self.gen else { return }
                guard let d = content?.displays.first(where: { $0.displayID == id }) ?? content?.displays.first else {
                    log("screen: capture unavailable: \(err?.localizedDescription ?? "no display")"); return
                }
                let s = SCStream(filter: SCContentFilter(display: d, excludingWindows: []), configuration: self.config(width: width, height: height, fps: fps), delegate: self)
                do { try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: self.q) } catch { log("screen: \(error.localizedDescription)"); return }
                self.stream = s
                s.startCapture { e in if let e { log("screen: capture failed: \(e.localizedDescription)") } }
            }
        }
    }

    func update(width: Int, height: Int, fps: Double) {
        stream?.updateConfiguration(config(width: width, height: height, fps: fps)) { e in if let e { log("screen: reconfigure failed: \(e.localizedDescription)") } }
    }

    func stop() {
        gen += 1
        stream?.stopCapture { _ in }
        stream = nil
    }

    func stream(_ s: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sb.isValid,
              let att = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = att.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        let img = CIImage(cvPixelBuffer: pb)
        guard let cg = ci.createCGImage(img, from: img.extent) else { return }
        onFrame?(cg, Date().timeIntervalSince1970)
    }

    func stream(_ s: SCStream, didStopWithError error: Error) { log("screen: stream stopped: \(error.localizedDescription)") }
}
