import Foundation
import Testing
@testable import MoteHelper

struct DiagFileTests {
    let now: Int64 = 1_000_000
    func file(pid: Int = 4242, at: Int64) -> Data {
        let s = DiagFile.stamp(["t": "diag", "v": 1, "running": true], pid: pid, at: at)
        return try! JSONSerialization.data(withJSONObject: s)
    }
    @Test func freshAndAliveIsUsed() {
        let r = DiagFile.resolve(data: file(at: now - 5_000), now: now, selfPid: 1, alive: { _ in true })
        #expect(r.snapshot?["source"] as? String == "running")
        #expect(r.snapshot?["pid"] as? Int == 4242 && r.snapshot?["writtenAt"] as? Int == Int(now - 5_000))
    }
    @Test func staleDeadMissingBrokenFallBack() {
        #expect(DiagFile.resolve(data: file(at: now - 60_000), now: now, selfPid: 1, alive: { _ in true }).snapshot == nil)
        #expect(DiagFile.resolve(data: file(at: now - 1), now: now, selfPid: 1, alive: { _ in false }).note.contains("not running"))
        #expect(DiagFile.resolve(data: nil, now: now, selfPid: 1, alive: { _ in true }).snapshot == nil)
        #expect(DiagFile.resolve(data: Data("{nope".utf8), now: now, selfPid: 1, alive: { _ in true }).note.contains("unreadable"))
        #expect(DiagFile.resolve(data: file(pid: 7, at: now), now: now, selfPid: 7, alive: { _ in true }).snapshot == nil)
    }
    @Test func pidAlive() {
        #expect(DiagFile.pidAlive(Int(getpid())))
        #expect(!DiagFile.pidAlive(0))
    }
    @Test func writeIsPrivateAndAtomic() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mote-difile-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = DiagFile(dir: dir) { ["t": "diag", "v": 1, "running": true] }
        f.write(); f.write()
        for n in [DiagFile.fileName, DiagFile.eventsName] {
            let a = try FileManager.default.attributesOfItem(atPath: dir.appendingPathComponent(n).path)
            #expect((a[.posixPermissions] as? Int) == 0o600, "\(n)")
        }
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        #expect(names == [DiagFile.eventsName, DiagFile.fileName])   // no tmp left behind
        let r = DiagFile.resolve(data: try Data(contentsOf: dir.appendingPathComponent(DiagFile.fileName)),
                                 now: Int64(Date().timeIntervalSince1970 * 1000), selfPid: -1, alive: DiagFile.pidAlive)
        #expect(r.snapshot?["pid"] as? Int == Int(getpid()))
    }
    @Test func dirRules() {
        var c = Config(); c.memoryStore = true
        #expect(DiagFile.dir(for: c) == nil)
        c.dataDir = URL(fileURLWithPath: "/tmp/x", isDirectory: true)
        #expect(DiagFile.dir(for: c)?.path == "/tmp/x")
        #expect(Config.parse(["m", "--data-dir", "/tmp/y"]).dataDir?.path == "/tmp/y")
    }
}
