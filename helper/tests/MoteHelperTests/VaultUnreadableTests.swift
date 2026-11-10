import CryptoKit
import Foundation
import Testing
@testable import MoteHelper

/// U5 / U6 (docs/journeys): another signature's item, launch order, gate, copy-not-rewrite, ephemeral mode.
struct VaultUnreadableTests {
    let svc = "dev.mote.test"
    let id = "cdhash:a"
    var own: String { "secrets.v4.cdhash:a" }
    let old = Date(timeIntervalSince1970: 1_700_000_000)
    let newer = Date(timeIntervalSince1970: 1_700_001_000)
    func blob(identity: UInt8 = 9) -> Data {
        var s = Secrets(); s.identity = Data(repeating: identity, count: 32)
        s.blocked = [LabsBlockedKey(fp: "bad", name: "x")]
        return try! Vault.encoder.encode(s)
    }
    /// Another build's `secrets.v3` (legacy), readable per `m`.
    func backend(_ m: FakeBackend.Mode, modified: Date? = nil) -> FakeBackend {
        let b = FakeBackend(); b.mode = m
        b.seed(svc, "secrets.v3", blob(), modified: modified ?? old)
        return b
    }
    func defaults() -> UserDefaults { UserDefaults(suiteName: "mote.test." + UUID().uuidString)! }
    func vault(_ b: FakeBackend, _ d: UserDefaults, id: String? = "cdhash:a", deferred: Bool = false) -> Vault {
        Vault(service: svc, memoryOnly: false, backend: b, defaults: d, identity: id, deferInteractive: deferred)
    }
    func ownData(_ b: FakeBackend) -> Secrets? { b.items[b.key(svc, own)].flatMap { try? Vault.decoder.decode(Secrets.self, from: $0) } }
    let seedIdentity = Data(repeating: 9, count: 32)

    // MARK: launch-order matrix

    @Test func ownNewestIsReadWithoutUIAndOthersAreNeverTouched() {
        let b = backend(.mismatch)
        b.seed(svc, own, blob(identity: 5), modified: newer, foreign: false)
        let v = vault(b, defaults())
        #expect(b.calls == ["list", "read:" + b.key(svc, own)])   // list first (attributes only), then one no-UI read of ours
        #expect(b.interactiveReads == 0 && b.writes == 0 && b.deletes == 0)
        #expect(!v.ephemeral && v.readResult == "found" && v.secrets.identity == Data(repeating: 5, count: 32))
        #expect(v.otherAccounts == ["secrets.v3"])
    }

    @Test func otherNewerIsTheOneInteractiveReadThenCopiedNotRewritten() {
        let b = backend(.mismatch, modified: newer)
        b.seed(svc, own, blob(identity: 5), modified: old, foreign: false)   // ours is stale
        let v = vault(b, defaults())
        #expect(b.interactiveReads == 1)
        #expect(!v.ephemeral && v.readResult == "found" && v.interactiveRead == "ok")
        #expect(v.secrets.identity == seedIdentity && v.secrets.blocked.map(\.fp) == ["bad"])   // kept, not re-minted
        #expect(ownData(b) == v.secrets)
        #expect(b.deletes == 0 && b.writeKeys == [b.key(svc, own)] && b.items[b.key(svc, "secrets.v3")] == blob())   // the old item is the backup
    }

    @Test func ownMissingReadsOtherInteractivelyAndCreatesOurs() {
        let b = backend(.mismatch)
        var adopted = 0
        let v = vault(b, defaults(), deferred: true)
        v.onAdopt = { adopted += 1 }
        #expect(v.reading && v.ephemeral && b.interactiveReads == 0 && b.writes == 0)
        v.startInteractiveRead(background: false)
        #expect(b.reads == 2 && b.interactiveReads == 1 && !v.ephemeral && adopted == 1)   // no-UI try, then the one interactive read
        #expect(b.deletes == 0 && ownData(b) == v.secrets)
        v.update { $0.identity = Data(repeating: 1, count: 32) }   // saving works, into our account
        #expect(b.writeKeys.allSatisfy { $0 == b.key(svc, own) } && b.writes == 2)
        let b2 = b   // next launch: ours is newest, no UI at all
        b2.interactiveReads = 0
        let v2 = vault(b2, defaults())
        #expect(b2.interactiveReads == 0 && v2.secrets.identity == Data(repeating: 1, count: 32) && !v2.ephemeral)
    }

    @Test func legacyOnlyReadableWithoutUIIsJustCopied() {
        let b = backend(.normal)   // same signature as the old build, or an Always Allow
        let v = vault(b, defaults())
        #expect(b.interactiveReads == 0 && !v.ephemeral && ownData(b) == v.secrets && b.deletes == 0)
        #expect(v.secrets.identity == seedIdentity)
    }

    @Test func readDeniedIsEphemeralAndNeverSaves() {
        let b = backend(.deniedInteractive)
        let before = b.items
        let v = vault(b, defaults())
        #expect(v.ephemeral && v.ephemeralReason == "denied" && v.interactiveRead == "denied" && v.readResult == "error")
        v.update { $0.devices = [] }
        #expect(b.writes == 0 && b.deletes == 0 && b.items == before && v.secrets.blocked.isEmpty)
    }

    @Test func otherReadErrorIsEphemeralNotMissingAndNeverPrompts() {
        let b = backend(.error)
        let v = vault(b, defaults())
        #expect(b.interactiveReads == 0 && v.readResult == "error" && v.interactiveRead == "n/a" && v.ephemeral && v.ephemeralReason == "error")
        v.update { $0.identity = Data(repeating: 3, count: 32) }
        #expect(b.writes == 0 && b.deletes == 0)
        #expect(v.secrets.identity == Data(repeating: 3, count: 32))   // memory only
    }

    @Test func ownReadErrorIsEphemeralNotMissing() {   // M3
        for st: Int32 in [-25308, -34018, -25293, -128, -1] {
            let b = backend(.normal)
            b.seed(svc, own, blob(identity: 5), modified: newer, foreign: false)
            b.readErrors[b.key(svc, own)] = st
            let v = vault(b, defaults())
            #expect(v.ephemeral && v.ephemeralReason == "error" && v.readResult == "error" && b.writes == 0 && b.deletes == 0 && b.interactiveReads == 0, "\(st)")
            v.update { $0.devices = [] }
            #expect(b.writes == 0)   // and a fresh item is never created over it
        }
    }

    @Test func listFailureIsEphemeralNotAFreshInstall() {   // M3
        let b = FakeBackend(); b.listError = KeychainError(status: -25308)
        let v = vault(b, defaults())
        #expect(v.ephemeral && v.ephemeralReason == "error" && b.reads == 0 && b.writes == 0)
        v.update { $0.identity = Data([1]) }
        #expect(b.writes == 0)
    }

    @Test func deniedKeepsTheGateOnRelaunchUntilTheItemChangesOrRetry() {   // H2
        let d = defaults()
        let b1 = backend(.deniedInteractive)
        let v1 = vault(b1, d)
        #expect(b1.interactiveReads == 1 && v1.interactiveRead == "denied" && v1.ephemeral)
        let b2 = backend(.deniedInteractive)   // relaunch, same item version: no second prompt
        let v2 = vault(b2, d)
        #expect(b2.interactiveReads == 0 && v2.interactiveRead == "skipped" && v2.ephemeral && v2.ephemeralReason == "denied")
        let b3 = backend(.deniedInteractive, modified: newer)   // the item changed: one more
        let v3 = vault(b3, d)
        #expect(b3.interactiveReads == 1 && v3.ephemeral)
        let b4 = backend(.deniedInteractive)   // another signature: one more
        _ = vault(b4, d, id: "cdhash:b")
        #expect(b4.interactiveReads == 1)
        #expect(b1.writes + b2.writes + b3.writes + b4.writes == 0)
    }

    @Test func failedInteractiveReadOtherThanDenialKeepsTheGateToo() {
        let d = defaults()
        let b = backend(.mismatch)
        b.readErrors[b.key(svc, "secrets.v3")] = -25308   // the item stays unreadable even interactively
        let v = vault(b, d)
        #expect(v.ephemeral && b.interactiveReads == 1)
        let b2 = backend(.mismatch)
        _ = vault(b2, d)
        #expect(b2.interactiveReads == 0)
    }

    @Test func tryReadAgainClearsTheGateAndReadsNonDestructively() {
        let d = defaults()
        let b = backend(.deniedInteractive)
        let v = vault(b, d)
        let before = b.items
        v.tryReadAgain(background: false)   // denied again: nothing changes
        #expect(v.ephemeral && v.ephemeralReason == "denied" && b.items == before && b.deletes == 0 && b.interactiveReads == 2)
        b.mode = .mismatch   // the user types the password this time
        var adopted = 0; v.onAdopt = { adopted += 1 }
        v.tryReadAgain(background: false)
        #expect(!v.ephemeral && b.interactiveReads == 3 && v.secrets.identity == seedIdentity && adopted == 1 && b.deletes == 0)
    }

    @Test func noIdentityMeansNoAccountOfOursAndNoInteractiveRead() {
        let b = backend(.mismatch)
        let v = vault(b, defaults(), id: nil)
        #expect(b.interactiveReads == 0 && b.writes == 0 && v.ephemeral && v.interactiveRead == "n/a" && v.ephemeralReason == "no-identity")
    }

    @Test func realKeychainUnderDryRunNeverReadsInteractively() {
        let b = backend(.mismatch)
        let v = Vault(service: svc, memoryOnly: false, backend: b, defaults: defaults(), identity: id, allowInteractive: false)
        #expect(b.interactiveReads == 0 && v.ephemeral && v.ephemeralReason == "dry-run" && v.interactiveRead == "n/a")
    }

    @Test func corruptOwnItemIsEphemeralToo() {
        let b = FakeBackend()
        b.seed(svc, own, Data("not json".utf8), modified: Date(), foreign: false)
        let v = vault(b, defaults())
        #expect(v.readResult == "unreadable" && v.ephemeral && v.ephemeralReason == "corrupt")
        v.update { $0.identity = Data([1]) }
        #expect(b.writes == 0)
    }

    // MARK: M4 write failures

    @Test func failedCopyIntoOurAccountIsAVisibleNotSavingState() {
        let b = backend(.mismatch)
        b.failAccounts = [own]
        var adopted = 0
        let v = vault(b, defaults(), deferred: true)
        v.onAdopt = { adopted += 1 }
        v.startInteractiveRead(background: false)
        #expect(v.ephemeral && v.ephemeralReason == "write-failed" && adopted == 1)   // M1 even though the copy failed
        #expect(v.secrets.identity == seedIdentity && b.items[b.key(svc, "secrets.v3")] == blob() && b.deletes == 0)
        // the Keychain recovers: the next change saves and ends the state
        b.failAccounts = []
        v.update { $0.devices = [] }
        #expect(!v.ephemeral && ownData(b) == v.secrets)
    }

    @Test func failedSaveEntersTheStateAndTryAgainRetriesTheWrite() {
        let b = FakeBackend()
        let v = vault(b, defaults())
        #expect(!v.ephemeral)
        b.failAccounts = [own]
        v.update { $0.identity = Data(repeating: 4, count: 32) }
        #expect(v.ephemeral && v.ephemeralReason == "write-failed")
        v.tryReadAgain(background: false)
        #expect(v.ephemeral)   // still failing
        b.failAccounts = []
        v.tryReadAgain(background: false)
        #expect(!v.ephemeral && ownData(b)?.identity == Data(repeating: 4, count: 32))
    }

    @Test func failedFreshInstallWriteIsNotHealthy() {
        let b = FakeBackend(); b.failWrites = true
        let v = vault(b, defaults())
        #expect(v.ephemeral && v.ephemeralReason == "write-failed")
    }

    // MARK: Repair

    @Test func repairDeletesOnlyOurAccountAndCreatesItFresh() {
        let d = defaults()
        let b = backend(.deniedInteractive)
        let v = vault(b, d)
        #expect(v.ephemeral)
        var adopted = 0; v.onAdopt = { adopted += 1 }
        #expect(v.repair())
        #expect(!v.ephemeral && v.readResult == "notfound" && adopted == 1)
        #expect(b.deleteKeys == [b.key(svc, own)] && b.items[b.key(svc, "secrets.v3")] == blob())   // the other build's item is left alone
        #expect(ownData(b) == v.secrets)
        v.update { $0.identity = Data([5]) }
        #expect(b.writes == 2)
        // next launch: ours is newest, the old item is never asked about again
        b.interactiveReads = 0
        let v2 = vault(b, d)
        #expect(b.interactiveReads == 0 && !v2.ephemeral && v2.secrets.identity == Data([5]))
    }

    @Test func failedRepairStaysEphemeral() {
        let b = backend(.error)
        let v = vault(b, defaults())
        b.failWrites = true
        #expect(!v.repair() && v.ephemeral)
        #expect(v.ephemeralReason == "write-failed")
    }

    @Test func failedDeleteDuringRepairChangesNothing() {
        let b = backend(.deniedInteractive)
        let v = vault(b, defaults())
        let before = b.items
        b.failDeletes = true
        #expect(!v.repair() && v.ephemeral && b.items == before && b.writes == 0)
    }

    // MARK: launch surface

    @Test func listQueryIsAttributesOnlyAndNeverUI() {
        let q = KeychainBackend.listQuery(service: svc)
        #expect(q[kSecReturnData as String] == nil && q[kSecReturnAttributes as String] as? Bool == true)
        #expect(q[kSecMatchLimit as String] as? String == kSecMatchLimitAll as String)
        #expect(q[kSecUseAuthenticationUI as String] as? String == kSecUseAuthenticationUIFail as String)
        #expect(q[kSecAttrAccount as String] == nil)   // every account of the service
    }

    @Test func accountNamesFollowTheSigningPartition() {
        #expect(Vault.account(for: "teamid:ABC") == "secrets.v4.teamid:ABC" && Vault.account(for: "cdhash:ff") == "secrets.v4.cdhash:ff")
        #expect(Vault.account(for: nil) == nil)
    }

    @Test func deferredReadStartsEphemeralAndTheKeychainWinsOverMemory() {
        let b = backend(.mismatch)
        let v = vault(b, defaults(), deferred: true)
        #expect(v.reading && v.ephemeral && v.ephemeralReason == "reading" && b.interactiveReads == 0 && b.writes == 0)
        v.update { $0.blocked = [LabsBlockedKey(fp: "mem", name: "m")] }   // made while reading
        v.startInteractiveRead(background: false)
        #expect(!v.reading && !v.ephemeral && b.interactiveReads == 1)
        #expect(v.secrets.blocked.map(\.fp) == ["bad"])   // the Keychain wins; what was held in memory meanwhile is dropped
    }

    @Test func fakeKeychainStatesMatchTheContract() {
        for (st, eph, ir) in [(FakeKeychainState.found, false, "none"), (.notFound, false, "none"), (.partitionMismatch, false, "ok"),
                              (.denied, true, "denied"), (.error, true, "n/a")] {
            let v = Vault(service: svc, memoryOnly: false, backend: FakeKeychain(state: st), identity: FakeKeychain.identity)
            #expect(v.ephemeral == eph && v.interactiveRead == ir, "\(st)")
        }
        let v = Vault(service: svc, memoryOnly: false, backend: FakeKeychain(state: .found), identity: FakeKeychain.identity)
        #expect(v.secrets.identity == FakeKeychain.seedIdentity)
    }
}

/// U6: no automatic trust while the Vault is ephemeral (a blocked phone included); U12 hi fields.
struct EphemeralTrustTests {
    func hub(_ keychain: FakeKeychainState) -> Hub {
        var c = Config()
        c.dryRun = true; c.headless = true; c.memoryStore = true; c.ble = false
        c.fakeKeychain = keychain; c.fakeProximity = true; c.fakeTailscaleOwner = "self"
        let h = Hub(config: c)
        h.vault.startInteractiveRead(background: false)   // the launch read has run (new pairings are refused while it is due)
        return h
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

    // M2: while reading, no new pairing (code or instant); the phone keeps what it has.
    func session(_ h: Hub, _ out: Box) -> Session {
        Session(hub: h, transport: "Wi-Fi", peer: "10.0.0.7", send: { d in
            if let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] { out.msgs.append(m) }
        }, close: {})
    }
    final class Box { var msgs: [[String: Any]] = [] }

    @Test func codePairingIsRefusedWhileReadingAndWorksAfter() {
        let h = hub(.partitionMismatch, defaults())
        h.pairing.open()
        let code = h.pairing.code ?? ""
        let out = Box(); let s = session(h, out)
        #expect(h.vault.reading)
        s.handle(Data(#"{"t":"pair","code":"\#(code)","name":"P"}"#.utf8))
        let e = out.msgs.last
        #expect(e?["t"] as? String == "err" && e?["code"] as? String == "keychain-reading" && e?["retryAfter"] as? Int == 2)
        #expect(!out.msgs.contains { $0["t"] as? String == "paired" } && h.store.devices.isEmpty)
        h.vault.startInteractiveRead(background: false)
        s.handle(Data(#"{"t":"pair","code":"\#(code)","name":"P"}"#.utf8))
        #expect(out.msgs.last?["t"] as? String == "paired")
    }

    @Test func instantHelloIsRefusedWhileReadingWithoutRevealingAKey() {
        let h = hub(.partitionMismatch, defaults())
        let out = Box(); let s = session(h, out)
        let key = Curve25519.Signing.PrivateKey()
        let pub = key.publicKey.rawRepresentation.hexString
        let sig = try! key.signature(for: Data(labsClientProof(hostId: h.settings.hostId, nonce: s.nonce, pub: pub).utf8)).hexString
        h.instantPairing.handle("labs.pair.hello", ["pub": pub, "cn": randomHex(16), "sig": sig, "name": "Phone"], from: s)
        let e = out.msgs.last
        #expect(e?["code"] as? String == "keychain-reading" && e?["retryAfter"] as? Int == 2)
        #expect(!out.msgs.contains { $0["t"] as? String == "labs.pair.hi" } && h.instantPairing.trusted.isEmpty)
    }
}

