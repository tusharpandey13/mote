import AppKit
import ApplicationServices
import Foundation

/// Owns the security state (devices, pairing, rate limits, pause) and every session. Main-queue only.
final class Hub {
    let config: Config
    let vault: Vault   // every secret, one Keychain item (Vault.swift)
    let store: DeviceStore
    let settings: Settings
    let pairing = Pairing()
    let limiter = RateLimiter()
    let injector: Injector
    let spaces: Spaces
    let liveScreen: LiveScreen   // Labs (docs/labs/live-screen.md)
    let tv: TvMode   // Labs (preview): Open… + TV mode, docs/labs/open-tv.md
    private(set) var instantPairing: InstantPairing!   // instant pairing (InstantPairing.swift)
    private(set) var bt: BtPair!   // Bluetooth HID setup started by a trusted phone (BtPair.swift)
    private(set) var sessions: [Session] = []
    /// Labs (preview) modules, registered in main.swift; each owns `labs.*` message prefixes and a Labs menu section.
    var labs: [LabsModule] = []
    private var macroGen = 0
    private(set) var macroRunning = false
    var onChange: (() -> Void)?
    /// Builds the `diag` snapshot with the transports main.swift owns (WebServer, BLEServer).
    var snapshotProvider: ((String) -> [String: Any])?
    func snapshot(source: String = "ws") -> [String: Any] { snapshotProvider?(source) ?? Diag.snapshot(hub: self, web: nil, ble: nil, source: source) }

    var paused = false {
        didSet {
            guard paused != oldValue else { return }
            log(paused ? "input paused" : "input resumed")
            if paused { cancelMacro(); injector.releaseAll() }
            broadcastState()
            onChange?()
        }
    }

    private(set) var axTrusted = AXIsProcessTrusted()

    init(config: Config) {
        self.config = config
        if config.fakeKeychain != nil && !config.dryRun { log("--fake-keychain needs --dry-run; ignoring it") }
        if config.usesFakeKeychain, let st = config.fakeKeychain {
            self.vault = Vault(service: config.keychainService, memoryOnly: false, backend: FakeKeychain(state: st), identity: "fake-keychain")
        } else {
            self.vault = Vault(service: config.keychainService, memoryOnly: config.memoryStore, defaults: .standard)
        }
        self.store = DeviceStore(vault: vault, path: \.devices)
        self.settings = Settings(config: config)
        self.injector = Injector(dryRun: config.dryRun)
        self.spaces = Spaces(config: config, settings: settings, injector: injector)
        self.liveScreen = LiveScreen(config: config, settings: settings, injector: injector)
        self.tv = TvMode(config: config, injector: injector)
        tv.activeDisplay = { [weak self] in self?.spaces.active?.cgID }
        tv.onChange = { [weak self] in guard let self else { return }; let st = self.tv.state(); ev(.labs, "push", coalesce: "tv", ["lab": "tvMode", "sessions": self.authedSessions.count]); for s in self.authedSessions { s.send(st) }; self.onChange?() }
        spaces.onChange = { [weak self] in self?.broadcastSpaces(); self?.onChange?() }
        spaces.isSubscribed = { [weak self] in self?.sessions.contains(where: { $0.spacesSub }) ?? false }
        liveScreen.onChange = { [weak self] in self?.onChange?() }
        pairing.onChange = { [weak self] in self?.onChange?() }
        // Accessibility can be granted at any time in System Settings; poll and tell clients.
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            guard let self else { return }
            let now = PermProbe.check(.accessibility).state == .granted
            if now != self.axTrusted { self.axTrusted = now; self.broadcastState(); self.onChange?() }
        }
        instantPairing = InstantPairing(hub: self)
        bt = BtPair(hub: self)
    }

    /// Accessibility doesn't matter in dry-run (nothing is posted).
    var inputReady: Bool { axTrusted || config.dryRun }

    func add(_ s: Session) { sessions.append(s); onChange?() }
    func remove(_ s: Session) {
        sessions.removeAll { $0 === s }
        instantPairing.ended(s)
        bt.ended(s)
        liveScreen.sessionGone(s)
        if !sessions.contains(where: { $0.authed }) { injector.releaseAll() }
        onChange?()
    }

    var authedSessions: [Session] { sessions.filter { $0.authed } }

    func broadcastState() {
        for s in authedSessions { s.send(["t": "state", "paused": paused, "ax": inputReady]) }
    }

    /// Sends the `spaces` state to every subscribed session (protocol 1.3).
    func broadcastSpaces() {
        let st = spaces.state()
        let subs = authedSessions.filter { $0.spacesSub }
        if !subs.isEmpty { ev(.labs, "push", coalesce: "spaces", ["lab": "spaces", "sessions": subs.count]) }
        for s in subs { s.send(st) }
    }

    /// "Keychain: Repair" (menu / Setup, after the user's confirmation): delete `secrets.v3` and create a fresh one.
    @discardableResult
    func repairKeychain() -> Bool {
        let ok = vault.repair()
        log("keychain: repair \(ok ? "done; phones must pair again" : "failed; still not saving pairings")")
        onChange?()
        return ok
    }

    func revokeAll() {
        store.revokeAll()
        instantPairing.revokeAll()
        spaces.clear()
        labsBridge?.forget()
        for s in sessions where s.authed { s.revoke() }
        log("all devices revoked")
        ev(.session, "revoked", ["scope": "all"])
        onChange?()
    }

    // MARK: input

    func input(_ m: [String: Any], from s: Session) throws {
        guard !paused else { ev(.input, "blocked", coalesce: "paused", ["reason": "paused"]); throw ProtoError("paused", "input is paused on the Mac") }
        guard inputReady else { ev(.input, "blocked", coalesce: "ax", ["reason": "accessibility"]); throw ProtoError("ax", "Accessibility permission not granted to Mote Helper") }
        Events.shared.countInput(m["t"] as? String ?? "?")   // counts per kind; never the keys or text
        switch m["t"] as? String {
        case "macro": try runMacro(m["steps"])
        case "cancel": cancelMacro()
        case "spaces.goto": try spaces.goto(m.int("i"), display: m.optStr("display"))
        case let t? where t.hasPrefix("labs.open.") || t.hasPrefix("labs.tv."): try tv.handle(m)
        default: if !tv.intercept(m) { try injector.exec(m) }
        }
    }

    private func runMacro(_ raw: Any?) throws {
        guard let steps = raw as? [[String: Any]], !steps.isEmpty, steps.count <= 64 else {
            throw ProtoError("bad", "macro needs 1-64 steps")
        }
        var total = 0
        for st in steps {
            let t = st["t"] as? String
            if t == "macro" || t == "cancel" || t == nil { throw ProtoError("bad", "bad macro step") }
            if t == "wait" {
                let ms = try st.int("ms")
                guard (0...5000).contains(ms) else { throw ProtoError("bad", "wait must be 0-5000 ms") }
                total += ms
            }
        }
        guard total <= 10_000 else { throw ProtoError("bad", "macro waits exceed 10 s") }
        cancelMacro()
        macroGen += 1
        macroRunning = true
        let gen = macroGen
        log("macro: \(steps.count) steps")
        ev(.input, "macro", ["steps": steps.count, "state": "start"])
        func run(_ i: Int) {
            guard gen == macroGen, !paused, i < steps.count else { if gen == macroGen { macroRunning = false }; return }
            var delay = 10
            if steps[i]["t"] as? String == "wait" { delay = max(10, (try? steps[i].int("ms")) ?? 0) }
            else {
                do { try injector.exec(steps[i]) } catch { log("macro step \(i) failed: \(error)"); macroRunning = false; return }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(delay)) { run(i + 1) }
        }
        run(0)
    }

    func cancelMacro() {
        if macroRunning { log("macro: cancelled"); ev(.input, "macro", ["state": "cancel"]) }
        macroGen += 1; macroRunning = false
    }
}

/// A Labs (preview) feature: handles messages starting with one of [prefixes] (authenticated sessions
/// only; the module applies pause / Accessibility itself) and adds items to the menu's Labs submenu.
protocol LabsModule: AnyObject {
    var prefixes: [String] { get }
    func handle(_ t: String, _ m: [String: Any], from s: Session)
    func menuItems() -> [NSMenuItem]
}

/// One client connection on either transport. Speaks the protocol; never injects before `authed`.
final class Session {
    let transport: String          // "Wi-Fi" | "BLE"
    let peer: String               // rate-limit key (IP or BLE central id)
    private let sendRaw: (Data) -> Void
    private let closeRaw: () -> Void
    private unowned let hub: Hub
    let nonce = randomHex(16)
    private(set) var authed = false
    private(set) var deviceName = ""
    private(set) var deviceId = ""       // paired device id, or `k:<fp>` for instant pairing
    private(set) var spacesSub = false   // subscribed to `spaces` state (protocol 1.3)
    private var unauthStrikes = 0
    // Diagnostics (protocol 1.5)
    let openedAt = Date()
    private(set) var clientProto: String?, clientApp: String?   // optional `client` object sent with auth / diag
    private var handlingCorr: String?                            // `corr` of the message being handled; echoed on its replies
    private var diagToken: UUID?
    private var diagBuf: [Event] = []
    private var diagFloor = 0, diagDropped = 0, diagFlushScheduled = false
    var diagSubscribed: Bool { diagToken != nil }
    /// Every message type this session handles (the event-coverage unit test feeds each one through `handle`).
    static let handledTypes = ["ping", "pong", "pair", "auth", "diag", "diag.subscribe", "spaces.sub", "spaces.thumb", "bt.pair", "bt.info",
                               "labs.tv.get", "labs.screen.start", "labs.screen.stop", "labs.screen.ack", "labs.screen.click", "labs.video.sub",
                               "labs.video.cmd", "labs.ctx.sub", "labs.np.sub", "labs.np.seek"]
    private var closed = false
    private var authDeadline: Timer?
    var isOpen: Bool { !closed }
    /// Bytes the transport has queued but not yet handed to the kernel (Wi-Fi); 0 if unknown.
    var backlog: () -> Int = { 0 }

    init(hub: Hub, transport: String, peer: String, send: @escaping (Data) -> Void, close: @escaping () -> Void) {
        self.hub = hub; self.transport = transport; self.peer = peer
        self.sendRaw = send; self.closeRaw = close
    }

    func start() {
        hub.add(self)
        ev(.session, "open", ["transport": transport])
        send(["t": "hello", "v": 1, "app": "mote-helper", "host": Host.computerName, "hostId": hub.settings.hostId, "local": Host.localName,
                   "nonce": nonce, "pairing": hub.pairing.isOpen])
        // Unauthenticated connections get the pairing window's length to finish, then are dropped.
        // A Timer (not asyncAfter) so a closed session's deadline is removed now, not 5 min later.
        authDeadline = Timer.scheduledTimer(withTimeInterval: Pairing.window, repeats: false) { [weak self] _ in
            if let self, !self.authed { self.close() }
        }
    }

    func send(_ m: [String: Any]) {
        // isValidJSONObject first: writing NaN/inf raises an ObjC exception that `try?` can't catch.
        var m = m
        if let handlingCorr, m["corr"] == nil { m["corr"] = handlingCorr }   // replies to a message carry its corr
        guard !closed, JSONSerialization.isValidJSONObject(m), let d = try? JSONSerialization.data(withJSONObject: m) else { return }
        // diag.events is not logged: logging it would feed the stream forever.
        if let t = m["t"] as? String, t != "diag.events" { ev(.protocolMsg, "out", coalesce: "out.\(t).\(handlingCorr ?? "")", ["t": t, "size": d.count, "transport": transport]) }
        sendRaw(d)
    }

    func close() {
        guard !closed else { return }
        closed = true
        authDeadline?.invalidate()
        diagUnsubscribe()
        logClosed("closed-by-helper")
        hub.remove(self)
        closeRaw()
    }

    /// Called by the transport when the peer went away.
    func ended() {
        guard !closed else { return }
        closed = true
        authDeadline?.invalidate()
        diagUnsubscribe()
        logClosed("peer-gone")
        hub.remove(self)
    }

    private func logClosed(_ why: String) {
        ev(.session, "closed", ["transport": transport, "authed": authed, "reason": why, "durationMs": Int(Date().timeIntervalSince(openedAt) * 1000)])
    }

    func revoke() {
        authed = false
        ev(.session, "revoked", ["scope": "device"])
        send(["t": "err", "code": "revoked", "msg": "This device was revoked on the Mac"])
        close()
    }

    private func err(_ code: String, _ msg: String, ref: String? = nil, retryAfter: Int? = nil) {
        var m: [String: Any] = ["t": "err", "code": code, "msg": msg]
        if let ref { m["ref"] = ref }
        if let retryAfter { m["retryAfter"] = retryAfter }
        ev(.protocolMsg, "error", coalesce: "err.\(code)", ["code": code, "ref": ref ?? NSNull()])
        send(m)
    }

    func handle(_ data: Data) {
        guard !closed else { return }
        guard data.count <= 65536, let m = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let t = m["t"] as? String else {
            ev(.protocolMsg, "bad", coalesce: "malformed", ["reason": "malformed", "size": data.count, "transport": transport])
            err("bad", "malformed message"); return
        }
        // Envelope (protocol 1.5): optional `corr` and `step`; events emitted while handling this message carry them.
        handlingCorr = Session.envelope(m["corr"]); Events.shared.currentCorr = handlingCorr; Events.shared.currentStep = Session.envelope(m["step"])
        defer { handlingCorr = nil; Events.shared.currentCorr = nil; Events.shared.currentStep = nil }
        ev(.protocolMsg, "in", coalesce: "in.\(t).\(handlingCorr ?? "")", ["t": t, "size": data.count, "authed": authed, "transport": transport])
        switch t {
        case "ping":
            // Echo `n` only if it can be written back (e.g. -1e400 parses to -inf).
            let n = m["n"] ?? 0
            send(["t": "pong", "n": JSONSerialization.isValidJSONObject([n]) ? n : 0])
        case "pong": break
        case "pair": pair(m)
        case "auth": auth(m)
        case let t where t.hasPrefix("labs.pair.") && hub.instantPairing.enabled: hub.instantPairing.handle(t, m, from: self)
        default:
            guard authed else {
                unauthStrikes += 1
                err("unauth", "authenticate first", ref: t)
                if unauthStrikes > 20 { close() }
                return
            }
            if t == "diag" { noteClient(m["client"]); send(hub.snapshot().merging(["t": "diag"]) { a, _ in a }); return }
            if t == "diag.subscribe" { noteClient(m["client"]); diagSubscribe(on: (m["on"] as? Bool) ?? true, since: m["since"] as? Int); return }
            if t == "spaces.sub" || t == "spaces.thumb" { spaces(t, m); return }
            if t == "bt.pair" || t == "bt.info" { hub.bt.handle(t, m, from: self); return }   // Bluetooth HID setup, not input
            if t.hasPrefix("labs.screen.") { labsScreen(t, m); return }
            if t == "labs.tv.get" { send(hub.tv.state()); return }   // Labs: a read, like spaces.sub
            if t.hasPrefix("labs.video."), let b = labsBridge {   // Labs: video bridge (docs/labs/video-bridge.md)
                do { try b.phone(t, m, from: self) } catch let e as ProtoError { err(e.code, e.msg, ref: t) } catch { err("bad", "\(error)", ref: t) }
                return
            }
            if t.hasPrefix("labs."), let mod = hub.labs.first(where: { $0.prefixes.contains(where: t.hasPrefix) }) { mod.handle(t, m, from: self); return }
            do { try hub.input(m, from: self) } catch let e as ProtoError { err(e.code, e.msg, ref: t) } catch { err("bad", "\(error)", ref: t) }
        }
    }

    /// Spaces state and thumbnails (protocol 1.3): reads, not input, so pause and Accessibility don't apply.
    private func spaces(_ t: String, _ m: [String: Any]) {
        if t == "spaces.sub" {
            guard (m["on"] as? Bool) ?? true else { spacesSub = false; ev(.labs, "subscribe", ["lab": "spaces", "on": false]); return }
            spacesSub = true
            ev(.labs, "subscribe", ["lab": "spaces", "on": true])
            hub.spaces.subscribed()
            send(hub.spaces.state())
            return
        }
        guard transport != "BLE" else { err("unsupported", "Space thumbnails need Wi-Fi or Tailscale", ref: t); return }
        guard let i = try? m.int("i") else { err("bad", "missing number 'i'", ref: t); return }
        let display: String?
        do { display = try m.optStr("display") } catch { err("bad", "'display' must be a string", ref: t); return }
        guard let reply = hub.spaces.thumbMessage(i, display: display) else { err("notfound", "no thumbnail for desktop \(i)", ref: t); return }
        send(reply)
    }

    /// Labs › Live screen (docs/labs/live-screen.md). Clicks go through the same pause / Accessibility gate as input.
    private func labsScreen(_ t: String, _ m: [String: Any]) {
        do {
            let hub = self.hub
            let input: () throws -> Void = {
                guard !hub.paused else { throw ProtoError("paused", "input is paused on the Mac") }
                guard hub.inputReady else { throw ProtoError("ax", "Accessibility permission not granted to Mote Helper") }
            }
            if let reply = try hub.liveScreen.handle(t, m, from: self, input: input) { send(reply) }
        } catch let e as ProtoError { err(e.code, e.msg, ref: t) } catch { err("bad", "\(error)", ref: t) }
    }

    private func pair(_ m: [String: Any]) {
        if let wait = hub.limiter.retryAfter(peer) { err("rate", "too many attempts", ref: "pair", retryAfter: wait); return }
        let code = (m["code"] as? String) ?? ""
        switch hub.pairing.check(code) {
        case .closed:
            ev(.session, "pair.failed", ["reason": "closed", "transport": transport])
            err("pairing-closed", "Open “Pair new device” in the Mote Helper menu on the Mac", ref: "pair")
        case .wrong:
            hub.limiter.fail(peer)
            log("pairing: wrong code from \(peer)")
            ev(.session, "pair.failed", ["reason": "wrong-code", "transport": transport])
            err("code", "Wrong code", ref: "pair")
        case .ok:
            let name = String(((m["name"] as? String) ?? "Device").prefix(64))
            let d = hub.store.add(name: name.isEmpty ? "Device" : name)
            hub.pairing.close()   // one device per window; the next pairing gets a new code
            log("paired \(d.name) (\(transport))")
            ev(.session, "paired", ["transport": transport])
            send(["t": "paired", "id": d.id, "token": d.token])
            hub.onChange?()
        }
    }

    private func auth(_ m: [String: Any]) {
        guard !authed else { return }
        if let wait = hub.limiter.retryAfter(peer) { err("rate", "too many attempts", ref: "auth", retryAfter: wait); return }
        guard let id = m["id"] as? String, let mac = m["mac"] as? String,
              let d = hub.store.verify(id: id, nonce: nonce, macHex: mac) else {
            hub.limiter.fail(peer)
            log("auth failed from \(peer)")
            ev(.session, "auth.failed", ["transport": transport])
            err("auth", "Unknown device or bad credentials", ref: "auth")
            return
        }
        hub.store.touch(d.id)
        noteClient(m["client"])
        markAuthed(id: d.id, name: d.name)
    }

    /// The instant-pairing handshake (InstantPairing.swift) authenticated this session.
    func labsAuthed(id: String, name: String) { guard !authed else { return }; markAuthed(id: id, name: name) }

    /// `corr` / `step`: a short printable string, else ignored.
    static func envelope(_ v: Any?) -> String? {
        guard let s = v as? String, !s.isEmpty, s.count <= 64, s.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value < 0x7f }) else { return nil }
        return s
    }

    private func noteClient(_ v: Any?) {
        guard let c = v as? [String: Any] else { return }
        if let p = c["proto"] as? String, p.count <= 16 { clientProto = p }
        if let a = c["app"] as? String, a.count <= 64 { clientApp = String(a.unicodeScalars.filter { $0.value >= 0x20 && $0.value < 0x7f }) }
    }

    // MARK: diag.subscribe / diag.events (protocol 1.5)

    private func diagSubscribe(on: Bool, since: Int?) {
        ev(.protocolMsg, "diag.subscribe", ["on": on, "since": since ?? NSNull()])
        diagUnsubscribe()
        guard on else { send(["t": "diag.events", "events": [], "subscribed": false, "head": Events.shared.head]); return }
        diagToken = Events.shared.addListener { [weak self] e in
            DispatchQueue.main.async { self?.diagEnqueue(e) }
        }
        let head = Events.shared.head
        diagFloor = since.map { min($0, head) } ?? head
        let replay = since == nil ? [] : Events.shared.recent(since: diagFloor, limit: 1000)
        if let last = replay.last { diagFloor = last.seq }
        // The first reply is the acknowledgement (and the replay, if asked for).
        send(["t": "diag.events", "events": replay.map(\.json), "subscribed": true, "head": head, "capacity": Events.capacity])
    }

    private func diagUnsubscribe() {
        if let id = diagToken { Events.shared.removeListener(id) }
        diagToken = nil; diagBuf = []
    }

    private func diagEnqueue(_ e: Event) {
        guard diagToken != nil, !closed, e.seq > diagFloor else { return }
        diagBuf.append(e)
        if diagBuf.count > 1000 { diagDropped += diagBuf.count - 1000; diagBuf.removeFirst(diagBuf.count - 1000) }
        guard !diagFlushScheduled else { return }
        diagFlushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in self?.diagFlush() }
    }

    private func diagFlush() {
        diagFlushScheduled = false
        guard diagToken != nil, !closed, !diagBuf.isEmpty else { return }
        let batch = diagBuf.sorted { $0.seq < $1.seq }
        diagBuf = []
        diagFloor = max(diagFloor, batch.last?.seq ?? 0)
        for chunk in stride(from: 0, to: batch.count, by: 100) {
            var m: [String: Any] = ["t": "diag.events", "events": batch[chunk..<min(chunk + 100, batch.count)].map(\.json), "head": Events.shared.head]
            if diagDropped > 0 { m["dropped"] = diagDropped; diagDropped = 0 }
            send(m)
        }
    }

    private func markAuthed(id: String, name: String) {
        authed = true
        authDeadline?.invalidate()
        deviceName = name
        deviceId = id
        log("authed \(name) via \(transport) from \(peer)")
        ev(.session, "authed", ["transport": transport, "durationMs": Int(Date().timeIntervalSince(openedAt) * 1000)])
        send(["t": "authed", "id": id, "name": name, "paused": hub.paused, "ax": hub.inputReady])
        hub.onChange?()
    }
}
