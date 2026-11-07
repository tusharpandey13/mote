import Testing
@testable import MoteHelper

/// Setup window rows (Permissions.swift): what each permission shows for each state.
struct PermissionTests {
    func row(_ p: Perm, _ rows: [PermRow]) -> PermRow { rows.first { $0.perm == p }! }

    @Test func tiers() {
        #expect(Perm.accessibility.tier == .required)
        #expect(Perm.bluetooth.tier == .recommended && Perm.localNetwork.tier == .recommended)
        #expect(Perm.screenRecording.tier == .optional && Perm.automation.tier == .optional)
    }

    @Test func freshInstall() {
        let st: [Perm: PermState] = [.accessibility: .notDetermined, .bluetooth: .notDetermined, .localNetwork: .unknown,
                                     .screenRecording: .notDetermined, .automation: .unknown]
        let r = PermissionModel.rows(states: st, enabled: [])
        #expect(row(.accessibility, r).status == .grant)
        #expect(row(.bluetooth, r).status == .grant)
        #expect(row(.localNetwork, r).status == .check)
        #expect(row(.screenRecording, r).status == .optional)   // off until enabled: no prompt
        #expect(row(.automation, r).status == .optional)
        #expect(PermissionModel.needsSetup(st))
        #expect(!PermissionModel.ready(st))
        #expect(r.allSatisfy { !$0.relaunch })
    }

    @Test func grantedShowsCheck() {
        let st: [Perm: PermState] = [.accessibility: .granted, .bluetooth: .granted, .localNetwork: .unknown,
                                     .screenRecording: .granted, .automation: .granted]
        let r = PermissionModel.rows(states: st, enabled: [])
        #expect(row(.accessibility, r).status == .ok)
        #expect(row(.screenRecording, r).status == .ok)
        #expect(!PermissionModel.needsSetup(st))
        #expect(PermissionModel.ready(st))
    }

    @Test func accessibilityAloneClearsAutoOpen() {
        #expect(!PermissionModel.needsSetup([.accessibility: .granted]))
        #expect(PermissionModel.needsSetup([.accessibility: .denied]))
        #expect(PermissionModel.needsSetup([:]))
    }

    @Test func optionalEnabledAsksForGrant() {
        let st: [Perm: PermState] = [.screenRecording: .notDetermined, .automation: .denied]
        let r = PermissionModel.rows(states: st, enabled: [.screenRecording, .automation])
        #expect(row(.screenRecording, r).status == .grant)
        #expect(row(.automation, r).status == .grant)
        // Automation with no browser running can't be read: Open Settings once enabled.
        let r2 = PermissionModel.rows(states: [.automation: .unknown], enabled: [.automation])
        #expect(row(.automation, r2).status == .check)
    }

    @Test func relaunchOfferedOnlyAfterAskingScreenRecording() {
        let st: [Perm: PermState] = [.screenRecording: .notDetermined]
        #expect(!row(.screenRecording, PermissionModel.rows(states: st, enabled: [.screenRecording])).relaunch)
        #expect(row(.screenRecording, PermissionModel.rows(states: st, enabled: [.screenRecording], requested: [.screenRecording])).relaunch)
        #expect(!row(.screenRecording, PermissionModel.rows(states: [.screenRecording: .granted], enabled: [], requested: [.screenRecording])).relaunch)
        #expect(!row(.accessibility, PermissionModel.rows(states: [:], enabled: [], requested: [.accessibility])).relaunch)
    }

    @Test func deniedRecommendedStillGrant() {
        let r = PermissionModel.rows(states: [.bluetooth: .denied], enabled: [])
        #expect(row(.bluetooth, r).status == .grant && row(.bluetooth, r).state == .denied)
    }

    @Test func permsFilter() {
        let r = PermissionModel.rows(states: [:], enabled: [], perms: [.accessibility, .bluetooth])
        #expect(r.map(\.perm) == [.accessibility, .bluetooth])
    }

    @Test func settingsPanes() {
        #expect(Perm.accessibility.settingsURL.absoluteString == "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
        #expect(Perm.screenRecording.settingsURL.absoluteString.hasSuffix("Privacy_ScreenCapture"))
    }
}
