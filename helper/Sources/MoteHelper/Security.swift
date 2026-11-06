import CryptoKit
import Foundation
import Network
import Security

// MARK: - Paired devices (login Keychain)

struct Device: Codable {
    var id: String
    var name: String
    var token: String          // 64 hex chars; HMAC key for challenge-response
    var created: Date
    var lastSeen: Date?
}

/// Paired devices, stored as one JSON blob in a generic-password Keychain item.
final class DeviceStore {
    private(set) var devices: [Device] = []
    private let service: String
    private let memoryOnly: Bool
    private let account = "devices"

    init(service: String, memoryOnly: Bool) {
        self.service = service
        self.memoryOnly = memoryOnly
        load()
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private func load() {
        guard !memoryOnly else { return }
        var q = baseQuery
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st == errSecSuccess, let data = out as? Data {
            let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
            devices = (try? dec.decode([Device].self, from: data)) ?? []
        } else if st != errSecItemNotFound {
            log("keychain read failed: \(st)")
        }
    }

    private func save() {
        guard !memoryOnly else { return }
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        guard let data = try? enc.encode(devices) else { return }
        var st = SecItemUpdate(baseQuery as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if st == errSecItemNotFound {
            var add = baseQuery
            add[kSecValueData as String] = data
            add[kSecAttrLabel as String] = "Mote Helper paired devices"
            st = SecItemAdd(add as CFDictionary, nil)
        }
        if st != errSecSuccess { log("keychain write failed: \(st)") }
    }

    func add(name: String) -> Device {
        let d = Device(id: UUID().uuidString.lowercased(), name: name, token: randomHex(32), created: Date(), lastSeen: Date())
        devices.append(d)
        save()
        return d
    }

    /// Constant-time check of mac = HMAC-SHA256(key: token bytes, msg: nonce).
    func verify(id: String, nonce: String, macHex: String) -> Device? {
        guard let d = devices.first(where: { $0.id == id }),
              let key = Data(hex: d.token), let mac = Data(hex: macHex) else { return nil }
        let ok = HMAC<SHA256>.isValidAuthenticationCode(mac, authenticating: Data(nonce.utf8), using: SymmetricKey(data: key))
        return ok ? d : nil
    }

    func touch(_ id: String) {
        guard let i = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[i].lastSeen = Date()
        save()
    }

    func revokeAll() {
        devices = []
        if !memoryOnly { SecItemDelete(baseQuery as CFDictionary) }
    }
}

// MARK: - Pairing window

/// A 6-digit code that is only valid while the pairing window is open (5 minutes, one device).
final class Pairing {
    static let window: TimeInterval = 300
    static let maxGlobalFailures = 10

    private(set) var code: String?
    private(set) var expires: Date?
    private var failures = 0
    private var timer: Timer?
    var onChange: (() -> Void)?

    var isOpen: Bool { code != nil }

    func open() {
        var g = SystemRandomNumberGenerator()   // arc4random: CSPRNG
        code = String(format: "%06u", UInt32.random(in: 0..<1_000_000, using: &g))
        expires = Date().addingTimeInterval(Pairing.window)
        failures = 0
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: Pairing.window, repeats: false) { [weak self] _ in self?.close() }
        onChange?()
    }

    func close() {
        guard code != nil else { return }
        code = nil; expires = nil
        timer?.invalidate(); timer = nil
        onChange?()
    }

    enum Result { case ok, wrong, closed }

    func check(_ attempt: String) -> Result {
        guard let code else { return .closed }
        if constantTimeEqual(Data(attempt.utf8), Data(code.utf8)) { return .ok }
        failures += 1
        if failures >= Pairing.maxGlobalFailures {
            log("pairing: \(failures) wrong codes, closing the pairing window")
            close()
        }
        return .wrong
    }
}

// MARK: - Rate limiting

/// Per-peer sliding window: 5 failures per 60 s, then blocked until the oldest failure ages out.
final class RateLimiter {
    private var fails: [String: [Date]] = [:]
    let limit = 5
    let window: TimeInterval = 60

    func retryAfter(_ peer: String) -> Int? {
        let now = Date()
        let list = (fails[peer] ?? []).filter { now.timeIntervalSince($0) < window }
        fails[peer] = list.isEmpty ? nil : list
        guard list.count >= limit, let oldest = list.first else { return nil }
        return max(1, Int(ceil(window - now.timeIntervalSince(oldest))))
    }

    func fail(_ peer: String) { fails[peer, default: []].append(Date()) }
}

// MARK: - LAN-only check

/// True for loopback, RFC 1918, CGNAT 100.64/10, link-local and IPv6 ULA / link-local.
func isLocalAddress(_ host: NWEndpoint.Host) -> Bool {
    switch host {
    case .ipv4(let a): return isLocalV4([UInt8](a.rawValue))
    case .ipv6(let a):
        if let v4 = a.asIPv4 { return isLocalV4([UInt8](v4.rawValue)) }
        let b = [UInt8](a.rawValue)
        if a.isLoopback { return true }
        if b[0] == 0xfe && (b[1] & 0xc0) == 0x80 { return true }   // fe80::/10
        if (b[0] & 0xfe) == 0xfc { return true }                     // fc00::/7
        return false
    default: return false
    }
}

private func isLocalV4(_ b: [UInt8]) -> Bool {
    guard b.count == 4 else { return false }
    switch (b[0], b[1]) {
    case (127, _), (10, _), (192, 168), (169, 254): return true
    case (172, 16...31): return true
    case (100, 64...127): return true
    default: return false
    }
}

// MARK: - helpers

func randomHex(_ bytes: Int) -> String {
    var b = [UInt8](repeating: 0, count: bytes)
    precondition(SecRandomCopyBytes(kSecRandomDefault, bytes, &b) == errSecSuccess)
    return b.map { String(format: "%02x", $0) }.joined()
}

func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
    guard a.count == b.count else { return false }
    var d: UInt8 = 0
    for (x, y) in zip(a, b) { d |= x ^ y }
    return d == 0
}

extension Data {
    init?(hex: String) {
        guard hex.count % 2 == 0, hex.count <= 256 else { return nil }
        var out = Data(capacity: hex.count / 2)
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let v = UInt8(hex[i..<j], radix: 16) else { return nil }
            out.append(v)
            i = j
        }
        self = out
    }
}
