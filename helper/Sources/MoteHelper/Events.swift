import Foundation
import os

/// The helper's event log (protocol 1.5, docs/protocol.md "Diagnostics"): one structured event per state change and
/// decision. 5,000-event ring buffer, os_log (subsystem dev.mote.helper, category = area), optional JSONL file
/// (`--events FILE`), and live delivery to sessions that sent `diag.subscribe`.
///
/// Never put secrets in fields: no tokens, keys, nonces, pairing codes, typed keys, message payloads, URLs of pages.
/// Sizes, counts, status codes and names only.
enum EvArea: String, CaseIterable {
    case lifecycle, signing, permission, keychain, discovery, transport, session, protocolMsg = "protocol", input, labs, ui
}

/// Every event name, per area. docs/protocol.md lists the same table (a unit test compares them).
enum EventCatalog {
    static let all: [EvArea: [String]] = [
        .lifecycle: ["start", "ready", "terminate", "setting", "diag.cli"],
        .signing: ["identity", "error"],
        .permission: ["check", "change", "request"],
        .keychain: ["read", "write", "partition", "interactive", "rewrite", "ephemeral.enter", "ephemeral.leave", "repair"],
        .discovery: ["bonjour.attempt", "bonjour.registered", "bonjour.failed", "ble.state", "ble.advertising", "ble.failed", "tailnet.detect"],
        .transport: ["listen.state", "conn.accepted", "conn.refused", "conn.closed", "ws.upgrade", "ws.refused", "ble.central", "http.response"],
        .session: ["open", "authed", "auth.failed", "paired", "pair.failed", "closed", "revoked"],
        .protocolMsg: ["in", "out", "error", "bad", "diag.subscribe"],
        .input: ["summary", "blocked", "macro"],
        .labs: ["toggle", "subscribe", "push", "stream"],
        .ui: ["setup.shown", "setup.closed", "setup.button", "menu.opened", "menu.item", "diagnostics.shown", "diagnostics.copied", "pairing.shown", "newdevice.shown"],
    ]
    static func known(_ a: EvArea, _ e: String) -> Bool { all[a]?.contains(e) ?? false }
}

struct Event {
    let t: Int64          // Unix ms
    let seq: Int
    let area: EvArea
    let event: String
    let journeyStep: String?
    let corr: String?
    let fields: [String: Any]

    var json: [String: Any] {
        var m: [String: Any] = ["t": t, "seq": seq, "side": "helper", "area": area.rawValue, "event": event,
                                "corr": corr ?? NSNull(), "fields": fields]
        if let journeyStep { m["journeyStep"] = journeyStep }
        return m
    }
    var line: String {
        (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}

final class Events {
    static let shared = Events()
    static let capacity = 5000

    private let lock = NSLock()
    private var ring: [Event] = []
    private var nextSeq = 1
    private var file: FileHandle?
    private var listeners: [UUID: (Event) -> Void] = [:]
    private var lastSeen: [String: (at: Date, skipped: Int)] = [:]   // coalescing windows
    private var inputCounts: [String: Int] = [:]
    private var inputSince = Date()
    private(set) var unknownNames: Set<String> = []   // "area.event" emitted but missing from the catalog (a unit test asserts it stays empty)
    /// Set by Session.handle while it processes one client message: events emitted meanwhile carry its `corr` / `step`.
    var currentCorr: String?
    var currentStep: String?

    var head: Int { lock.lock(); defer { lock.unlock() }; return nextSeq - 1 }
    var count: Int { lock.lock(); defer { lock.unlock() }; return ring.count }

    func openFile(_ path: String) {
        lock.lock(); defer { lock.unlock() }
        if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil) }
        file = FileHandle(forWritingAtPath: path)
        _ = try? file?.seekToEnd()
    }

    /// Records one event. [coalesce]: events with the same key inside one second are counted; the next emit after the
    /// window carries `skipped` (the high-rate protocol and push events use this so the ring keeps its history).
    func emit(_ area: EvArea, _ event: String, step: String? = nil, corr: String? = nil, coalesce: String? = nil, _ fields: [String: Any] = [:]) {
        var f = Events.clean(fields)
        lock.lock()
        if !EventCatalog.known(area, event) { unknownNames.insert("\(area.rawValue).\(event)") }
        if let key = coalesce {
            let now = Date(), k = "\(area.rawValue).\(event).\(key)"
            if let s = lastSeen[k], now.timeIntervalSince(s.at) < 1 { lastSeen[k] = (s.at, s.skipped + 1); lock.unlock(); return }
            if let s = lastSeen[k], s.skipped > 0 { f["skipped"] = s.skipped }
            lastSeen[k] = (now, 0)
            if lastSeen.count > 512 { lastSeen = lastSeen.filter { now.timeIntervalSince($0.value.at) < 1 } }
        }
        let e = Event(t: Int64(Date().timeIntervalSince1970 * 1000), seq: nextSeq, area: area, event: event,
                      journeyStep: step ?? currentStep, corr: corr ?? currentCorr, fields: f)
        nextSeq += 1
        ring.append(e)
        if ring.count > Events.capacity { ring.removeFirst(ring.count - Events.capacity) }
        let fh = file, ls = Array(listeners.values)
        lock.unlock()
        if let fh, let d = (e.line + "\n").data(using: .utf8) { try? fh.write(contentsOf: d) }
        Events.osLog(e)
        for l in ls { l(e) }
    }

    /// Events with seq > [since], oldest first, at most [limit] (the newest ones).
    func recent(since: Int = 0, limit: Int = Events.capacity) -> [Event] {
        lock.lock(); defer { lock.unlock() }
        let r = ring.filter { $0.seq > since }
        return Array(r.suffix(limit))
    }

    func addListener(_ f: @escaping (Event) -> Void) -> UUID {
        let id = UUID(); lock.lock(); listeners[id] = f; lock.unlock(); return id
    }
    func removeListener(_ id: UUID) { lock.lock(); listeners[id] = nil; lock.unlock() }

    /// Input is counted, never logged per event (a mouse move is ~100 Hz). `input.summary` goes out every 5 s while there is any.
    func countInput(_ kind: String) {
        lock.lock(); inputCounts[kind, default: 0] += 1; lock.unlock()
    }
    func flushInput() {
        lock.lock()
        let c = inputCounts, ms = Int(Date().timeIntervalSince(inputSince) * 1000)
        inputCounts = [:]; inputSince = Date()
        lock.unlock()
        if !c.isEmpty { emit(.input, "summary", ["counts": c, "windowMs": ms]) }
    }

    /// Test hook.
    func reset() {
        lock.lock(); ring = []; nextSeq = 1; lastSeen = [:]; inputCounts = [:]; unknownNames = []; lock.unlock()
        currentCorr = nil; currentStep = nil
    }

    private static let loggers: [EvArea: Logger] = Dictionary(uniqueKeysWithValues: EvArea.allCases.map { ($0, Logger(subsystem: "dev.mote.helper", category: $0.rawValue)) })
    private static func osLog(_ e: Event) {
        let f = (try? JSONSerialization.data(withJSONObject: e.fields, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        loggers[e.area]?.info("\(e.event, privacy: .public) corr=\(e.corr ?? "-", privacy: .public) \(f, privacy: .public)")
    }

    /// Makes [fields] JSON-safe (NaN, URLs, errors, nested non-JSON values become strings).
    static func clean(_ fields: [String: Any]) -> [String: Any] {
        fields.mapValues { v in
            switch v {
            case let n as NSNumber: return n.doubleValue.isFinite ? n : NSNumber(value: 0)
            case is String, is NSNull: return v
            case let a as [Any]: return a.map { clean(["x": $0])["x"] ?? NSNull() }
            case let d as [String: Any]: return clean(d)
            default: return String(describing: v)
            }
        }
    }
}

/// Shorthand: `ev(.session, "open", ["transport": "Wi-Fi"])`.
func ev(_ area: EvArea, _ event: String, step: String? = nil, corr: String? = nil, coalesce: String? = nil, _ fields: [String: Any] = [:]) {
    Events.shared.emit(area, event, step: step, corr: corr, coalesce: coalesce, fields)
}
