import AppKit
import CoreImage
import ServiceManagement

/// Menu-bar UI. Default menu: status, devices, Pause input, Setup…, Quit. Everything else is under
/// Advanced (network, Space thumbnails, code pairing, approval, login item) or Labs (previews).
final class MenuBar: NSObject, NSMenuDelegate, NSWindowDelegate {
    private let hub: Hub
    private let web: WebServer?
    var ble: BLEServer? { didSet { refresh() } }
    var openSetup: (() -> Void)?
    var openDiagnostics: (() -> Void)?
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private var pairWindow: NSWindow?

    init(hub: Hub, web: WebServer?, ble: BLEServer?) {
        self.hub = hub; self.web = web; self.ble = ble
        super.init()
        let menu = NSMenu()
        menu.delegate = self
        item.menu = menu
        refresh()
        refreshTailnet()
    }

    func menuWillOpen(_ menu: NSMenu) { ev(.ui, "menu.opened"); refreshTailnet() }

    var port: UInt16 { hub.config.port }
    var mdnsURL: String { "http://\(Host.localName).local:\(port)" }
    var ipURL: String? { Host.lanIPv4.first.map { "http://\($0):\(port)" } }
    /// Tailnet address for the menu and the pairing QR: MagicDNS name if the CLI told us, else the 100.x IP.
    var tailnetHost: String? {
        guard hub.settings.allowTailscale else { return nil }
        return Tailnet.name ?? Host.tailnetIPv4
    }

    /// Pairing link: the web remote URL (any phone camera opens it), plus fields for the Android app.
    /// `#code=` is what site/remote reads; `id` = hostId, `ts` = tailnet address (only when allowed).
    func pairingURL(_ code: String) -> String {
        var s = "\(ipURL ?? mdnsURL)/#code=\(code)&id=\(hub.settings.hostId)"
        if let ts = tailnetHost { s += "&ts=\(ts)" }
        return s
    }

    /// Called whenever hub / transports change.
    func refresh() {
        let symbol = hub.paused ? "pause.circle" : !hub.inputReady ? "exclamationmark.triangle"
            : hub.authedSessions.isEmpty ? "dot.radiowaves.left.and.right" : "dot.radiowaves.left.and.right.fill"
        let img = NSImage(systemSymbolName: symbol, accessibilityDescription: "Mote Helper")
            ?? NSImage(systemSymbolName: "dot.radiowaves.left.and.right", accessibilityDescription: "Mote Helper")
        img?.isTemplate = true
        item.button?.image = img
        // Labs › Live screen: a visible indicator for as long as the screen is being streamed.
        item.button?.title = hub.liveScreen.streaming ? " ● Sharing screen" : ""
        item.button?.imagePosition = .imageLeading
        item.button?.toolTip = hub.liveScreen.streaming ? "Mote Helper: sharing the screen with \(hub.liveScreen.viewerName)" : "Mote Helper"
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
        func sub(_ m: NSMenu, _ title: String, _ action: Selector? = nil, state: Bool? = nil, obj: Any? = nil) {
            let i = NSMenuItem(title: title, action: action, keyEquivalent: "")
            i.target = self; i.representedObject = obj; i.isEnabled = action != nil
            if let state { i.state = state ? .on : .off }
            m.addItem(i)
        }
        func submenu(_ title: String, _ m: NSMenu) {
            let i = NSMenuItem(title: title, action: nil, keyEquivalent: ""); i.submenu = m; menu.addItem(i)
        }
        let status = hub.paused ? "Paused: input is ignored" : !hub.inputReady ? "Needs Accessibility"
            : hub.config.dryRun ? "Ready (dry run: logging only)" : "Ready"
        _ = add("Mote Helper: \(status)")
        if !hub.inputReady { _ = add("Finish setup…", #selector(showSetup)) }
        if hub.liveScreen.streaming {
            _ = add("● Sharing screen with \(hub.liveScreen.viewerName)")
            _ = add("Stop sharing screen", #selector(stopScreen))
        }
        menu.addItem(.separator())

        if let code = hub.pairing.code {   // the 6-digit code (Advanced › Pair with code), only while it is open
            _ = add("Pairing code: \(code.prefix(3)) \(code.suffix(3))")
            _ = add("Show pairing code and QR…", #selector(showPairing))
            _ = add("Stop pairing", #selector(stopPairing))
            menu.addItem(.separator())
        }

        let clients = hub.authedSessions
        _ = add(clients.isEmpty ? "No phone connected" : "Connected:")
        for s in clients { _ = add("   \(s.deviceName) · \(s.transport)") }
        // Devices: trusted phones (Block each), blocked phones (Unblock each), code-paired count, Revoke all.
        let ip = hub.instantPairing!
        let devices = NSMenu()
        if ip.enabled {
            sub(devices, ip.trusted.isEmpty ? "No trusted phones" : "Trusted phones")
            for k in ip.trusted {
                let via = k.via == "nearby" ? "Bluetooth proximity" : k.via == "tailscale" ? "Tailscale" : "approved"
                sub(devices, "   \(k.name) · \(via)")
                sub(devices, "      Block", #selector(blockDevice(_:)), obj: k.fp)
            }
            if !ip.blocked.isEmpty {
                devices.addItem(.separator())
                sub(devices, "Blocked")
                for b in ip.blocked {
                    sub(devices, "   \(b.name) · \(b.fp.prefix(4)) \(b.fp.dropFirst(4).prefix(4))")
                    sub(devices, "      Unblock", #selector(unblockDevice(_:)), obj: b.fp)
                }
            }
            devices.addItem(.separator())
        }
        if !hub.store.devices.isEmpty { sub(devices, "Paired with a code: \(hub.store.devices.count)") }
        sub(devices, "Revoke all devices…", #selector(revokeAll))
        submenu("Devices (\(ip.enabled ? ip.trustedCount : hub.store.devices.count))\(ip.blocked.isEmpty ? "" : " · \(ip.blocked.count) blocked")", devices)
        menu.addItem(.separator())

        _ = add("Pause input", #selector(togglePause), key: "p", state: hub.paused)
        _ = add("Setup…", #selector(showSetup), key: ",")
        _ = add("Diagnostics…", #selector(showDiagnostics))

        let adv = NSMenu()   // off the default path
        sub(adv, "Wi-Fi remote: \(mdnsURL)", #selector(copyURL(_:)), obj: mdnsURL)
        if let ip = ipURL { sub(adv, "     or \(ip)", #selector(copyURL(_:)), obj: ip) }
        sub(adv, "Wi-Fi: \(web?.status ?? "off")")
        sub(adv, "Bluetooth: \(ble?.status ?? (PermProbe.bluetooth == .granted ? "off" : "not allowed yet (Setup…)"))")
        sub(adv, "Allow Tailscale connections", #selector(toggleTailscale), state: hub.settings.allowTailscale)
        if let ts = tailnetHost {
            let u = "http://\(ts):\(port)"
            sub(adv, "     Tailscale: \(u)", #selector(copyURL(_:)), obj: u)
        } else if hub.settings.allowTailscale {
            sub(adv, "     Tailscale: not detected (is it running?)")
        }
        adv.addItem(.separator())
        let cap = hub.spaces.captureStatus
        sub(adv, "Share Space thumbnails", #selector(toggleShareThumbs), state: hub.settings.shareSpaceThumbnails)
        if hub.settings.shareSpaceThumbnails {
            if hub.config.dryRun && hub.config.fakeSpaces == nil { sub(adv, "     off in a dry run") }
            else if cap == "unavailable" { sub(adv, "     needs macOS 14") }
            else if cap == "denied" { sub(adv, "     Allow Screen Recording…", #selector(grantScreenRecording)) }
            sub(adv, "     Clear Space thumbnails", #selector(clearThumbs))
        }
        adv.addItem(.separator())
        if ip.enabled {
            sub(adv, "Require approval for new devices", #selector(toggleRequireApproval), state: ip.requireApproval)
            sub(adv, "This Mac's key: \(groupedFingerprint(ip.fp))")
        }
        sub(adv, "Pair with code (web remote)…", #selector(startPairing))
        sub(adv, "Open at login", #selector(toggleLogin), state: SMAppService.mainApp.status == .enabled)
        submenu("Advanced", adv)

        let labs = NSMenu()   // Labs (previews, off by default)
        sub(labs, "TV mode (preview)", #selector(toggleTvLab), state: hub.tv.enabled)
        if hub.tv.on { sub(labs, "Exit TV mode", #selector(exitTv)) }
        hub.labs.flatMap { $0.menuItems() }.forEach(labs.addItem)   // Labs modules (Context & now playing)
        if let b = labsBridge { labs.addItem(.separator()); addBridgeItems(to: labs, bridge: b) }
        // Labs › Live screen (docs/labs/live-screen.md)
        sub(labs, "Live screen (preview)", #selector(toggleLiveScreen), state: hub.settings.labsLiveScreen)
        if hub.settings.labsLiveScreen { sub(labs, "    Pause screen sharing", #selector(toggleScreenPause), state: hub.liveScreen.paused) }
        submenu("Labs", labs)
        menu.addItem(.separator())
        _ = add("Quit Mote Helper", #selector(quit), key: "q")
    }

    // MARK: actions

    @objc func showSetup() { ev(.ui, "menu.item", ["item": "setup"]); openSetup?() }
    @objc func showDiagnostics() { ev(.ui, "menu.item", ["item": "diagnostics"]); openDiagnostics?() }

    @objc func copyURL(_ sender: NSMenuItem) {
        guard let s = sender.representedObject as? String else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
    }

    @objc func startPairing() { ev(.ui, "menu.item", ["item": "startPairing"]); hub.pairing.open(); showPairing() }
    @objc func stopPairing() { ev(.ui, "menu.item", ["item": "stopPairing"]); hub.pairing.close() }
    @objc func togglePause() { ev(.ui, "menu.item", ["item": "togglePause"]); hub.paused.toggle() }
    @objc func blockDevice(_ sender: NSMenuItem) { ev(.ui, "menu.item", ["item": "blockDevice"]); if let fp = sender.representedObject as? String { hub.instantPairing.block(fp) } }
    @objc func unblockDevice(_ sender: NSMenuItem) { ev(.ui, "menu.item", ["item": "unblockDevice"]); if let fp = sender.representedObject as? String { hub.instantPairing.unblock(fp) } }
    @objc func toggleRequireApproval() { ev(.ui, "menu.item", ["item": "toggleRequireApproval"]); hub.instantPairing.requireApproval.toggle() }
    @objc func toggleTvLab() { ev(.ui, "menu.item", ["item": "toggleTvLab"]); hub.tv.enabled.toggle() }
    @objc func exitTv() { ev(.ui, "menu.item", ["item": "exitTv"]); hub.tv.exit() }
    @objc func toggleShareThumbs() { ev(.ui, "menu.item", ["item": "toggleShareThumbs"]); setShareThumbnails(!hub.settings.shareSpaceThumbnails) }

    /// A click (menu or Setup window): turning thumbnails on is where Screen Recording is asked for.
    func setShareThumbnails(_ on: Bool) {
        hub.settings.shareSpaceThumbnails = on
        if on { hub.spaces.requestPermission() }
    }
    @objc func clearThumbs() { ev(.ui, "menu.item", ["item": "clearThumbs"]); hub.spaces.clear() }
    @objc func toggleLiveScreen() { ev(.ui, "menu.item", ["item": "toggleLiveScreen"]);
        hub.settings.labsLiveScreen.toggle()
        if hub.settings.labsLiveScreen, !hub.config.dryRun, !CGPreflightScreenCaptureAccess() { _ = CGRequestScreenCaptureAccess() }
    }
    @objc func toggleScreenPause() { ev(.ui, "menu.item", ["item": "toggleScreenPause"]); hub.liveScreen.paused.toggle() }
    @objc func stopScreen() { ev(.ui, "menu.item", ["item": "stopScreen"]); hub.liveScreen.stop("stop") }
    @objc func grantScreenRecording() { ev(.ui, "menu.item", ["item": "grantScreenRecording"]); NSWorkspace.shared.open(Perm.screenRecording.settingsURL) }
    @objc func toggleTailscale() { ev(.ui, "menu.item", ["item": "toggleTailscale"]); hub.settings.allowTailscale.toggle(); refreshTailnet() }

    /// Re-reads the MagicDNS name in the background (Tailscale may start after the helper).
    func refreshTailnet() {
        guard hub.settings.allowTailscale else { return }
        Tailnet.refresh { [weak self] in self?.refresh() }
    }
    @objc func quit() { ev(.ui, "menu.item", ["item": "quit"]); NSApp.terminate(nil) }

    @objc func revokeAll() { ev(.ui, "menu.item", ["item": "revokeAll"]);
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Revoke all devices?"
        a.informativeText = "Every paired phone and browser is disconnected and has to pair again. Blocked phones are unblocked."
        a.addButton(withTitle: "Revoke All")
        a.addButton(withTitle: "Cancel")
        a.alertStyle = .warning
        if a.runModal() == .alertFirstButtonReturn { hub.revokeAll() }
    }

    @objc func toggleLogin() { ev(.ui, "menu.item", ["item": "toggleLogin"]);
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() }
            else { try SMAppService.mainApp.register() }
        } catch {
            let a = NSAlert(error: error)
            a.runModal()
        }
    }

    // MARK: new-device notice

    private var notice: NSPanel?
    private var noticeFp: String?
    private var noticeGen = 0

    /// Non-modal, non-activating notice for a newly trusted phone, top right, gone after 15 s. One Block button.
    func showNewDevice(name: String, via: String, fp: String) {
        ev(.ui, "newdevice.shown", ["via": via])
        notice?.close()
        let how = via == "nearby" ? "via Bluetooth proximity" : via == "tailscale" ? "via Tailscale" : "approved on this Mac"
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 320, height: 76),
                        styleMask: [.titled, .closable, .nonactivatingPanel, .utilityWindow, .hudWindow], backing: .buffered, defer: false)
        p.title = "Mote"
        p.isFloatingPanel = true
        p.level = .floating
        p.hidesOnDeactivate = false
        p.becomesKeyOnlyIfNeeded = true
        p.isReleasedWhenClosed = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        let title = NSTextField(labelWithString: "\(name) connected to Mote.")
        title.font = .systemFont(ofSize: 13, weight: .semibold)
        title.lineBreakMode = .byTruncatingTail
        let sub = NSTextField(labelWithString: how)
        sub.font = .systemFont(ofSize: 11)
        sub.textColor = .secondaryLabelColor
        let text = NSStackView(views: [title, sub])
        text.orientation = .vertical
        text.alignment = .leading
        text.spacing = 2
        let block = NSButton(title: "Block", target: self, action: #selector(blockNotice))
        block.bezelStyle = .rounded
        let row = NSStackView(views: [text, block])
        row.orientation = .horizontal
        row.alignment = .centerY
        row.spacing = 12
        row.edgeInsets = NSEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)
        text.setContentHuggingPriority(.defaultLow, for: .horizontal)
        block.setContentHuggingPriority(.required, for: .horizontal)
        p.contentView = row
        if let vf = NSScreen.main?.visibleFrame {
            p.setFrameTopLeftPoint(NSPoint(x: vf.maxX - p.frame.width - 12, y: vf.maxY - 12))
        }
        p.orderFrontRegardless()
        notice = p
        noticeFp = fp
        noticeGen += 1
        let gen = noticeGen
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, self.noticeGen == gen else { return }
            self.notice?.close(); self.notice = nil; self.noticeFp = nil
        }
    }

    @objc func blockNotice() { ev(.ui, "menu.item", ["item": "blockNotice"]);
        if let fp = noticeFp { hub.instantPairing.block(fp) }
        notice?.close(); notice = nil; noticeFp = nil
    }

    // MARK: pairing window

    @objc func showPairing() { ev(.ui, "menu.item", ["item": "showPairing"]);
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
        let qr = NSImageView(image: qrImage(pairingURL(code)) ?? NSImage())
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
