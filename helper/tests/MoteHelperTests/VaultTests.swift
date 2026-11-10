import Foundation
import Testing
@testable import MoteHelper

/// In-memory stand-in for the Keychain; counts calls so "one prompt at most" and "never touches the old item" are checkable.
/// `foreign` items belong to another signature: `mode` decides how reads of THEM behave; writes / deletes of them fail.
final class FakeBackend: SecretBackend {
    enum Mode { case normal, mismatch, deniedInteractive, error }
    var items: [String: Data] = [:]
    var mods: [String: Date] = [:]
    var foreign: Set<String> = []
    var mode = Mode.normal
    var listError: KeychainError?
    var readErrors: [String: Int32] = [:]   // key -> status: any read of that item throws (own items too)
    var denied: Set<String> = []            // any read throws userCanceled (-128)
    var lists = 0, reads = 0, interactiveReads = 0, writes = 0, deletes = 0
    var failWrites = false
    var failAccounts: Set<String> = []      // writes to these accounts (by name) fail
    var failDeletes = false
    var calls: [String] = []                // "list", "read:<key>", "write:<key>", "delete:<key>" in order
    var readKeys: [String] = [], writeKeys: [String] = [], deleteKeys: [String] = []
    func key(_ s: String, _ a: String) -> String { s + "/" + a }

    /// Another build's item.
    func seed(_ svc: String, _ account: String, _ data: Data, modified: Date, foreign f: Bool = true) {
        items[key(svc, account)] = data; mods[key(svc, account)] = modified
        if f { foreign.insert(key(svc, account)) }
    }

    func list(service: String) throws -> [KeychainEntry] {
        lists += 1; calls.append("list")
        if let e = listError { throw e }
        return items.keys.filter { $0.hasPrefix(service + "/") }.map {
            KeychainEntry(account: String($0.dropFirst(service.count + 1)), modified: mods[$0])
        }
    }
    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        let k = key(service, account)
        reads += 1; readKeys.append(k); calls.append("read:" + k)
        if interactive { interactiveReads += 1 }
        if let st = readErrors[k] { throw KeychainError(status: st) }
        if denied.contains(k) { throw KeychainError(status: -128) }
        if foreign.contains(k) {
            switch mode {
            case .normal: break
            case .mismatch: if !interactive { throw KeychainError(status: -25308) }
            case .deniedInteractive: throw KeychainError(status: interactive ? -128 : -25308)
            case .error: throw KeychainError(status: -34018)
            }
        }
        return items[k]
    }
    func write(service: String, account: String, data: Data, label: String) -> Bool {
        let k = key(service, account)
        writes += 1; writeKeys.append(k); calls.append("write:" + k)
        if failWrites || failAccounts.contains(account) || foreign.contains(k) { return false }
        items[k] = data; mods[k] = Date()
        return true
    }
    func delete(service: String, account: String) -> Bool {
        let k = key(service, account)
        deletes += 1; deleteKeys.append(k); calls.append("delete:" + k)
        if failDeletes || foreign.contains(k) { return false }
        items[k] = nil; mods[k] = nil
        return true
    }
}

struct VaultTests {
    let svc = "dev.mote.test"
    let date = Date(timeIntervalSince1970: 1_700_000_000)
    let id = "cdhash:a"
    var own: String { "secrets.v4.cdhash:a" }

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
        let v = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        #expect(b.readKeys.isEmpty && b.deletes == 0)   // not one of them is read or deleted
        #expect(v.secrets == Secrets())                 // nothing carried over: the user re-pairs
        #expect(b.items.count == before.count + 1 && b.items[b.key(svc, "secrets.v2")] == before[b.key(svc, "secrets.v2")])
        _ = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        #expect(b.readKeys == [b.key(svc, own)])   // the second launch reads only our own account
    }

    @Test func authErrorIsNotPresentAndNeverRetriedOrOverwritten() {
        let b = FakeBackend()
        b.seed(svc, own, Data("{}".utf8), modified: Date(), foreign: false)
        b.denied = [b.key(svc, own)]
        let v = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        #expect(b.reads == 1 && b.writes == 0 && v.secrets == Secrets() && v.ephemeral)   // M3: an error is not "missing"
    }

    @Test func freshInstallWritesBlobOnceSoLaterLaunchesNeverRetryMigration() {
        let b = FakeBackend()
        let v = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        #expect(b.items.count == 1 && b.writes == 1 && b.items[b.key(svc, own)] != nil)
        let readsAfterFirst = b.reads
        _ = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        #expect(b.reads == readsAfterFirst + 1)   // only our own account is read
        v.update { $0.identity = Data([1, 2, 3]) }
        #expect(b.items.count == 1 && b.writes == 2)
        #expect(Vault(service: svc, memoryOnly: false, backend: b, identity: id).secrets.identity == Data([1, 2, 3]))
    }

    @Test func memoryOnlyNeverTouchesTheBackend() {
        let b = stale()
        let v = Vault(service: svc, memoryOnly: true, backend: b)
        v.update { $0.devices = [] }
        #expect(b.reads == 0 && b.writes == 0 && b.lists == 0)
        #expect(v.secrets.devices.isEmpty && v.secrets.identity == nil)
    }

    @Test func storesShareTheBlob() {
        let b = FakeBackend()
        let v = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        let web = DeviceStore(vault: v, path: \.devices), bridge = DeviceStore(vault: v, path: \.bridge)
        let keys = LabsKeyStore(vault: v)
        let d = web.add(name: "Phone")
        _ = bridge.add(name: "ext")
        _ = keys.identity()
        keys.add(LabsTrustedKey(fp: "f", pub: "p", name: "n", via: "nearby", created: date))
        keys.block("f", name: "x")
        #expect(keys.keys.isEmpty && keys.blocked.map(\.fp) == ["f"] && keys.blocked[0].name == "n")
        #expect(b.items.count == 1)
        let again = Vault(service: svc, memoryOnly: false, backend: b, identity: id)
        #expect(again.secrets.devices.map(\.id) == [d.id] && again.secrets.bridge.count == 1 && again.secrets.identity?.count == 32)
        web.revokeAll()
        #expect(v.secrets.devices.isEmpty && v.secrets.bridge.count == 1)
    }

    @Test func tolerantDecode() throws {
        let s = try Vault.decoder.decode(Secrets.self, from: Data(#"{"v":1,"devices":[]}"#.utf8))
        #expect(s.trusted.isEmpty && s.identity == nil)
    }
}
