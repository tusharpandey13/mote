import Foundation
import Testing
@testable import MoteHelper

/// In-memory stand-in for the Keychain; counts reads so "one prompt at most" is checkable.
final class FakeBackend: SecretBackend {
    enum Mode { case normal, mismatch, deniedInteractive, error }
    var items: [String: Data] = [:]
    var denied: Set<String> = []     // any read throws userCanceled (-128): a failure no prompt can fix
    var mode = Mode.normal
    var reads = 0, interactiveReads = 0, writes = 0, deletes = 0
    var failWrites = false
    var readKeys: [String] = []
    func key(_ s: String, _ a: String) -> String { s + "/" + a }
    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        reads += 1; readKeys.append(key(service, account))
        if interactive { interactiveReads += 1 }
        if denied.contains(key(service, account)) { throw KeychainError(status: -128) }
        switch mode {
        case .normal: break
        case .mismatch: if !interactive { throw KeychainError(status: -25308) }
        case .deniedInteractive: throw KeychainError(status: interactive ? -128 : -25308)
        case .error: throw KeychainError(status: -34018)
        }
        return items[key(service, account)]
    }
    func write(service: String, account: String, data: Data, label: String) -> Bool {
        writes += 1
        if failWrites { return false }
        items[key(service, account)] = data; return true
    }
    func delete(service: String, account: String) -> Bool {
        deletes += 1
        items[key(service, account)] = nil
        mode = .normal   // a fresh item is ours again
        return true
    }
}

struct VaultTests {
    let svc = "dev.mote.test"
    let date = Date(timeIntervalSince1970: 1_700_000_000)

    func enc<T: Encodable>(_ v: T) -> Data { try! Vault.encoder.encode(v) }

    /// Items a cdhash-partitioned build would have left behind; reading any of them can prompt.
    func stale() -> FakeBackend {
        let b = FakeBackend()
        for (s, a) in [(svc, "secrets.v2"), (svc, "secrets"), (svc, "devices"), (svc + ".bridge", "devices"),
                       (svc + ".labs", "trusted"), (svc + ".labs", "blocked"), (svc + ".labs", "identity")] {
            b.items[b.key(s, a)] = Data("{}".utf8)
        }
        return b
    }

    @Test func legacyAccountsAreNeverQueriedOrTouched() {
        let b = stale()
        let before = b.items
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.readKeys == [b.key(svc, "secrets.v3")])
        #expect(v.secrets == Secrets())                 // nothing carried over: the user re-pairs
        #expect(b.items.count == before.count + 1 && b.items[b.key(svc, "secrets.v2")] == before[b.key(svc, "secrets.v2")])
        _ = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(Set(b.readKeys) == [b.key(svc, "secrets.v3")])
    }

    @Test func authErrorIsNotPresentAndNeverRetriedOrOverwritten() {
        let b = FakeBackend()
        b.denied = [b.key(svc, "secrets.v3")]
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.reads == 1 && b.writes == 0 && v.secrets == Secrets())
    }

    @Test func freshInstallWritesBlobOnceSoLaterLaunchesNeverRetryMigration() {
        let b = FakeBackend()
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.items.count == 1 && b.writes == 1 && b.items[b.key(svc, "secrets.v3")] != nil)
        let readsAfterFirst = b.reads
        _ = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.reads == readsAfterFirst + 1)   // only v3 is read
        v.update { $0.identity = Data([1, 2, 3]) }
        #expect(b.items.count == 1 && b.writes == 2)
        #expect(Vault(service: svc, memoryOnly: false, backend: b).secrets.identity == Data([1, 2, 3]))
    }

    @Test func memoryOnlyNeverTouchesTheBackend() {
        let b = stale()
        let v = Vault(service: svc, memoryOnly: true, backend: b)
        v.update { $0.devices = [] }
        #expect(b.reads == 0 && b.writes == 0)
        #expect(v.secrets.devices.isEmpty && v.secrets.identity == nil)
    }

    @Test func storesShareTheBlob() {
        let b = FakeBackend()
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        let web = DeviceStore(vault: v, path: \.devices), bridge = DeviceStore(vault: v, path: \.bridge)
        let keys = LabsKeyStore(vault: v)
        let d = web.add(name: "Phone")
        _ = bridge.add(name: "ext")
        _ = keys.identity()
        keys.add(LabsTrustedKey(fp: "f", pub: "p", name: "n", via: "nearby", created: date))
        keys.block("f", name: "x")
        #expect(keys.keys.isEmpty && keys.blocked.map(\.fp) == ["f"] && keys.blocked[0].name == "n")
        #expect(b.items.count == 1)
        let again = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(again.secrets.devices.map(\.id) == [d.id] && again.secrets.bridge.count == 1 && again.secrets.identity?.count == 32)
        web.revokeAll()
        #expect(v.secrets.devices.isEmpty && v.secrets.bridge.count == 1)
    }

    @Test func tolerantDecode() throws {
        let s = try Vault.decoder.decode(Secrets.self, from: Data(#"{"v":1,"devices":[]}"#.utf8))
        #expect(s.trusted.isEmpty && s.identity == nil)
    }
}
