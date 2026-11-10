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
/// unreadable without UI (partition mismatch). `init` never shows UI and never blocks: it starts EPHEMERAL ("reading") and the
/// caller starts the one interactive read after launch (`startInteractiveRead`, off the main thread). Gate: exactly ONCE per
/// identity (UserDefaults, written BEFORE the read so a crash or relaunch can't loop). On success the Keychain contents win (what
/// was held in memory meanwhile, e.g. pairings allowed during "reading", is discarded) and the item is rewritten so this build's
/// partition owns it. On denial or any other failure the Vault stays EPHEMERAL: secrets live in memory only, nothing is saved,
/// and trust is never automatic (InstantPairing). Only a successful read (`tryReadAgain`, next launch) or `repair()` ends it.
///
/// The rewrite never loses data: the secrets are first copied to the backup account `secrets.v3.pending`; `load()` falls back to
/// it when `secrets.v3` is missing. A failed rewrite is non-destructive (nothing deleted, gate cleared for a retry).
final class Vault {
    static let account = "secrets.v3"
    static let pendingAccount = "secrets.v3.pending"
    static let askedKey = "keychain.interactiveFor"
    static func partitionKey(_ service: String) -> String { "keychain.partitionAtWrite." + service }
    private(set) var secrets = Secrets()
    /// Diagnostics: what the launch-time read of `secrets.v3` returned. nil = memory store (never touches the Keychain).
    private(set) var readStatus: Int32?
    private(set) var readResult = "memory"   // memory | found | notfound | error | unreadable
    /// none (no read failed) | skipped (failed, already asked for this identity) | n/a (failure a prompt can't fix) | ok | denied | error
    private(set) var interactiveRead = "none"
    /// Secrets in memory only, never saved, no automatic trust. Set by `load()`, cleared by `repair()` / a successful read.
    private(set) var ephemeral = false
    /// reading | denied | error | corrupt | rewrite-failed | no-identity | dry-run
    private(set) var ephemeralReason: String?
    /// The interactive read is due or running ("Reading Keychain…"). Implies `ephemeral`.
    private(set) var reading = false
    /// Something changed after launch (read finished, left ephemeral): refresh the UI.
    var onChange: (() -> Void)?
    /// Left ephemeral mode: the Keychain contents (or Repair) replaced the in-memory secrets; re-derive anything cached from them.
    var onLeaveEphemeral: (() -> Void)?
    private let service: String
    private let backend: SecretBackend?
    private let defaults: UserDefaults?
    private let identityKey: String?
    private let allowInteractive: Bool
    private var memGate: String?
    private var readInFlight = false
    private var pendingExists = false
    private var retryAt: Date?
    private var retryCount = 0

    /// `defaults`: where the once-per-identity gate lives (nil = per process: fakes, tests). `identity`: the signing identity as
    /// "teamid:..." / "cdhash:..." (nil with the real Keychain = read from the code signature; nil otherwise = unknown, and the
    /// gate then never allows an interactive read). `allowInteractive: false` (real Keychain under --dry-run) never reads with UI.
    /// `deferInteractive`: the caller runs `startInteractiveRead()` after launch (otherwise it runs, synchronously, in init).
    init(service: String, memoryOnly: Bool, backend: SecretBackend = KeychainBackend(),
         defaults: UserDefaults? = nil, identity: String? = nil, allowInteractive: Bool = true, deferInteractive: Bool = false) {
        self.service = service
        self.backend = memoryOnly ? nil : backend
        self.defaults = defaults
        self.allowInteractive = allowInteractive
        self.identityKey = identity ?? (!memoryOnly && backend is KeychainBackend ? SigningInfo.current().expectedPartition : nil)
        load()
        if reading && !deferInteractive { startInteractiveRead(background: false) }
    }

    private var gateKey: String { Vault.askedKey + "." + service }
    private var asked: String? {
        get { defaults?.string(forKey: gateKey) ?? memGate }
        set { memGate = newValue; if let newValue { defaults?.set(newValue, forKey: gateKey) } else { defaults?.removeObject(forKey: gateKey) } }
    }

    /// Only errSecInteractionNotAllowed / errSecAuthFailed (UIFail refused a prompt) can be fixed by a prompt.
    private func promptCouldHelp(_ status: Int32) -> Bool { status == -25308 || status == -25293 }

    private func adopt(_ d: Data) -> Bool {
        if let s = try? Vault.decoder.decode(Secrets.self, from: d) { secrets = s; return true }
        return false
    }

    private func enterEphemeral(_ reason: String) {
        if ephemeral { ephemeralReason = reason; ev(.keychain, "ephemeral.enter", ["reason": reason, "update": true]); return }
        ephemeral = true; ephemeralReason = reason
        log("keychain: \(Vault.account) not readable (\(readStatus ?? 0), \(reason)); pairings are NOT saved and nothing is trusted automatically")
        ev(.keychain, "ephemeral.enter", ["reason": reason, "status": Int(readStatus ?? 0), "interactive": interactiveRead])
    }

    private func leaveEphemeral(via: String) {
        guard ephemeral else { return }
        ephemeral = false; ephemeralReason = nil
        ev(.keychain, "ephemeral.leave", ["via": via])
        onLeaveEphemeral?()
    }

    private func readPending(_ b: SecretBackend) -> Data? { ((try? b.read(service: service, account: Vault.pendingAccount, interactive: false)) ?? nil) }

    /// Best effort: the backup is ours, so deleting it needs no prompt.
    private func dropPending(_ b: SecretBackend) {
        guard pendingExists else { return }
        let ok = b.delete(service: service, account: Vault.pendingAccount)
        if ok { pendingExists = false }
        ev(.keychain, "pending", ["op": "delete", "ok": ok])
    }

    private func load() {
        guard let b = backend else { return }
        do {
            if let d = try b.read(service: service, account: Vault.account, interactive: false) {
                readStatus = 0; readResult = "found"
                if !adopt(d) { readResult = "unreadable"; enterEphemeral("corrupt") }
            } else {
                readStatus = errSecItemNotFound; readResult = "notfound"
                // A rewrite that deleted the item and could not add it back (crash, add failure): the backup has everything.
                if let p = readPending(b), adopt(p) {
                    pendingExists = true
                    ev(.keychain, "pending", ["op": "restore", "ok": true])
                    log("keychain: restored secrets from \(Vault.pendingAccount)")
                }
                if save() { dropPending(b) } else if pendingExists { scheduleRetry() }
            }
        } catch let e as KeychainError {
            readStatus = e.status; readResult = "error"
            guard promptCouldHelp(e.status) else { interactiveRead = "n/a"; enterEphemeral("error"); return }
            guard allowInteractive else { interactiveRead = "n/a"; enterEphemeral("dry-run"); return }
            guard identityKey != nil else { interactiveRead = "n/a"; enterEphemeral("no-identity"); return }
            guard asked != identityKey else { interactiveRead = "skipped"; enterEphemeral("denied"); return }
            reading = true; enterEphemeral("reading")
        } catch {
            log("keychain: read failed: \(error)")
            readResult = "error"; interactiveRead = "n/a"; enterEphemeral("error")
        }
    }

    // MARK: interactive read

    /// The single interactive read, after launch. The blocking Keychain call runs on a background queue (it may wait for the
    /// user's password); the result is applied on the main queue. No-op unless a read is due (`reading`).
    func startInteractiveRead(background: Bool = true) {
        guard reading, !readInFlight, let b = backend else { return }
        guard allowInteractive, let id = identityKey else {
            reading = false; interactiveRead = "n/a"; enterEphemeral(allowInteractive ? "no-identity" : "dry-run"); onChange?(); return
        }
        readInFlight = true
        asked = id   // before the read: whatever happens, this identity is never asked again until a retry / Repair
        ev(.keychain, "interactive", ["result": "start", "background": background])
        let svc = service
        func read() -> Result<Data?, KeychainError> {
            do { return .success(try b.read(service: svc, account: Vault.account, interactive: true)) }
            catch let e as KeychainError { return .failure(e) }
            catch { return .failure(KeychainError(status: -1)) }
        }
        if background {
            DispatchQueue.global(qos: .userInitiated).async {
                let r = read()
                DispatchQueue.main.async { self.finishInteractive(b, r) }
            }
        } else {
            finishInteractive(b, read())
        }
    }

    /// "Try reading Keychain again" (menu / Setup): non-destructive. Clears the gate and does one interactive read.
    func tryReadAgain(background: Bool = true) {
        guard backend != nil, ephemeral, !readInFlight else { return }
        asked = nil
        reading = true; ephemeralReason = "reading"
        ev(.keychain, "reread", ["background": background])
        onChange?()
        startInteractiveRead(background: background)
    }

    private func finishInteractive(_ b: SecretBackend, _ r: Result<Data?, KeychainError>) {
        readInFlight = false; reading = false
        switch r {
        case .success(nil):
            // The item vanished meanwhile: what is in memory is all there is; create the item from it.
            interactiveRead = "ok"; readStatus = errSecItemNotFound; readResult = "notfound"
            ev(.keychain, "interactive", ["result": "notfound"])
            leaveEphemeral(via: "interactive"); save()
        case .success(let d?):
            interactiveRead = "ok"
            guard adopt(d) else {
                readResult = "unreadable"
                ev(.keychain, "interactive", ["result": "corrupt"]); enterEphemeral("corrupt"); break
            }
            readStatus = 0; readResult = "found"
            ev(.keychain, "interactive", ["result": "ok"])
            switch rewrite(b, d) {
            case .ok, .recreate: leaveEphemeral(via: "interactive")
            case .failed: enterEphemeral("rewrite-failed"); asked = nil
            }
        case .failure(let e):
            interactiveRead = (e.status == -128 || e.status == -25293 || e.status == -60005) ? "denied" : "error"
            ev(.keychain, "interactive", ["result": interactiveRead, "status": Int(e.status)])
            enterEphemeral(interactiveRead)
        }
        onChange?()
    }

    // MARK: rewrite

    private enum Rewrite { case ok, recreate, failed }

    /// Make this build's partition own the item without ever holding the data only in memory.
    /// 1. copy to the backup account; 2. try an in-place UIFail update (an "Always Allow" may already have added our partition)
    /// and verify with a no-UI read; 3. only if that read fails, delete (may prompt) and add. Delete failed: nothing changed.
    /// Delete ok but add failed: `.recreate`, the item is "not found" and the next save (retried) creates it; the backup holds
    /// the data meanwhile. Verify failed after the add: `.failed`, nothing further is deleted.
    private func rewrite(_ b: SecretBackend, _ d: Data) -> Rewrite {
        func verify() -> Bool { ((try? b.read(service: service, account: Vault.account, interactive: false)) ?? nil) != nil }
        let backedUp = b.write(service: service, account: Vault.pendingAccount, data: d, label: "Mote Helper (backup)")
        if backedUp { pendingExists = true }
        ev(.keychain, "pending", ["op": "write", "ok": backedUp])

        if b.write(service: service, account: Vault.account, data: d, label: "Mote Helper"), verify() {
            ev(.keychain, "rewrite", ["ok": true, "verified": true, "via": "update"])
            recordPartition(); dropPending(b); return .ok
        }
        guard backedUp else {   // no safety copy: do not delete anything
            ev(.keychain, "rewrite", ["ok": false, "verified": false, "stage": "backup"]); return .failed
        }
        guard b.delete(service: service, account: Vault.account) else {
            ev(.keychain, "rewrite", ["ok": false, "verified": false, "stage": "delete"]); dropPending(b); return .failed
        }
        var ok = b.write(service: service, account: Vault.account, data: d, label: "Mote Helper")
        if !ok { ok = b.write(service: service, account: Vault.account, data: d, label: "Mote Helper") }
        guard ok else {
            // The item is gone and the backup has the data: behave as "not found" so the next save recreates it.
            readStatus = errSecItemNotFound; readResult = "notfound"
            ev(.keychain, "rewrite", ["ok": false, "verified": false, "stage": "add"])
            scheduleRetry(); return .recreate
        }
        guard verify() else {
            ev(.keychain, "rewrite", ["ok": true, "verified": false, "stage": "verify"]); return .failed
        }
        ev(.keychain, "rewrite", ["ok": true, "verified": true, "via": "delete"])
        recordPartition(); dropPending(b); return .ok
    }

    private func scheduleRetry(after: TimeInterval = 30) {
        guard retryCount < 10 else { return }
        retryAt = Date().addingTimeInterval(after)
    }

    /// Called every couple of seconds by the Hub: re-create an item that a rewrite deleted but could not add back.
    func retryIfNeeded(now: Date = Date()) {
        guard let t = retryAt, now >= t, let b = backend else { return }
        retryCount += 1; retryAt = nil
        let ok = save()
        ev(.keychain, "retry", ["ok": ok, "n": retryCount])
        if ok { dropPending(b) } else { scheduleRetry() }
    }

    private func recordPartition() {
        guard let id = identityKey else { return }
        defaults?.set(id, forKey: Vault.partitionKey(service))
        ev(.keychain, "partition", ["partition": id])
    }

    /// "Keychain: Repair" (after the UI's confirmation): delete the item and create a fresh one. Phones must pair again
    /// (their pinned key for this Mac is gone). What is in memory (explicit Allows of this run, this run's identity) is saved
    /// into the new item. False = nothing changed (a failed delete leaves the item alone), still ephemeral.
    @discardableResult
    func repair() -> Bool {
        guard let b = backend, !readInFlight else { return false }
        guard b.delete(service: service, account: Vault.account) else {
            ev(.keychain, "repair", ["ok": false, "stage": "delete"]); return false
        }
        _ = b.delete(service: service, account: Vault.pendingAccount); pendingExists = false
        guard let d = try? Vault.encoder.encode(secrets), b.write(service: service, account: Vault.account, data: d, label: "Mote Helper") else {
            ev(.keychain, "repair", ["ok": false, "stage": "add"]); return false
        }
        ev(.keychain, "repair", ["ok": true])
        readStatus = errSecItemNotFound; readResult = "notfound"
        asked = nil; reading = false
        recordPartition()
        leaveEphemeral(via: "repair")
        onChange?()
        return true
    }

    /// Mutate and persist in one step (memory only while ephemeral).
    func update(_ f: (inout Secrets) -> Void) {
        f(&secrets)
        if save(), let b = backend { dropPending(b) }
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
