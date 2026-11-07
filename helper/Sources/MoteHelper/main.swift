import AppKit
import ApplicationServices

let config = Config.parse(CommandLine.arguments)
let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menu-bar only (also LSUIElement in Info.plist)

let hub = Hub(config: config)
hub.labs.append(LabsContext(hub: hub))
log("Mote Helper starting\(config.dryRun ? " (dry run)" : "") on \(Host.localName).local:\(config.port)")

// Web root: --web-root, else the bundled copy of site/remote (+ design assets) in Resources/web.
let webRoot = config.webRoot ?? Bundle.main.resourceURL?.appendingPathComponent("web")
var web: WebServer?
if let webRoot {
    web = WebServer(hub: hub, root: webRoot)
    do { try web?.start() } catch { log("wifi: failed to start: \(error)"); web = nil }
}
labsBridge = Bridge(hub: hub)
let ble: BLEServer? = config.ble ? BLEServer(hub: hub) : nil

var menu: MenuBar?
if !config.headless {
    menu = MenuBar(hub: hub, web: web, ble: ble)
    if !config.dryRun && !AXIsProcessTrusted() {
        // Shows the system prompt that deep-links to Privacy & Security > Accessibility.
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
    }
}
let refresh: () -> Void = { menu?.refresh() }
hub.onChange = refresh
web?.onChange = { refresh(); hub.instantPairing.printInfoIfReady() }
hub.instantPairing.port = { web?.port ?? 0 }
hub.instantPairing.onToggle = { ble?.publish(); hub.instantPairing.printInfoIfReady(); refresh() }
var shareThumbs = hub.settings.shareSpaceThumbnails
hub.settings.onChange = {
    if !hub.settings.allowTailscale { web?.dropTailnetPeers() }
    if hub.settings.shareSpaceThumbnails != shareThumbs { shareThumbs = hub.settings.shareSpaceThumbnails; hub.spaces.shareToggled() }
    refresh()
}
if let n = config.fakeSpaces {
    if config.dryRun { log("fake spaces: \(n.map(String.init).joined(separator: ","))") } else { log("--fake-spaces needs --dry-run; ignoring it") }
}
log("hostId \(hub.settings.hostId), tailscale connections \(hub.settings.allowTailscale ? "allowed" : "refused")")
ble?.onChange = refresh

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
                    case "pause": hub.paused = true
                    case "resume": hub.paused = false
                    case let c where c.hasPrefix("bridge "): labsBridge?.stdin(c)
                    case let c where c.hasPrefix("display "): hub.spaces.fakeActivate(Int(c.dropFirst(8)) ?? 0)
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
