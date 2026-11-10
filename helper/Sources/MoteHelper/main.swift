import AppKit
import ApplicationServices

let config = Config.parse(CommandLine.arguments)
if let f = config.eventsFile { Events.shared.openFile(f) }
ev(.lifecycle, "start", ["dryRun": config.dryRun, "headless": config.headless, "memoryStore": config.memoryStore, "ble": config.ble,
                         "port": Int(config.port), "diag": config.diag])
// --diag: one JSON snapshot on stdout, then exit. Only no-UI calls: preflight permission checks, a no-UI Keychain attribute
// query, code-signing info. Never creates the Hub, the listener, Bluetooth or any window.
if config.diag {
    ev(.lifecycle, "diag.cli")
    print(Diag.json(DiagFile.cliOutput(config: config)))
    exit(0)
}
let signing = SigningInfo.current()
ev(.signing, signing.error == nil ? "identity" : "error", signing.json)
let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menu-bar only (also LSUIElement in Info.plist)

if !config.dryRun { LoginItem.reconcile() }
let hub = Hub(config: config)
updater = Updater(config: config)
var diagnostics: DiagnosticsWindow?
let labsContext = LabsContext(hub: hub)
hub.labs.append(labsContext)
log("Mote Helper starting\(config.dryRun ? " (dry run)" : "") on \(Host.localName).local:\(config.port)")

// Web root: --web-root, else the bundled copy of site/remote (+ design assets) in Resources/web.
let webRoot = config.webRoot ?? Bundle.main.resourceURL?.appendingPathComponent("web")
var web: WebServer?
if let webRoot {
    web = WebServer(hub: hub, root: webRoot)
    do { try web?.start() } catch { log("wifi: failed to start: \(error)"); web = nil }
}
labsBridge = Bridge(hub: hub)
// Bluetooth: creating the peripheral manager is what shows the macOS Bluetooth prompt, so it only
// starts at launch once Bluetooth is allowed; otherwise the Setup window's "Grant" starts it.
var ble: BLEServer?
var menu: MenuBar?
var setup: SetupWindow?
let refresh: () -> Void = { menu?.refresh(); setup?.model.refresh() }
func startBLE() {
    guard config.ble, ble == nil else { return }
    ble = BLEServer(hub: hub)
        menu?.ble = ble
}
hub.snapshotProvider = { Diag.snapshot(hub: hub, web: web, ble: ble, source: $0) }
var diagFile: DiagFile?
if let dir = DiagFile.dir(for: config) {
    diagFile = DiagFile(dir: dir) { hub.snapshot(source: "running") }
}
if PermProbe.check(.bluetooth, force: true).state == .granted { startBLE() }

if !config.headless {
    menu = MenuBar(hub: hub, web: web, ble: ble)
    diagnostics = DiagnosticsWindow()
    diagnostics?.provider = { hub.snapshot(source: "menu") }
    menu?.openDiagnostics = { diagnostics?.show() }
    setup = SetupWindow(actions: SetupActions(
        snapshot: { hub.snapshot(source: "setup") },
        startBluetooth: { startBLE() },
        isEnabled: { p in
            switch p {
            case .screenRecording: return hub.settings.shareSpaceThumbnails || hub.settings.labsLiveScreen
            case .automation: return labsContext.enabled
            default: return true
            }
        },
        enable: { p in
            switch p {
            case .screenRecording: menu?.setShareThumbnails(true)
            case .automation: labsContext.setEnabled(true)
            default: break
            }
        },
        keychainEphemeral: { hub.vault.ephemeral },
        repairKeychain: { KeychainRepair.confirmAndRepair(hub) },
        keychainReading: { hub.vault.reading },
        retryKeychain: { hub.retryKeychainRead() }))
    menu?.openSetup = { setup?.show() }
    // No permission prompt at launch: the Setup window explains each one and asks on a click.
    if !config.dryRun && SetupWindow.shouldAutoOpen(force: config.openSetup) { setup?.show() }
}
hub.onChange = refresh
updater?.onChange = refresh
updater?.start()
web?.onChange = { refresh(); hub.instantPairing.printInfoIfReady() }
hub.instantPairing.port = { web?.port ?? 0 }
hub.instantPairing.onNewDevice = { name, via, fp in menu?.showNewDevice(name: name, via: via, fp: fp) }
var shareThumbs = hub.settings.shareSpaceThumbnails
hub.settings.onChange = {
    if !hub.settings.allowTailscale { web?.dropTailnetPeers() }
    hub.liveScreen.labToggled()
    if hub.settings.shareSpaceThumbnails != shareThumbs { shareThumbs = hub.settings.shareSpaceThumbnails; hub.spaces.shareToggled() }
    refresh()
}
if let n = config.fakeSpaces {
    if config.dryRun { log("fake spaces: \(n.map(String.init).joined(separator: ","))") } else { log("--fake-spaces needs --dry-run; ignoring it") }
}
log("hostId \(hub.settings.hostId), tailscale connections \(hub.settings.allowTailscale ? "allowed" : "refused")")

func openPairing() {
    hub.pairing.open()
    if let code = hub.pairing.code { print("PAIRING CODE: \(code)"); fflush(stdout) }
    menu?.showPairing()
}
if config.openPairing || (!config.headless && hub.store.devices.isEmpty && !hub.instantPairing.enabled) { openPairing() }

// Test-only: stand in for the menu-bar actions in headless e2e tests. Dry-run only, so it can never drive real input.
if config.stdinControl {
    if !config.dryRun { log("--stdin-control needs --dry-run; ignoring it") } else {
        Thread.detachNewThread {
            while let line = readLine() {
                DispatchQueue.main.async {
                    switch line.trimmingCharacters(in: .whitespaces) {
                    case "pair": openPairing()
                    case "revoke": hub.revokeAll()
                    case "block": hub.instantPairing.blockLast()
                    case "unblock": hub.instantPairing.unblockLast()
                    case "pause": hub.paused = true
                    case "resume": hub.paused = false
                    case let c where c.hasPrefix("bridge "): labsBridge?.stdin(c)
                    case "screen-pause": hub.liveScreen.paused = true
                    case "screen-resume": hub.liveScreen.paused = false
                    case "screen-lab-off": hub.settings.labsLiveScreen = false
                    case "screen-lab-on": hub.settings.labsLiveScreen = true
                    case let c where c.hasPrefix("display "): hub.spaces.fakeActivate(Int(c.dropFirst(8)) ?? 0)
                    case let c where c.hasPrefix("displays "): hub.spaces.fakeReplug(String(c.dropFirst(9)))
                    case "nowplaying on": labsContext.setNowPlayingEnabled(true)
                    case "nowplaying off": labsContext.setNowPlayingEnabled(false)
                    case "updates on": updater?.enabled = true
                    case "updates off": updater?.enabled = false
                    // tests: the confirmed Repair / the retry; only against a simulated or non-default Keychain service
                    case "repair-keychain" where config.usesFakeKeychain || config.keychainService != "dev.mote.helper": hub.repairKeychain()
                    case "reread-keychain" where config.usesFakeKeychain || config.keychainService != "dev.mote.helper": hub.retryKeychainRead()
                    case "tailscale on": hub.settings.allowTailscale = true
                    case "tailscale off": hub.settings.allowTailscale = false
                    case let c: log("stdin-control: unknown command '\(c)'")
                    }
                }
            }
        }
    }
}

ev(.lifecycle, "ready", ["wifi": web != nil, "hostId": hub.settings.hostId])
diagFile?.start()
Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { _ in Events.shared.flushInput() }
signal(SIGTERM) { _ in ev(.lifecycle, "terminate", ["signal": "TERM"]); exit(0) }
signal(SIGINT) { _ in ev(.lifecycle, "terminate", ["signal": "INT"]); exit(0) }
// The one interactive Keychain read (if due) runs after launch, off the main thread: the menu and listener are up meanwhile.
DispatchQueue.main.async { hub.vault.startInteractiveRead() }
app.run()
