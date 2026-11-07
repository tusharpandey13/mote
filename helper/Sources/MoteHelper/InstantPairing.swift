import AppKit
import CoreBluetooth
import CryptoKit
import Foundation
import Network
import Security
import ServiceManagement

/// Instant pairing (docs/pairing.md; on by default, wire names keep the `labs.pair.*` prefix).
/// Ed25519 key-based trust instead of the 6-digit code: BLE info + proximity characteristics,
/// `labs.pair.*` handshake, trust decided by a known key, a fresh BLE proximity token, the same
/// Tailscale owner, or (with "Require approval for new devices") the user's Allow. Main-queue only.
final class InstantPairing: NSObject {
    static let infoUUID = CBUUID(string: "6D6F7465-0001-4C61-6273-50616972696E")
    static let proxUUID = CBUUID(string: "6D6F7465-0002-4C61-6273-50616972696E")
    static let promptTimeout: TimeInterval = 60
    static let promptWindow: TimeInterval = 600     // budgets and the deny cool-down
    static let perPeerPrompts = 3
    static let globalPrompts = 6

    private unowned let hub: Hub
    private let store: LabsKeyStore
    private let identity: Curve25519.Signing.PrivateKey
    private let defaults: UserDefaults?
    let fp: String
    /// Real WebSocket port (with --port 0 only known once the listener is up).
    var port: () -> UInt16 = { 0 }
    /// A NEW device was trusted (name, via, fp): main.swift shows the menu bar's notice panel.
    var onNewDevice: ((String, String, String) -> Void)?
    private let proximity: Proximity
    private var proxCache: Data?
    private var lastNew: String?   // fp of the most recently trusted new device (stdin-control `block`)

    // Test-only fakes (dry-run only).
    private let fakeApprove: Bool?
    private let fakeOwner: String?
    private let fakeProximity: Bool

    private var prompt: (session: Session, fp: String, gen: Int)?
    private var promptGen = 0
    private var peerPrompts: [String: [Date]] = [:]
    private var globalPromptTimes: [Date] = []
    private var deniedUntil: [String: Date] = [:]
    private var infoPrinted = false
    private var infoCache: Data?
    private var alertOpen = false

    /// On by default. Kill switch: UserDefaults `instantPairingDisabled` (read at launch) or --no-instant-pairing.
    let enabled: Bool

    /// "Require approval for new devices": new keys go through the Allow/Deny alert instead of proximity / Tailscale.
    var requireApproval: Bool {
        didSet {
            guard requireApproval != oldValue else { return }
            defaults?.set(requireApproval, forKey: "requireApproval")
            log("instant pairing: require approval \(requireApproval ? "on" : "off")")
            if !requireApproval { cancelPrompt() }
            hub.onChange?()
        }
    }

    var trustedCount: Int { store.keys.count }

    init(hub: Hub) {
        self.hub = hub
        let c = hub.config
        defaults = c.memoryStore ? nil : UserDefaults.standard
        store = LabsKeyStore(service: c.keychainService + ".labs", memoryOnly: c.memoryStore)
        identity = store.identity()
        fp = labsFingerprint(identity.publicKey.rawRepresentation)
        enabled = c.instantPairing ?? !(defaults?.bool(forKey: "instantPairingDisabled") ?? false)
        requireApproval = c.requireApproval || (defaults?.bool(forKey: "requireApproval") ?? false)
        proximity = Proximity(hostId: hub.settings.hostId)
        let fakes = c.fakeApprove != nil || c.fakeTailscaleOwner != nil || c.fakeProximity
        if fakes && !c.dryRun { log("--fake-approve / --fake-tailscale-owner / --fake-proximity need --dry-run; ignoring them") }
        fakeApprove = c.dryRun ? c.fakeApprove : nil
        fakeOwner = c.dryRun ? c.fakeTailscaleOwner : nil
        fakeProximity = c.dryRun && c.fakeProximity
        super.init()
        log("instant pairing: \(enabled ? "on" : "off")\(requireApproval ? ", require approval" : "") (key \(fp.prefix(8)))")
        // Once, at the first real launch: Launch at login, so the Mac is always discoverable.
        if enabled, !c.dryRun, let d = defaults, !d.bool(forKey: "loginApplied") {
            d.set(true, forKey: "loginApplied")
            if !d.bool(forKey: "labsLoginApplied"), SMAppService.mainApp.status != .enabled {
                do { try SMAppService.mainApp.register(); log("instant pairing: launch at login on") } catch { log("instant pairing: launch at login failed: \(error)") }
            }
        }
    }

    // MARK: BLE info

    func info() -> [String: Any] {
        var d: [String: Any] = ["v": 1, "id": hub.settings.hostId, "n": String(Host.computerName.prefix(64)), "fp": fp,
                                "lan": Array(Host.lanIPv4.prefix(4)), "p": Int(port())]
        if hub.settings.allowTailscale {
            if let n = Tailnet.name { d["ts"] = n }
            if let ip = Host.tailnetIPv4 { d["t4"] = ip }
        }
        return d
    }

    /// Info characteristic value. Offset 0 recomputes it; long reads (offset > 0) get the same bytes.
    func infoData(offset: Int) -> Data {
        if offset == 0 || infoCache == nil {
            infoCache = (try? JSONSerialization.data(withJSONObject: info(), options: [.sortedKeys])) ?? Data()
        }
        return infoCache!
    }

    static func infoCharacteristic() -> CBMutableCharacteristic {
        CBMutableCharacteristic(type: infoUUID, properties: [.read], value: nil, permissions: [.readable])
    }

    /// Proximity characteristic: the current token (`<w>:<tk>`, Proximity.swift). Never sent over the
    /// WebSocket (except --fake-proximity in tests). Offset 0 makes a fresh one; long reads reuse it.
    static func proxCharacteristic() -> CBMutableCharacteristic {
        CBMutableCharacteristic(type: proxUUID, properties: [.read], value: nil, permissions: [.readable])
    }

    func proxData(offset: Int) -> Data {
        if offset == 0 || proxCache == nil { proxCache = Data(proximity.wire().utf8) }
        return proxCache!
    }

    /// Dry-run: print the info once the port is known (tests use it as the fake BLE payload).
    func printInfoIfReady() {
        guard enabled, hub.config.dryRun, !infoPrinted, port() != 0,
              let d = try? JSONSerialization.data(withJSONObject: info(), options: [.sortedKeys]) else { return }
        infoPrinted = true
        print("LABS PAIR INFO: \(String(decoding: d, as: UTF8.self))"); fflush(stdout)
    }

    // MARK: protocol

    /// `labs.pair.*` from an unauthenticated (or authed) session. Only called while enabled.
    func handle(_ t: String, _ m: [String: Any], from s: Session) {
        switch t {
        case "labs.pair.info":
            var d = info(); d["t"] = "labs.pair.info"
            s.send(d)
        case "labs.pair.hello":
            hello(m, from: s)
        case "labs.pair.prox" where fakeProximity:   // tests: stands in for the BLE proximity read
            let w = proximity.window() + Int64(min(max((m["dw"] as? Int) ?? 0, -3), 3))
            s.send(["t": "labs.pair.prox", "v": proximity.wire(window: w), "w": w])
        default:
            s.send(["t": "err", "code": "unknown", "msg": "unknown message", "ref": t])
        }
    }

    private func hello(_ m: [String: Any], from s: Session) {
        guard !s.authed else { return }
        let ref = "labs.pair.hello"
        if let wait = hub.limiter.retryAfter(s.peer) {
            s.send(["t": "err", "code": "rate", "msg": "too many attempts", "ref": ref, "retryAfter": wait]); return
        }
        guard let pubHex = (m["pub"] as? String)?.lowercased(), pubHex.count == 64, let pubRaw = Data(hex: pubHex),
              let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: pubRaw),
              let cn = (m["cn"] as? String)?.lowercased(), cn.count == 32, Data(hex: cn) != nil,
              let sigHex = m["sig"] as? String, sigHex.count == 128, let sig = Data(hex: sigHex),
              pub.isValidSignature(sig, for: Data(labsClientProof(hostId: hub.settings.hostId, nonce: s.nonce, pub: pubHex).utf8)) else {
            hub.limiter.fail(s.peer)
            log("labs: bad hello from \(s.peer)")
            s.send(["t": "err", "code": "bad", "msg": "bad labs.pair.hello", "ref": ref]); return
        }
        let raw = (m["name"] as? String).map { String($0.prefix(64)) } ?? ""
        let name = raw.isEmpty ? "Phone" : raw
        let proof = labsHelperProof(hostId: hub.settings.hostId, nonce: s.nonce, cn: cn, pub: pubHex)
        guard let mySig = try? identity.signature(for: Data(proof.utf8)) else { return }
        s.send(["t": "labs.pair.hi", "pub": identity.publicKey.rawRepresentation.hexString, "fp": fp, "sig": mySig.hexString,
                "host": Host.computerName, "hostId": hub.settings.hostId])
        let cfp = labsFingerprint(pubRaw)
        // Trust decisions, in order (docs/pairing.md section 4). A stale / bad token still
        // falls through to the Tailscale check; only its deny reason is kept.
        if store.isBlocked(cfp) { denied(s, "blocked"); return }
        if store.keys.contains(where: { $0.fp == cfp }) { accept(s, fp: cfp, name: name, via: "trusted"); return }
        if requireApproval { ask(s, fp: cfp, pub: pubHex, name: name); return }
        var failure = "far"
        if let p = m["prox"] {
            switch (p as? String).map(proximity.verify) ?? .bad {
            case .ok: trustNew(s, fp: cfp, pub: pubHex, name: name, via: "nearby"); return
            case .stale: failure = "stale"
            case .replay: failure = "stale"; log("labs: proximity token replayed by \(s.peer)")
            case .bad: failure = "bad-proximity"; hub.limiter.fail(s.peer)
            }
        }
        sameTailscaleOwner(peer: s.peer) { [weak self] same in
            guard let self, s.isOpen, !s.authed else { return }
            if same { self.trustNew(s, fp: cfp, pub: pubHex, name: name, via: "tailscale") } else { self.denied(s, failure) }
        }
    }

    private func trustNew(_ s: Session, fp cfp: String, pub: String, name: String, via: String) {
        store.add(LabsTrustedKey(fp: cfp, pub: pub, name: name, via: via, created: Date()))
        accept(s, fp: cfp, name: name, via: via)
    }

    private func accept(_ s: Session, fp cfp: String, name: String, via: String) {
        let id = "k:" + cfp
        log("labs: trusted \(name) via \(via)")
        s.send(["t": "labs.pair.ok", "via": via, "id": id, "name": name])
        s.labsAuthed(id: id, name: name)
        guard via != "trusted" else { return }
        // A new device: say so without blocking (notice panel with Block; headless: log / stdout).
        lastNew = cfp
        log("labs: new device \(name) via \(via) (\(id))")
        if hub.config.dryRun, let d = try? JSONSerialization.data(withJSONObject: ["name": name, "via": via, "id": id], options: [.sortedKeys]) {
            print("NEW DEVICE: \(String(decoding: d, as: UTF8.self))"); fflush(stdout)
        }
        if !hub.config.headless { onNewDevice?(name, via, cfp) }
        hub.onChange?()
    }

    /// Block: forget the key, refuse it from now on, and drop its open sessions.
    func block(_ cfp: String) {
        let open = hub.sessions.filter { $0.authed && $0.deviceId == "k:" + cfp }
        store.block(cfp, name: open.first?.deviceName ?? "Phone")
        for s in open { s.revoke() }
        log("labs: blocked k:\(cfp)")
        hub.onChange?()
    }

    /// Tests (--stdin-control `block`): blocks the most recently trusted new device.
    func blockLast() {
        guard let f = lastNew else { log("labs: block: no new device"); return }
        block(f)
    }

    /// Unblock: the phone can connect again by proximity / Tailscale (it is not trusted until then).
    func unblock(_ cfp: String) {
        store.unblock(cfp)
        log("labs: unblocked k:\(cfp)")
        hub.onChange?()
    }

    /// Tests (--stdin-control `unblock`): unblocks the most recently blocked phone.
    func unblockLast() {
        guard let f = store.blocked.last?.fp else { log("labs: unblock: nothing blocked"); return }
        unblock(f)
    }

    var trusted: [LabsTrustedKey] { store.keys }
    var blocked: [LabsBlockedKey] { store.blocked }

    private func denied(_ s: Session, _ reason: String, retryAfter: Int? = nil) {
        var m: [String: Any] = ["t": "labs.pair.denied", "reason": reason]
        if let retryAfter { m["retryAfter"] = retryAfter }
        log("labs: denied (\(reason)) \(s.peer)")
        s.send(m)
    }

    // MARK: approval prompt

    private func ask(_ s: Session, fp cfp: String, pub: String, name: String) {
        let now = Date()
        if let until = deniedUntil[cfp], until > now { denied(s, "denied"); return }
        guard prompt == nil else { denied(s, "busy"); return }
        let w = InstantPairing.promptWindow
        globalPromptTimes = globalPromptTimes.filter { now.timeIntervalSince($0) < w }
        let mine = (peerPrompts[s.peer] ?? []).filter { now.timeIntervalSince($0) < w }
        peerPrompts[s.peer] = mine
        if mine.count >= InstantPairing.perPeerPrompts || globalPromptTimes.count >= InstantPairing.globalPrompts {
            let oldest = (mine.count >= InstantPairing.perPeerPrompts ? mine.first : globalPromptTimes.first) ?? now
            denied(s, "rate", retryAfter: max(1, Int(ceil(w - now.timeIntervalSince(oldest))))); return
        }
        peerPrompts[s.peer, default: []].append(now)
        globalPromptTimes.append(now)
        promptGen += 1
        let gen = promptGen
        prompt = (s, cfp, gen)
        s.send(["t": "labs.pair.pending", "timeout": Int(InstantPairing.promptTimeout)])
        log("labs: prompt \(name) (\(s.transport) \(s.peer))")
        hub.onChange?()

        let finish: (Bool?) -> Void = { [weak self] allow in self?.answered(gen: gen, allow: allow, pub: pub, name: name) }
        DispatchQueue.main.asyncAfter(deadline: .now() + InstantPairing.promptTimeout) { [weak self] in
            guard self?.prompt?.gen == gen else { return }
            self?.closeAlert()
            finish(nil)
        }
        if let fake = fakeApprove {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { finish(fake) }
        } else if !hub.config.headless {
            DispatchQueue.main.async { [weak self] in self?.showAlert(name: name, fp: cfp, s: s, gen: gen, finish: finish) }
        } // headless without a fake: nobody can answer, so it times out
    }

    private func showAlert(name: String, fp cfp: String, s: Session, gen: Int, finish: @escaping (Bool?) -> Void) {
        guard prompt?.gen == gen else { return }
        let route = s.transport == "BLE" ? "Bluetooth" : isTailnetPeer(s.peer) ? "Tailscale (\(s.peer))" : "Wi-Fi (\(s.peer))"
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Allow “\(name)” to control this Mac?"
        a.informativeText = "It will be able to type, click and move the pointer, like a keyboard and mouse.\n\nVia \(route)\nKey \(groupedFingerprint(cfp))\n\nDeny if you didn't just tap Connect on this phone."
        a.addButton(withTitle: "Allow")
        a.addButton(withTitle: "Deny")
        a.alertStyle = .warning
        alertOpen = true
        let r = a.runModal()
        alertOpen = false
        guard prompt?.gen == gen else { return }   // timed out or the phone went away
        finish(r == .alertFirstButtonReturn ? true : r == .alertSecondButtonReturn ? false : nil)
    }

    private func answered(gen: Int, allow: Bool?, pub: String, name: String) {
        guard let p = prompt, p.gen == gen else { return }
        prompt = nil
        defer { hub.onChange?() }
        switch allow {
        case true?:
            store.add(LabsTrustedKey(fp: p.fp, pub: pub, name: name, via: "approved", created: Date()))
            if p.session.isOpen && !p.session.authed { accept(p.session, fp: p.fp, name: name, via: "approved") }
        case false?:
            deniedUntil[p.fp] = Date().addingTimeInterval(InstantPairing.promptWindow)
            denied(p.session, "denied")
        case nil:
            denied(p.session, "timeout")
        }
    }

    private func cancelPrompt() {
        guard prompt != nil else { return }
        prompt = nil
        closeAlert()
    }

    /// Ends our modal alert (only ours: another modal, e.g. "Revoke all", stays).
    private func closeAlert() {
        guard alertOpen else { return }
        alertOpen = false
        NSApp.abortModal()
    }

    /// A session went away: close its alert, so a phone that gave up leaves nothing open on the Mac.
    func ended(_ s: Session) {
        guard prompt?.session === s else { return }
        log("labs: prompt closed (phone went away)")
        cancelPrompt()
    }

    func revokeAll() {
        store.revokeAll()
        deniedUntil = [:]
    }

    // MARK: Tailscale owner

    private func isTailnetPeer(_ peer: String) -> Bool {
        if let a = IPv4Address(peer) { let b = [UInt8](a.rawValue); return b[0] == 100 && (64...127).contains(b[1]) }
        if let a = IPv6Address(peer) { return [UInt8](a.rawValue).starts(with: [0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0]) }
        return false
    }

    /// True when the peer is a tailnet node owned by this Mac's Tailscale user (never tagged nodes).
    private func sameTailscaleOwner(peer: String, _ done: @escaping (Bool) -> Void) {
        if let fake = fakeOwner {
            let ok = !peer.hasPrefix("ble:") && fake == "self"
            log("labs: fake whois \(peer) owner=\(fake)")
            // chaos: "timeout" stands in for a Tailscale CLI that hangs until the 3 s kill (falls through to the prompt).
            if fake == "timeout" { DispatchQueue.main.asyncAfter(deadline: .now() + 3) { done(false) }; return }
            done(ok); return
        }
        guard isTailnetPeer(peer) else { done(false); return }
        DispatchQueue.global(qos: .userInitiated).async {
            let me = (labsTailscaleJSON(["status", "--json", "--peers=false"])?["Self"] as? [String: Any])?["UserID"]
            let who = labsTailscaleJSON(["whois", "--json", peer])
            let owner = (who?["UserProfile"] as? [String: Any])?["ID"]
            let tags = ((who?["Node"] as? [String: Any])?["Tags"] as? [Any]) ?? []
            let same = me != nil && owner != nil && tags.isEmpty && "\(me!)" == "\(owner!)" && "\(me!)" != "0"
            DispatchQueue.main.async {
                log("labs: whois \(peer): \(same ? "same owner" : "not the same owner")")
                done(same)
            }
        }
    }
}

// MARK: - crypto helpers (shared shapes with the Android app, docs/pairing.md section 1)

func labsFingerprint(_ pub: Data) -> String { Data(SHA256.hash(data: pub).prefix(16)).hexString }
func labsClientProof(hostId: String, nonce: String, pub: String) -> String { "mote-labs-pair-v1|client|\(hostId)|\(nonce)|\(pub)" }
func labsHelperProof(hostId: String, nonce: String, cn: String, pub: String) -> String { "mote-labs-pair-v1|helper|\(hostId)|\(nonce)|\(cn)|\(pub)" }
func groupedFingerprint(_ fp: String) -> String {
    stride(from: 0, to: min(fp.count, 16), by: 4).map { i in
        String(fp[fp.index(fp.startIndex, offsetBy: i)..<fp.index(fp.startIndex, offsetBy: i + 4)])
    }.joined(separator: " ") + " …"
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

/// Runs the Tailscale CLI (same paths as the MagicDNS lookup) and parses its JSON. 3 s timeout. Off-main.
private func labsTailscaleJSON(_ args: [String]) -> [String: Any]? {
    guard let bin = Tailnet.candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else { return nil }
    let p = Process()
    p.executableURL = URL(fileURLWithPath: bin)
    p.arguments = args
    let out = Pipe()
    p.standardOutput = out
    p.standardError = FileHandle.nullDevice
    do { try p.run() } catch { return nil }
    let killer = DispatchWorkItem { if p.isRunning { p.terminate() } }
    DispatchQueue.global().asyncAfter(deadline: .now() + 3, execute: killer)
    let data = out.fileHandleForReading.readDataToEndOfFile()
    p.waitUntilExit()
    killer.cancel()
    return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
}

// MARK: - trust store (login Keychain)

struct LabsTrustedKey: Codable {
    var fp: String
    var pub: String
    var name: String
    var via: String       // nearby | tailscale | approved
    var created: Date
}

struct LabsBlockedKey: Codable {
    var fp: String
    var name: String
}

/// The helper's Ed25519 identity, the trusted phone keys and the blocked fingerprints, three generic-password items.
final class LabsKeyStore {
    private(set) var keys: [LabsTrustedKey] = []
    private(set) var blocked: [LabsBlockedKey] = []
    private let service: String
    private let memoryOnly: Bool

    init(service: String, memoryOnly: Bool) {
        self.service = service
        self.memoryOnly = memoryOnly
        if let d = read("trusted") {
            let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
            keys = (try? dec.decode([LabsTrustedKey].self, from: d)) ?? []
        }
        if let d = read("blocked") {   // [{fp, name}]; early builds stored [fp]
            blocked = (try? JSONDecoder().decode([LabsBlockedKey].self, from: d))
                ?? ((try? JSONDecoder().decode([String].self, from: d)) ?? []).map { LabsBlockedKey(fp: $0, name: "Phone") }
        }
    }

    func isBlocked(_ fp: String) -> Bool { blocked.contains { $0.fp == fp } }

    func block(_ fp: String, name: String) {
        let name = keys.first { $0.fp == fp }?.name ?? name
        keys.removeAll { $0.fp == fp }
        save()
        if !isBlocked(fp) { blocked.append(LabsBlockedKey(fp: fp, name: name)) }
        saveBlocked()
    }

    func unblock(_ fp: String) {
        blocked.removeAll { $0.fp == fp }
        saveBlocked()
    }

    private func saveBlocked() {
        if let d = try? JSONEncoder().encode(blocked) { write("blocked", d, label: "Mote Helper instant pairing blocked phones") }
    }

    func identity() -> Curve25519.Signing.PrivateKey {
        if let d = read("identity"), let k = try? Curve25519.Signing.PrivateKey(rawRepresentation: d) { return k }
        let k = Curve25519.Signing.PrivateKey()
        write("identity", k.rawRepresentation, label: "Mote Helper instant pairing key")
        return k
    }

    func add(_ k: LabsTrustedKey) {
        keys.removeAll { $0.fp == k.fp }
        keys.append(k)
        save()
    }

    func revokeAll() {
        keys = []
        blocked = []
        if !memoryOnly { SecItemDelete(query("trusted") as CFDictionary); SecItemDelete(query("blocked") as CFDictionary) }
    }

    private func save() {
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        if let d = try? enc.encode(keys) { write("trusted", d, label: "Mote Helper instant pairing trusted phones") }
    }

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func read(_ account: String) -> Data? {
        guard !memoryOnly else { return nil }
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st != errSecSuccess && st != errSecItemNotFound { log("labs: keychain read failed: \(st)") }
        return st == errSecSuccess ? out as? Data : nil
    }

    private func write(_ account: String, _ data: Data, label: String) {
        guard !memoryOnly else { return }
        var st = SecItemUpdate(query(account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if st == errSecItemNotFound {
            var add = query(account)
            add[kSecValueData as String] = data
            add[kSecAttrLabel as String] = label
            st = SecItemAdd(add as CFDictionary, nil)
        }
        if st != errSecSuccess { log("labs: keychain write failed: \(st)") }
    }
}
