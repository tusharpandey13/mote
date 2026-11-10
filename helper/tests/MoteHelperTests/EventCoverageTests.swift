import Foundation
import Testing
@testable import MoteHelper

/// A public code path with no event fails here: every handled message type, every permission check, every connect step.
@Suite(.serialized) struct EventCoverageTests {
    static let sources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Sources/MoteHelper")

    final class Wire { var sent: [[String: Any]] = []; var closed = false }

    func makeHub() -> Hub {
        var c = Config(); c.dryRun = true; c.memoryStore = true; c.headless = true
        return Hub(config: c)
    }
    func session(_ hub: Hub, _ wire: Wire, transport: String = "Wi-Fi") -> Session {
        Session(hub: hub, transport: transport, peer: "127.0.0.1",
                send: { wire.sent.append((try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] ?? [:]) }, close: { wire.closed = true })
    }
    func feed(_ s: Session, _ m: [String: Any]) { s.handle(try! JSONSerialization.data(withJSONObject: m)) }
    func has(_ area: EvArea, _ name: String, where f: (Event) -> Bool = { _ in true }) -> Bool {
        Events.shared.recent().contains { $0.area == area && $0.event == name && f($0) }
    }

    @Test func everyHandledMessageTypeLeavesAnInEvent() {
        let hub = makeHub()
        for t in Session.handledTypes {
            Events.shared.reset()
            let w = Wire(), s = session(hub, w)
            s.start()
            s.labsAuthed(id: "test", name: "Test")
            feed(s, ["t": t, "corr": "cov-\(t)"])
            #expect(has(.protocolMsg, "in", where: { ($0.fields["t"] as? String) == t && $0.corr == "cov-\(t)" }), "no protocol.in event for '\(t)'")
            s.close()
            #expect(Events.shared.unknownNames.isEmpty, "uncatalogued events: \(Events.shared.unknownNames)")
        }
    }

    /// The type table must name every literal Session.handle dispatches on.
    @Test func handledTypesTableMatchesTheCode() throws {
        let src = try String(contentsOf: Self.sources.appendingPathComponent("Hub.swift"), encoding: .utf8)
        let a = src.range(of: "func handle(_ data: Data)")!.upperBound, b = src.range(of: "/// Spaces state and thumbnails")!.lowerBound
        let body = String(src[a..<b])
        let re = try NSRegularExpression(pattern: #"(?:case |t == )"([a-z][a-z.]*)""#)
        let found = Set(re.matches(in: body, range: NSRange(body.startIndex..., in: body)).map { String(body[Range($0.range(at: 1), in: body)!]) })
        #expect(found.count > 5)
        for t in found { #expect(Session.handledTypes.contains(t), "Session.handle dispatches '\(t)' but it is not in Session.handledTypes (add it, and the event test covers it)") }
    }

    @Test func everyPermissionCheckLeavesAnEvent() {
        for p in Perm.allCases {
            Events.shared.reset()
            let c = PermProbe.check(p, force: true)
            #expect(has(.permission, "check", where: { ($0.fields["perm"] as? String) == p.rawValue && ($0.fields["api"] as? String) == c.api && $0.fields["raw"] != nil }),
                    "no permission.check for \(p)")
            #expect(!c.api.isEmpty && !c.raw.isEmpty)
        }
    }

    @Test func connectStepsLeaveEvents() {
        let hub = makeHub()
        Events.shared.reset()
        let w = Wire(), s = session(hub, w)
        s.start()
        #expect(has(.session, "open") && has(.protocolMsg, "out", where: { ($0.fields["t"] as? String) == "hello" }))
        feed(s, ["t": "auth", "id": "nope", "mac": "00"])
        #expect(has(.session, "auth.failed") && has(.protocolMsg, "error", where: { ($0.fields["code"] as? String) == "auth" }))
        feed(s, ["t": "pair", "code": "000000", "name": "x"])
        #expect(has(.session, "pair.failed"))
        s.labsAuthed(id: "t", name: "T")
        #expect(has(.session, "authed"))
        s.ended()
        #expect(has(.session, "closed"))
        s.handle(Data("not json".utf8))   // closed: ignored
        let s2 = session(hub, Wire()); s2.start(); s2.handle(Data("not json".utf8))
        #expect(has(.protocolMsg, "bad"))
        s2.close()
    }

    @Test func inputAndPauseLeaveEvents() {
        let hub = makeHub()
        Events.shared.reset()
        let w = Wire(), s = session(hub, w)
        s.start(); s.labsAuthed(id: "t", name: "T")
        feed(s, ["t": "mouse", "dx": 1, "dy": 1])
        Events.shared.flushInput()
        #expect(has(.input, "summary", where: { (($0.fields["counts"] as? [String: Int]) ?? [:]).values.reduce(0, +) > 0 }))
        hub.paused = true
        feed(s, ["t": "key", "k": "a"])
        #expect(has(.input, "blocked", where: { ($0.fields["reason"] as? String) == "paused" }))
        s.close()
    }

    @Test func corrIsEchoedOnRepliesAndEvents() {
        let hub = makeHub()
        Events.shared.reset()
        let w = Wire(), s = session(hub, w)
        s.start(); s.labsAuthed(id: "t", name: "T")
        w.sent = []
        feed(s, ["t": "ping", "n": 7, "corr": "abc-1", "step": "J1.2"])
        #expect(w.sent.first?["corr"] as? String == "abc-1" && w.sent.first?["t"] as? String == "pong")
        #expect(has(.protocolMsg, "out", where: { $0.corr == "abc-1" && $0.journeyStep == "J1.2" }))
        w.sent = []
        feed(s, ["t": "ping", "n": 8])
        #expect(w.sent.first?["corr"] == nil)
        feed(s, ["t": "ping", "corr": String(repeating: "x", count: 65)])   // too long: ignored
        #expect(!has(.protocolMsg, "in", where: { ($0.fields["t"] as? String) == "ping" && ($0.corr?.count ?? 0) > 64 }))
    }

    @Test func diagAndSubscribe() async throws {
        let hub = makeHub()
        Events.shared.reset()
        let w = Wire(), s = session(hub, w)
        s.start()
        feed(s, ["t": "diag"])   // not authed
        #expect(w.sent.last?["code"] as? String == "unauth")
        s.labsAuthed(id: "t", name: "T")
        feed(s, ["t": "diag", "corr": "d1", "client": ["app": "mote-android", "proto": "1.5"]])
        let d = w.sent.last!
        #expect(d["t"] as? String == "diag" && d["corr"] as? String == "d1" && d["v"] as? Int == 1)
        #expect(d["features"] != nil && d["permissions"] != nil && d["signing"] != nil && d["sessions"] != nil)
        feed(s, ["t": "diag.subscribe", "on": true, "since": 0])
        let ack = w.sent.last!
        #expect(ack["t"] as? String == "diag.events" && ack["subscribed"] as? Bool == true && (ack["events"] as! [[String: Any]]).count > 0)
        ev(.ui, "menu.opened")
        try await Task.sleep(nanoseconds: 400_000_000)
        let pushed = w.sent.filter { $0["t"] as? String == "diag.events" }.flatMap { ($0["events"] as? [[String: Any]]) ?? [] }
        #expect(pushed.contains { $0["event"] as? String == "menu.opened" })
        #expect(!Events.shared.recent().contains { $0.event == "out" && ($0.fields["t"] as? String) == "diag.events" })
        feed(s, ["t": "diag.subscribe", "on": false])
        #expect(w.sent.last?["subscribed"] as? Bool == false)
        s.close()
    }

    @Test func catalogMatchesTheProtocolDoc() throws {
        let doc = try String(contentsOf: Self.sources.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("docs/protocol.md"), encoding: .utf8)
        for (area, names) in EventCatalog.all {
            let line = doc.split(separator: "\n").first { $0.hasPrefix("| `\(area.rawValue)` |") }
            #expect(line != nil, "docs/protocol.md has no event row for area \(area.rawValue)")
            for n in names { #expect(line.map { $0.contains("`\(n)`") } ?? false, "docs/protocol.md area \(area.rawValue) misses event \(n)") }
        }
    }
}
