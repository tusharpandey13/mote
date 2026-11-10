import Foundation

/// `--fake-keychain STATE` (dry-run only): an in-memory stand-in for the login Keychain item `secrets.v3`, so the L2 journeys
/// can drive every read result without touching the real Keychain or any macOS prompt. Per process; events carry `fake: true`.
///
/// - found: a readable item with a fixed identity (seed).
/// - notFound: no item; the Vault creates one.
/// - partitionMismatch: no-UI reads fail with errSecInteractionNotAllowed; the one interactive read succeeds (the "user typed
///   the password"), after which the item is rewritten and readable.
/// - denied: no-UI reads fail; the interactive read is refused (userCanceled).
/// - error: every read fails with errSecMissingEntitlement (a failure no prompt can fix).
/// Writes fail while the item is unreadable (as UIFail writes do); `delete` always works (Repair), then everything is readable.
enum FakeKeychainState: String {
    case found, notFound = "notfound", partitionMismatch = "partitionmismatch", denied, error

    init?(arg: String) { self.init(rawValue: arg.lowercased().replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "_", with: "")) }
}

final class FakeKeychain: SecretBackend {
    let state: FakeKeychainState
    private var item: Data?
    private var healthy: Bool   // true once the item is ours again (rewritten or repaired)

    /// Fixed 32-byte identity so tests can tell "seed key" from "minted key".
    static let seedIdentity = Data(repeating: 7, count: 32)

    init(state: FakeKeychainState) {
        self.state = state
        switch state {
        case .notFound: item = nil; healthy = true
        case .found: item = FakeKeychain.seed(); healthy = true
        case .partitionMismatch, .denied, .error: item = FakeKeychain.seed(); healthy = false
        }
    }

    static func seed() -> Data {
        var s = Secrets()
        s.identity = seedIdentity
        return (try? Vault.encoder.encode(s)) ?? Data()
    }

    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        let r: Result<Data?, KeychainError>
        if healthy { r = .success(item) }
        else {
            switch state {
            case .partitionMismatch: r = interactive ? .success(item) : .failure(KeychainError(status: -25308))
            case .denied: r = .failure(KeychainError(status: interactive ? -128 : -25308))
            default: r = .failure(KeychainError(status: -34018))
            }
        }
        var f: [String: Any] = ["account": account, "interactive": interactive, "fake": true]
        if case .failure(let e) = r { f["status"] = Int(e.status) } else { f["status"] = item == nil ? -25300 : 0 }
        ev(.keychain, "read", f)
        return try r.get()
    }

    func write(service: String, account: String, data: Data, label: String) -> Bool {
        let ok = healthy
        if ok { item = data }
        ev(.keychain, "write", ["account": account, "status": ok ? 0 : -25308, "ok": ok, "fake": true])
        return ok
    }

    func delete(service: String, account: String) -> Bool {
        item = nil; healthy = true
        ev(.keychain, "write", ["account": account, "status": 0, "ok": true, "op": "delete", "fake": true])
        return true
    }
}
