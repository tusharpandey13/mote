import Foundation
import Testing
@testable import MoteHelper

/// In-memory stand-in for the Keychain; counts reads so "one prompt at most" is checkable.
final class FakeBackend: SecretBackend {
    var items: [String: Data] = [:]
    var denied: Set<String> = []
    var reads = 0, writes = 0
    func key(_ s: String, _ a: String) -> String { s + "/" + a }
    func read(service: String, account: String) throws -> Data? {
        reads += 1
        if denied.contains(key(service, account)) { throw KeychainError(status: -128) }   // userCanceled
        return items[key(service, account)]
    }
    func write(service: String, account: String, data: Data, label: String) -> Bool { writes += 1; items[key(service, account)] = data; return true }
    func delete(service: String, account: String) { items[key(service, account)] = nil }
}

struct VaultTests {
    let svc = "dev.mote.test"
    let date = Date(timeIntervalSince1970: 1_700_000_000)

    func enc<T: Encodable>(_ v: T) -> Data { try! Vault.encoder.encode(v) }

    func legacy() -> FakeBackend {
        let b = FakeBackend()
        b.items[b.key(svc, "devices")] = enc([Device(id: "d1", name: "Pixel", token: String(repeating: "a", count: 64), created: date)])
        b.items[b.key(svc + ".bridge", "devices")] = enc([Device(id: "b1", name: "chrome-extension://x", token: String(repeating: "b", count: 64), created: date)])
        b.items[b.key(svc + ".labs", "trusted")] = enc([LabsTrustedKey(fp: "fp1", pub: "pub1", name: "Pixel", via: "nearby", created: date)])
        b.items[b.key(svc + ".labs", "blocked")] = try! JSONEncoder().encode(["fpOld"])   // early builds: [fp]
        b.items[b.key(svc + ".labs", "identity")] = Data(repeating: 7, count: 32)
        return b
    }

    @Test func migratesFiveItemsIntoOne() throws {
        let b = legacy()
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(v.secrets.devices.map(\.id) == ["d1"])
        #expect(v.secrets.bridge.map(\.id) == ["b1"])
        #expect(v.secrets.trusted.map(\.fp) == ["fp1"])
        #expect(v.secrets.blocked == [LabsBlockedKey(fp: "fpOld", name: "Phone")])
        #expect(v.secrets.identity == Data(repeating: 7, count: 32))
        #expect(Array(b.items.keys) == [b.key(svc, Vault.account)])   // old items gone, one left

        // Next launch: exactly one Keychain read, same contents.
        b.reads = 0
        let v2 = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.reads == 1)
        #expect(v2.secrets == v.secrets)
    }

    @Test func freshInstallWritesBlobOnceSoLaterLaunchesNeverRetryMigration() {
        let b = FakeBackend()
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.items.count == 1 && b.writes == 1)
        let readsAfterFirst = b.reads
        _ = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(b.reads == readsAfterFirst + 1)   // only the blob is read; no legacy reads, so no prompts
        v.update { $0.identity = Data([1, 2, 3]) }
        #expect(b.items.count == 1 && b.writes == 2)
        #expect(Vault(service: svc, memoryOnly: false, backend: b).secrets.identity == Data([1, 2, 3]))
    }

    @Test func deniedLegacyItemIsKept() {
        let b = legacy()
        b.denied = [b.key(svc + ".labs", "identity")]
        let v = Vault(service: svc, memoryOnly: false, backend: b)
        #expect(v.secrets.identity == nil)
        #expect(v.secrets.devices.count == 1)
        #expect(b.items[b.key(svc + ".labs", "identity")] != nil)   // not deleted: nothing lost
        #expect(b.items[b.key(svc, "devices")] == nil)
    }

    @Test func memoryOnlyNeverTouchesTheBackend() {
        let b = legacy()
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
