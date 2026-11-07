import AppKit
import CoreBluetooth
import CryptoKit
import Foundation
import Network
import Security
import ServiceManagement

/// Labs: Instant pairing (preview, docs/labs/pairing.md). Ed25519 key-based trust instead of the
/// 6-digit code: BLE info characteristic for discovery, `labs.pair.*` handshake, trust decided by a
/// known key, the same Tailscale owner, or the user's Allow on the Mac. Main-queue only.
final class LabsPairing: NSObject {
    static let infoUUID = CBUUID(string: "6D6F7465-0001-4C61-6273-50616972696E")
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
    /// Called when the toggle changes (main.swift re-publishes the BLE service and the menu).
    var onToggle: (() -> Void)?

    // Test-only fakes (dry-run only).
    private let fakeApprove: Bool?
    private let fakeOwner: String?

    private var prompt: (session: Session, fp: String, gen: Int)?
    private var promptGen = 0
    private var peerPrompts: [String: [Date]] = [:]
    private var globalPromptTimes: [Date] = []
    private var deniedUntil: [String: Date] = [:]
    private var infoPrinted = false
    private var infoCache: Data?
    private var alertOpen = false

    var enabled: Bool {
        didSet {
            guard enabled != oldValue else { return }
            defaults?.set(enabled, forKey: "labsPairing")
            log("labs pairing: \(enabled ? "on" : "off")")
            if !enabled { cancelPrompt() }
            onToggle?()
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
        enabled = c.labsPairing ?? defaults?.bool(forKey: "labsPairing") ?? false
        let fakes = c.fakeApprove != nil || c.fakeTailscaleOwner != nil
        if fakes && !c.dryRun { log("--fake-approve / --fake-tailscale-owner need --dry-run; ignoring them") }
        fakeApprove = c.dryRun ? c.fakeApprove : nil
        fakeOwner = c.dryRun ? c.fakeTailscaleOwner : nil
        super.init()
        log("labs pairing: \(enabled ? "on" : "off") (key \(fp.prefix(8)))")
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
        if store.keys.contains(where: { $0.fp == cfp }) { accept(s, fp: cfp, name: name, via: "trusted"); return }
        sameTailscaleOwner(peer: s.peer) { [weak self] same in
            guard let self, s.isOpen, !s.authed else { return }
            if same {
                self.store.add(LabsTrustedKey(fp: cfp, pub: pubHex, name: name, via: "tailscale", created: Date()))
                self.accept(s, fp: cfp, name: name, via: "tailscale")
            } else {
                self.ask(s, fp: cfp, pub: pubHex, name: name)
            }
        }
    }

    private func accept(_ s: Session, fp cfp: String, name: String, via: String) {
        let id = "k:" + cfp
        log("labs: trusted \(name) via \(via)")
        s.send(["t": "labs.pair.ok", "via": via, "id": id, "name": name])
        s.labsAuthed(id: id, name: name)
    }

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
        let w = LabsPairing.promptWindow
        globalPromptTimes = globalPromptTimes.filter { now.timeIntervalSince($0) < w }
        let mine = (peerPrompts[s.peer] ?? []).filter { now.timeIntervalSince($0) < w }
        peerPrompts[s.peer] = mine
        if mine.count >= LabsPairing.perPeerPrompts || globalPromptTimes.count >= LabsPairing.globalPrompts {
            let oldest = (mine.count >= LabsPairing.perPeerPrompts ? mine.first : globalPromptTimes.first) ?? now
            denied(s, "rate", retryAfter: max(1, Int(ceil(w - now.timeIntervalSince(oldest))))); return
        }
        peerPrompts[s.peer, default: []].append(now)
        globalPromptTimes.append(now)
        promptGen += 1
        let gen = promptGen
        prompt = (s, cfp, gen)
        s.send(["t": "labs.pair.pending", "timeout": Int(LabsPairing.promptTimeout)])
        log("labs: prompt \(name) (\(s.transport) \(s.peer))")
        hub.onChange?()

        let finish: (Bool?) -> Void = { [weak self] allow in self?.answered(gen: gen, allow: allow, pub: pub, name: name) }
        DispatchQueue.main.asyncAfter(deadline: .now() + LabsPairing.promptTimeout) { [weak self] in
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
            deniedUntil[p.fp] = Date().addingTimeInterval(LabsPairing.promptWindow)
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

    // MARK: menu

    /// Items for the menu's shared "Labs" submenu.
    func addMenuItems(to m: NSMenu) {
        let t = NSMenuItem(title: "Instant pairing (preview)", action: #selector(toggle), keyEquivalent: "")
        t.target = self
        t.state = enabled ? .on : .off
        m.addItem(t)
        let n = NSMenuItem(title: "Trusted phones (instant pairing): \(trustedCount)", action: nil, keyEquivalent: "")
        n.isEnabled = false
        m.addItem(n)
        if enabled {
            let k = NSMenuItem(title: "This Mac's key: \(groupedFingerprint(fp))", action: nil, keyEquivalent: "")
            k.isEnabled = false
            m.addItem(k)
        }
    }

    /// Turning it on also allows Tailscale peers and, once, turns on Launch at login (always discoverable).
    @objc func toggle() {
        enabled.toggle()
        guard enabled else { return }
        hub.settings.allowTailscale = true
        if defaults?.bool(forKey: "labsLoginApplied") != true, !hub.config.dryRun {
            defaults?.set(true, forKey: "labsLoginApplied")
            if SMAppService.mainApp.status != .enabled {
                do { try SMAppService.mainApp.register() } catch { log("labs: launch at login failed: \(error)") }
            }
        }
    }
}

// MARK: - crypto helpers (shared shapes with the Android app, docs/labs/pairing.md section 1)

func labsFingerprint(_ pub: Data) -> String { Data(SHA256.hash(data: pub).prefix(16)).hexString }
func labsClientProof(hostId: String, nonce: String, pub: String) -> String { "mote-labs-pair-v1|client|\(hostId)|\(nonce)|\(pub)" }
func labsHelperProof(hostId: String, nonce: String, cn: String, pub: String) -> String { "mote-labs-pair-v1|helper|\(hostId)|\(nonce)|\(cn)|\(pub)" }
private func groupedFingerprint(_ fp: String) -> String {
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
    var via: String       // approved | tailscale
    var created: Date
}

/// The helper's Ed25519 identity and the trusted phone keys, two generic-password items.
final class LabsKeyStore {
    private(set) var keys: [LabsTrustedKey] = []
    private let service: String
    private let memoryOnly: Bool

    init(service: String, memoryOnly: Bool) {
        self.service = service
        self.memoryOnly = memoryOnly
        if let d = read("trusted") {
            let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
            keys = (try? dec.decode([LabsTrustedKey].self, from: d)) ?? []
        }
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
        if !memoryOnly { SecItemDelete(query("trusted") as CFDictionary) }
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
