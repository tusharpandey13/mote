import AppKit
import ApplicationServices

let config = Config.parse(CommandLine.arguments)
let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menu-bar only (also LSUIElement in Info.plist)

let hub = Hub(config: config)
log("Mote Helper starting\(config.dryRun ? " (dry run)" : "") on \(Host.localName).local:\(config.port)")

// Web root: --web-root, else the bundled copy of site/remote (+ design assets) in Resources/web.
let webRoot = config.webRoot ?? Bundle.main.resourceURL?.appendingPathComponent("web")
var web: WebServer?
if let webRoot {
    web = WebServer(hub: hub, root: webRoot)
    do { try web?.start() } catch { log("wifi: failed to start: \(error)"); web = nil }
}
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
web?.onChange = refresh
ble?.onChange = refresh

if config.openPairing || (!config.headless && hub.store.devices.isEmpty) {
    hub.pairing.open()
    if let code = hub.pairing.code { print("PAIRING CODE: \(code)"); fflush(stdout) }
    menu?.showPairing()
}

signal(SIGTERM) { _ in exit(0) }
signal(SIGINT) { _ in exit(0) }
app.run()
