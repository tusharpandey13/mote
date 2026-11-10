import Foundation

/// `--fake-keychain STATE` (dry-run only): an in-memory stand-in for the login Keychain, so the L2 journeys can drive every launch
/// case without touching the real Keychain or any macOS prompt. Per process; events carry `fake: true`. The simulated build's
/// identity is `fake-keychain`, so its own account is `secrets.v4.fake-keychain`; the other accounts belong to "another build".
///
/// - found: our own account exists with a fixed identity (seed): read without UI.
/// - notFound: no account; the Vault creates ours.
/// - partitionMismatch: only another build's `secrets.v3`; no-UI reads fail with errSecInteractionNotAllowed, the one interactive
///   read succeeds (the "user typed the password"), after which the data is copied into our account.
/// - denied: as above, but the interactive read is refused (userCanceled).
/// - error: another build's item; every read fails with errSecMissingEntitlement (a failure no prompt can fix).
/// Listing always works (attributes only). Writes to another build's account fail; `delete` only works on our own account.
enum FakeKeychainState: String {
    case found, notFound = "notfound", partitionMismatch = "partitionmismatch", denied, error

    init?(arg: String) { self.init(rawValue: arg.lowercased().replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "_", with: "")) }
}

final class FakeKeychain: SecretBackend {
    let state: FakeKeychainState
    private var items: [String: (data: Data, modified: Date, foreign: Bool)] = [:]

    /// Fixed 32-byte identity so tests can tell "seed key" from "minted key".
    static let seedIdentity = Data(repeating: 7, count: 32)
    static let identity = "fake-keychain"

    init(state: FakeKeychainState) {
        self.state = state
        switch state {
        case .notFound: break
        case .found: items[Vault.account(for: FakeKeychain.identity)!] = (FakeKeychain.seed(), Date(), false)
        case .partitionMismatch, .denied, .error:
            items[Vault.legacyAccount] = (FakeKeychain.seed(), Date(timeIntervalSince1970: 1_700_000_000), true)
        }
    }

    static func seed() -> Data {
        var s = Secrets()
        s.identity = seedIdentity
        return (try? Vault.encoder.encode(s)) ?? Data()
    }

    func list(service: String) throws -> [KeychainEntry] {
        ev(.keychain, "list", ["status": 0, "n": items.count, "fake": true])
        return items.map { KeychainEntry(account: $0.key, modified: $0.value.modified) }
    }

    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        let it = items[account]
        var status = it == nil ? -25300 : 0
        var failure: KeychainError?
        if let it, it.foreign {
            switch state {
            case .partitionMismatch: if !interactive { failure = KeychainError(status: -25308) }
            case .denied: failure = KeychainError(status: interactive ? -128 : -25308)
            default: failure = KeychainError(status: -34018)
            }
        }
        if let failure { status = Int(failure.status) }
        ev(.keychain, "read", ["account": account, "status": status, "interactive": interactive, "fake": true])
        if let failure { throw failure }
        return it?.data
    }

    func write(service: String, account: String, data: Data, label: String) -> Bool {
        let ok = !(items[account]?.foreign ?? false)
        if ok { items[account] = (data, Date(), false) }
        ev(.keychain, "write", ["account": account, "status": ok ? 0 : -25308, "ok": ok, "fake": true])
        return ok
    }

    func delete(service: String, account: String) -> Bool {
        let ok = !(items[account]?.foreign ?? false)
        if ok { items[account] = nil }
        ev(.keychain, "write", ["account": account, "status": ok ? 0 : -25308, "ok": ok, "op": "delete", "fake": true])
        return ok
    }
}
