import Foundation
import Security

/// Everything secret the helper keeps, as ONE JSON blob in ONE generic-password Keychain item
/// (service `dev.mote.helper`, account `secrets`). Read once at launch and cached in memory, so macOS
/// asks at most once, and never again after "Always Allow" (with the stable signature, build.sh).
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
    func delete(service: String, account: String)
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

    func delete(service: String, account: String) { SecItemDelete(query(service, account) as CFDictionary) }
}

/// Owner of `Secrets`. `memoryOnly` (--memory-store, tests) never touches the Keychain.
final class Vault {
    static let account = "secrets.v2"   // v1 "secrets" may carry an ad-hoc-build ACL; never prompt for it
    private(set) var secrets = Secrets()
    private let service: String
    private let backend: SecretBackend?

    /// Pre-0.6 layout: five items over three services. Migrated into the blob once, then deleted.
    static func legacyItems(_ service: String) -> [(service: String, account: String)] {
        [(service, "devices"), (service + ".bridge", "devices"),
         (service + ".labs", "trusted"), (service + ".labs", "blocked"), (service + ".labs", "identity"), (service, "secrets")]
    }

    init(service: String, memoryOnly: Bool, backend: SecretBackend = KeychainBackend()) {
        self.service = service
        self.backend = memoryOnly ? nil : backend
        load()
    }

    private func load() {
        guard let b = backend else { return }
        do {
            if let d = try b.read(service: service, account: Vault.account) {
                if let s = try? Vault.decoder.decode(Secrets.self, from: d) { secrets = s } else { log("keychain: unreadable secrets, starting empty") }
                return
            }
        } catch let e as KeychainError {
            log("keychain read failed: \(e.status) (pairings are kept in memory until the next launch)")
            return
        } catch { return }
        migrate(b)
    }

    /// Old items -> one blob. Items that can't be read (denied) are left in place and logged.
    private func migrate(_ b: SecretBackend) {
        var s = Secrets()
        var moved: [(String, String)] = []
        for (svc, acc) in Vault.legacyItems(service) {
            let data: Data
            do { guard let d = try b.read(service: svc, account: acc) else { continue }; data = d } catch {
                log("keychain: legacy item \(svc)/\(acc) unreadable (\((error as? KeychainError)?.status ?? 0)), not migrated"); continue
            }
            moved.append((svc, acc))
            switch (svc.hasSuffix(".bridge"), svc.hasSuffix(".labs"), acc) {
            case (false, false, "devices"): s.devices = (try? Vault.decoder.decode([Device].self, from: data)) ?? []
            case (true, _, "devices"): s.bridge = (try? Vault.decoder.decode([Device].self, from: data)) ?? []
            case (_, true, "trusted"): s.trusted = (try? Vault.decoder.decode([LabsTrustedKey].self, from: data)) ?? []
            case (_, true, "blocked"):   // [{fp, name}]; early builds stored [fp]
                s.blocked = (try? JSONDecoder().decode([LabsBlockedKey].self, from: data))
                    ?? ((try? JSONDecoder().decode([String].self, from: data)) ?? []).map { LabsBlockedKey(fp: $0, name: "Phone") }
            case (_, true, "identity"): s.identity = data
            case (false, false, "secrets"): if let v1 = try? Vault.decoder.decode(Secrets.self, from: data) { s = v1 }
            default: break
            }
        }
        // Always write the blob, even when nothing could be migrated: otherwise the next launch finds no blob,
        // retries the legacy reads, and macOS prompts again on every launch.
        secrets = s
        if save() {
            for (svc, acc) in moved { b.delete(service: svc, account: acc) }
            log("keychain: migrated \(moved.count) item(s) into one")
        }
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
