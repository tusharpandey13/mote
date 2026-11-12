import Foundation
import Testing
@testable import MoteHelper

/// The snapshot builder is pure: feed it recorded/imagined OS answers and read the verdicts.
struct DiagTests {
    func check(_ p: Perm, _ s: PermState, api: String = "api", raw: String = "raw") -> PermCheck {
        PermCheck(perm: p, state: s, api: api, raw: raw, at: 1, ms: 0.1)
    }
    func input(_ mod: (inout DiagInput) -> Void = { _ in }) -> [String: Any] {
        var i = DiagInput()
        i.perms = [.accessibility: check(.accessibility, .granted), .bluetooth: check(.bluetooth, .granted),
                   .screenRecording: check(.screenRecording, .granted), .localNetwork: check(.localNetwork, .unknown),
                   .automation: check(.automation, .unknown)]
        i.listenerState = "ready"; i.listenerPort = 8790; i.bonjour = "registered"
        i.bleStatus = "advertising"; i.bleAdvertising = true
        i.keychain.readResult = "found"; i.keychain.readStatus = 0
        i.signing.teamId = "ABCDE12345"; i.signing.cdhash = "aa"
        mod(&i)
        return Diag.build(i)
    }
    func feat(_ s: [String: Any], _ k: String) -> [String: Any] { (s["features"] as! [String: Any])[k] as! [String: Any] }
    func perm(_ s: [String: Any], _ k: String) -> [String: Any] { (s["permissions"] as! [String: Any])[k] as! [String: Any] }

    @Test func healthyMac() {
        let s = input()
        for k in ["input", "wifi", "bonjour", "bluetooth", "keychain"] { #expect(feat(s, k)["state"] as? String == "ok", "\(k)") }
        #expect(perm(s, "localNetwork")["state"] as? String == "ok")   // inferred from the Bonjour registration
        #expect(perm(s, "localNetwork")["inferred"] as? Bool == true)
        #expect(s["v"] as? Int == 1 && (s["helper"] as! [String: Any])["protocol"] as? String == "1.5")
        #expect(JSONSerialization.isValidJSONObject(s))
    }

    /// B2: Local Network allowed, but the Setup window showed "Open Settings" because the state was always unknown.
    @Test func localNetworkDeniedShowsPolicyDenied() {
        let s = input { $0.bonjour = "failed"; $0.bonjourErrorCode = -65570; $0.listenerState = "waiting"; $0.listenerError = "-65570" }
        #expect(perm(s, "localNetwork")["state"] as? String == "blocked")
        #expect(feat(s, "bonjour")["blockedBy"] as? String == "local-network-denied")
        #expect(feat(s, "wifi")["blockedBy"] as? String == "local-network-denied")
        #expect(Diag.states(fromSnapshot: s)[.localNetwork] == .denied)
    }

    @Test func localNetworkUnknownIsNeverBlocked() {
        let s = input { $0.bonjour = "attempting" }
        #expect(perm(s, "localNetwork")["state"] as? String == "unknown")
        #expect(Diag.states(fromSnapshot: s)[.localNetwork] == .unknown)
        let rows = PermissionModel.rows(states: Diag.states(fromSnapshot: s), enabled: [])
        #expect(rows.first { $0.perm == .localNetwork }?.status == .check)   // the view prints "Can't check", no button
        let cli = input { $0.running = false }
        #expect(feat(cli, "wifi")["state"] as? String == "unknown")
    }

    /// B1 data: the raw API answer, the second signal and the signing identity are all in the snapshot.
    @Test func screenRecordingExposesRawData() {
        let s = input {
            $0.perms[.screenRecording] = PermCheck(perm: .screenRecording, state: .notDetermined, api: "CGPreflightScreenCaptureAccess", raw: "false", at: 5, ms: 2,
                                                   detail: ["otherAppWindows": "4", "otherAppWindowTitlesVisible": "0"])
            $0.liveScreenLab = true
            $0.signing.adhoc = true; $0.signing.teamId = nil; $0.signing.cdhash = "bb"
        }
        let p = perm(s, "screenRecording")
        #expect(p["raw"] as? String == "false" && p["api"] as? String == "CGPreflightScreenCaptureAccess")
        #expect((p["detail"] as! [String: String])["otherAppWindowTitlesVisible"] == "0")
        #expect(p["state"] as? String == "blocked" && p["blockedBy"] as? String == "screenRecording-not-granted")
        #expect(feat(s, "labs.liveScreen")["blockedBy"] as? String == "screen-recording-not-granted")
        #expect((s["signing"] as! [String: Any])["expectedKeychainPartition"] as? String == "cdhash:bb")
    }

    @Test func listenerFailedAndWaitingAreBlockedWithAFix() {
        var s = input { $0.listenerState = "failed"; $0.listenerError = "Address already in use" }
        #expect(feat(s, "wifi")["blockedBy"] as? String == "listener-failed" && feat(s, "wifi")["fix"] as? String == "free-port")
        s = input { $0.listenerState = "waiting" }
        #expect(feat(s, "wifi")["blockedBy"] as? String == "listener-waiting" && feat(s, "wifi")["fix"] as? String == "restart-helper")
    }

    @Test func spaceThumbnailsBlockedWithoutScreenRecording() {
        let s = input { $0.shareThumbnails = true; $0.perms[.screenRecording] = check(.screenRecording, .notDetermined) }
        #expect(feat(s, "spaceThumbnails")["blockedBy"] as? String == "screen-recording-not-granted")
        #expect(feat(s, "spaceThumbnails")["fix"] as? String == "grant-screen-recording")
    }

    @Test func screenRecordingOffWhenNothingUsesIt() {
        let s = input { $0.perms[.screenRecording] = check(.screenRecording, .notDetermined) }
        #expect(perm(s, "screenRecording")["state"] as? String == "off")
        #expect(feat(s, "spaceThumbnails")["state"] as? String == "off")
    }

    @Test func accessibilityAndPause() {
        var s = input { $0.perms[.accessibility] = check(.accessibility, .notDetermined) }
        #expect(feat(s, "input")["blockedBy"] as? String == "accessibility-not-granted" && feat(s, "input")["fix"] as? String == "grant-accessibility")
        s = input { $0.paused = true }
        #expect(feat(s, "input")["blockedBy"] as? String == "input-paused")
        s = input { $0.dryRun = true; $0.perms[.accessibility] = check(.accessibility, .notDetermined) }
        #expect(feat(s, "input")["state"] as? String == "ok")
    }

    @Test func keychainStatusCodes() {
        let s = input { $0.keychain.readResult = "error"; $0.keychain.readStatus = -25308 }
        #expect(feat(s, "keychain")["blockedBy"] as? String == "keychain-unreadable")
        #expect((s["keychain"] as! [String: Any])["readStatus"] as? Int == -25308)
        let m = input { $0.keychain.memoryOnly = true; $0.keychain.readResult = "memory" }
        #expect(feat(m, "keychain")["state"] as? String == "off")
        let p = input { $0.keychain.partitionAtWrite = "teamid:ABCDE12345" }
        #expect((p["keychain"] as! [String: Any])["partitionMatchesSigning"] as? Bool == true)
    }

    @Test func ephemeralVaultIsBlockedWithRepair() {
        let s = input { $0.keychain.readResult = "error"; $0.keychain.readStatus = -25308; $0.keychain.ephemeral = true
                        $0.keychain.ephemeralReason = "denied"; $0.keychain.interactiveRead = "denied" }
        let f = feat(s, "keychain"), k = s["keychain"] as! [String: Any]
        #expect(f["state"] as? String == "blocked" && f["blockedBy"] as? String == "keychain-unreadable" && f["fix"] as? String == "repair-keychain")
        #expect((f["detail"] as? String)?.hasPrefix("Pairings aren't being saved: Keychain unavailable") == true)
        #expect(k["ephemeral"] as? Bool == true && k["interactiveRead"] as? String == "denied" && k["ephemeralReason"] as? String == "denied")
        let c = input { $0.keychain.readResult = "unreadable"; $0.keychain.ephemeral = true; $0.keychain.ephemeralReason = "corrupt" }
        #expect(feat(c, "keychain")["blockedBy"] as? String == "keychain-corrupt")
        let ok = input { $0.keychain.readResult = "found" }
        #expect(feat(ok, "keychain")["state"] as? String == "ok" && (ok["keychain"] as! [String: Any])["ephemeral"] as? Bool == false)
    }

    @Test func labsAndSessions() {
        let s = input {
            $0.contextLab = true; $0.nowPlayingStatus = "mediaremote"; $0.tvLab = false
            var d = DiagSession(deviceName: "Pixel", transport: "BLE", authed: true, connectedS: 3, clientProto: "1.5", clientApp: "mote-android")
            d.spaces = true; d.diag = true
            $0.sessions = [d]
        }
        #expect(feat(s, "labs.context")["state"] as? String == "unknown")      // no browser running: Automation can't be read
        #expect(feat(s, "labs.nowPlaying")["state"] as? String == "ok")
        #expect(feat(s, "labs.tvMode")["state"] as? String == "off")
        let ss = (s["sessions"] as! [[String: Any]])[0]
        #expect(ss["transport"] as? String == "BLE" && (ss["protocol"] as! [String: Any])["client"] as? String == "1.5")
        #expect((ss["subscriptions"] as! [String: Bool])["spaces"] == true && (ss["subscriptions"] as! [String: Bool])["context"] == false)
    }

    @Test func readableText() {
        let t = Diag.text(input())
        #expect(t.contains("FEATURES") && t.contains("PERMISSIONS") && t.contains("SIGNING") && t.contains("KEYCHAIN"))
    }
}
