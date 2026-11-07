import AppKit
import ApplicationServices
import CoreBluetooth
import CoreGraphics

/// The permissions Mote Helper can use, and what the Setup window shows for each. The model is
/// pure (`PermissionModel.rows`), unit-tested in helper/tests/MoteHelperTests/PermissionTests.swift.
///
/// Rule: no TCC prompt ever comes from a background event (a phone subscribing, a Space switch, a
/// browser coming to the front). Prompts only follow a click in the Setup window or on a feature toggle.
enum Perm: String, CaseIterable {
    case accessibility, bluetooth, localNetwork, screenRecording, automation

    enum Tier { case required, recommended, optional }

    var tier: Tier {
        switch self {
        case .accessibility: return .required
        case .bluetooth, .localNetwork: return .recommended
        case .screenRecording, .automation: return .optional
        }
    }

    var title: String {
        switch self {
        case .accessibility: return "Accessibility"
        case .bluetooth: return "Bluetooth"
        case .localNetwork: return "Local Network"
        case .screenRecording: return "Screen Recording"
        case .automation: return "Automation"
        }
    }

    var why: String {
        switch self {
        case .accessibility: return "Lets your phone move the pointer, click and type."
        case .bluetooth: return "Finds your phone nearby for one-tap pairing, and works without Wi-Fi."
        case .localNetwork: return "Lets your phone reach this Mac over Wi-Fi."
        case .screenRecording: return "Space thumbnails on the phone, and Labs › Live screen."
        case .automation: return "Labs › Context: reads your browser's active tab for smarter controls."
        }
    }

    /// The exact System Settings pane.
    var settingsURL: URL {
        let pane: String
        switch self {
        case .accessibility: pane = "Privacy_Accessibility"
        case .bluetooth: pane = "Privacy_Bluetooth"
        case .localNetwork: pane = "Privacy_LocalNetwork"
        case .screenRecording: pane = "Privacy_ScreenCapture"
        case .automation: pane = "Privacy_Automation"
        }
        return URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane)")!
    }
}

enum PermState: Equatable {
    case granted, denied, notDetermined
    case unknown   // macOS has no API to read it (Local Network; Automation with no browser running)
}

/// One Setup window row.
struct PermRow: Equatable {
    enum Status: Equatable {
        case ok           // ✓
        case grant        // needs a click: "Grant"
        case optional     // feature off: "Optional" + "Enable"
        case check        // state can't be read: "Open Settings"
    }
    let perm: Perm
    let state: PermState
    let status: Status
    /// Shown after the user asked for it, while macOS still reports "not granted" (Screen Recording only
    /// takes effect after a relaunch on some macOS versions).
    let relaunch: Bool
}

enum PermissionModel {
    /// `enabled`: optional features the user turned on. `requested`: permissions asked for this run.
    static func rows(states: [Perm: PermState], enabled: Set<Perm>, requested: Set<Perm> = [],
                     perms: [Perm] = Perm.allCases) -> [PermRow] {
        perms.map { p in
            let st = states[p] ?? .unknown
            let status: PermRow.Status
            switch (st, p.tier) {
            case (.granted, _): status = .ok
            case (.unknown, _): status = enabled.contains(p) || p.tier != .optional ? .check : .optional
            case (_, .optional): status = enabled.contains(p) ? .grant : .optional
            default: status = .grant
            }
            let relaunch = p == .screenRecording && requested.contains(p) && st != .granted
            return PermRow(perm: p, state: st, status: status, relaunch: relaunch)
        }
    }

    /// Required permissions missing: the Setup window opens by itself.
    static func needsSetup(_ states: [Perm: PermState]) -> Bool {
        Perm.allCases.contains { $0.tier == .required && states[$0] != .granted }
    }

    /// The default path is done: required + recommended (where readable) granted.
    static func ready(_ states: [Perm: PermState]) -> Bool {
        Perm.allCases.allSatisfy { p in
            p.tier == .optional || states[p] == .granted || states[p] == .unknown
        }
    }
}

/// Reads the live state. Every probe here is silent: none can show a macOS prompt.
enum PermProbe {
    static var accessibility: PermState { AXIsProcessTrusted() ? .granted : .notDetermined }

    static var bluetooth: PermState {
        switch CBManager.authorization {
        case .allowedAlways: return .granted
        case .denied, .restricted: return .denied
        default: return .notDetermined
        }
    }

    static var screenRecording: PermState { CGPreflightScreenCaptureAccess() ? .granted : .notDetermined }

    /// Local Network privacy exists from macOS 15; there is no API to read it.
    static var hasLocalNetworkPrivacy: Bool { ProcessInfo.processInfo.isOperatingSystemAtLeast(OperatingSystemVersion(majorVersion: 15, minorVersion: 0, patchVersion: 0)) }

    /// Automation is per target app: granted if any running scriptable browser allows it.
    static func automation() -> PermState {
        let running = NSWorkspace.shared.runningApplications.compactMap(\.bundleIdentifier).filter(Browsers.scriptable)
        let states = Set(running.map { Browsers.automation($0, ask: false) })
        if states.contains("granted") { return .granted }
        if states.contains("denied") { return .denied }
        if states.contains("ask") { return .notDetermined }
        return .unknown
    }

    static func all() -> [Perm: PermState] {
        [.accessibility: accessibility, .bluetooth: bluetooth, .localNetwork: .unknown,
         .screenRecording: screenRecording, .automation: automation()]
    }
}
