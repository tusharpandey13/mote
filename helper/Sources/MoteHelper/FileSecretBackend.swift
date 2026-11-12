import Foundation

/// `--secrets-dir DIR` (dry-run only): the Vault's items as files, `DIR/<service>/<account>` (mode 0600), so a restarted test helper
/// keeps its identity key, trusted phones and tokens with no Keychain access and no prompt. The Vault logic above it is the real one
/// (account `secrets.v4.secrets-dir`, trusted list, ...); only the storage differs.
struct FileSecretBackend: SecretBackend {
    static let identity = "secrets-dir"
    let dir: URL
    private func folder(_ service: String) -> URL { dir.appendingPathComponent(service, isDirectory: true) }
    private func file(_ service: String, _ account: String) -> URL { folder(service).appendingPathComponent(account) }

    func list(service: String) throws -> [KeychainEntry] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: folder(service), includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return urls.map { KeychainEntry(account: $0.lastPathComponent, modified: (try? $0.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate) }
    }

    func read(service: String, account: String, interactive: Bool) throws -> Data? {
        try? Data(contentsOf: file(service, account))
    }

    func write(service: String, account: String, data: Data, label: String) -> Bool {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: folder(service), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let f = file(service, account)
            try data.write(to: f, options: .atomic)
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: f.path)
            return true
        } catch { return false }
    }

    func delete(service: String, account: String) -> Bool {
        (try? FileManager.default.removeItem(at: file(service, account))) != nil
    }
}
