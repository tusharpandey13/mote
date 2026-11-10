import AppKit
import ApplicationServices

let config = Config.parse(CommandLine.arguments)
let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menu-bar only (also LSUIElement in Info.plist)

let hub = Hub(config: config)
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
if PermProbe.bluetooth == .granted { startBLE() }

if !config.headless {
    menu = MenuBar(hub: hub, web: web, ble: ble)
    setup = SetupWindow(actions: SetupActions(
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
        }))
    menu?.openSetup = { setup?.show() }
    // No permission prompt at launch: the Setup window explains each one and asks on a click.
    if !config.dryRun && SetupWindow.shouldAutoOpen(force: config.openSetup) { setup?.show() }
}
hub.onChange = refresh
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
                    case "tailscale on": hub.settings.allowTailscale = true
                    case "tailscale off": hub.settings.allowTailscale = false
                    case let c: log("stdin-control: unknown command '\(c)'")
                    }
                }
            }
        }
    }
}

signal(SIGTERM) { _ in exit(0) }
signal(SIGINT) { _ in exit(0) }
app.run()
