import CryptoKit
import Foundation
import Testing
@testable import MoteHelper

/// U5 / U6 (docs/journeys): unreadable secrets.v3 after a signing-identity change.
struct VaultUnreadableTests {
    let svc = "dev.mote.test"
    func blob(identity: UInt8 = 9) -> Data {
        var s = Secrets(); s.identity = Data(repeating: identity, count: 32)
        s.blocked = [LabsBlockedKey(fp: "bad", name: "x")]
        return try! Vault.encoder.encode(s)
    }
    func backend(_ m: FakeBackend.Mode) -> FakeBackend {
        let b = FakeBackend(); b.mode = m
        b.items[b.key(svc, "secrets.v3")] = blob()
        return b
    }
    func defaults() -> UserDefaults { UserDefaults(suiteName: "mote.test." + UUID().uuidString)! }
    func vault(_ b: FakeBackend, _ d: UserDefaults, id: String? = "cdhash:a") -> Vault {
        Vault(service: svc, memoryOnly: false, backend: b, defaults: d, identity: id)
    }

    @Test func partitionMismatchDoesOneInteractiveReadAndRewrites() {
        let b = backend(.mismatch)
        let v = vault(b, defaults())
        #expect(b.reads == 4 && b.interactiveReads == 1)   // no-UI read, the one interactive read, a no-UI verify after each write attempt
        #expect(v.readResult == "found" && v.interactiveRead == "ok" && !v.ephemeral)
        #expect(v.secrets.identity == Data(repeating: 9, count: 32) && v.secrets.blocked.map(\.fp) == ["bad"])   // kept, not re-minted
        // backup, in-place update (not readable yet), then delete + add so this build's partition owns the item; backup dropped
        #expect(b.writes == 3 && b.deletes == 2 && b.items[b.key(svc, Vault.pendingAccount)] == nil)
        #expect(v.secrets == (try! Vault.decoder.decode(Secrets.self, from: b.items[b.key(svc, Vault.account)]!)))
        v.update { $0.identity = Data(repeating: 1, count: 32) }
        #expect(b.writes == 4)                      // saving works again
    }

    @Test func interactiveReadIsGatedPerIdentity() {
        let d = defaults()
        let b1 = backend(.deniedInteractive)
        let v1 = vault(b1, d)
        #expect(b1.interactiveReads == 1 && v1.interactiveRead == "denied" && v1.ephemeral)
        let b2 = backend(.deniedInteractive)   // relaunch of the same build: no second prompt
        let v2 = vault(b2, d)
        #expect(b2.interactiveReads == 0 && b2.reads == 1 && v2.interactiveRead == "skipped" && v2.ephemeral)
        let b3 = backend(.deniedInteractive)   // a new build (identity changed): one more
        let v3 = vault(b3, d, id: "cdhash:b")
        #expect(b3.interactiveReads == 1 && v3.ephemeral)
        #expect(b1.writes + b2.writes + b3.writes == 0)
    }

    @Test func noIdentityMeansNoInteractiveRead() {
        let b = backend(.mismatch)
        let v = vault(b, defaults(), id: nil)
        #expect(b.interactiveReads == 0 && v.ephemeral)
    }

    @Test func errorReadNeverPromptsNeverSavesKeepsIdentityInMemory() {
        let b = backend(.error)
        let v = vault(b, defaults())
        #expect(b.reads == 1 && b.interactiveReads == 0 && v.readResult == "error" && v.interactiveRead == "n/a" && v.ephemeral)
        let before = b.items
        v.update { $0.identity = Data(repeating: 3, count: 32) }
        v.update { $0.trusted = [LabsTrustedKey(fp: "f", pub: "p", name: "n", via: "approved", created: Date())] }
        #expect(b.writes == 0 && b.deletes == 0 && b.items == before)   // never saves while the read failed
        #expect(v.secrets.identity == Data(repeating: 3, count: 32) && v.secrets.trusted.count == 1)   // memory only
    }

    @Test func deniedIsEphemeralAndNeverSaves() {
        let b = backend(.deniedInteractive)
        let v = vault(b, defaults())
        #expect(v.ephemeral && v.ephemeralReason == "denied" && v.readResult == "error")
        v.update { $0.devices = [] }
        #expect(b.writes == 0 && b.deletes == 0 && v.secrets.blocked.isEmpty)   // the real item's lists are not loaded
    }

    @Test func corruptItemIsEphemeralToo() {
        let b = FakeBackend()
        b.items[b.key(svc, "secrets.v3")] = Data("not json".utf8)
        let v = vault(b, defaults())
        #expect(v.readResult == "unreadable" && v.ephemeral)
        v.update { $0.identity = Data([1]) }
        #expect(b.writes == 0)
    }

    @Test func repairDeletesRecreatesAndLeavesEphemeral() {
        let d = defaults()
        let b = backend(.deniedInteractive)
        let v = vault(b, d)
        #expect(v.ephemeral)
        #expect(v.repair())
        #expect(!v.ephemeral && v.readResult == "notfound" && b.deletes == 2 && b.writes == 1)   // main + backup
        v.update { $0.identity = Data([5]) }
        #expect(b.writes == 2)
        // a stale gate must not survive a repair: the next identity change may ask again
        let b2 = backend(.mismatch)
        _ = vault(b2, d)
        #expect(b2.interactiveReads == 1)
    }

    @Test func failedRepairStaysEphemeral() {
        let b = backend(.error)
        let v = vault(b, defaults())
        b.failWrites = true
        #expect(!v.repair() && v.ephemeral)
    }

    @Test func failedDeleteDuringRepairLeavesTheItemAlone() {
        let b = backend(.deniedInteractive)
        let v = vault(b, defaults())
        let before = b.items
        b.failDeletes = true
        #expect(!v.repair() && v.ephemeral && b.items == before && b.writes == 0)
    }

    @Test func noIdentityIsNotAnInteractiveReadAndSaysWhy() {
        let v = vault(backend(.mismatch), defaults(), id: nil)
        #expect(v.ephemeral && v.interactiveRead == "n/a" && v.ephemeralReason == "no-identity")
    }

    @Test func realKeychainUnderDryRunNeverReadsInteractively() {
        let b = backend(.mismatch)
        let v = Vault(service: svc, memoryOnly: false, backend: b, defaults: defaults(), identity: "cdhash:a", allowInteractive: false)
        #expect(b.interactiveReads == 0 && v.ephemeral && v.ephemeralReason == "dry-run" && v.interactiveRead == "n/a")
    }

    @Test func alwaysAllowUpdateNeedsNoDelete() {
        let b = backend(.mismatch)
        b.updateFixes = true   // the user's earlier "Always Allow" already added our partition: the in-place update is enough
        let v = vault(b, defaults())
        #expect(!v.ephemeral && b.deletes == 1 /* only the backup */ && b.items[b.key(svc, Vault.account)] != nil)
    }

    @Test func failedDeleteDuringRewriteIsNonDestructive() {
        let b = backend(.mismatch)
        b.failDeletes = true
        let d = defaults()
        let v = vault(b, d)
        #expect(v.ephemeral && v.ephemeralReason == "rewrite-failed" && v.secrets.identity == Data(repeating: 9, count: 32))
        #expect(b.items[b.key(svc, Vault.account)] == blob())   // the item is untouched
        #expect(d.string(forKey: Vault.partitionKey(svc)) == nil)   // not recorded as ours
        let b2 = backend(.mismatch)   // gate cleared: a later launch (or Try again) may ask again
        _ = vault(b2, d)
        #expect(b2.interactiveReads == 1)
    }

    @Test func deleteOkAddFailedKeepsTheDataAndRecreatesTheItem() {
        let b = backend(.mismatch)
        b.failAccounts = [Vault.account]   // the in-place update and the add both fail; the delete works
        let v = vault(b, defaults())
        #expect(!v.ephemeral && v.readResult == "notfound")                // not ephemeral: the next save recreates it
        #expect(b.items[b.key(svc, Vault.account)] == nil && b.items[b.key(svc, Vault.pendingAccount)] != nil)   // data lives in the backup
        // a crash right now: the next launch restores from the backup
        let relaunch = Vault(service: svc, memoryOnly: false, backend: b, defaults: defaults(), identity: "cdhash:a")
        #expect(relaunch.secrets.identity == Data(repeating: 9, count: 32) && relaunch.secrets.blocked.map(\.fp) == ["bad"])
        // the retry recreates the item and drops the backup
        b.failAccounts = []
        v.retryIfNeeded(now: Date().addingTimeInterval(60))
        #expect(b.items[b.key(svc, Vault.account)] != nil && b.items[b.key(svc, Vault.pendingAccount)] == nil)
        #expect(v.secrets.identity == Data(repeating: 9, count: 32))
    }

    @Test func deleteOkAddFailedThenASaveRecreates() {
        let b = backend(.mismatch)
        b.failAccounts = [Vault.account]
        let v = vault(b, defaults())
        b.failAccounts = []
        v.update { $0.identity = Data(repeating: 4, count: 32) }
        let saved = try! Vault.decoder.decode(Secrets.self, from: b.items[b.key(svc, Vault.account)]!)
        #expect(saved.identity == Data(repeating: 4, count: 32) && saved.blocked.map(\.fp) == ["bad"] && b.items[b.key(svc, Vault.pendingAccount)] == nil)
    }

    @Test func unverifiedRewriteIsFailedNotRecorded() {
        let b = backend(.mismatch)
        b.persistMismatch = true   // added fine, but still unreadable without UI
        let d = defaults()
        let v = vault(b, d)
        #expect(v.ephemeral && v.ephemeralReason == "rewrite-failed" && d.string(forKey: Vault.partitionKey(svc)) == nil)
        #expect(b.items[b.key(svc, Vault.pendingAccount)] != nil && v.secrets.identity == Data(repeating: 9, count: 32))
    }

    @Test func deferredReadStartsEphemeralAndLeavesItWhenTheKeychainAnswers() {
        let b = backend(.mismatch)
        let v = Vault(service: svc, memoryOnly: false, backend: b, defaults: defaults(), identity: "cdhash:a", deferInteractive: true)
        #expect(v.reading && v.ephemeral && v.ephemeralReason == "reading" && b.interactiveReads == 0 && b.writes == 0)
        v.update { $0.blocked = [LabsBlockedKey(fp: "mem", name: "m")] }   // made while reading
        var left = 0; v.onLeaveEphemeral = { left += 1 }
        v.startInteractiveRead(background: false)
        #expect(!v.reading && !v.ephemeral && left == 1 && b.interactiveReads == 1)
        #expect(v.secrets.blocked.map(\.fp) == ["bad"])   // the Keychain wins; what was held in memory meanwhile is dropped
    }

    @Test func tryReadAgainClearsTheGateAndReads() {
        let d = defaults()
        let b = backend(.deniedInteractive)
        let v = vault(b, d)
        #expect(v.ephemeral && v.interactiveRead == "denied")
        b.mode = .mismatch   // the user types the password this time
        v.tryReadAgain(background: false)
        #expect(!v.ephemeral && b.interactiveReads == 2 && v.secrets.identity == Data(repeating: 9, count: 32))
    }

    @Test func tryReadAgainDeniedStaysEphemeralAndChangesNothing() {
        let b = backend(.deniedInteractive)
        let v = vault(b, defaults())
        let before = b.items
        v.tryReadAgain(background: false)
        #expect(v.ephemeral && v.ephemeralReason == "denied" && b.items == before && b.deletes == 0 && b.interactiveReads == 2)
    }

    @Test func fakeKeychainStatesMatchTheContract() {
        for (st, eph, ir) in [(FakeKeychainState.found, false, "none"), (.notFound, false, "none"), (.partitionMismatch, false, "ok"),
                              (.denied, true, "denied"), (.error, true, "n/a")] {
            let v = Vault(service: svc, memoryOnly: false, backend: FakeKeychain(state: st), identity: "cdhash:t")
            #expect(v.ephemeral == eph && v.interactiveRead == ir, "\(st)")
        }
        let v = Vault(service: svc, memoryOnly: false, backend: FakeKeychain(state: .found))
        #expect(v.secrets.identity == FakeKeychain.seedIdentity)
    }
}

/// U6: no automatic trust while the Vault is ephemeral (a blocked phone included); U12 hi fields.
struct EphemeralTrustTests {
    func hub(_ keychain: FakeKeychainState) -> Hub {
        var c = Config()
        c.dryRun = true; c.headless = true; c.memoryStore = true; c.ble = false
        c.fakeKeychain = keychain; c.fakeProximity = true; c.fakeTailscaleOwner = "self"
        return Hub(config: c)
    }

    /// One hello from a fresh phone key presenting a valid proximity token AND the same Tailscale owner. Returns the messages sent.
    func hello(_ hub: Hub) -> [[String: Any]] {
        var out: [[String: Any]] = []
        let s = Session(hub: hub, transport: "Wi-Fi", peer: "100.64.0.9", send: { d in
            if let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] { out.append(m) }
        }, close: {})
        let key = Curve25519.Signing.PrivateKey()
        let pub = key.publicKey.rawRepresentation.hexString
        hub.instantPairing.handle("labs.pair.prox", [:], from: s)
        let prox = out.last?["v"] as? String ?? ""
        let sig = try! key.signature(for: Data(labsClientProof(hostId: hub.settings.hostId, nonce: s.nonce, pub: pub).utf8)).hexString
        hub.instantPairing.handle("labs.pair.hello", ["pub": pub, "cn": randomHex(16), "sig": sig, "name": "Phone", "prox": prox], from: s)
        return out
    }

    func types(_ m: [[String: Any]]) -> [String] { m.compactMap { $0["t"] as? String } }

    @Test func healthyVaultTrustsByProximity() {
        #expect(types(hello(hub(.found))).contains("labs.pair.ok"))
    }

    @Test(arguments: [FakeKeychainState.denied, .error]) func ephemeralVaultNeverAutoTrusts(_ st: FakeKeychainState) {
        let h = hub(st)
        #expect(h.vault.ephemeral && h.instantPairing.needsApproval)
        let m = hello(h)
        #expect(!types(m).contains("labs.pair.ok"))          // proximity and Tailscale owner are both ignored
        #expect(types(m).contains("labs.pair.pending"))      // only an explicit Allow on the Mac decides
        #expect(h.instantPairing.trusted.isEmpty)
    }

    @Test func helloReportsEphemeralKey() {
        let m = hello(hub(.denied))
        #expect(m.first { $0["t"] as? String == "labs.pair.hi" }?["ephemeral"] as? Bool == true)
        let ok = hello(hub(.found))
        #expect(ok.first { $0["t"] as? String == "labs.pair.hi" }?["ephemeral"] == nil)
    }

    @Test func repairRestoresAutomaticTrust() {
        let h = hub(.denied)
        #expect(h.repairKeychain() && !h.vault.ephemeral && !h.instantPairing.needsApproval)
        #expect(types(hello(h)).contains("labs.pair.ok"))
    }
}

/// The real Hub path: the launch read is deferred, the gate persists across launches, pinned phones get a distinct error.
struct HubKeychainTests {
    func defaults() -> UserDefaults { UserDefaults(suiteName: "mote.test." + UUID().uuidString)! }
    func hub(_ st: FakeKeychainState, _ d: UserDefaults) -> Hub {
        var c = Config()
        c.dryRun = true; c.headless = true; c.memoryStore = true; c.ble = false
        c.fakeKeychain = st
        return Hub(config: c, defaults: d)
    }

    @Test func twoLaunchesShareThePersistedGate() {
        let d = defaults()
        let h1 = hub(.denied, d)
        #expect(h1.vault.reading && h1.vault.ephemeral && h1.vault.interactiveRead == "none")   // nothing asked yet
        h1.vault.startInteractiveRead(background: false)
        #expect(!h1.vault.reading && h1.vault.interactiveRead == "denied" && h1.vault.ephemeral)
        let h2 = hub(.denied, d)   // relaunch, same identity, same defaults: not asked again
        #expect(!h2.vault.reading && h2.vault.interactiveRead == "skipped" && h2.vault.ephemeral)
        h2.vault.startInteractiveRead(background: false)
        #expect(h2.vault.interactiveRead == "skipped")
        h2.vault.tryReadAgain(background: false)   // the explicit retry asks once more
        #expect(h2.vault.interactiveRead == "denied")
    }

    @Test func asyncReadLeavesEphemeralAndReloadsTheKey() {
        let h = hub(.partitionMismatch, defaults())
        let minted = h.instantPairing.fp
        var changes = 0; h.onChange = { changes += 1 }
        #expect(h.vault.ephemeral && h.instantPairing.needsApproval)
        h.vault.startInteractiveRead(background: false)
        #expect(!h.vault.ephemeral && !h.instantPairing.needsApproval && changes > 0)
        #expect(h.vault.secrets.identity == FakeKeychain.seedIdentity)
        #expect(h.instantPairing.fp != minted)   // the Keychain's key replaced the per-process one
    }

    @Test func pinnedPhoneGetsKeychainUnavailableWhileEphemeral() {
        let h = hub(.denied, defaults())
        var out: [[String: Any]] = []
        let s = Session(hub: h, transport: "Wi-Fi", peer: "10.0.0.5", send: { d in
            if let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] { out.append(m) }
        }, close: {})
        for _ in 0..<12 { s.handle(Data(#"{"t":"auth","id":"dev","mac":"00"}"#.utf8)) }
        let e = out.last { $0["t"] as? String == "err" }
        #expect(e?["code"] as? String == "keychain-unavailable" && e?["ephemeral"] as? Bool == true)
        #expect(!out.contains { $0["code"] as? String == "rate" })   // an unloaded pairing is not a guessing attempt
        let ok = hub(.found, defaults())
        out = []
        let s2 = Session(hub: ok, transport: "Wi-Fi", peer: "10.0.0.6", send: { d in
            if let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] { out.append(m) }
        }, close: {})
        s2.handle(Data(#"{"t":"auth","id":"dev","mac":"00"}"#.utf8))
        let e2 = out.last { $0["t"] as? String == "err" }
        #expect(e2?["code"] as? String == "auth" && e2?["ephemeral"] == nil)
    }
}
