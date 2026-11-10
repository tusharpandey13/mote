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

    /// Local Network has no macOS API; the row is inferred from Bonjour, so it never says "Allowed" (U7).
    static func localNetworkLabel(_ s: PermRow.Status) -> String { s == .ok ? "Probably allowed" : "Unknown" }
    static let localNetworkNote = "macOS doesn't let apps check this permission"

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

/// One permission check: the API called, its raw answer, and when. Nothing here can show a macOS prompt.
struct PermCheck: Equatable {
    let perm: Perm
    let state: PermState
    let api: String
    let raw: String
    let at: Int64          // Unix ms
    let ms: Double         // how long the call took
    var detail: [String: String] = [:]   // extra raw signals (per-browser Automation, secondary Screen Recording signal)

    var json: [String: Any] {
        ["perm": perm.rawValue, "state": PermProbe.name(state), "api": api, "raw": raw, "checkedAt": at,
         "ms": (ms * 100).rounded() / 100, "detail": detail]
    }
}

/// Reads the live state. Every probe here is silent: none can show a macOS prompt.
enum PermProbe {
    /// Tests (--fake-ax-denied, dry-run only): Accessibility reads as not granted whatever macOS says.
    static var fakeAxDenied = false
    static var axTrusted: Bool { !fakeAxDenied && AXIsProcessTrusted() }
    static var accessibility: PermState { axTrusted ? .granted : .notDetermined }

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
    static func automation() -> PermState { automationCheck().state }

    private static func automationCheck() -> (state: PermState, raw: String) {
        let running = NSWorkspace.shared.runningApplications.compactMap(\.bundleIdentifier).filter(Browsers.scriptable).sorted()
        let results = running.map { ($0, Browsers.automation($0, ask: false)) }
        let raw = results.isEmpty ? "no scriptable browser running" : results.map { "\($0.0)=\($0.1)" }.joined(separator: ",")
        let states = Set(results.map(\.1))
        if states.contains("granted") { return (.granted, raw) }
        if states.contains("denied") { return (.denied, raw) }
        if states.contains("ask") { return (.notDetermined, raw) }
        return (.unknown, raw)
    }

    static func name(_ s: PermState) -> String {
        switch s { case .granted: return "granted"; case .denied: return "denied"; case .notDetermined: return "notDetermined"; case .unknown: return "unknown" }
    }

    /// Windows of other apps whose title macOS shows us. Without Screen Recording the titles are hidden, so 0 with
    /// other apps' windows on screen means "not granted to this build" even when System Settings shows the switch on.
    static func windowTitlesVisible() -> (visible: Int, others: Int) {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] else { return (0, 0) }
        let me = Int(getpid())
        let others = list.filter { ($0[kCGWindowOwnerPID as String] as? Int) != me && ($0[kCGWindowLayer as String] as? Int) == 0 }
        return (others.filter { !(($0[kCGWindowName as String] as? String) ?? "").isEmpty }.count, others.count)
    }

    private static var lastEmit: [Perm: (raw: String, at: Date)] = [:]
    private(set) static var last: [Perm: PermCheck] = [:]

    /// The check, with its raw answer. Emits `permission.check` when the answer changed or 30 s passed (or [force]).
    @discardableResult
    static func check(_ p: Perm, force: Bool = false) -> PermCheck {
        let t0 = Date()
        var api = "", raw = "", state = PermState.unknown, detail: [String: String] = [:]
        switch p {
        case .accessibility:
            api = "AXIsProcessTrusted"; let v = axTrusted; raw = "\(v)"; state = v ? .granted : .notDetermined
        case .bluetooth:
            api = "CBManager.authorization"
            let a = CBManager.authorization
            raw = ["notDetermined", "restricted", "denied", "allowedAlways"][min(max(a.rawValue, 0), 3)]
            state = bluetooth
        case .screenRecording:
            api = "CGPreflightScreenCaptureAccess"; let v = CGPreflightScreenCaptureAccess(); raw = "\(v)"; state = v ? .granted : .notDetermined
            let w = windowTitlesVisible()
            detail["otherAppWindows"] = "\(w.others)"; detail["otherAppWindowTitlesVisible"] = "\(w.visible)"
        case .localNetwork:
            api = "none"; raw = hasLocalNetworkPrivacy ? "no API; inferred from the Bonjour listener" : "not applicable before macOS 15"
        case .automation:
            api = "AEDeterminePermissionToAutomateTarget(ask: false)"; let a = automationCheck(); raw = a.raw; state = a.state
        }
        let c = PermCheck(perm: p, state: state, api: api, raw: raw, at: Int64(Date().timeIntervalSince1970 * 1000),
                          ms: Date().timeIntervalSince(t0) * 1000, detail: detail)
        let prev = last[p]
        last[p] = c
        if let prev, prev.state != state { ev(.permission, "change", ["perm": p.rawValue, "from": name(prev.state), "to": name(state), "raw": raw]) }
        if force || lastEmit[p]?.raw != raw || Date().timeIntervalSince(lastEmit[p]?.at ?? .distantPast) > 30 {
            lastEmit[p] = (raw, Date())
            ev(.permission, "check", ["perm": p.rawValue, "api": api, "raw": raw, "state": name(state), "ms": (c.ms * 100).rounded() / 100])
        }
        return c
    }

    static func all(force: Bool = false) -> [Perm: PermState] {
        Dictionary(uniqueKeysWithValues: Perm.allCases.map { ($0, check($0, force: force).state) })
    }
}
