import CryptoKit
import Foundation
import Network
import Security

// MARK: - Paired devices (login Keychain)

struct Device: Codable, Equatable {
    var id: String
    var name: String
    var token: String          // 64 hex chars; HMAC key for challenge-response
    var created: Date
    var lastSeen: Date?
}

/// Paired devices: one list in the Vault (Vault.swift), `\.devices` (code pairing) or `\.bridge` (Labs bridge).
final class DeviceStore {
    private let vault: Vault
    private let path: WritableKeyPath<Secrets, [Device]>
    var devices: [Device] { vault.secrets[keyPath: path] }

    init(vault: Vault, path: WritableKeyPath<Secrets, [Device]>) {
        self.vault = vault
        self.path = path
    }

    func add(name: String) -> Device {
        let d = Device(id: UUID().uuidString.lowercased(), name: name, token: randomHex(32), created: Date(), lastSeen: Date())
        vault.update { $0[keyPath: path].append(d) }
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
        vault.update { $0[keyPath: path][i].lastSeen = Date() }
    }

    func revokeAll() {
        vault.update { $0[keyPath: path] = [] }
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

/// True for loopback, RFC 1918, link-local and IPv6 ULA / link-local. Tailscale's ranges
/// (CGNAT 100.64/10 and fd7a:115c:a1e0::/48) count only when `tailnet` is on.
func isLocalAddress(_ host: NWEndpoint.Host, tailnet: Bool) -> Bool {
    switch host {
    case .ipv4(let a): return isLocalV4([UInt8](a.rawValue), tailnet: tailnet)
    case .ipv6(let a): return isLocalV6(a, tailnet: tailnet)
    default: return false
    }
}

private func isLocalV6(_ a: IPv6Address, tailnet: Bool) -> Bool {
    if let v4 = a.asIPv4 { return isLocalV4([UInt8](v4.rawValue), tailnet: tailnet) }
    let b = [UInt8](a.rawValue)
    if a.isLoopback { return true }
    if b[0] == 0xfe && (b[1] & 0xc0) == 0x80 { return true }   // fe80::/10
    if isTailnetV6(b) { return tailnet }                       // fd7a:115c:a1e0::/48
    if (b[0] & 0xfe) == 0xfc { return true }                   // fc00::/7
    return false
}

private func isTailnetV4(_ b: [UInt8]) -> Bool { b.count == 4 && b[0] == 100 && (64...127).contains(b[1]) }
private func isTailnetV6(_ b: [UInt8]) -> Bool { b.starts(with: [0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0]) }

private func isLocalV4(_ b: [UInt8], tailnet: Bool) -> Bool {
    guard b.count == 4 else { return false }
    if isTailnetV4(b) { return tailnet }   // 100.64/10 (also carrier CGNAT, hence opt-in)
    switch (b[0], b[1]) {
    case (127, _), (10, _), (192, 168), (169, 254): return true
    case (172, 16...31): return true
    default: return false
    }
}

/// DNS-rebinding guard for the HTTP `Host` header: only names that point at this Mac.
/// Always: `localhost`, `*.local`, IP literals. With `tailnet`: also `*.ts.net` MagicDNS names;
/// without it, tailnet IP literals (100.64/10, fd7a:115c:a1e0::/48) are refused too.
func hostHeaderAllowed(_ header: String, tailnet: Bool) -> Bool {
    var h = header.lowercased()
    if h.hasPrefix("[") {                                                  // IPv6 literal, optional :port
        guard let end = h.firstIndex(of: "]") else { return false }
        let lit = String(h[h.index(after: h.startIndex)..<end]).components(separatedBy: "%")[0]
        guard let a = IPv6Address(lit) else { return false }
        return tailnet || !isTailnetV6([UInt8](a.rawValue))
    }
    if let i = h.lastIndex(of: ":") { h = String(h[..<i]) }
    if h.hasSuffix(".") { h.removeLast() }                                 // FQDN form "mac.local."
    if h == "localhost" || h.hasSuffix(".local") { return true }
    if h.hasSuffix(".ts.net") { return tailnet && h.count > ".ts.net".count }
    guard let a = IPv4Address(h) else { return false }
    return tailnet || !isTailnetV4([UInt8](a.rawValue))
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
