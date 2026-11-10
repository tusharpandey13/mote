import Foundation
import Testing
@testable import MoteHelper

/// Events.shared is process-wide: these suites run serialized.
@Suite(.serialized) struct EventsTests {
    @Test func ringKeepsTheNewest5000() {
        let e = Events.shared; e.reset()
        for i in 0..<(Events.capacity + 25) { e.emit(.lifecycle, "setting", ["i": i]) }
        let all = e.recent()
        #expect(all.count == Events.capacity)
        #expect(all.first?.seq == 26 && all.last?.seq == Events.capacity + 25)
        #expect(e.head == Events.capacity + 25)
        #expect(e.recent(since: Events.capacity + 20).count == 5)
    }

    @Test func schema() throws {
        let e = Events.shared; e.reset()
        e.currentCorr = "c-1"; e.currentStep = "J4.2"
        e.emit(.session, "open", ["transport": "Wi-Fi", "bad": Date(), "nan": Double.nan])
        e.currentCorr = nil; e.currentStep = nil
        e.emit(.session, "closed")
        let a = e.recent()[0].json, b = e.recent()[1].json
        #expect(a["side"] as? String == "helper" && a["area"] as? String == "session" && a["event"] as? String == "open")
        #expect(a["corr"] as? String == "c-1" && a["journeyStep"] as? String == "J4.2" && a["seq"] as? Int == 1)
        #expect(b["corr"] is NSNull && b["journeyStep"] == nil)
        #expect(a["t"] is Int64)
        let f = a["fields"] as! [String: Any]
        #expect(f["nan"] as? Double == 0 && f["bad"] is String)
        #expect(JSONSerialization.isValidJSONObject(a))
    }

    @Test func coalescingCountsTheSkipped() {
        let e = Events.shared; e.reset()
        for _ in 0..<10 { e.emit(.protocolMsg, "in", coalesce: "k", ["t": "ping"]) }
        #expect(e.recent().count == 1)
        e.emit(.protocolMsg, "in", coalesce: "other", ["t": "pong"])
        #expect(e.recent().count == 2)
    }

    @Test func jsonlFile() throws {
        let e = Events.shared; e.reset()
        let path = NSTemporaryDirectory() + "mote-events-\(UUID().uuidString).jsonl"
        e.openFile(path)
        e.emit(.keychain, "read", ["account": "secrets.v3", "status": -25300])
        e.emit(.ui, "menu.opened")
        let lines = try String(contentsOfFile: path, encoding: .utf8).split(separator: "\n")
        #expect(lines.count == 2)
        let j = try JSONSerialization.jsonObject(with: Data(lines[0].utf8)) as! [String: Any]
        #expect(j["area"] as? String == "keychain" && (j["fields"] as! [String: Any])["status"] as? Int == -25300)
        try? FileManager.default.removeItem(atPath: path)
    }

    @Test func inputIsCountedNeverLogged() {
        let e = Events.shared; e.reset()
        for _ in 0..<500 { e.countInput("move") }
        e.countInput("key")
        #expect(e.recent().isEmpty)
        e.flushInput()
        let f = e.recent().first!.fields
        #expect(((f["counts"] as! [String: Int])["move"]) == 500)
        e.flushInput()
        #expect(e.recent().count == 1)
    }

    @Test func catalogHasNoDuplicatesAndEveryAreaIsListed() {
        for a in EvArea.allCases { #expect(!(EventCatalog.all[a] ?? []).isEmpty, "area \(a) has no events") }
        for (a, names) in EventCatalog.all { #expect(Set(names).count == names.count, "duplicate in \(a)") }
    }
}
