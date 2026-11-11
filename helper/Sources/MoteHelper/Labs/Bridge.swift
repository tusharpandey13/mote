import AppKit
import CryptoKit
import Foundation
import Network

// Labs (preview): Mote Video Bridge. A loopback-only WebSocket for the browser extension, which
// reports the active tab's main <video>; the helper relays that state to subscribed phones and
// routes their `labs.video.cmd` back. Contract and threat model: docs/labs/video-bridge.md.

/// Bridge options, read straight from the command line (Config ignores unknown flags).
struct BridgeOptions {
    var forceOn = false          // --labs-bridge: toggle on for this run (tests)
    var port: UInt16 = 8792      // --bridge-port N (0 = any free port)

    static func parse(_ args: [String]) -> BridgeOptions {
        var o = BridgeOptions()
        var it = args.dropFirst().makeIterator()
        while let a = it.next() {
            switch a {
            case "--labs-bridge": o.forceOn = true
            case "--bridge-port": if let v = it.next(), let p = UInt16(v) { o.port = p }
            default: break
            }
        }
        return o
    }
}

/// Main-queue only, like Hub.
final class Bridge {
    static let commands: Set<String> = ["play", "pause", "toggle", "seek", "seekBy", "rate", "volume", "mute",
                                        "fullscreen", "next", "prev", "captions", "skip"]
    static let skipKinds: Set<String> = ["ad", "intro", "recap", "credits"]

    private unowned let hub: Hub
    let options: BridgeOptions
    private let defaults: UserDefaults?
    let store: DeviceStore              // one "device" per paired extension; name = its pinned origin
    let pairing = Pairing()
    private let limiter = RateLimiter()
    private var listener: NWListener?
    private var conns: [ObjectIdentifier: BridgeConn] = [:]
    private var current: BridgeConn?    // the client whose state is relayed
    private var subs: [ObjectIdentifier: () -> Session?] = [:]   // phone sessions subscribed to labs.video.state
    private(set) var status = "off"
    private(set) var boundPort: UInt16 = 0

    init(hub: Hub, options: BridgeOptions = .parse(CommandLine.arguments)) {
        self.hub = hub
        self.options = options
        defaults = hub.config.memoryStore ? nil : UserDefaults.standard
        store = DeviceStore(vault: hub.vault, path: \.bridge)
        pairing.onChange = { [weak self] in self?.changed() }
        if enabled { start() }
    }

    /// Menu › Labs › "Browser extension bridge". Default off; persisted.
    private(set) lazy var enabledValue = options.forceOn || (defaults?.bool(forKey: "labsBridge") ?? false)
    var enabled: Bool {
        get { enabledValue }
        set {
            guard newValue != enabledValue else { return }
            enabledValue = newValue
            defaults?.set(newValue, forKey: "labsBridge")
            log("bridge: \(newValue ? "on" : "off")")
            ev(.labs, "toggle", ["lab": "bridge", "on": newValue])
            newValue ? start() : stop()
            changed()
        }
    }

    var connected: Bool { conns.values.contains { $0.authed } }
    var pairedCount: Int { store.devices.count }

    private func changed() { broadcast(); hub.onChange?() }

    // MARK: listener

    private func start() {
        guard listener == nil else { return }
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        // Bind 127.0.0.1 only: the port isn't reachable from the network at all.
        params.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: options.port) ?? .any)
        do {
            let l = try NWListener(using: params)
            l.stateUpdateHandler = { [weak self, weak l] st in
                guard let self else { return }
                switch st {
                case .ready:
                    self.boundPort = l?.port?.rawValue ?? self.options.port
                    self.status = "listening on 127.0.0.1:\(self.boundPort)"
                case .failed(let e): self.status = "failed: \(e)"
                default: return
                }
                log("bridge: \(self.status)")
                self.hub.onChange?()
            }
            l.newConnectionHandler = { [weak self] c in self?.accept(c) }
            l.start(queue: .main)
            listener = l
        } catch {
            status = "failed: \(error)"; log("bridge: \(status)")
        }
    }

    private func stop() {
        listener?.cancel(); listener = nil
        for c in conns.values { c.conn.cancel() }
        conns = [:]; current = nil
        pairing.close()
        status = "off"; boundPort = 0
    }

    private func accept(_ c: NWConnection) {
        guard case .hostPort(let host, _) = c.endpoint, isLoopback(host) else {
            log("bridge: refused non-loopback peer \(c.endpoint)")
            c.cancel(); return
        }
        guard conns.count < 8 else { log("bridge: connection limit"); c.cancel(); return }
        let b = BridgeConn(conn: c, bridge: self)
        conns[ObjectIdentifier(b)] = b
        b.start()
    }

    fileprivate func finished(_ b: BridgeConn) {
        conns[ObjectIdentifier(b)] = nil
        if current === b { current = conns.values.filter { $0.authed && $0.state?["active"] as? Bool == true }.last }
        changed()
    }

    /// Host header: only the loopback names of this port (DNS-rebinding guard).
    fileprivate func hostAllowed(_ h: String) -> Bool {
        let p = boundPort
        return ["127.0.0.1:\(p)", "localhost:\(p)"].contains(h.lowercased())
    }

    /// Origin header: a browser extension. Pages can't forge Origin; native processes can, hence the token.
    fileprivate func originAllowed(_ o: String?) -> Bool {
        guard let o = o?.lowercased(), let r = o.range(of: "://") else { return false }
        let scheme = o[..<r.lowerBound], id = o[r.upperBound...]
        return ["chrome-extension", "moz-extension", "safari-web-extension"].contains(String(scheme))
            && !id.isEmpty && id.count <= 64 && id.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" }
    }

    // MARK: extension messages

    fileprivate func handle(_ m: [String: Any], t: String, from b: BridgeConn) {
        switch t {
        case "ping": b.send(["t": "pong", "n": (m["n"] as? NSNumber)?.intValue ?? 0])
        case "pong": break
        case "labs.video.pair":
            if let wait = limiter.retryAfter(b.peer) { b.err("rate", "too many attempts", ref: t, retryAfter: wait); return }
            switch pairing.check((m["code"] as? String) ?? "") {
            case .closed: b.err("pairing-closed", "Open Labs › Pair browser extension… in the Mote Helper menu", ref: t)
            case .wrong: limiter.fail(b.peer); log("bridge: wrong pairing code"); b.err("code", "Wrong code", ref: t)
            case .ok:
                // One extension at a time: a new pairing replaces the old one, pinned to this origin.
                store.revokeAll()
                let d = store.add(name: b.origin)
                pairing.close()
                log("bridge: paired \(b.origin)")
                b.send(["t": "labs.video.paired", "id": d.id, "token": d.token])
                changed()
            }
        case "labs.video.auth":
            guard !b.authed else { return }
            if let wait = limiter.retryAfter(b.peer) { b.err("rate", "too many attempts", ref: t, retryAfter: wait); return }
            guard let id = m["id"] as? String, let mac = m["mac"] as? String,
                  let d = store.verify(id: id, nonce: b.nonce, macHex: mac), d.name == b.origin else {
                limiter.fail(b.peer)
                log("bridge: auth failed from \(b.origin)")
                b.err("auth", "Unknown extension or bad credentials", ref: t)
                return
            }
            b.authed = true
            log("bridge: authed \(b.origin)")
            b.send(["t": "labs.video.authed", "id": d.id])
            changed()
        default:
            guard b.authed else { b.strike(t); return }
            switch t {
            case "labs.video.state":
                let st = Bridge.sanitize(m)
                b.state = st
                if st["active"] as? Bool == true || current == nil || current === b { current = b }
                broadcast()
            case "labs.video.result":
                if (m["ok"] as? Bool) == false {
                    let code = (m["code"] as? String).map { String($0.prefix(32)) } ?? "notfound"
                    let cmd = (m["cmd"] as? String).map { String($0.prefix(32)) } ?? ""
                    log("bridge: \(cmd) failed: \(code)")
                    for s in subscribers() { s.send(["t": "err", "code": code, "msg": "The page couldn't do \(cmd)", "ref": "labs.video.cmd"]) }
                }
            default: b.err("unknown", "unknown message", ref: t)
            }
        }
    }

    // MARK: phone side (called by Session after `authed`)

    func phone(_ t: String, _ m: [String: Any], from s: Session) throws {
        switch t {
        case "labs.video.sub":
            let id = ObjectIdentifier(s)
            guard (m["on"] as? Bool) ?? true else { subs[id] = nil; ev(.labs, "subscribe", ["lab": "bridge", "on": false]); return }
            subs[id] = { [weak s] in s }
            ev(.labs, "subscribe", ["lab": "bridge", "on": true])
            s.send(stateMessage())
        case "labs.video.cmd":
            guard enabled else { throw ProtoError("unsupported", "Browser extension bridge is off on the Mac") }
            guard !hub.paused else { throw ProtoError("paused", "input is paused on the Mac") }
            guard let cmd = m["cmd"] as? String, Bridge.commands.contains(cmd) else { throw ProtoError("bad", "unknown cmd") }
            var out: [String: Any] = ["t": "labs.video.cmd", "cmd": cmd]
            if let v = m["value"], !(v is NSNull) {
                switch cmd {
                case "seek", "seekBy", "rate", "volume":
                    guard let n = Bridge.number(v) else { throw ProtoError("bad", "'value' must be a number") }
                    out["value"] = cmd == "rate" ? min(max(n, 0.25), 4) : cmd == "volume" ? min(max(n, 0), 1) : cmd == "seek" ? max(n, 0) : min(max(n, -3600), 3600)
                case "mute", "fullscreen", "captions":
                    guard let b = Bridge.bool(v) else { throw ProtoError("bad", "'value' must be a bool") }
                    out["value"] = b
                case "skip":
                    guard let k = v as? String, Bridge.skipKinds.contains(k) else { throw ProtoError("bad", "unknown skip kind") }
                    out["value"] = k
                default: break
                }
            } else if ["seek", "seekBy", "rate", "volume"].contains(cmd) {
                throw ProtoError("bad", "missing number 'value'")
            }
            guard let c = current, c.authed, c.state?["active"] as? Bool == true else {
                throw ProtoError("notfound", "No video in the browser")
            }
            log("bridge: cmd \(cmd)\(out["value"].map { " \($0)" } ?? "")")
            c.send(out)
        default:
            throw ProtoError("unknown", "unknown message")
        }
    }

    func isSubscribed(_ s: Session) -> Bool { subs[ObjectIdentifier(s)]?() != nil }

    private func subscribers() -> [Session] {
        var out: [Session] = []
        for (k, get) in subs { if let s = get(), s.authed { out.append(s) } else { subs[k] = nil } }
        return out
    }

    func stateMessage() -> [String: Any] {
        var m: [String: Any] = ["t": "labs.video.state", "bridge": !enabled ? "off" : connected ? "connected" : "waiting", "active": false]
        if enabled, let c = current, c.authed, let st = c.state { m.merge(st) { _, n in n } }
        return m
    }

    private func broadcast() {
        let subs = subscribers()
        guard !subs.isEmpty else { return }
        let m = stateMessage()
        ev(.labs, "push", coalesce: "bridge", ["lab": "bridge", "sessions": subs.count])
        for s in subs { s.send(m) }
    }

    /// Menu › Labs › "Forget browser extension" (and Revoke all devices).
    func forget() {
        store.revokeAll()
        for c in conns.values where c.authed { c.revoke("The extension was forgotten on the Mac") }
        log("bridge: extension forgotten")
        changed()
    }

    func openPairing() {
        guard enabled else { log("bridge: turn the bridge on first"); return }
        pairing.open()
        if let code = pairing.code { print("BRIDGE CODE: \(code)"); fflush(stdout) }
    }

    /// Test-only stdin commands (with --stdin-control --dry-run): `bridge pair|on|off|forget`.
    func stdin(_ line: String) {
        switch line {
        case "bridge pair": openPairing()
        case "bridge on": enabled = true
        case "bridge off": enabled = false
        case "bridge forget": forget()
        default: log("stdin-control: unknown command '\(line)'")
        }
    }

    // MARK: state sanitising (page content -> phone)

    static func number(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite else { return nil }
        return n.doubleValue
    }

    static func bool(_ v: Any?) -> Bool? {
        guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }

    /// Rebuilds a `labs.video.state` from whitelisted, type-checked fields only.
    static func sanitize(_ m: [String: Any]) -> [String: Any] {
        var o: [String: Any] = ["active": bool(m["active"]) ?? false]
        guard o["active"] as? Bool == true else { return o }
        func str(_ k: String, _ max: Int) { if let s = m[k] as? String { o[k] = String(s.prefix(max)) } }
        str("title", 200)
        str("host", 100)
        if let s = m["site"] as? String, ["youtube", "netflix", "prime", "twitch", "generic"].contains(s) { o["site"] = s }
        if let d = number(m["duration"]), d >= 0 { o["duration"] = d } else { o["duration"] = NSNull() }
        if let t = number(m["time"]) { o["time"] = max(t, 0) }
        if let r = number(m["rate"]) { o["rate"] = min(max(r, 0.0625), 16) }
        if let v = number(m["volume"]) { o["volume"] = min(max(v, 0), 1) }
        for k in ["paused", "muted", "fullscreen", "live", "seekable", "hasNext", "hasPrev"] { if let b = bool(m[k]) { o[k] = b } }
        if let c = m["captions"] as? String, ["on", "off", "none"].contains(c) { o["captions"] = c }
        if let a = m["skips"] as? [Any] { o["skips"] = Array(Set(a.compactMap { $0 as? String }).intersection(skipKinds)).sorted() }
        return o
    }
}

private func isLoopback(_ host: NWEndpoint.Host) -> Bool {
    switch host {
    case .ipv4(let a): return a.isLoopback
    case .ipv6(let a): return a.isLoopback || (a.asIPv4?.isLoopback ?? false)
    default: return false
    }
}

/// One extension connection: HTTP upgrade + RFC 6455 text frames (same limits as WebServer).
private final class BridgeConn {
    let conn: NWConnection
    unowned let bridge: Bridge
    let peer = "bridge"                  // all peers are loopback: one rate-limit bucket
    let nonce = randomHex(16)
    var origin = ""
    var authed = false
    var state: [String: Any]?
    private var buf = Data()
    private var upgraded = false
    private var wsMessage = Data()
    private var done = false
    private var strikes = 0
    private var unsent = 0

    init(conn: NWConnection, bridge: Bridge) { self.conn = conn; self.bridge = bridge }

    func start() {
        conn.stateUpdateHandler = { [weak self] st in
            switch st {
            case .failed: self?.conn.cancel()
            case .cancelled: self?.finish()
            default: break
            }
        }
        conn.start(queue: .main)
        receive()
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            if let self, !self.upgraded { self.conn.cancel() }
        }
        // Unauthenticated sockets get the pairing window's length, like phone sessions.
        DispatchQueue.main.asyncAfter(deadline: .now() + Pairing.window) { [weak self] in
            if let self, !self.authed { self.conn.cancel() }
        }
    }

    private func finish() {
        guard !done else { return }
        done = true
        bridge.finished(self)
    }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, err in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buf.append(data)
                if self.upgraded { self.parseFrames() } else { self.parseHTTP() }
            }
            if complete || err != nil { self.conn.cancel(); return }
            if !self.done { self.receive() }
        }
    }

    private func respond(_ code: Int, _ reason: String) {
        let h = "HTTP/1.1 \(code) \(reason)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        conn.send(content: Data(h.utf8), completion: .contentProcessed { [weak self] _ in self?.conn.cancel() })
    }

    private func parseHTTP() {
        guard let end = buf.range(of: Data("\r\n\r\n".utf8)) else {
            if buf.count > 8192 { respond(431, "Request Header Fields Too Large") }
            return
        }
        let head = String(decoding: buf[..<end.lowerBound], as: UTF8.self)
        buf.removeSubrange(..<end.upperBound)
        var lines = head.components(separatedBy: "\r\n")
        let req = lines.removeFirst().split(separator: " ")
        var headers: [String: String] = [:]
        for l in lines {
            guard let i = l.firstIndex(of: ":") else { continue }
            headers[l[..<i].trimmingCharacters(in: .whitespaces).lowercased()] = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces)
        }
        guard req.count == 3, req[0] == "GET", req[1] == "/bridge" else { respond(404, "Not Found"); return }
        guard bridge.hostAllowed(headers["host"] ?? "") else { log("bridge: refused Host \(headers["host"] ?? "-")"); respond(421, "Misdirected Request"); return }
        guard bridge.originAllowed(headers["origin"]) else {
            log("bridge: refused origin \(headers["origin"] ?? "(none)")"); respond(403, "Forbidden"); return
        }
        guard headers["upgrade"]?.lowercased() == "websocket", let key = headers["sec-websocket-key"] else { respond(400, "Bad Request"); return }
        origin = headers["origin"]!.lowercased()
        let accept = Data(Insecure.SHA1.hash(data: Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
        conn.send(content: Data("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n".utf8),
                  completion: .contentProcessed { _ in })
        upgraded = true
        send(["t": "hello", "v": 1, "app": "mote-helper", "bridge": 1, "nonce": nonce, "pairing": bridge.pairing.isOpen])
        if !buf.isEmpty { parseFrames() }
    }

    private func parseFrames() {
        while !done {
            let b = [UInt8](buf.prefix(14))
            guard b.count >= 2 else { return }
            let fin = b[0] & 0x80 != 0, opcode = b[0] & 0x0f, masked = b[1] & 0x80 != 0
            var len = UInt64(b[1] & 0x7f), off = 2
            if len == 126 { guard b.count >= 4 else { return }; len = UInt64(b[2]) << 8 | UInt64(b[3]); off = 4 }
            else if len == 127 { guard b.count >= 10 else { return }; len = b[2..<10].reduce(0) { $0 << 8 | UInt64($1) }; off = 10 }
            guard masked, len <= 65536, wsMessage.count + Int(len) <= 65536 else { close(1002); return }
            guard buf.count >= off + 4 + Int(len) else { return }
            let mask = [UInt8](buf[buf.startIndex + off ..< buf.startIndex + off + 4])
            var payload = [UInt8](buf[buf.startIndex + off + 4 ..< buf.startIndex + off + 4 + Int(len)])
            for i in payload.indices { payload[i] ^= mask[i & 3] }
            buf.removeFirst(off + 4 + Int(len))
            switch opcode {
            case 0, 1:
                wsMessage.append(contentsOf: payload)
                if fin { let m = wsMessage; wsMessage = Data(); handle(m) }
            case 8: sendFrame(8, Data()); conn.cancel(); return
            case 9: sendFrame(10, Data(payload))
            case 10: break
            default: close(1002); return
            }
        }
    }

    private func handle(_ d: Data) {
        guard let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any], let t = m["t"] as? String else {
            err("bad", "malformed message"); return
        }
        bridge.handle(m, t: t, from: self)
    }

    func strike(_ t: String) {
        strikes += 1
        err("unauth", "authenticate first", ref: t)
        if strikes > 20 { conn.cancel() }
    }

    func send(_ m: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(m), let d = try? JSONSerialization.data(withJSONObject: m) else { return }
        sendFrame(1, d)
    }

    func err(_ code: String, _ msg: String, ref: String? = nil, retryAfter: Int? = nil) {
        var m: [String: Any] = ["t": "err", "code": code, "msg": msg]
        if let ref { m["ref"] = ref }
        if let retryAfter { m["retryAfter"] = retryAfter }
        send(m)
    }

    /// Sends `err revoked` and a close frame, then cancels once they are written (at most 2 s later).
    /// A plain cancel() drops what is still queued, so the extension would never learn why (same as WebServer.cancelAfterFlush).
    func revoke(_ msg: String) {
        err("revoked", msg)
        sendFrame(8, Data([0x03, 0xE8]))
        let c = conn
        c.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { c.cancel() }
    }

    private func close(_ code: UInt16) { sendFrame(8, Data([UInt8(code >> 8), UInt8(code & 0xff)])); conn.cancel() }

    private func sendFrame(_ opcode: UInt8, _ payload: Data) {
        guard !done else { return }
        var h = Data([0x80 | opcode])
        if payload.count < 126 { h.append(UInt8(payload.count)) }
        else if payload.count <= 0xffff { h.append(126); h.append(UInt8(payload.count >> 8)); h.append(UInt8(payload.count & 0xff)) }
        else { h.append(127); for i in (0..<8).reversed() { h.append(UInt8((UInt64(payload.count) >> (UInt64(i) * 8)) & 0xff)) } }
        let n = h.count + payload.count
        unsent += n
        guard unsent <= 1 << 20 else { log("bridge: client isn't reading, closing"); conn.cancel(); return }
        conn.send(content: h + payload, completion: .contentProcessed { [weak self] _ in self?.unsent -= n })
    }
}

// MARK: menu (Labs submenu)

extension MenuBar {
    /// Items for Menu › Labs. Called from menuNeedsUpdate.
    func addBridgeItems(to labs: NSMenu, bridge: Bridge) {
        func add(_ title: String, _ action: Selector?, state: Bool? = nil) {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: "")
            i.target = self
            if action == nil { i.isEnabled = false }
            if let state { i.state = state ? .on : .off }
            labs.addItem(i)
        }
        add("Browser extension bridge", #selector(toggleBridge), state: bridge.enabled)
        guard bridge.enabled else { return }
        add("   Bridge: \(bridge.connected ? "extension connected" : bridge.status)", nil)
        if let code = bridge.pairing.code {
            add("   Extension code: \(code.prefix(3)) \(code.suffix(3))", nil)
            add("   Stop pairing", #selector(stopBridgePairing))
        } else {
            add("   Pair browser extension…", #selector(pairBridge))
        }
        if bridge.pairedCount > 0 { add("   Forget browser extension", #selector(forgetBridge)) }
    }

    @objc func toggleBridge() { labsBridge?.enabled.toggle() }
    @objc func pairBridge() { labsBridge?.openPairing() }
    @objc func stopBridgePairing() { labsBridge?.pairing.close() }
    @objc func forgetBridge() { labsBridge?.forget() }
}

/// The running bridge (set in main.swift); nil only before startup.
var labsBridge: Bridge?
