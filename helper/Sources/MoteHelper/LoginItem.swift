import AppKit
import ServiceManagement

/// "Open Mote Helper at login" (SMAppService.mainApp). Remembers the bundle path it registered and whether the user wants it,
/// so a helper moved or replaced in place (install.sh to a new folder, drag to /Applications) re-registers itself (U15), and
/// shows "Needs approval in Login Items" when macOS holds the item until the user approves it (U17).
enum LoginItem {
    enum Status: String, Equatable { case enabled, notRegistered, requiresApproval, notFound }
    enum Decision: Equatable { case none, record, reregister }

    static var status: Status {
        switch SMAppService.mainApp.status {
        case .enabled: return .enabled
        case .requiresApproval: return .requiresApproval
        case .notFound: return .notFound
        default: return .notRegistered
        }
    }

    /// Pure: what to do at launch. [wanted]: the user (or the first-run default) turned the login item on and not off since.
    static func decide(wanted: Bool, status: Status, storedPath: String?, currentPath: String) -> Decision {
        if storedPath == currentPath { return .none }
        if storedPath == nil { return status == .enabled ? .record : .none }   // before 0.7: no path was kept; adopt it
        return wanted || status == .enabled ? .reregister : .record
    }

    /// Menu / Setup text for a status (U17).
    static func caption(_ s: Status) -> String? { s == .requiresApproval ? "Needs approval in Login Items" : nil }

    private static let d = UserDefaults.standard

    /// At launch (not in --dry-run): re-register when the bundle path changed since it was registered.
    static func reconcile() {
        let path = Bundle.main.bundlePath
        let st = status
        let dec = decide(wanted: d.bool(forKey: "loginWanted"), status: st, storedPath: d.string(forKey: "loginPath"), currentPath: path)
        ev(.lifecycle, "login.item", ["action": "reconcile", "decision": "\(dec)", "status": st.rawValue])
        switch dec {
        case .none: break
        case .record: d.set(path, forKey: "loginPath")
        case .reregister:
            try? SMAppService.mainApp.unregister()
            do { try SMAppService.mainApp.register(); d.set(path, forKey: "loginPath"); log("login item re-registered for \(path)") }
            catch { ev(.lifecycle, "login.item", ["action": "reregister.failed", "error": "\(error)"]); log("login item re-register failed: \(error)") }
        }
    }

    /// The user's choice (menu, Setup) or the first-run default.
    static func set(_ on: Bool) throws {
        if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        d.set(on, forKey: "loginWanted")
        d.set(Bundle.main.bundlePath, forKey: "loginPath")
        ev(.lifecycle, "login.item", ["action": on ? "register" : "unregister", "status": status.rawValue])
    }

    static func openApproval() {
        ev(.ui, "menu.item", ["item": "openLoginItems"])
        SMAppService.openSystemSettingsLoginItems()
    }
}
