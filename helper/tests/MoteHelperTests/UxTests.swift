import Testing
@testable import MoteHelper

/// U7 (Local Network wording), U15/U17 (login item), U21 (update version compare).
struct UxTests {
    @Test func localNetworkNeverSaysAllowed() {
        #expect(PermissionModel.localNetworkLabel(.ok) == "Probably allowed")
        #expect(PermissionModel.localNetworkLabel(.check) == "Unknown")
        #expect(PermissionModel.localNetworkNote == "macOS doesn't let apps check this permission")
    }

    @Test func loginItemDecision() {
        let here = "/Users/x/Applications/MoteHelper.app", old = "/Applications/MoteHelper.app"
        #expect(LoginItem.decide(wanted: true, status: .enabled, storedPath: here, currentPath: here) == .none)
        #expect(LoginItem.decide(wanted: true, status: .notRegistered, storedPath: old, currentPath: here) == .reregister)
        #expect(LoginItem.decide(wanted: false, status: .enabled, storedPath: old, currentPath: here) == .reregister)
        #expect(LoginItem.decide(wanted: false, status: .notRegistered, storedPath: old, currentPath: here) == .record)   // user turned it off: stay off
        #expect(LoginItem.decide(wanted: false, status: .enabled, storedPath: nil, currentPath: here) == .record)         // pre-0.7 install: adopt
        #expect(LoginItem.decide(wanted: false, status: .notRegistered, storedPath: nil, currentPath: here) == .none)
    }

    @Test func loginItemCaption() {
        #expect(LoginItem.caption(.requiresApproval) == "Needs approval in Login Items")
        #expect(LoginItem.caption(.enabled) == nil && LoginItem.caption(.notRegistered) == nil)
    }

    @Test func versionCompare() {
        #expect(Updater.isNewer("0.7.0", than: "0.6.0") && Updater.isNewer("v0.10.0", than: "0.9.9"))
        #expect(Updater.isNewer("1.0", than: "0.9.9") && Updater.isNewer("0.6.1", than: "0.6"))
        #expect(!Updater.isNewer("0.6.0", than: "0.6.0") && !Updater.isNewer("0.6", than: "0.6.0") && !Updater.isNewer("0.5.9", than: "0.6.0"))
        #expect(!Updater.isNewer("garbage", than: "0.6.0") && !Updater.isNewer("0.7.0", than: "garbage"))
        #expect(Updater.parse("v1.2.3-rc1") == [1, 2, 3] && Updater.parse("") == nil)
    }
}
