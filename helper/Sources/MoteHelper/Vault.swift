import Foundation
import Security

/// Everything secret the helper keeps, as ONE JSON blob in ONE generic-password Keychain item
/// (service `dev.mote.helper`, account `secrets.v3`). Read once at launch and cached in memory.
///
/// Why v3 and no migration: an item created by a build without a Team ID (ad-hoc or self-signed) gets a
/// `cdhash:` partition, so any other build prompts for the login password when it reads it. v3 is created
/// by the Developer-ID build (`teamid:` partition, docs/signing.md) and read without UI forever after.
/// Older items (`secrets.v2`, `secrets`, the per-secret ones) are NEVER read or deleted: touching them can
/// prompt. They are stale; the user removes them in Keychain Access (docs/pairing.md). Users re-pair once.
///
/// The proximity secret (Proximity.swift) is deliberately not stored: it is per process, memory only.
struct Secrets: Codable, Equatable {
    var v = 1
    var devices: [Device] = []            // web remote / app pairings (code pairing)
    var bridge: [Device] = []             // Labs › browser extension bridge pairings
    var trusted: [LabsTrustedKey] = []    // instant pairing: trusted phone keys
    var blocked: [LabsBlockedKey] = []    // instant pairing: blocked phone keys
    var identity: Data? = nil             // instant pairing: this Mac's Ed25519 private key (raw)

    init() {}
    init(from d: Decoder) throws {   // tolerant: missing fields get their defaults
        let c = try d.container(keyedBy: CodingKeys.self)
        v = (try? c.decode(Int.self, forKey: .v)) ?? 1
        devices = (try? c.decode([Device].self, forKey: .devices)) ?? []
        bridge = (try? c.decode([Device].self, forKey: .bridge)) ?? []
        trusted = (try? c.decode([LabsTrustedKey].self, forKey: .trusted)) ?? []
        blocked = (try? c.decode([LabsBlockedKey].self, forKey: .blocked)) ?? []
        identity = try? c.decode(Data.self, forKey: .identity)
    }
}

/// Raw Keychain access (generic passwords), injectable so the migration is unit-testable.
protocol SecretBackend {
    /// nil = not found; throws on any other failure (denied, locked, ...).
    func read(service: String, account: String) throws -> Data?
    func write(service: String, account: String, data: Data, label: String) -> Bool
}

struct KeychainError: Error { let status: OSStatus }

struct KeychainBackend: SecretBackend {
    private func query(_ s: String, _ a: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: s, kSecAttrAccount as String: a]
    }

    func read(service: String, account: String) throws -> Data? {
        var q = query(service, account)
        // Never show Keychain UI: items whose ACL doesn't match this signature fail instead of prompting.
        q[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st == errSecItemNotFound { return nil }
        guard st == errSecSuccess else { throw KeychainError(status: st) }
        return out as? Data
    }

    func write(service: String, account: String, data: Data, label: String) -> Bool {
        var uq = query(service, account); uq[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        var st = SecItemUpdate(uq as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if st == errSecItemNotFound {
            var add = query(service, account)
            add[kSecValueData as String] = data
            add[kSecAttrLabel as String] = label
            st = SecItemAdd(add as CFDictionary, nil)
        }
        if st != errSecSuccess { log("keychain write failed: \(st)") }
        return st == errSecSuccess
    }
}

/// Owner of `Secrets`. `memoryOnly` (--memory-store, tests) never touches the Keychain.
final class Vault {
    static let account = "secrets.v3"
    private(set) var secrets = Secrets()
    private let service: String
    private let backend: SecretBackend?

    init(service: String, memoryOnly: Bool, backend: SecretBackend = KeychainBackend()) {
        self.service = service
        self.backend = memoryOnly ? nil : backend
        load()
    }

    /// One read, never retried. Not found: write an empty blob once (so later launches only ever read v3).
    /// Any other error (interaction required, auth failed, locked...): treat as not present, log, and do not
    /// write, so an existing item is never overwritten and nothing loops.
    private func load() {
        guard let b = backend else { return }
        do {
            if let d = try b.read(service: service, account: Vault.account) {
                if let s = try? Vault.decoder.decode(Secrets.self, from: d) { secrets = s } else { log("keychain: unreadable secrets, starting empty") }
            } else {
                save()
            }
        } catch let e as KeychainError {
            log("keychain: \(Vault.account) not readable without UI (\(e.status)); pairings are kept in memory until the next launch")
        } catch { log("keychain: read failed: \(error)") }
    }

    /// Mutate and persist in one step.
    func update(_ f: (inout Secrets) -> Void) {
        f(&secrets)
        save()
    }

    @discardableResult
    private func save() -> Bool {
        guard let b = backend, let d = try? Vault.encoder.encode(secrets) else { return backend == nil }
        return b.write(service: service, account: Vault.account, data: d, label: "Mote Helper")
    }

    static var decoder: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }
    static var encoder: JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = .sortedKeys; return e }
}
