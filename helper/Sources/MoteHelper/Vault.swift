import Foundation
import Security

/// Everything secret the helper keeps, as ONE JSON blob in ONE generic-password Keychain item
/// (service `dev.mote.helper`, account `secrets.v4.<partition>`, the partition being this build's signing identity:
/// `teamid:X` or `cdhash:H`). Read once at launch and cached in memory.
///
/// Why one account per signing identity: an item is readable without UI only by the signature that created it (its
/// partition). Instead of rewriting another build's item in place (delete + add: it can lose data), a build COPIES the data
/// into its own account after one successful (interactive) read, and never deletes the old item, which stays as the backup.
/// Older items (`secrets.v2`, `secrets`, the per-secret ones) are NEVER read or deleted: touching them can prompt.
///
/// The proximity secret (Proximity.swift) is deliberately not stored: it is per process, memory only.
struct Secrets: Codable, Equatable {
    var v = 1
    var devices: [Device] = []            // web remote / app pairings (code pairing)
    var bridge: [Device] = []             // Labs › browser extension bridge pairings
    var trusted: [LabsTrustedKey] = []    // instant pairing: trusted phone keys
    var blocked: [LabsBlockedKey] = []    // instant pairing: blocked phone keys
    var revoked: [String] = []            // instant pairing: fingerprints of revoked phones (oldest first, capped), so they get `revoked`, not `far`
    var identity: Data? = nil             // instant pairing: this Mac's Ed25519 private key (raw)

    init() {}
    init(from d: Decoder) throws {   // tolerant: missing fields get their defaults
        let c = try d.container(keyedBy: CodingKeys.self)
        v = (try? c.decode(Int.self, forKey: .v)) ?? 1
        devices = (try? c.decode([Device].self, forKey: .devices)) ?? []
        bridge = (try? c.decode([Device].self, forKey: .bridge)) ?? []
        trusted = (try? c.decode([LabsTrustedKey].self, forKey: .trusted)) ?? []
        blocked = (try? c.decode([LabsBlockedKey].self, forKey: .blocked)) ?? []
        revoked = (try? c.decode([String].self, forKey: .revoked)) ?? []
        identity = try? c.decode(Data.self, forKey: .identity)
    }
}

/// One generic-password item of a service, as listed by attributes only.
struct KeychainEntry: Equatable {
    let account: String
    let modified: Date?
}

/// Raw Keychain access (generic passwords), injectable so every branch is unit-testable.
protocol SecretBackend {
    /// Accounts of the service with their modification dates, from attributes only (no data): needs no ACL and never shows UI.
    func list(service: String) throws -> [KeychainEntry]
    /// nil = not found; throws on any other failure (denied, locked, ...). `interactive: false` must never show Keychain UI.
    func read(service: String, account: String, interactive: Bool) throws -> Data?
    func write(service: String, account: String, data: Data, label: String) -> Bool
    /// Only ever called for this build's own account (no UI).
    func delete(service: String, account: String) -> Bool
}

struct KeychainError: Error { let status: OSStatus }

struct KeychainBackend: SecretBackend {
    /// OSStatus of the last write (diagnostics).
    static var lastWriteStatus: OSStatus?

    private func query(_ s: String, _ a: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: s, kSecAttrAccount as String: a]
    }

    /// The call shape (no kSecReturnData, kSecMatchLimitAll, UI fail) is what makes this ACL-free; L3 confirms it on a real Keychain.
    static func listQuery(service: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecMatchLimit as String: kSecMatchLimitAll, kSecReturnAttributes as String: true,
         kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail]
    }

    func list(service: String) throws -> [KeychainEntry] {
        var out: CFTypeRef?
        let st = SecItemCopyMatching(KeychainBackend.listQuery(service: service) as CFDictionary, &out)
        ev(.keychain, "list", ["status": Int(st), "n": (out as? [Any])?.count ?? 0])
        if st == errSecItemNotFound { return [] }
        guard st == errSecSuccess else { throw KeychainError(status: st) }
        return (out as? [[String: Any]] ?? []).compactMap { a in
            (a[kSecAttrAccount as String] as? String).map { KeychainEntry(account: $0, modified: a[kSecAttrModificationDate as String] as? Date) }
        }
    }

    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        var q = query(service, account)
        // Normally never show Keychain UI: items whose ACL / partition doesn't match this signature fail instead of prompting.
        // The one interactive read per item version (Vault) is the only caller that passes `interactive: true`.
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

    /// Own account only (created by this signature): needs no prompt. Other builds' items are never deleted.
    func delete(service: String, account: String) -> Bool {
        var q = query(service, account); q[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail
        let st = SecItemDelete(q as CFDictionary)
        ev(.keychain, "write", ["account": account, "status": Int(st), "ok": st == errSecSuccess || st == errSecItemNotFound, "op": "delete"])
        return st == errSecSuccess || st == errSecItemNotFound
    }
}

/// Owner of `Secrets`. `memoryOnly` (--memory-store, tests) never touches the Keychain.
///
/// Launch order (owner decisions U5/U6, docs/journeys, docs/pairing.md section 7):
/// 1. List the service's accounts by attributes only (no UI, no ACL). A failure is `ephemeral("error")`, never "missing" (M3).
/// 2. Our own account `secrets.v4.<identity>` exists and is at least as new as every other vault account (`secrets.v3`, other
///    builds' `secrets.v4.*`): read it without UI.
/// 3. Another account is newer, or ours is missing: read that one without UI; if only a prompt could help, this is THE interactive
///    read: gated once per (identity, account, modification date), started after launch off the main thread (`startInteractiveRead`),
///    meanwhile EPHEMERAL "reading". A denied / failed read keeps the gate (no prompt on the next launch); only
///    `tryReadAgain` or `repair` retries (H2).
/// 4. After any successful read of another account its data is COPIED into our own account (SecItemAdd, our ACL). Nothing is ever
///    deleted or rewritten on the other items: they stay as the backup. A failed copy / write is `ephemeral("write-failed")` (M4).
/// 5. No account at all: a fresh install; we create ours.
///
/// Ephemeral = secrets in memory only, nothing saved (except the retry of our own failed write), trust never automatic
/// (InstantPairing), new pairings refused while `reading` (Session / InstantPairing). Only a successful read, a successful write
/// (write-failed) or `repair()` ends it.
final class Vault {
    static let legacyAccount = "secrets.v3"
    static let accountPrefix = "secrets.v4."
    static let askedKey = "keychain.interactiveFor"
    static func partitionKey(_ service: String) -> String { "keychain.partitionAtWrite." + service }
    /// This build's account for a signing identity ("teamid:X" / "cdhash:H"); nil = identity unknown.
    static func account(for identity: String?) -> String? { identity.map { accountPrefix + $0 } }
    private static func isVaultAccount(_ a: String) -> Bool { a == legacyAccount || a.hasPrefix(accountPrefix) }

    private(set) var secrets = Secrets()
    /// Diagnostics: what the launch-time read returned. nil = memory store (never touches the Keychain).
    private(set) var readStatus: Int32?
    private(set) var readResult = "memory"   // memory | found | notfound | error | unreadable
    /// none (no read needed) | skipped (needed, already asked for this item version) | n/a (failure a prompt can't fix) | ok | denied | error
    private(set) var interactiveRead = "none"
    /// Secrets in memory only, never saved (but see write-failed), no automatic trust. Cleared by a successful read, write or `repair()`.
    private(set) var ephemeral = false
    /// reading | denied | error | corrupt | write-failed | no-identity | dry-run
    private(set) var ephemeralReason: String?
    /// The interactive read is due or running ("Reading Keychain…"). Implies `ephemeral`; new pairings are refused (M2).
    private(set) var reading = false
    /// Other vault accounts of this service (diagnostics `keychain.otherAccounts`); never modified by this build.
    private(set) var otherAccounts: [String] = []
    /// This build's account (nil = identity unknown or memory store).
    let ownAccount: String?
    /// Something changed after launch (read finished, left ephemeral): refresh the UI.
    var onChange: (() -> Void)?
    /// Secrets were adopted from the Keychain (any adopt, M1): re-derive anything cached from them (this Mac's key, trust list).
    var onAdopt: (() -> Void)?
    private let service: String
    private let backend: SecretBackend?
    private let defaults: UserDefaults?
    private let identityKey: String?
    private let allowInteractive: Bool
    private var memGate: String?
    private var readInFlight = false
    private var source: KeychainEntry?   // the item the interactive read fetches

    /// `defaults`: where the once-per-item-version gate lives (nil = per process: fakes, tests). `identity`: the signing identity as
    /// "teamid:..." / "cdhash:..." (nil with the real Keychain = read from the code signature; nil otherwise = unknown: no account
    /// of ours, so the Vault stays ephemeral). `allowInteractive: false` (real Keychain under --dry-run) never reads with UI.
    /// `deferInteractive`: the caller runs `startInteractiveRead()` after launch (otherwise it runs, synchronously, in init).
    init(service: String, memoryOnly: Bool, backend: SecretBackend = KeychainBackend(),
         defaults: UserDefaults? = nil, identity: String? = nil, allowInteractive: Bool = true, deferInteractive: Bool = false) {
        self.service = service
        self.backend = memoryOnly ? nil : backend
        self.defaults = defaults
        self.allowInteractive = allowInteractive
        let id = identity ?? (!memoryOnly && backend is KeychainBackend ? SigningInfo.current().expectedPartition : nil)
        self.identityKey = id
        self.ownAccount = memoryOnly ? nil : Vault.account(for: id)
        load()
        if reading && !deferInteractive { startInteractiveRead(background: false) }
    }

    private var gateKey: String { Vault.askedKey + "." + service }
    private var asked: String? {
        get { defaults?.string(forKey: gateKey) ?? memGate }
        set { memGate = newValue; if let newValue { defaults?.set(newValue, forKey: gateKey) } else { defaults?.removeObject(forKey: gateKey) } }
    }
    /// identity | account | modification date: a new signature or a newer item may ask once more.
    private func gate(_ e: KeychainEntry) -> String {
        "\(identityKey ?? "?")|\(e.account)|\(Int((e.modified?.timeIntervalSince1970 ?? 0) * 1000))"
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
        log("keychain: secrets not readable or not writable (\(readStatus ?? 0), \(reason)); pairings are NOT saved and nothing is trusted automatically")
        ev(.keychain, "ephemeral.enter", ["reason": reason, "status": Int(readStatus ?? 0), "interactive": interactiveRead])
    }

    private func leaveEphemeral(via: String) {
        guard ephemeral else { return }
        ephemeral = false; ephemeralReason = nil
        ev(.keychain, "ephemeral.leave", ["via": via])
    }

    private func newer(_ a: KeychainEntry, _ b: KeychainEntry) -> Bool { (a.modified ?? .distantPast) > (b.modified ?? .distantPast) }

    /// Launch (and "Try reading Keychain again"): decide which account to read. Never shows UI.
    private func load() {
        guard let b = backend else { return }
        reading = false; source = nil; interactiveRead = "none"
        guard let own = ownAccount else {
            readResult = "error"; interactiveRead = "n/a"; enterEphemeral("no-identity"); return
        }
        let entries: [KeychainEntry]
        do { entries = try b.list(service: service) }
        catch {
            readStatus = (error as? KeychainError)?.status ?? -1; readResult = "error"; interactiveRead = "n/a"
            enterEphemeral("error"); return   // M3: never "missing"
        }
        otherAccounts = entries.map(\.account).filter { $0 != own }.sorted()
        let mine = entries.first { $0.account == own }
        let newestOther = entries.filter { $0.account != own && Vault.isVaultAccount($0.account) }
            .reduce(nil as KeychainEntry?) { best, e in best.map { newer(e, $0) ? e : $0 } ?? e }
        ev(.keychain, "accounts", ["own": mine != nil, "others": otherAccounts.count, "newest": (mine != nil && (newestOther.map { !newer($0, mine!) } ?? true)) ? "own" : (newestOther?.account ?? "none")])

        if let m = mine, newestOther.map({ !newer($0, m) }) ?? true { readOwn(b, own); return }
        if let o = newestOther { readOther(b, o); return }
        // Fresh install: the list worked and shows no vault account at all.
        readStatus = errSecItemNotFound; readResult = "notfound"
        if ephemeral && ephemeralReason != "write-failed" { leaveEphemeral(via: "launch") }
        save()
    }

    private func readOwn(_ b: SecretBackend, _ own: String) {
        do {
            guard let d = try b.read(service: service, account: own, interactive: false) else {
                readStatus = errSecItemNotFound; readResult = "error"; interactiveRead = "n/a"; enterEphemeral("error"); return   // listed, then gone
            }
            readStatus = 0; readResult = "found"
            guard adopt(d) else { readResult = "unreadable"; enterEphemeral("corrupt"); return }
            leaveEphemeral(via: "launch"); onAdopt?()
        } catch {
            readStatus = (error as? KeychainError)?.status ?? -1; readResult = "error"; interactiveRead = "n/a"
            enterEphemeral("error")   // M3
        }
    }

    private func readOther(_ b: SecretBackend, _ o: KeychainEntry) {
        do {
            guard let d = try b.read(service: service, account: o.account, interactive: false) else {
                readStatus = errSecItemNotFound; readResult = "error"; interactiveRead = "n/a"; enterEphemeral("error"); return
            }
            readStatus = 0; readResult = "found"
            guard adopt(d) else { readResult = "unreadable"; enterEphemeral("corrupt"); return }
            copyToOwn(via: "launch", from: o.account)
        } catch {
            let st = (error as? KeychainError)?.status ?? -1
            readStatus = st; readResult = "error"
            guard promptCouldHelp(st) else { interactiveRead = "n/a"; enterEphemeral("error"); return }   // M3
            guard allowInteractive else { interactiveRead = "n/a"; enterEphemeral("dry-run"); return }
            guard identityKey != nil else { interactiveRead = "n/a"; enterEphemeral("no-identity"); return }
            source = o
            guard asked != gate(o) else { interactiveRead = "skipped"; enterEphemeral("denied"); return }   // H2
            reading = true; enterEphemeral("reading")
        }
    }

    /// Our own account gets the adopted secrets (SecItemAdd with our ACL). Never touches the source item. M1: always re-derive.
    private func copyToOwn(via: String, from: String) {
        let ok = writeOwn()
        ev(.keychain, "copy", ["from": from, "ok": ok])
        if ok { leaveEphemeral(via: via) } else { enterEphemeral("write-failed") }
        onAdopt?()
    }

    private func writeOwn() -> Bool {
        guard let b = backend, let own = ownAccount, let d = try? Vault.encoder.encode(secrets) else { return false }
        let ok = b.write(service: service, account: own, data: d, label: "Mote Helper")
        if ok { recordPartition() }
        return ok
    }

    // MARK: interactive read

    /// The single interactive read, after launch. The blocking Keychain call runs on a background queue (it may wait for the
    /// user's password); the result is applied on the main queue. No-op unless a read is due (`reading`).
    func startInteractiveRead(background: Bool = true) {
        guard reading, !readInFlight, let b = backend, let src = source else { return }
        guard allowInteractive, identityKey != nil else {
            reading = false; interactiveRead = "n/a"; enterEphemeral(allowInteractive ? "no-identity" : "dry-run"); onChange?(); return
        }
        readInFlight = true
        asked = gate(src)   // before the read: whatever happens, this item version is never asked again until a retry / Repair
        ev(.keychain, "interactive", ["result": "start", "background": background, "account": src.account])
        let svc = service
        func read() -> Result<Data?, KeychainError> {
            do { return .success(try b.read(service: svc, account: src.account, interactive: true)) }
            catch let e as KeychainError { return .failure(e) }
            catch { return .failure(KeychainError(status: -1)) }
        }
        if background {
            DispatchQueue.global(qos: .userInitiated).async {
                let r = read()
                DispatchQueue.main.async { self.finishInteractive(src, r) }
            }
        } else {
            finishInteractive(src, read())
        }
    }

    /// "Try reading Keychain again" (menu / Setup): non-destructive. Clears the gate and starts over from the account list.
    /// A failed write of our own account is simply retried.
    func tryReadAgain(background: Bool = true) {
        guard backend != nil, ephemeral, !readInFlight else { return }
        ev(.keychain, "reread", ["background": background])
        if ephemeralReason == "write-failed" {
            if writeOwn() { leaveEphemeral(via: "retry"); onAdopt?() }
            onChange?(); return
        }
        asked = nil
        load()
        if reading { onChange?(); startInteractiveRead(background: background) } else { onChange?() }
    }

    private func finishInteractive(_ src: KeychainEntry, _ r: Result<Data?, KeychainError>) {
        readInFlight = false; reading = false
        switch r {
        case .success(nil):
            // Listed, then gone: nothing to adopt. Not "missing" (M3); the next launch re-lists.
            interactiveRead = "error"; readStatus = errSecItemNotFound; readResult = "error"; asked = nil
            ev(.keychain, "interactive", ["result": "notfound"]); enterEphemeral("error")
        case .success(let d?):
            interactiveRead = "ok"
            guard adopt(d) else {
                readResult = "unreadable"
                ev(.keychain, "interactive", ["result": "corrupt"]); enterEphemeral("corrupt"); break
            }
            readStatus = 0; readResult = "found"
            ev(.keychain, "interactive", ["result": "ok"])
            copyToOwn(via: "interactive", from: src.account)
        case .failure(let e):
            interactiveRead = (e.status == -128 || e.status == -25293 || e.status == -60005) ? "denied" : "error"
            readStatus = e.status; readResult = "error"
            ev(.keychain, "interactive", ["result": interactiveRead, "status": Int(e.status)])
            enterEphemeral(interactiveRead)   // the gate stays set (H2)
        }
        onChange?()
    }

    private func recordPartition() {
        guard let id = identityKey else { return }
        defaults?.set(id, forKey: Vault.partitionKey(service))
        ev(.keychain, "partition", ["partition": id])
    }

    /// "Keychain: Repair" (after the UI's confirmation): delete ONLY our own account (no UI) and create it fresh from what is in
    /// memory (explicit Allows of this run, this run's identity). Other builds' items are left alone. Phones must pair again (their
    /// pinned key for this Mac is gone). False = still ephemeral (a failed delete changes nothing; a failed add is "write-failed").
    @discardableResult
    func repair() -> Bool {
        guard let b = backend, let own = ownAccount, !readInFlight else { return false }
        guard b.delete(service: service, account: own) else {
            ev(.keychain, "repair", ["ok": false, "stage": "delete"]); return false
        }
        reading = false; source = nil; asked = nil
        guard writeOwn() else {
            ev(.keychain, "repair", ["ok": false, "stage": "add"])
            readStatus = errSecItemNotFound; readResult = "notfound"; enterEphemeral("write-failed"); onChange?(); return false
        }
        ev(.keychain, "repair", ["ok": true])
        readStatus = errSecItemNotFound; readResult = "notfound"
        leaveEphemeral(via: "repair")
        onAdopt?(); onChange?()
        return true
    }

    /// Mutate and persist in one step (memory only while ephemeral).
    func update(_ f: (inout Secrets) -> Void) {
        f(&secrets)
        save()
    }

    /// Writes our own account. Not while the vault could not be read (ephemeral), except to retry a failed write of our own (M4).
    @discardableResult
    private func save() -> Bool {
        guard backend != nil else { return true }
        guard !ephemeral || ephemeralReason == "write-failed" else { return false }
        let ok = writeOwn()
        if ok { if ephemeral { leaveEphemeral(via: "write") } }
        else { enterEphemeral("write-failed") }
        return ok
    }

    static var decoder: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }
    static var encoder: JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = .sortedKeys; return e }
}
