import Foundation

/// The running helper's own diagnostics snapshot on disk, so `MoteHelper --diag` (a separate process, whose TCC answers
/// differ) can show what the RUNNING app sees. `diag.json` (snapshot + `pid`, `writtenAt`) and `diag-events.jsonl` (last 500
/// events), both mode 0600, replaced atomically. No secrets: the snapshot is the same object a paired client gets over `diag`.
/// Written at launch, ~1 s after any event (debounced) and every 30 s. Not written with --memory-store unless --data-dir is given.
final class DiagFile {
    static let fileName = "diag.json", eventsName = "diag-events.jsonl"
    static let maxAgeMs: Int64 = 60_000, eventCount = 500

    static func defaultDir() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("dev.mote.helper", isDirectory: true)
    }
    /// Where this run reads/writes the files; nil = nowhere (--memory-store without --data-dir).
    static func dir(for c: Config) -> URL? { c.dataDir ?? (c.memoryStore ? nil : defaultDir()) }

    private let dir: URL
    private let provider: () -> [String: Any]
    private var pending = false
    private var token: UUID?
    private var timer: Timer?

    init(dir: URL, provider: @escaping () -> [String: Any]) { self.dir = dir; self.provider = provider }

    /// Main thread only (the provider reads Hub state).
    func start() {
        write()
        token = Events.shared.addListener { [weak self] _ in DispatchQueue.main.async { self?.schedule() } }
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.write() }
    }
    func stop() {
        if let token { Events.shared.removeListener(token) }
        timer?.invalidate()
    }
    private func schedule() {
        guard !pending else { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.pending = false; self?.write() }
    }

    func write() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let snap = DiagFile.stamp(provider(), pid: Int(getpid()), at: now)
        guard let data = try? JSONSerialization.data(withJSONObject: snap, options: [.sortedKeys]) else { return }
        let events = Events.shared.recent(limit: DiagFile.eventCount).map { $0.line }.joined(separator: "\n") + "\n"
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        DiagFile.atomicWrite(Data(events.utf8), to: dir.appendingPathComponent(DiagFile.eventsName))
        DiagFile.atomicWrite(data, to: dir.appendingPathComponent(DiagFile.fileName))   // last: its mtime/writtenAt = "complete"
    }

    static func stamp(_ snap: [String: Any], pid: Int, at: Int64) -> [String: Any] {
        var s = snap
        s["source"] = "running"; s["pid"] = pid; s["writtenAt"] = at
        return s
    }

    static func atomicWrite(_ data: Data, to url: URL) {
        let tmp = url.appendingPathExtension("tmp\(getpid())")
        guard FileManager.default.createFile(atPath: tmp.path, contents: data, attributes: [.posixPermissions: 0o600]) else { return }
        if rename(tmp.path, url.path) != 0 { try? FileManager.default.removeItem(at: tmp) }
    }

    static func pidAlive(_ pid: Int) -> Bool {
        pid > 0 && (kill(pid_t(pid), 0) == 0 || errno == EPERM)
    }

    /// `--diag`: the running helper's snapshot if its file is fresh and its pid alive, else nil and why.
    static func resolve(data: Data?, now: Int64, selfPid: Int, alive: (Int) -> Bool) -> (snapshot: [String: Any]?, note: String) {
        guard let data else { return (nil, "no running helper snapshot found (helper not running, or older than this feature)") }
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], j["t"] as? String == "diag",
              let at = (j["writtenAt"] as? NSNumber)?.int64Value, let pid = (j["pid"] as? NSNumber)?.intValue
        else { return (nil, "running helper snapshot is unreadable") }
        if pid == selfPid { return (nil, "snapshot belongs to this process") }
        if !alive(pid) { return (nil, "the helper that wrote the snapshot (pid \(pid)) is not running") }
        if now - at >= maxAgeMs { return (nil, "running helper snapshot is stale (\((now - at) / 1000) s old)") }
        return (j, "")
    }

    static func cliOutput(config: Config) -> [String: Any] {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var note = "no data directory (--memory-store)"
        if let dir = dir(for: config) {
            let r = resolve(data: try? Data(contentsOf: dir.appendingPathComponent(fileName)), now: now, selfPid: Int(getpid()), alive: pidAlive)
            if let s = r.snapshot { return s }
            note = r.note
        }
        var s = Diag.cliSnapshot(config: config)
        s["note"] = "\(note); runtime sections are unknown and permissions are this CLI process's view, not the running app's"
        return s
    }
}
