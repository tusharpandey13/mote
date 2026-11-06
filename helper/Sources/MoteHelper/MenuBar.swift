import AppKit
import CoreImage
import ServiceManagement

/// Menu-bar UI: status, pairing (code + QR window), URLs, clients, pause, revoke, launch at login, quit.
final class MenuBar: NSObject, NSMenuDelegate, NSWindowDelegate {
    private let hub: Hub
    private let web: WebServer?
    private let ble: BLEServer?
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private var pairWindow: NSWindow?

    init(hub: Hub, web: WebServer?, ble: BLEServer?) {
        self.hub = hub; self.web = web; self.ble = ble
        super.init()
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        refresh()
    }

    var port: UInt16 { hub.config.port }
    var mdnsURL: String { "http://\(Host.localName).local:\(port)" }
    var ipURL: String? { Host.lanIPv4.first.map { "http://\($0):\(port)" } }

    /// Called whenever hub / transports change.
    func refresh() {
        let symbol = hub.paused ? "pause.circle" : !hub.inputReady ? "exclamationmark.triangle"
            : hub.authedSessions.isEmpty ? "dot.radiowaves.left.and.right" : "dot.radiowaves.left.and.right.fill"
        let img = NSImage(systemSymbolName: symbol, accessibilityDescription: "Mote Helper")
            ?? NSImage(systemSymbolName: "dot.radiowaves.left.and.right", accessibilityDescription: "Mote Helper")
        img?.isTemplate = true
        item.button?.image = img
        item.button?.toolTip = "Mote Helper"
        if !hub.pairing.isOpen, let w = pairWindow { pairWindow = nil; w.close() }
        if let menu = item.menu { menuNeedsUpdate(menu) }
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        func add(_ title: String, _ action: Selector? = nil, key: String = "", state: Bool? = nil) -> NSMenuItem {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
            i.target = self
            if action == nil { i.isEnabled = false }
            if let state { i.state = state ? .on : .off }
            menu.addItem(i)
            return i
        }
        let status = hub.paused ? "Paused: input is ignored" : !hub.inputReady ? "Needs Accessibility permission"
            : hub.config.dryRun ? "Ready (dry run: logging only)" : "Ready"
        _ = add("Mote Helper: \(status)")
        if !hub.inputReady { _ = add("Grant Accessibility…", #selector(openAX)) }
        menu.addItem(.separator())

        if let code = hub.pairing.code {
            _ = add("Pairing code: \(code.prefix(3)) \(code.suffix(3))")
            _ = add("Show pairing code and QR…", #selector(showPairing))
            _ = add("Stop pairing", #selector(stopPairing))
        } else {
            _ = add("Pair new device…", #selector(startPairing), key: "n")
        }
        menu.addItem(.separator())

        _ = add("Wi-Fi remote: \(mdnsURL)", #selector(copyURL(_:))).representedObject = mdnsURL
        if let ip = ipURL { _ = add("     or \(ip)", #selector(copyURL(_:))).representedObject = ip }
        _ = add("Wi-Fi: \(web?.status ?? "off")")
        _ = add("Bluetooth: \(ble?.status ?? "off")")
        menu.addItem(.separator())

        let clients = hub.authedSessions
        _ = add(clients.isEmpty ? "No connected devices" : "Connected devices:")
        for s in clients { _ = add("   \(s.deviceName) · \(s.transport)") }
        _ = add("Paired devices: \(hub.store.devices.count)")
        menu.addItem(.separator())

        _ = add("Pause input", #selector(togglePause), key: "p", state: hub.paused)
        _ = add("Revoke all devices…", #selector(revokeAll))
        _ = add("Launch at login", #selector(toggleLogin), state: SMAppService.mainApp.status == .enabled)
        menu.addItem(.separator())
        _ = add("Quit Mote Helper", #selector(quit), key: "q")
    }

    // MARK: actions

    @objc func openAX() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }

    @objc func copyURL(_ sender: NSMenuItem) {
        guard let s = sender.representedObject as? String else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
    }

    @objc func startPairing() { hub.pairing.open(); showPairing() }
    @objc func stopPairing() { hub.pairing.close() }
    @objc func togglePause() { hub.paused.toggle() }
    @objc func quit() { NSApp.terminate(nil) }

    @objc func revokeAll() {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Revoke all devices?"
        a.informativeText = "Every paired phone and browser is disconnected and has to pair again with a new code."
        a.addButton(withTitle: "Revoke All")
        a.addButton(withTitle: "Cancel")
        a.alertStyle = .warning
        if a.runModal() == .alertFirstButtonReturn { hub.revokeAll() }
    }

    @objc func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() }
            else { try SMAppService.mainApp.register() }
        } catch {
            let a = NSAlert(error: error)
            a.runModal()
        }
    }

    // MARK: pairing window

    @objc func showPairing() {
        guard let code = hub.pairing.code else { return }
        pairWindow?.close()
        let url = ipURL ?? mdnsURL
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 380, height: 520), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        w.title = "Pair a device with Mote"
        w.isReleasedWhenClosed = false
        w.level = .floating
        w.delegate = self

        func label(_ s: String, _ font: NSFont, _ color: NSColor = .labelColor) -> NSTextField {
            let l = NSTextField(wrappingLabelWithString: s)
            l.font = font; l.textColor = color; l.alignment = .center
            l.preferredMaxLayoutWidth = 320
            return l
        }
        let codeLabel = label("\(code.prefix(3)) \(code.suffix(3))", .monospacedDigitSystemFont(ofSize: 44, weight: .semibold))
        codeLabel.isSelectable = true
        let qr = NSImageView(image: qrImage("\(url)/#code=\(code)") ?? NSImage())
        qr.imageScaling = .scaleProportionallyUpOrDown
        qr.translatesAutoresizingMaskIntoConstraints = false
        qr.widthAnchor.constraint(equalToConstant: 220).isActive = true
        qr.heightAnchor.constraint(equalToConstant: 220).isActive = true
        let stack = NSStackView(views: [
            label("Enter this code in the Mote remote", .systemFont(ofSize: 13), .secondaryLabelColor),
            codeLabel,
            qr,
            label("Or scan with the phone camera (same Wi-Fi) to open \(url)", .systemFont(ofSize: 12), .secondaryLabelColor),
            label("Pairing closes after 5 minutes or once a device pairs. Only pair devices you own.", .systemFont(ofSize: 11), .tertiaryLabelColor),
        ])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: 24, left: 24, bottom: 24, right: 24)
        w.contentView = stack
        w.center()
        pairWindow = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ n: Notification) {
        guard (n.object as? NSWindow) === pairWindow else { return }
        pairWindow = nil
        hub.pairing.close()   // closing the window ends the pairing window too
    }

    private func qrImage(_ s: String) -> NSImage? {
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(s.utf8), forKey: "inputMessage")
        f.setValue("M", forKey: "inputCorrectionLevel")
        guard let ci = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: ci)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}
