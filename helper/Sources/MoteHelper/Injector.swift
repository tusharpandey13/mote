import AppKit
import CoreGraphics
import Foundation

struct ProtoError: Error {
    let code: String
    let msg: String
    init(_ code: String, _ msg: String) { self.code = code; self.msg = msg }
}

// JSON accessors (JSONSerialization gives NSNumber / String / [Any]).
extension Dictionary where Key == String, Value == Any {
    func int(_ k: String, _ def: Int? = nil) throws -> Int {
        if let n = self[k] as? NSNumber, n.doubleValue.isFinite { return n.intValue }
        if let def { return def }
        throw ProtoError("bad", "missing number '\(k)'")
    }
    func dbl(_ k: String, _ def: Double? = nil) throws -> Double {
        if let n = self[k] as? NSNumber, n.doubleValue.isFinite { return n.doubleValue }
        if let def { return def }
        throw ProtoError("bad", "missing number '\(k)'")
    }
    /// Optional string: nil if absent or null, `bad` if present with another type.
    func optStr(_ k: String) throws -> String? {
        guard let v = self[k], !(v is NSNull) else { return nil }
        guard let s = v as? String else { throw ProtoError("bad", "'\(k)' must be a string") }
        return s
    }

    func str(_ k: String, _ def: String? = nil) throws -> String {
        if let s = self[k] as? String { return s }
        if let def { return def }
        throw ProtoError("bad", "missing string '\(k)'")
    }
}

/// Turns protocol input messages into CGEvents (or log lines with --dry-run).
final class Injector {
    let dryRun: Bool
    private let src = CGEventSource(stateID: .hidSystemState)
    private var held: Set<String> = []   // mouse buttons currently down

    init(dryRun: Bool) { self.dryRun = dryRun }

    func exec(_ m: [String: Any]) throws {
        switch try m.str("t") {
        case "key": try key(usage: m.int("usage"), mods: m.int("mods", 0), action: m.str("action", "tap"))
        case "text": try text(m.str("s"))
        case "move": try move(dx: m.dbl("dx", 0), dy: m.dbl("dy", 0))
        case "button": try button(m.str("b", "left"), action: m.str("action", "click"), count: m.int("count", 1))
        case "scroll": try scroll(dx: m.int("dx", 0), dy: m.int("dy", 0))
        case "clickAt":
            try clickAt(x: m.dbl("x"), y: m.dbl("y"), unit: m.str("unit", "norm"), display: m.int("display", 0),
                        b: m.str("b", "left"), count: m.int("count", 1))
        case "consumer": try consumer(m.str("key"), action: m.str("action", "tap"))
        case let t: throw ProtoError("unknown", "unknown message '\(t)'")
        }
    }

    // MARK: keys

    private static let modKeys: [(bit: Int, vk: CGKeyCode, flag: CGEventFlags)] = [
        (1, 0x3B, .maskControl), (2, 0x38, .maskShift), (4, 0x3A, .maskAlternate), (8, 0x37, .maskCommand), (256, 0x3F, .maskSecondaryFn),
    ]

    func key(usage: Int, mods: Int, action: String) throws {
        guard let vk = KeyMap.vk[usage] else { throw ProtoError("bad", "unsupported usage \(usage)") }
        guard ["tap", "down", "up"].contains(action) else { throw ProtoError("bad", "bad action") }
        if dryRun { log("DRY key usage=\(usage) mods=\(mods) action=\(action)"); return }
        let mk = Injector.modKeys.filter { mods & $0.bit != 0 }
        var flags = CGEventFlags()
        if action != "up" {
            for m in mk { flags.insert(m.flag); post(keyEvent(m.vk, down: true, flags: flags)) }
        } else {
            for m in mk { flags.insert(m.flag) }
        }
        var kf = flags
        if KeyMap.navKeys.contains(vk) { kf.insert(.maskSecondaryFn) }
        if KeyMap.arrows.contains(vk) { kf.insert(.maskNumericPad) }
        if action != "up" { post(keyEvent(vk, down: true, flags: kf)) }
        if action != "down" {
            post(keyEvent(vk, down: false, flags: kf))
            for m in mk.reversed() { flags.remove(m.flag); post(keyEvent(m.vk, down: false, flags: flags)) }
        }
    }

    private func keyEvent(_ vk: CGKeyCode, down: Bool, flags: CGEventFlags) -> CGEvent? {
        let e = CGEvent(keyboardEventSource: src, virtualKey: vk, keyDown: down)
        e?.flags = flags
        return e
    }

    func text(_ s: String) throws {
        guard s.utf16.count <= 2000 else { throw ProtoError("bad", "text too long") }
        if dryRun { log("DRY text \(s.debugDescription)"); return }
        var chunk: [UniChar] = []
        func flush() {
            guard !chunk.isEmpty else { return }
            for down in [true, false] {
                let e = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: down)
                e?.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                post(e)
            }
            chunk.removeAll()
            usleep(2000)
        }
        for ch in s {
            switch ch {
            case "\n", "\r\n", "\r": flush(); try key(usage: 40, mods: 0, action: "tap")
            case "\t": flush(); try key(usage: 43, mods: 0, action: "tap")
            default:
                let u = Array(String(ch).utf16)
                if chunk.count + u.count > 20 { flush() }   // CGEvent unicode strings are capped at 20 units
                chunk += u
            }
        }
        flush()
    }

    // MARK: mouse

    private var cursor: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    private func displayBounds() -> [CGRect] {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        CGGetActiveDisplayList(16, &ids, &n)
        return ids.prefix(Int(n)).map { CGDisplayBounds($0) }
    }

    private func clamp(_ p: CGPoint) -> CGPoint {
        let ds = displayBounds()
        if ds.contains(where: { $0.contains(p) }) { return p }
        // Snap to the nearest point on the nearest display.
        var best = p, bestD = CGFloat.greatestFiniteMagnitude
        for r in ds {
            let q = CGPoint(x: min(max(p.x, r.minX), r.maxX - 1), y: min(max(p.y, r.minY), r.maxY - 1))
            let d = hypot(q.x - p.x, q.y - p.y)
            if d < bestD { bestD = d; best = q }
        }
        return best
    }

    func move(dx: Double, dy: Double) throws {
        guard abs(dx) <= 4000, abs(dy) <= 4000 else { throw ProtoError("bad", "move too large") }
        if dryRun { log("DRY move dx=\(dx) dy=\(dy)"); return }
        let p = clamp(CGPoint(x: cursor.x + dx, y: cursor.y + dy))
        let type: CGEventType = held.contains("left") ? .leftMouseDragged : held.contains("right") ? .rightMouseDragged
            : held.contains("middle") ? .otherMouseDragged : .mouseMoved
        let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: .left)
        e?.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx))
        e?.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy))
        post(e)
    }

    private func buttonTypes(_ b: String) throws -> (CGEventType, CGEventType, CGMouseButton) {
        switch b {
        case "left": return (.leftMouseDown, .leftMouseUp, .left)
        case "right": return (.rightMouseDown, .rightMouseUp, .right)
        case "middle": return (.otherMouseDown, .otherMouseUp, .center)
        default: throw ProtoError("bad", "bad button")
        }
    }

    func button(_ b: String, action: String, count: Int, at: CGPoint? = nil) throws {
        let (dn, up, btn) = try buttonTypes(b)
        guard ["click", "down", "up"].contains(action), (1...3).contains(count) else { throw ProtoError("bad", "bad button action") }
        if dryRun { log("DRY button \(b) \(action) count=\(count)" + (at.map { " at=\($0)" } ?? "")); return }
        let p = at ?? cursor
        func ev(_ t: CGEventType) {
            let e = CGEvent(mouseEventSource: src, mouseType: t, mouseCursorPosition: p, mouseButton: btn)
            e?.setIntegerValueField(.mouseEventClickState, value: Int64(count))
            post(e)
        }
        if action != "up" { ev(dn); held.insert(b) }
        if action != "down" { ev(up); held.remove(b) }
    }

    func scroll(dx: Int, dy: Int) throws {
        let dx = max(-2000, min(2000, dx)), dy = max(-2000, min(2000, dy))
        if dryRun { log("DRY scroll dx=\(dx) dy=\(dy)"); return }
        // CG: positive wheel1 scrolls the view up; protocol: dy > 0 moves the view down.
        post(CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 2, wheel1: Int32(-dy), wheel2: Int32(-dx), wheel3: 0))
    }

    func clickAt(x: Double, y: Double, unit: String, display: Int, b: String, count: Int) throws {
        var p: CGPoint
        switch unit {
        case "pt": p = CGPoint(x: x, y: y)
        case "norm":
            guard (0...1).contains(x), (0...1).contains(y) else { throw ProtoError("bad", "norm coords must be 0..1") }
            var ids = [CGDirectDisplayID](repeating: 0, count: 16)
            var n: UInt32 = 0
            CGGetActiveDisplayList(16, &ids, &n)
            // Index 0 = main display, then the others in system order.
            let order = [CGMainDisplayID()] + ids.prefix(Int(n)).filter { $0 != CGMainDisplayID() }
            guard display >= 0, display < order.count else { throw ProtoError("bad", "no display \(display)") }
            let r = CGDisplayBounds(order[display])
            p = CGPoint(x: r.minX + x * (r.width - 1), y: r.minY + y * (r.height - 1))
        default: throw ProtoError("bad", "bad unit")
        }
        if dryRun { log("DRY clickAt \(p) b=\(b) count=\(count)"); return }
        p = clamp(p)
        CGWarpMouseCursorPosition(p)
        CGAssociateMouseAndMouseCursorPosition(1)
        if count > 0 { try button(b, action: "click", count: count, at: p) }
    }

    // MARK: media / brightness

    private static let nx: [String: Int32] = [
        "volup": 0, "voldown": 1, "brightup": 2, "brightdown": 3, "mute": 7, "play": 16, "next": 17, "prev": 18,
        "fast": 19, "rewind": 20,
    ]

    /// The consumer key held by `action: down` (hold-to-seek), re-posted as key repeat until `up`.
    private var heldNX: (code: Int32, timer: DispatchSourceTimer)?

    func consumer(_ k: String, action: String = "tap") throws {
        guard let code = Injector.nx[k] else { throw ProtoError("bad", "unknown consumer key '\(k)'") }
        switch action {
        case "tap": break
        case "down", "up":
            if dryRun { log("DRY consumer \(k) \(action)"); return }
            releaseNX()
            if action == "down" { holdNX(code) }
            return
        default: throw ProtoError("bad", "unknown consumer action '\(action)'")
        }
        if dryRun { log("DRY consumer \(k)"); return }
        let before = k.hasPrefix("bright") ? Brightness.get() : nil
        postNX(code)
        // NX brightness keys are ignored on some Macs/displays; verify and fall back to DisplayServices.
        if let before {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                guard let now = Brightness.get(), abs(now - before) < 0.001 else { return }
                let target = max(0, min(1, before + (k == "brightup" ? 1 : -1) / 16))
                if !Brightness.set(target) { log("brightness: NX key and DisplayServices both unavailable") }
            }
        }
    }

    private func postNX(_ code: Int32) {
        postNX(code, down: true)
        postNX(code, down: false)
    }

    /// One NX key event; `repeating` sets the key-repeat bit like a held hardware media key.
    private func postNX(_ code: Int32, down: Bool, repeating: Bool = false) {
        let flags = NSEvent.ModifierFlags(rawValue: down ? 0xa00 : 0xb00)
        let data1 = Int((code << 16) | ((down ? 0xa : 0xb) << 8) | (repeating ? 1 : 0))
        let e = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0,
                                   windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)
        post(e?.cgEvent)
    }

    private func holdNX(_ code: Int32) {
        postNX(code, down: true)
        let t = DispatchSource.makeTimerSource(queue: .main)
        t.schedule(deadline: .now() + 0.3, repeating: 0.1)
        t.setEventHandler { [weak self] in self?.postNX(code, down: true, repeating: true) }
        t.resume()
        heldNX = (code, t)
    }

    private func releaseNX() {
        guard let h = heldNX else { return }
        h.timer.cancel()
        postNX(h.code, down: false)
        heldNX = nil
    }

    private func post(_ e: CGEvent?) { e?.post(tap: .cghidEventTap) }

    /// Releases any mouse buttons we left down (pause, disconnect).
    func releaseAll() {
        releaseNX()
        for b in held { try? button(b, action: "up", count: 1) }
        held.removeAll()
    }
}

/// Built-in display brightness via the private DisplayServices framework, loaded lazily; nil/false if missing.
enum Brightness {
    private typealias Get = @convention(c) (CGDirectDisplayID, UnsafeMutablePointer<Float>) -> Int32
    private typealias Set = @convention(c) (CGDirectDisplayID, Float) -> Int32
    private static let handle = dlopen("/System/Library/PrivateFrameworks/DisplayServices.framework/DisplayServices", RTLD_LAZY)
    private static let getFn: Get? = handle.flatMap { dlsym($0, "DisplayServicesGetBrightness") }.map { unsafeBitCast($0, to: Get.self) }
    private static let setFn: Set? = handle.flatMap { dlsym($0, "DisplayServicesSetBrightness") }.map { unsafeBitCast($0, to: Set.self) }

    static var builtin: CGDirectDisplayID? {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        CGGetOnlineDisplayList(16, &ids, &n)
        return ids.prefix(Int(n)).first { CGDisplayIsBuiltin($0) != 0 }
    }

    static func get() -> Float? {
        guard let f = getFn, let d = builtin else { return nil }
        var v: Float = 0
        return f(d, &v) == 0 ? v : nil
    }

    static func set(_ v: Float) -> Bool {
        guard let f = setFn, let d = builtin else { return false }
        return f(d, v) == 0
    }
}

/// USB HID keyboard usage (page 0x07) -> macOS virtual key code (ANSI positions).
enum KeyMap {
    static let vk: [Int: CGKeyCode] = {
        var m: [Int: CGKeyCode] = [:]
        // a..z
        let letters: [CGKeyCode] = [0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
                                    0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06]
        for (i, v) in letters.enumerated() { m[4 + i] = v }
        // 1..9, 0
        let digits: [CGKeyCode] = [0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19, 0x1D]
        for (i, v) in digits.enumerated() { m[30 + i] = v }
        let rest: [Int: CGKeyCode] = [
            40: 0x24, 41: 0x35, 42: 0x33, 43: 0x30, 44: 0x31, 45: 0x1B, 46: 0x18, 47: 0x21, 48: 0x1E, 49: 0x2A,
            50: 0x2A, 51: 0x29, 52: 0x27, 53: 0x32, 54: 0x2B, 55: 0x2F, 56: 0x2C, 57: 0x39,
            58: 0x7A, 59: 0x78, 60: 0x63, 61: 0x76, 62: 0x60, 63: 0x61, 64: 0x62, 65: 0x64, 66: 0x65, 67: 0x6D, 68: 0x67, 69: 0x6F,
            73: 0x72, 74: 0x73, 75: 0x74, 76: 0x75, 77: 0x77, 78: 0x79, 79: 0x7C, 80: 0x7B, 81: 0x7D, 82: 0x7E,
            100: 0x0A, 104: 0x69, 105: 0x6B, 106: 0x71, 107: 0x6A, 108: 0x40, 109: 0x4F, 110: 0x50, 111: 0x5A,
            224: 0x3B, 225: 0x38, 226: 0x3A, 227: 0x37, 228: 0x3E, 229: 0x3C, 230: 0x3D, 231: 0x36,
        ]
        m.merge(rest) { a, _ in a }
        return m
    }()

    /// The nav cluster carries the fn flag, arrows also the numeric-pad flag, on real Apple keyboards (needed for ⌃← Spaces etc.).
    static let navKeys: Set<CGKeyCode> = [0x7B, 0x7C, 0x7D, 0x7E, 0x73, 0x74, 0x75, 0x77, 0x79, 0x72]
    static let arrows: Set<CGKeyCode> = [0x7B, 0x7C, 0x7D, 0x7E]
}
