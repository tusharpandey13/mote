import AppKit
import ApplicationServices
import ServiceManagement
import SwiftUI

/// What the Setup window can do to the rest of the helper (wired in main.swift).
struct SetupActions {
    /// The diagnostics snapshot the rows are drawn from (nil in tests: rows then use the raw probes).
    var snapshot: (() -> [String: Any])? = nil
    var startBluetooth: () -> Void = {}
    var isEnabled: (Perm) -> Bool = { _ in false }
    var enable: (Perm) -> Void = { _ in }
    /// Vault in ephemeral mode (pairings not saved) and its Repair (asks for confirmation itself).
    var keychainEphemeral: () -> Bool = { false }
    var repairKeychain: () -> Void = {}
    /// The one interactive read is running ("Reading Keychain…"), and the non-destructive "Try reading again".
    var keychainReading: () -> Bool = { false }
    var retryKeychain: () -> Void = {}
}

/// Live model behind the Setup window: re-reads every permission once a second while it is open.
final class SetupModel: ObservableObject {
    @Published private(set) var rows: [PermRow] = []
    @Published private(set) var keychainEphemeral = false
    @Published private(set) var keychainReading = false
    @Published var launchAtLogin = LoginItem.status == .enabled
    @Published var loginNeedsApproval = LoginItem.status == .requiresApproval
    private(set) var requested: Set<Perm> = []
    let actions: SetupActions
    var onChange: (() -> Void)?

    init(actions: SetupActions) {
        self.actions = actions
        refresh()
    }

    var perms: [Perm] { Perm.allCases.filter { $0 != .localNetwork || PermProbe.hasLocalNetworkPrivacy } }

    func refresh() {
        let st: [Perm: PermState] = actions.snapshot.map { Diag.states(fromSnapshot: $0()) } ?? PermProbe.all()
        let enabled = Set(Perm.allCases.filter(actions.isEnabled))
        let new = PermissionModel.rows(states: st, enabled: enabled, requested: requested, perms: perms)
        if new != rows { rows = new; onChange?() }
        let eph = actions.keychainEphemeral()
        if eph != keychainEphemeral { keychainEphemeral = eph }
        let rd = actions.keychainReading()
        if rd != keychainReading { keychainReading = rd }
        let ls = LoginItem.status
        if (ls == .enabled) != launchAtLogin { launchAtLogin = ls == .enabled }
        if (ls == .requiresApproval) != loginNeedsApproval { loginNeedsApproval = ls == .requiresApproval }
    }

    /// The row's button. Every macOS prompt the helper shows starts here (or at a feature toggle).
    func act(_ r: PermRow) {
        requested.insert(r.perm)
        ev(.ui, "setup.button", ["perm": r.perm.rawValue, "status": "\(r.status)", "state": PermProbe.name(r.state)])
        switch (r.perm, r.status) {
        case (.accessibility, _):
            // The prompt also adds Mote Helper to the Accessibility list, so the user only flips the switch.
            _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
            NSWorkspace.shared.open(r.perm.settingsURL)
        case (.bluetooth, _):
            if r.state == .notDetermined { actions.startBluetooth() } else { NSWorkspace.shared.open(r.perm.settingsURL) }
        case (.screenRecording, .optional), (.automation, .optional):
            actions.enable(r.perm)   // turns the feature on; it asks macOS in context
        case (.screenRecording, _):
            if !CGRequestScreenCaptureAccess() { NSWorkspace.shared.open(r.perm.settingsURL) }
        default:
            NSWorkspace.shared.open(r.perm.settingsURL)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.refresh() }
    }

    func setLaunchAtLogin(_ on: Bool) {
        do { try LoginItem.set(on) }
        catch { log("launch at login: \(error)") }
        refresh()
    }

    static func relaunch() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "sleep 1; /usr/bin/open -n \"$0\" --args --setup", Bundle.main.bundlePath]
        try? p.run()
        NSApp.terminate(nil)
    }
}

/// Design tokens (docs/design/design-language.md, "Graphite" dark / "Putty" light).
private enum Tok {
    static func dyn(_ dark: UInt32, _ light: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { a in
            let v = a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat(v >> 16 & 0xff) / 255, green: CGFloat(v >> 8 & 0xff) / 255, blue: CGFloat(v & 0xff) / 255, alpha: 1)
        })
    }
    static let bg = dyn(0x15161A, 0xF4F2EE)
    static let surface = dyn(0x1B1D22, 0xFFFFFF)
    static let onSurface = dyn(0xECEAE6, 0x1B1C1F)
    static let muted = dyn(0xA9A7A2, 0x5C5A55)
    static let accent = dyn(0xFF8A3D, 0xB34700)
    static let onAccent = dyn(0x2A1200, 0xFFFFFF)
    static let ok = dyn(0x5BD68A, 0x1E7A45)
}

struct SetupView: View {
    @ObservedObject var model: SetupModel
    var close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Set up Mote Helper").font(.system(size: 20, weight: .semibold)).foregroundColor(Tok.onSurface)
                Text(headline).font(.system(size: 13)).foregroundColor(Tok.muted)
            }
            VStack(spacing: 8) {
                ForEach(model.rows, id: \.perm) { row($0) }
                if model.keychainEphemeral { keychainRow }
            }
            Toggle("Open Mote Helper at login", isOn: Binding(get: { model.launchAtLogin }, set: { model.setLaunchAtLogin($0) }))
                .toggleStyle(.switch).tint(Tok.accent).font(.system(size: 13)).foregroundColor(Tok.onSurface)
            if model.loginNeedsApproval {
                HStack {
                    Text(LoginItem.caption(.requiresApproval) ?? "").font(.system(size: 12)).foregroundColor(Tok.muted)
                    Button("Open Login Items") { LoginItem.openApproval() }
                }
            }
            HStack {
                if model.rows.contains(where: \.relaunch) {
                    Button("Relaunch helper") { SetupModel.relaunch() }
                        .help("Screen Recording takes effect after Mote Helper restarts")
                }
                Spacer()
                Button(ready ? "Done" : "Later", action: close).keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 500)
        .background(Tok.bg)
    }

    private var keychainRow: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Keychain").font(.system(size: 14, weight: .semibold)).foregroundColor(Tok.onSurface)
                Text(model.keychainReading ? "Reading Keychain…" : "Pairings aren't being saved: Keychain unavailable. Every phone needs your Allow until this is repaired.")
                    .font(.system(size: 12)).foregroundColor(Tok.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            if !model.keychainReading {
                Button("Try reading Keychain again") { model.actions.retryKeychain() }.buttonStyle(AccentButton())
                Button("Repair") { model.actions.repairKeychain() }.buttonStyle(AccentButton())
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Tok.surface))
    }

    private var ready: Bool { !model.rows.contains { $0.perm.tier == .required && $0.status != .ok } }

    private var headline: String {
        ready ? "You're set. Open Mote on your phone and tap this Mac."
              : "Mote needs Accessibility to control this Mac. Everything else is optional."
    }

    @ViewBuilder private func row(_ r: PermRow) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(r.perm.title).font(.system(size: 14, weight: .semibold)).foregroundColor(Tok.onSurface)
                    Text(tierLabel(r.perm.tier)).font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundColor(Tok.muted)
                }
                Text(r.perm.why).font(.system(size: 12)).foregroundColor(Tok.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            trailing(r)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Tok.surface))
    }

    private func tierLabel(_ t: Perm.Tier) -> String {
        switch t { case .required: return "REQUIRED"; case .recommended: return "RECOMMENDED"; case .optional: return "OPTIONAL" }
    }

    @ViewBuilder private func trailing(_ r: PermRow) -> some View {
        switch r.status {
        case .ok where r.perm == .localNetwork:
            // Inferred from our own Bonjour registration, so say "probably" and why (owner decision U7).
            VStack(alignment: .trailing, spacing: 2) {
                Label(PermissionModel.localNetworkLabel(r.status), systemImage: "checkmark.circle.fill").labelStyle(.titleAndIcon)
                    .font(.system(size: 12, weight: .medium)).foregroundColor(Tok.ok)
                Text(PermissionModel.localNetworkNote).font(.system(size: 11)).foregroundColor(Tok.muted)
                    .multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true).frame(maxWidth: 150, alignment: .trailing)
            }
        case .ok:
            Label("Allowed", systemImage: "checkmark.circle.fill").labelStyle(.titleAndIcon)
                .font(.system(size: 12, weight: .medium)).foregroundColor(Tok.ok)
        case .grant:
            Button(r.state == .denied ? "Open Settings" : "Grant") { model.act(r) }
                .buttonStyle(AccentButton())
        case .optional:
            Button("Enable") { model.act(r) }
        case .check where r.perm == .localNetwork:
            VStack(alignment: .trailing, spacing: 2) {
                Text(PermissionModel.localNetworkLabel(r.status)).font(.system(size: 12, weight: .medium)).foregroundColor(Tok.muted)
                Text(PermissionModel.localNetworkNote).font(.system(size: 11)).foregroundColor(Tok.muted)
                    .multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true).frame(maxWidth: 150, alignment: .trailing)
            }
        case .check:
            // macOS has no API for this permission: say so instead of offering a button that can't change anything.
            Text("Can't check: macOS doesn't report this").font(.system(size: 11)).foregroundColor(Tok.muted)
                .multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true).frame(maxWidth: 150, alignment: .trailing)
        }
    }
}

private struct AccentButton: ButtonStyle {
    func makeBody(configuration c: Configuration) -> some View {
        c.label.font(.system(size: 12, weight: .semibold)).foregroundColor(Tok.onAccent)
            .padding(.horizontal, 14).padding(.vertical, 5)
            .background(Capsule().fill(Tok.accent.opacity(c.isPressed ? 0.85 : 1)))
    }
}

/// The window. Opens at first launch (and after an upgrade to this version), whenever Accessibility
/// is missing, and from the menu ("Setup…").
final class SetupWindow: NSObject, NSWindowDelegate {
    static let version = 1   // bump to show the window once more after an upgrade
    let model: SetupModel
    private var window: NSWindow?
    private var timer: Timer?

    init(actions: SetupActions) {
        model = SetupModel(actions: actions)
        super.init()
    }

    var isOpen: Bool { window != nil }

    func show() {
        if window == nil {
            let host = NSHostingController(rootView: SetupView(model: model) { [weak self] in self?.window?.close() })
            let w = NSWindow(contentViewController: host)
            w.title = "Mote Helper Setup"
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            w.delegate = self
            w.center()
            window = w
        }
        model.refresh()
        ev(.ui, "setup.shown", ["rows": model.rows.map { "\($0.perm.rawValue)=\($0.status)" }])
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.model.refresh() }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        UserDefaults.standard.set(SetupWindow.version, forKey: "setupShown")
    }

    func windowWillClose(_ n: Notification) {
        ev(.ui, "setup.closed")
        timer?.invalidate(); timer = nil
        window = nil
    }

    /// First launch of this setup version, or a required permission missing.
    static func shouldAutoOpen(force: Bool) -> Bool {
        force || UserDefaults.standard.integer(forKey: "setupShown") < version
            || PermissionModel.needsSetup([.accessibility: PermProbe.accessibility])
    }
}
