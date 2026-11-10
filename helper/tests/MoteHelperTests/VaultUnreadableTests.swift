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
        #expect(b.reads == 3 && b.interactiveReads == 1)   // no-UI read, the one interactive read, the no-UI verify after the rewrite
        #expect(v.readResult == "found" && v.interactiveRead == "ok" && !v.ephemeral)
        #expect(v.secrets.identity == Data(repeating: 9, count: 32) && v.secrets.blocked.map(\.fp) == ["bad"])   // kept, not re-minted
        #expect(b.deletes == 1 && b.writes == 1)   // rewritten (delete + add) so this build's partition owns it
        v.update { $0.identity = Data(repeating: 1, count: 32) }
        #expect(b.writes == 2)                      // saving works again
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
        #expect(!v.ephemeral && v.readResult == "notfound" && b.deletes == 1 && b.writes == 1)
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

    @Test func failedRewriteFallsBackToEphemeralWithSecretsInMemory() {
        let b = backend(.mismatch)
        b.failWrites = true
        let v = vault(b, defaults())
        #expect(v.ephemeral && v.ephemeralReason == "rewrite-failed" && v.secrets.identity == Data(repeating: 9, count: 32))
        v.update { _ in }
        #expect(b.writes == 2)   // the two rewrite attempts only; nothing after
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
