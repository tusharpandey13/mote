import ApplicationServices
import Foundation

/// Owns the security state (devices, pairing, rate limits, pause) and every session. Main-queue only.
final class Hub {
    let config: Config
    let store: DeviceStore
    let settings: Settings
    let pairing = Pairing()
    let limiter = RateLimiter()
    let injector: Injector
    let spaces: Spaces
    let tv: TvMode   // Labs (preview): Open… + TV mode, docs/labs/open-tv.md
    private(set) var labs: LabsPairing!   // Labs: instant pairing (Labs/Pairing.swift)
    private(set) var sessions: [Session] = []
    private var macroGen = 0
    private(set) var macroRunning = false
    var onChange: (() -> Void)?

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
        self.store = DeviceStore(service: config.keychainService, memoryOnly: config.memoryStore)
        self.settings = Settings(config: config)
        self.injector = Injector(dryRun: config.dryRun)
        self.spaces = Spaces(config: config, settings: settings, injector: injector)
        self.tv = TvMode(config: config, injector: injector)
        tv.activeDisplay = { [weak self] in self?.spaces.active?.cgID }
        tv.onChange = { [weak self] in guard let self else { return }; let st = self.tv.state(); for s in self.authedSessions { s.send(st) }; self.onChange?() }
        spaces.onChange = { [weak self] in self?.broadcastSpaces(); self?.onChange?() }
        spaces.isSubscribed = { [weak self] in self?.sessions.contains(where: { $0.spacesSub }) ?? false }
        pairing.onChange = { [weak self] in self?.onChange?() }
        // Accessibility can be granted at any time in System Settings; poll and tell clients.
        Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            guard let self else { return }
            let now = AXIsProcessTrusted()
            if now != self.axTrusted { self.axTrusted = now; self.broadcastState(); self.onChange?() }
        }
        labs = LabsPairing(hub: self)
    }

    /// Accessibility doesn't matter in dry-run (nothing is posted).
    var inputReady: Bool { axTrusted || config.dryRun }

    func add(_ s: Session) { sessions.append(s); onChange?() }
    func remove(_ s: Session) {
        sessions.removeAll { $0 === s }
        labs.ended(s)
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
        for s in authedSessions where s.spacesSub { s.send(st) }
    }

    func revokeAll() {
        store.revokeAll()
        labs.revokeAll()
        spaces.clear()
        for s in sessions where s.authed { s.revoke() }
        log("all devices revoked")
        onChange?()
    }

    // MARK: input

    func input(_ m: [String: Any], from s: Session) throws {
        guard !paused else { throw ProtoError("paused", "input is paused on the Mac") }
        guard inputReady else { throw ProtoError("ax", "Accessibility permission not granted to Mote Helper") }
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
        if macroRunning { log("macro: cancelled") }
        macroGen += 1; macroRunning = false
    }
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
    private(set) var spacesSub = false   // subscribed to `spaces` state (protocol 1.3)
    private var unauthStrikes = 0
    private var closed = false
    private var authDeadline: Timer?
    var isOpen: Bool { !closed }

    init(hub: Hub, transport: String, peer: String, send: @escaping (Data) -> Void, close: @escaping () -> Void) {
        self.hub = hub; self.transport = transport; self.peer = peer
        self.sendRaw = send; self.closeRaw = close
    }

    func start() {
        hub.add(self)
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
        guard !closed, JSONSerialization.isValidJSONObject(m), let d = try? JSONSerialization.data(withJSONObject: m) else { return }
        sendRaw(d)
    }

    func close() {
        guard !closed else { return }
        closed = true
        authDeadline?.invalidate()
        hub.remove(self)
        closeRaw()
    }

    /// Called by the transport when the peer went away.
    func ended() {
        guard !closed else { return }
        closed = true
        authDeadline?.invalidate()
        hub.remove(self)
    }

    func revoke() {
        authed = false
        send(["t": "err", "code": "revoked", "msg": "This device was revoked on the Mac"])
        close()
    }

    private func err(_ code: String, _ msg: String, ref: String? = nil, retryAfter: Int? = nil) {
        var m: [String: Any] = ["t": "err", "code": code, "msg": msg]
        if let ref { m["ref"] = ref }
        if let retryAfter { m["retryAfter"] = retryAfter }
        send(m)
    }

    func handle(_ data: Data) {
        guard !closed else { return }
        guard data.count <= 65536, let m = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let t = m["t"] as? String else { err("bad", "malformed message"); return }
        switch t {
        case "ping":
            // Echo `n` only if it can be written back (e.g. -1e400 parses to -inf).
            let n = m["n"] ?? 0
            send(["t": "pong", "n": JSONSerialization.isValidJSONObject([n]) ? n : 0])
        case "pong": break
        case "pair": pair(m)
        case "auth": auth(m)
        case let t where t.hasPrefix("labs.pair.") && hub.labs.enabled: hub.labs.handle(t, m, from: self)
        default:
            guard authed else {
                unauthStrikes += 1
                err("unauth", "authenticate first", ref: t)
                if unauthStrikes > 20 { close() }
                return
            }
            if t == "spaces.sub" || t == "spaces.thumb" { spaces(t, m); return }
            if t == "labs.tv.get" { send(hub.tv.state()); return }   // Labs: a read, like spaces.sub
            do { try hub.input(m, from: self) } catch let e as ProtoError { err(e.code, e.msg, ref: t) } catch { err("bad", "\(error)", ref: t) }
        }
    }

    /// Spaces state and thumbnails (protocol 1.3): reads, not input, so pause and Accessibility don't apply.
    private func spaces(_ t: String, _ m: [String: Any]) {
        if t == "spaces.sub" {
            guard (m["on"] as? Bool) ?? true else { spacesSub = false; return }
            spacesSub = true
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

    private func pair(_ m: [String: Any]) {
        if let wait = hub.limiter.retryAfter(peer) { err("rate", "too many attempts", ref: "pair", retryAfter: wait); return }
        let code = (m["code"] as? String) ?? ""
        switch hub.pairing.check(code) {
        case .closed:
            err("pairing-closed", "Open “Pair new device” in the Mote Helper menu on the Mac", ref: "pair")
        case .wrong:
            hub.limiter.fail(peer)
            log("pairing: wrong code from \(peer)")
            err("code", "Wrong code", ref: "pair")
        case .ok:
            let name = String(((m["name"] as? String) ?? "Device").prefix(64))
            let d = hub.store.add(name: name.isEmpty ? "Device" : name)
            hub.pairing.close()   // one device per window; the next pairing gets a new code
            log("paired \(d.name) (\(transport))")
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
            err("auth", "Unknown device or bad credentials", ref: "auth")
            return
        }
        hub.store.touch(d.id)
        markAuthed(id: d.id, name: d.name)
    }

    /// Labs: the instant-pairing handshake (Labs/Pairing.swift) authenticated this session.
    func labsAuthed(id: String, name: String) { guard !authed else { return }; markAuthed(id: id, name: name) }

    private func markAuthed(id: String, name: String) {
        authed = true
        authDeadline?.invalidate()
        deviceName = name
        log("authed \(name) via \(transport) from \(peer)")
        send(["t": "authed", "id": id, "name": name, "paused": hub.paused, "ax": hub.inputReady])
        hub.onChange?()
    }
}
