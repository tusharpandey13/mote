import Foundation
import Testing
@testable import MoteHelper

/// Replays OS states (helper/tests/Fixtures/system/*.json, recorded with `MoteHelper --record-system` or hand-made with
/// "source": "synthetic") through the diagnostics and the Setup window model.
struct SystemFixtureTests {
    static func fixture(_ name: String) throws -> SystemFixture {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Fixtures/system")
        return try SystemFixture.load(dir.appendingPathComponent("\(name).json"))
    }
    /// The Setup window's rows for a fixture: the same path SetupModel.refresh takes.
    func rows(_ f: SystemFixture) -> [PermRow] {
        let snap = Diag.replay(f)
        return PermissionModel.rows(states: Diag.states(fromSnapshot: snap), enabled: [], regrant: Diag.regrant(fromSnapshot: snap))
    }
    func row(_ p: Perm, _ rows: [PermRow]) -> PermRow { rows.first { $0.perm == p }! }
    func feat(_ s: [String: Any], _ k: String) -> [String: Any] { (s["features"] as! [String: Any])[k] as! [String: Any] }

    @Test func healthyMac() throws {
        let f = try Self.fixture("healthy")
        #expect(f.source == "synthetic")
        let s = Diag.replay(f)
        for k in ["wifi", "bonjour", "bluetooth", "keychain"] { #expect(feat(s, k)["state"] as? String == "ok", "\(k)") }
        let r = rows(f)
        #expect(row(.localNetwork, r).status == .ok && row(.screenRecording, r).status == .ok)
        #expect(!r.contains { $0.regrant })
    }

    /// Owner Mac, recorded with --record-system (identifiers redacted): all granted, Local Network inferred from the
    /// ready listener, older Keychain accounts present. Screen Recording history names an older cdhash but the grant is live.
    @Test func recordedMacos26Healthy() throws {
        let f = try Self.fixture("recorded-macos-26.6")
        #expect(f.source == "recorded")
        let s = Diag.replay(f)
        for k in ["wifi", "bonjour", "bluetooth"] { #expect(feat(s, k)["state"] as? String == "ok", "\(k)") }
        let r = rows(f)
        #expect(row(.screenRecording, r).status == .ok && row(.accessibility, r).status == .ok)
        #expect(row(.localNetwork, r).status != .grant)
        #expect(!r.contains { $0.regrant })
    }

    /// B1: Screen Recording granted to an earlier signature. The row used to say "Grant" with no hint; now it says why.
    @Test func b1StaleScreenRecordingGrantIsExplained() throws {
        let f = try Self.fixture("b1-screen-recording-stale-grant")
        let s = Diag.replay(f)
        let sr = (s["permissions"] as! [String: Any])["screenRecording"] as! [String: Any]
        #expect(sr["apiState"] as? String == "notDetermined" && sr["regrant"] as? Bool == true)
        let r = row(.screenRecording, PermissionModel.rows(states: Diag.states(fromSnapshot: s), enabled: [.screenRecording], requested: [.screenRecording], regrant: Diag.regrant(fromSnapshot: s)))
        #expect(r.status == .grant && r.regrant)
        #expect(r.relaunch)   // Grant already asked, macOS still says no: offer Relaunch (cached answer in the process)
    }

    @Test func noRegrantHintWithoutAnOlderSignatureOrWhenGranted() throws {
        var f = try Self.fixture("b1-screen-recording-stale-grant")
        f.screenGrantedBy = nil
        #expect(!row(.screenRecording, rows(f)).regrant)
        f.screenGrantedBy = f.signingInfo.designatedRequirement   // same signature, still off: a plain Grant
        #expect(!row(.screenRecording, rows(f)).regrant)
        f = try Self.fixture("healthy")
        f.screenGrantedBy = "cdhash H\"old\""
        #expect(!row(.screenRecording, rows(f)).regrant)
    }

    /// B2: Local Network allowed (the listener is ready), but the Setup window showed "Open Settings" from a stale PolicyDenied.
    @Test func b2StalePolicyDeniedIsNotDenied() throws {
        let f = try Self.fixture("b2-local-network-stale-denied")
        let s = Diag.replay(f)
        let ln = (s["permissions"] as! [String: Any])["localNetwork"] as! [String: Any]
        #expect(ln["state"] as? String == "unknown")
        #expect(feat(s, "bonjour")["state"] as? String == "unknown" && feat(s, "wifi")["blockedBy"] as? String == nil)
        let r = row(.localNetwork, rows(f))
        #expect(r.state == .unknown && r.status == .check)   // "Unknown", no Open Settings button
    }

    @Test func realPolicyDeniedStillOpensSettings() throws {
        let f = try Self.fixture("local-network-denied")
        let s = Diag.replay(f)
        #expect(feat(s, "bonjour")["blockedBy"] as? String == "local-network-denied")
        let r = row(.localNetwork, rows(f))
        #expect(r.state == .denied && r.status == .grant)
    }

    /// The recorder writes only probe answers, and what it writes loads back into the same state.
    @Test func recorderRoundTripsAndHoldsNoSecrets() throws {
        var f = try Self.fixture("b1-screen-recording-stale-grant")
        f.reading = DiscoveryReading(running: true)
        let rec = SystemRecorder.record(f.adapters, service: "dev.mote.helper")
        #expect(rec["source"] as? String == "recorded")
        let data = try JSONSerialization.data(withJSONObject: rec)
        let back = SystemFixture(json: try JSONSerialization.jsonObject(with: data) as! [String: Any])
        #expect(back.checks[.screenRecording]?.state == .notDetermined && back.checks[.screenRecording]?.raw == "false")
        #expect(back.accounts == ["secrets.v4.cdhash:bb"] && back.signingInfo.designatedRequirement == f.signingInfo.designatedRequirement)
        #expect(back.screenGrantedBy == f.screenGrantedBy)
        // The Keychain section lists account names; it has no field that could carry item data.
        #expect(Set((rec["keychain"] as! [String: Any]).keys) == ["service", "accounts", "listStatus"])
    }

    @Test func failedKeychainListIsRecordedAsStatus() throws {
        var f = try Self.fixture("healthy")
        f.accounts = nil; f.listStatus = -25308
        let rec = SystemRecorder.record(f.adapters, service: "dev.mote.helper")
        #expect((rec["keychain"] as! [String: Any])["listStatus"] as? Int == -25308)
        #expect(feat(Diag.replay(f), "keychain")["state"] as? String == "blocked")
    }
}
