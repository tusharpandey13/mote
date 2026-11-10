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

/// Raw Keychain access (generic passwords), injectable so every branch is unit-testable.
protocol SecretBackend {
    /// nil = not found; throws on any other failure (denied, locked, ...). `interactive: false` must never show Keychain UI.
    func read(service: String, account: String, interactive: Bool) throws -> Data?
    func write(service: String, account: String, data: Data, label: String) -> Bool
    func delete(service: String, account: String) -> Bool
}

struct KeychainError: Error { let status: OSStatus }

struct KeychainBackend: SecretBackend {
    /// OSStatus of the last write (diagnostics).
    static var lastWriteStatus: OSStatus?

    private func query(_ s: String, _ a: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: s, kSecAttrAccount as String: a]
    }

    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        var q = query(service, account)
        // Normally never show Keychain UI: items whose ACL / partition doesn't match this signature fail instead of prompting.
        // The one interactive read per identity change (Vault.load) is the only caller that passes `interactive: true`.
        if !interactive { q[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail }
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let t0 = Date()
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        ev(.keychain, "read", ["account": account, "status": Int(st), "ms": Int(Date().timeIntervalSince(t0) * 1000), "interactive": interactive])
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
        KeychainBackend.lastWriteStatus = st
        ev(.keychain, "write", ["account": account, "status": Int(st), "ok": st == errSecSuccess])
        return st == errSecSuccess
    }

    /// May show a Keychain prompt (macOS asks before deleting an item this build does not own): only after the user's
    /// confirmation of "Keychain: Repair", or the rewrite right after a successful interactive read.
    func delete(service: String, account: String) -> Bool {
        let st = SecItemDelete(query(service, account) as CFDictionary)
        ev(.keychain, "write", ["account": account, "status": Int(st), "ok": st == errSecSuccess || st == errSecItemNotFound, "op": "delete"])
        return st == errSecSuccess || st == errSecItemNotFound
    }
}

/// Owner of `Secrets`. `memoryOnly` (--memory-store, tests) never touches the Keychain.
///
/// Unreadable item (docs/signing.md; owner decisions U5/U6, docs/journeys): `secrets.v3` made by another signing identity is
/// unreadable without UI (partition mismatch). Then, exactly ONCE per identity (gate: UserDefaults, written BEFORE the read so a
/// crash or relaunch can't loop), one interactive read; on success the item is rewritten so this build's partition owns it. On
/// denial or any other failure the Vault is EPHEMERAL: secrets live in memory only, nothing is ever saved, and trust is never
/// automatic (InstantPairing). Only a successful read (next launch) or `repair()` ends it.
final class Vault {
    static let account = "secrets.v3"
    static let partitionKey = "keychain.partitionAtWrite"
    static let askedKey = "keychain.interactiveFor"
    private(set) var secrets = Secrets()
    /// Diagnostics: what the launch-time read of `secrets.v3` returned. nil = memory store (never touches the Keychain).
    private(set) var readStatus: Int32?
    private(set) var readResult = "memory"   // memory | found | notfound | error | unreadable
    /// none (no read failed) | skipped (failed, already asked for this identity) | n/a (failure a prompt can't fix) | ok | denied | error
    private(set) var interactiveRead = "none"
    /// Secrets in memory only, never saved, no automatic trust. Set by `load()`, cleared by `repair()`.
    private(set) var ephemeral = false
    private(set) var ephemeralReason: String?
    private let service: String
    private let backend: SecretBackend?
    private let defaults: UserDefaults?
    private let identityKey: String?
    private var memGate: String?

    /// `defaults`: where the once-per-identity gate lives (nil = per process: fakes, tests). `identity`: the signing identity as
    /// "teamid:..." / "cdhash:..." (nil with the real Keychain = read from the code signature; nil otherwise = unknown, and the
    /// gate then never allows an interactive read).
    init(service: String, memoryOnly: Bool, backend: SecretBackend = KeychainBackend(),
         defaults: UserDefaults? = nil, identity: String? = nil) {
        self.service = service
        self.backend = memoryOnly ? nil : backend
        self.defaults = defaults
        self.identityKey = identity ?? (!memoryOnly && backend is KeychainBackend ? SigningInfo.current().expectedPartition : nil)
        load()
    }

    private var gateKey: String { Vault.askedKey + "." + service }
    private var asked: String? {
        get { defaults?.string(forKey: gateKey) ?? memGate }
        set { memGate = newValue; if let newValue { defaults?.set(newValue, forKey: gateKey) } else { defaults?.removeObject(forKey: gateKey) } }
    }

    /// errSecInteractionNotAllowed / errSecAuthFailed (UIFail refused a prompt), or the recorded partition is not ours.
    private func promptCouldHelp(_ status: Int32) -> Bool {
        if status == -25308 || status == -25293 { return true }
        if let p = defaults?.string(forKey: Vault.partitionKey), let id = identityKey, p != id { return true }
        return false
    }

    private func adopt(_ d: Data) -> Bool {
        if let s = try? Vault.decoder.decode(Secrets.self, from: d) { secrets = s; return true }
        return false
    }

    private func enterEphemeral(_ reason: String) {
        guard !ephemeral else { return }
        ephemeral = true; ephemeralReason = reason
        log("keychain: \(Vault.account) not readable (\(readStatus ?? 0), \(reason)); pairings are NOT saved and nothing is trusted automatically")
        ev(.keychain, "ephemeral.enter", ["reason": reason, "status": Int(readStatus ?? 0), "interactive": interactiveRead])
    }

    private func load() {
        guard let b = backend else { return }
        do {
            if let d = try b.read(service: service, account: Vault.account, interactive: false) {
                readStatus = 0; readResult = "found"
                if !adopt(d) { readResult = "unreadable"; enterEphemeral("corrupt") }
            } else {
                readStatus = errSecItemNotFound; readResult = "notfound"
                save()
            }
        } catch let e as KeychainError {
            readStatus = e.status; readResult = "error"
            guard promptCouldHelp(e.status) else { interactiveRead = "n/a"; enterEphemeral("error"); return }
            guard let id = identityKey, asked != id else { interactiveRead = "skipped"; enterEphemeral("denied"); return }
            asked = id   // before the read: whatever happens, this identity is never asked again until Repair
            interactiveLoad(b)
        } catch {
            log("keychain: read failed: \(error)")
            readResult = "error"; interactiveRead = "n/a"; enterEphemeral("error")
        }
    }

    /// The single interactive read.
    private func interactiveLoad(_ b: SecretBackend) {
        do {
            guard let d = try b.read(service: service, account: Vault.account, interactive: true) else {
                interactiveRead = "ok"; readStatus = errSecItemNotFound; readResult = "notfound"
                ev(.keychain, "interactive", ["result": "notfound"]); save(); return
            }
            interactiveRead = "ok"
            guard adopt(d) else {
                readResult = "unreadable"
                ev(.keychain, "interactive", ["result": "corrupt"]); enterEphemeral("corrupt"); return
            }
            readStatus = 0; readResult = "found"
            ev(.keychain, "interactive", ["result": "ok"])
            rewrite(d)
        } catch let e as KeychainError {
            interactiveRead = (e.status == -128 || e.status == -25293 || e.status == -60005) ? "denied" : "error"
            ev(.keychain, "interactive", ["result": interactiveRead, "status": Int(e.status)])
            enterEphemeral(interactiveRead)
        } catch { interactiveRead = "error"; enterEphemeral("error") }
    }

    /// Re-create the item so THIS build's partition owns it. SecItemUpdate only changes the value (the partition list is an ACL
    /// attribute that stays with the item), so it is delete + add; the secrets stay in memory if the add fails (ephemeral then).
    private func rewrite(_ d: Data) {
        guard let b = backend else { return }
        _ = b.delete(service: service, account: Vault.account)
        var ok = b.write(service: service, account: Vault.account, data: d, label: "Mote Helper")
        if !ok { ok = b.write(service: service, account: Vault.account, data: d, label: "Mote Helper") }
        var verified = false
        if ok { verified = ((try? b.read(service: service, account: Vault.account, interactive: false)) ?? nil) != nil }
        ev(.keychain, "rewrite", ["ok": ok, "verified": verified])
        if ok { recordPartition() } else { enterEphemeral("rewrite-failed") }
    }

    private func recordPartition() {
        guard let id = identityKey else { return }
        defaults?.set(id, forKey: Vault.partitionKey)
        ev(.keychain, "partition", ["partition": id])
    }

    /// "Keychain: Repair" (after the UI's confirmation): delete the item and create a fresh one. Phones must pair again
    /// (their pinned key for this Mac is gone). What is in memory (explicit Allows of this run, this run's identity) is saved
    /// into the new item. False = nothing changed, still ephemeral.
    @discardableResult
    func repair() -> Bool {
        guard let b = backend else { return false }
        _ = b.delete(service: service, account: Vault.account)
        guard let d = try? Vault.encoder.encode(secrets), b.write(service: service, account: Vault.account, data: d, label: "Mote Helper") else {
            ev(.keychain, "repair", ["ok": false]); return false
        }
        ev(.keychain, "repair", ["ok": true])
        readStatus = errSecItemNotFound; readResult = "notfound"
        asked = nil
        recordPartition()
        if ephemeral {
            ephemeral = false; ephemeralReason = nil
            ev(.keychain, "ephemeral.leave", ["via": "repair"])
        }
        return true
    }

    /// Mutate and persist in one step (memory only while ephemeral).
    func update(_ f: (inout Secrets) -> Void) {
        f(&secrets)
        save()
    }

    @discardableResult
    private func save() -> Bool {
        guard let b = backend else { return true }
        guard !ephemeral, readResult != "error", readResult != "unreadable", let d = try? Vault.encoder.encode(secrets) else { return false }
        let ok = b.write(service: service, account: Vault.account, data: d, label: "Mote Helper")
        if ok, readResult == "notfound" { recordPartition() }   // we created the item: its partition is this identity
        return ok
    }

    static var decoder: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }
    static var encoder: JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = .sortedKeys; return e }
}
