import CryptoKit
import Foundation

/// Proximity token for instant pairing: proof that a phone read this Mac's BLE characteristic
/// within the last ~minute. Pure (Foundation + CryptoKit), so helper/tests/proximity can test it alone.
///
///   w     = floor(unixSeconds / 30)
///   tk    = hex(HMAC-SHA256(secret, "mote-prox-v1|<hostId>|<w>")[0..<16])   (32 lower-case hex)
///   wire  = "<w>:<tk>"
///
/// The secret is 32 random bytes per helper process, memory only. `verify` checks, in order:
/// malformed or wrong HMAC -> .bad (so a forgery never reads as merely stale), |w - now| > 1 -> .stale,
/// already accepted -> .replay, else .ok (and the token is spent). Not thread-safe: main queue only.
final class Proximity {
    enum Verdict: String { case ok, stale, bad, replay }
    static let period: Double = 30

    private let key: SymmetricKey
    private let hostId: String
    private let now: () -> Date
    private var used: Set<Int64> = []   // a window's token is the same for every reader, so the window is the token
    var usedCount: Int { used.count }

    init(hostId: String, secret: Data? = nil, now: @escaping () -> Date = Date.init) {
        key = secret.map { SymmetricKey(data: $0) } ?? SymmetricKey(size: .bits256)
        self.hostId = hostId
        self.now = now
    }

    func window(at d: Date? = nil) -> Int64 { Int64(floor((d ?? now()).timeIntervalSince1970 / Proximity.period)) }

    private func mac(_ w: Int64) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("mote-prox-v1|\(hostId)|\(w)".utf8), using: key).prefix(16))
    }

    /// Wire form for window `w` (default: the current one).
    func wire(window w: Int64? = nil) -> String {
        let w = w ?? window()
        return "\(w):" + mac(w).map { String(format: "%02x", $0) }.joined()
    }

    func verify(_ s: String) -> Verdict {
        let parts = s.split(separator: ":", omittingEmptySubsequences: false)
        guard s.count <= 64, parts.count == 2, parts[0].count <= 20, let w = Int64(parts[0]),
              parts[0].allSatisfy({ $0.isASCII && ($0.isNumber || $0 == "-") }),
              let tk = lowerHex(parts[1]), tk.count == 16 else { return .bad }
        var diff: UInt8 = 0
        for (a, b) in zip(mac(w), tk) { diff |= a ^ b }   // constant time
        guard diff == 0 else { return .bad }
        let cur = window()
        used = used.filter { $0 >= cur - 1 }   // prune: older tokens are stale anyway
        guard abs(w - cur) <= 1 else { return .stale }
        guard used.insert(w).inserted else { return .replay }
        return .ok
    }

    private func lowerHex<S: StringProtocol>(_ s: S) -> Data? {
        guard s.count % 2 == 0 else { return nil }
        var out = Data()
        var hi: UInt8?
        for c in s.utf8 {
            let v: UInt8
            switch c {
            case 0x30...0x39: v = c - 0x30
            case 0x61...0x66: v = c - 0x61 + 10
            default: return nil
            }
            if let h = hi { out.append(h << 4 | v); hi = nil } else { hi = v }
        }
        return out
    }
}
