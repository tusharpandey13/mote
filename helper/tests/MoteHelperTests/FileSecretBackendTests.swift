import Foundation
import Testing
@testable import MoteHelper

struct FileSecretBackendTests {
    func tmp() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("mote-secrets-" + UUID().uuidString) }

    @Test func writeListReadDeleteOneFilePerAccount() throws {
        let dir = tmp(); defer { try? FileManager.default.removeItem(at: dir) }
        let b = FileSecretBackend(dir: dir)
        #expect(try b.list(service: "svc").isEmpty)
        #expect(try b.read(service: "svc", account: "a", interactive: false) == nil)
        #expect(b.write(service: "svc", account: "a", data: Data([1]), label: "x"))
        #expect(b.write(service: "svc", account: "a", data: Data([2]), label: "x"))
        #expect(b.write(service: "other", account: "b", data: Data([3]), label: "x"))
        #expect(try b.list(service: "svc").map(\.account) == ["a"])
        #expect(try b.read(service: "svc", account: "a", interactive: false) == Data([2]))
        let mode = try FileManager.default.attributesOfItem(atPath: dir.appendingPathComponent("svc/a").path)[.posixPermissions] as? Int
        #expect(mode == 0o600)
        #expect(b.delete(service: "svc", account: "a"))
        #expect(try b.list(service: "svc").isEmpty)
    }

    @Test func vaultKeepsItsIdentityAcrossInstances() {
        let dir = tmp(); defer { try? FileManager.default.removeItem(at: dir) }
        let key = Data(repeating: 9, count: 32)
        let v1 = Vault(service: "svc", memoryOnly: false, backend: FileSecretBackend(dir: dir), identity: FileSecretBackend.identity, allowInteractive: false)
        v1.update { $0.identity = key }
        let v2 = Vault(service: "svc", memoryOnly: false, backend: FileSecretBackend(dir: dir), identity: FileSecretBackend.identity, allowInteractive: false)
        #expect(v2.secrets.identity == key)
        #expect(v2.ownAccount == "secrets.v4.secrets-dir")
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("svc/secrets.v4.secrets-dir").path))
    }
}
