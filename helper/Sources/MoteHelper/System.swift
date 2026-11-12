import Foundation

/// The OS-facing answers the diagnostics and the Setup window are built from, behind small protocols so tests replay a
/// recorded (or hand-made) `SystemFixture` instead of the real Mac. Production adapters wrap the existing calls
/// (`PermProbe`, `KeychainBackend`, `SigningInfo.current`, the listener/BLE status); nothing here can show a prompt.
/// `MoteHelper --record-system FILE` writes the live answers as a fixture; fixtures live in helper/tests/Fixtures/system/.

protocol PermissionProbe { func check(_ p: Perm) -> PermCheck }
/// The Vault's Keychain backend is the secret store (attribute listing needs no ACL and never prompts).
typealias SecretStore = SecretBackend
protocol SigningProvider { func current() -> SigningInfo }
protocol Discovery { var reading: DiscoveryReading { get } }

/// Wi-Fi listener, Bonjour registration and BLE advertising as macOS reported them.
struct DiscoveryReading: Equatable {
    var running = false           // false: no helper process to learn from (`--diag`, `--record-system` without a running helper)
    var bonjour = "off", bonjourErrorCode: Int? = nil
    var listenerState = "none", listenerError: String? = nil
    var bleStatus: String? = nil, bleAdvertising = false

    var json: [String: Any] {
        ["running": running, "bonjour": bonjour, "bonjourErrorCode": bonjourErrorCode ?? NSNull(), "listenerState": listenerState,
         "listenerError": listenerError ?? NSNull(), "bleStatus": bleStatus ?? NSNull(), "bleAdvertising": bleAdvertising]
    }
    init(running: Bool = false) { self.running = running }
    init(json j: [String: Any]) {
        running = j["running"] as? Bool ?? false
        bonjour = j["bonjour"] as? String ?? "off"; bonjourErrorCode = (j["bonjourErrorCode"] as? NSNumber)?.intValue
        listenerState = j["listenerState"] as? String ?? "none"; listenerError = j["listenerError"] as? String
        bleStatus = j["bleStatus"] as? String; bleAdvertising = j["bleAdvertising"] as? Bool ?? false
    }
    /// From a diag snapshot (the running helper's diag.json).
    init(snapshot s: [String: Any]) {
        let t = s["transports"] as? [String: Any] ?? [:]
        let wifi = t["wifi"] as? [String: Any] ?? [:], bj = t["bonjour"] as? [String: Any] ?? [:], ble = t["ble"] as? [String: Any] ?? [:]
        running = s["running"] as? Bool ?? false
        bonjour = bj["state"] as? String ?? "off"; bonjourErrorCode = (bj["errorCode"] as? NSNumber)?.intValue
        listenerState = wifi["state"] as? String ?? "none"; listenerError = wifi["error"] as? String
        bleStatus = ble["status"] as? String; bleAdvertising = ble["advertising"] as? Bool ?? false
    }
}

struct LivePermissionProbe: PermissionProbe {
    var force = false
    func check(_ p: Perm) -> PermCheck { PermProbe.check(p, force: force) }
}
struct LiveSigning: SigningProvider {
    func current() -> SigningInfo { Diag.cachedSigningInfo() }
}
struct LiveDiscovery: Discovery {
    let web: WebServer?, ble: BLEServer?
    var reading: DiscoveryReading {
        var r = DiscoveryReading(running: true)
        if let w = web {
            r.listenerState = w.listenerState; r.listenerError = w.listenerError
            r.bonjour = w.bonjour; r.bonjourErrorCode = w.bonjourErrorCode
        }
        r.bleStatus = ble?.status; r.bleAdvertising = ble?.isAdvertising ?? false
        return r
    }
}
/// `--record-system` has no listener of its own: it reads the running helper's diag.json if one is fresh.
struct SnapshotDiscovery: Discovery {
    let snapshot: [String: Any]?
    var reading: DiscoveryReading { snapshot.map { DiscoveryReading(snapshot: $0) } ?? DiscoveryReading() }
}

/// Everything the diagnostics read from the OS, injectable.
struct SystemAdapters {
    var permissions: PermissionProbe
    var secrets: SecretStore
    var signing: SigningProvider
    var discovery: Discovery
    /// The designated requirement Screen Recording was last seen granted for (nil = never seen), and the writer for it.
    var screenGrantedBy: () -> String?
    var noteScreenGranted: (String) -> Void

    private static let grantKey = "screenRecordingGrantedBy"
    /// `remember`: only the real running helper records grants; `--diag`, `--record-system` and `--dry-run` write nothing.
    static func live(discovery: Discovery = SnapshotDiscovery(snapshot: nil), force: Bool = false, remember: Bool = false) -> SystemAdapters {
        SystemAdapters(permissions: LivePermissionProbe(force: force), secrets: KeychainBackend(), signing: LiveSigning(), discovery: discovery,
                       screenGrantedBy: { UserDefaults.standard.string(forKey: grantKey) },
                       noteScreenGranted: { if remember { UserDefaults.standard.set($0, forKey: grantKey) } })
    }
}

/// A recorded or hand-made OS state (`"source": "recorded" | "synthetic"`): JSON in helper/tests/Fixtures/system/.
struct SystemFixture: PermissionProbe, SigningProvider, Discovery, SecretBackend {
    var source = "synthetic", note = "", macOS = "", arch = ""
    var checks: [Perm: PermCheck] = [:]
    var signingInfo = SigningInfo()
    var accounts: [String]? = nil          // nil: Keychain listing failed (listStatus)
    var listStatus = 0
    var reading = DiscoveryReading()
    var screenGrantedBy: String? = nil

    func check(_ p: Perm) -> PermCheck { checks[p] ?? PermCheck(perm: p, state: .unknown, api: "none", raw: "not in fixture", at: 0, ms: 0) }
    func current() -> SigningInfo { signingInfo }
    func list(service: String) throws -> [KeychainEntry] {
        guard let accounts else { throw KeychainError(status: OSStatus(listStatus)) }
        return accounts.map { KeychainEntry(account: $0, modified: nil) }
    }
    func read(service: String, account: String, interactive: Bool) throws -> Data? { nil }
    func write(service: String, account: String, data: Data, label: String) -> Bool { false }
    func delete(service: String, account: String) -> Bool { false }

    var adapters: SystemAdapters {
        SystemAdapters(permissions: self, secrets: self, signing: self, discovery: self, screenGrantedBy: { screenGrantedBy }, noteScreenGranted: { _ in })
    }

    static func load(_ url: URL) throws -> SystemFixture {
        guard let j = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else { throw CocoaError(.fileReadCorruptFile) }
        return SystemFixture(json: j)
    }

    init() {}
    init(json j: [String: Any]) {
        source = j["source"] as? String ?? "synthetic"; note = j["note"] as? String ?? ""
        macOS = j["macOS"] as? String ?? ""; arch = j["arch"] as? String ?? ""
        for (k, v) in j["permissions"] as? [String: Any] ?? [:] {
            guard let p = Perm(rawValue: k), let m = v as? [String: Any] else { continue }
            let st: PermState
            switch m["state"] as? String { case "granted"?: st = .granted; case "denied"?: st = .denied; case "notDetermined"?: st = .notDetermined; default: st = .unknown }
            checks[p] = PermCheck(perm: p, state: st, api: m["api"] as? String ?? "", raw: m["raw"] as? String ?? "", at: (m["checkedAt"] as? NSNumber)?.int64Value ?? 0,
                                  ms: (m["ms"] as? NSNumber)?.doubleValue ?? 0, detail: m["detail"] as? [String: String] ?? [:])
        }
        signingInfo = SigningInfo(json: j["signing"] as? [String: Any] ?? [:])
        let kc = j["keychain"] as? [String: Any] ?? [:]
        accounts = kc["accounts"] as? [String]; listStatus = (kc["listStatus"] as? NSNumber)?.intValue ?? 0
        reading = DiscoveryReading(json: j["discovery"] as? [String: Any] ?? [:])
        screenGrantedBy = (j["history"] as? [String: Any])?["screenRecordingGrantedBy"] as? String
    }
}

enum SystemRecorder {
    /// The fixture JSON for what [system] answers right now. Status codes, booleans, signing identity and Keychain
    /// ACCOUNT NAMES only (never an item's data). Calls only the silent probes of the adapters.
    static func record(_ system: SystemAdapters, service: String, source: String = "recorded") -> [String: Any] {
        var perms: [String: Any] = [:]
        for p in Perm.allCases { perms[p.rawValue] = system.permissions.check(p).json.filter { $0.key != "perm" } }
        var kc: [String: Any] = ["service": service]
        do { kc["accounts"] = try system.secrets.list(service: service).map(\.account).sorted(); kc["listStatus"] = 0 }
        catch let e as KeychainError { kc["accounts"] = NSNull(); kc["listStatus"] = Int(e.status) }
        catch { kc["accounts"] = NSNull(); kc["listStatus"] = -1 }
        let v = ProcessInfo.processInfo.operatingSystemVersion
        var arch = "x86_64"
        #if arch(arm64)
        arch = "arm64"
        #endif
        return ["source": source, "note": "", "macOS": "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)", "arch": arch,
                "permissions": perms, "signing": system.signing.current().json, "keychain": kc,
                "discovery": system.discovery.reading.json,
                "history": ["screenRecordingGrantedBy": system.screenGrantedBy().map { $0 as Any } ?? NSNull()],
                "recordedAt": Int64(Date().timeIntervalSince1970 * 1000)]
    }
}

extension SigningInfo {
    /// Inverse of `json` (fixtures).
    init(json j: [String: Any]) {
        self.init()
        identifier = j["identifier"] as? String; teamId = j["teamId"] as? String; cdhash = j["cdhash"] as? String
        authorities = j["authorities"] as? [String] ?? []; designatedRequirement = j["designatedRequirement"] as? String
        adhoc = j["adhoc"] as? Bool ?? false; hardenedRuntime = j["hardenedRuntime"] as? Bool ?? false
        bundlePath = j["bundlePath"] as? String ?? ""; executablePath = j["executablePath"] as? String ?? ""
        quarantined = j["quarantined"] as? Bool ?? false; translocated = j["translocated"] as? Bool ?? false
        error = (j["error"] as? NSNumber).map { Int32($0.intValue) }
    }
}
