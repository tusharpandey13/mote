import AppKit
import Foundation
import Security

/// The diagnostics snapshot (protocol 1.5 `diag`, `MoteHelper --diag`, menu › Diagnostics…).
/// `Diag.build` is pure (input struct in, JSON out) so the unit tests can feed it any combination of OS answers.
/// Every field is a fact the helper read without UI or a conclusion drawn from facts; never a secret.

struct Feat: Equatable {
    var state: String            // ok | blocked | off | unknown
    var blockedBy: String? = nil // code, see docs/protocol.md "blockedBy codes"
    var fix: String? = nil       // code, see "fix codes"
    var detail: String? = nil    // short human sentence
    var json: [String: Any] {
        ["state": state, "blockedBy": blockedBy ?? NSNull(), "fix": fix ?? NSNull(), "detail": detail ?? NSNull()]
    }
    static func ok(_ d: String? = nil) -> Feat { Feat(state: "ok", detail: d) }
    static func off(_ d: String? = nil, fix: String? = nil) -> Feat { Feat(state: "off", fix: fix, detail: d) }
    static func unknown(_ why: String, by: String? = nil) -> Feat { Feat(state: "unknown", blockedBy: by, detail: why) }
    static func blocked(_ by: String, fix: String, _ d: String? = nil) -> Feat { Feat(state: "blocked", blockedBy: by, fix: fix, detail: d) }
}

struct DiagSession {
    var deviceName = "", transport = "", authed = false, connectedS = 0
    var clientProto: String? = nil, clientApp: String? = nil
    var spaces = false, context = false, nowPlaying = false, bridge = false, diag = false, liveScreenViewer = false
}

struct DiagInput {
    var source = "ws"                     // cli | ws | menu
    var at = Int64(Date().timeIntervalSince1970 * 1000)
    // helper
    var version = "dev", pid = Int(getpid()), uptimeS = 0, macOS = "", arch = "", hostId = ""
    var dryRun = false, headless = false, memoryStore = false, port = 8790
    var signing = SigningInfo()
    var perms: [Perm: PermCheck] = [:]
    // runtime state; nil `running` = this process is not the running helper (--diag), so these are unknown
    var running = true
    var paused = false, inputReady = true
    var listenerState = "none", listenerError: String? = nil, listenerPort: Int? = nil, connections = 0, accepted = 0, refused = 0
    var bonjour = "off", bonjourErrorCode: Int? = nil, noBonjour = false
    var bleEnabled = true, bleStatus: String? = nil, bleAdvertising = false
    var tailscaleAllowed = false, tailnetName: String? = nil, tailnetAddress: String? = nil
    var keychain = KeychainInfo()
    var sessions: [DiagSession] = []
    var shareThumbnails = false
    var liveScreenLab = false, liveScreenStreaming = false, liveScreenViewer: String? = nil
    var contextLab = false, nowPlayingOn = true, nowPlayingStatus = "off", tvLab = false, tvOn = false, bridgeLab = false, bridgeStatus = "off", bridgeConnected = false
    var instantPairing = false
    var update: [String: Any]? = nil
    var events = (head: 0, count: 0)
}

struct KeychainInfo {
    var service = "dev.mote.helper", account = "", memoryOnly = false, otherAccounts: [String] = []
    var readStatus: Int? = nil, readResult = "memory", writeStatus: Int? = nil
    var itemCount: Int? = nil
    var partitionAtWrite: String? = nil
    var ephemeral = false, ephemeralReason: String? = nil, interactiveRead = "none"
}

enum Diag {
    static let snapshotVersion = 1
    static let errSecItemNotFound = -25300, errSecInteractionNotAllowed = -25308, errSecAuthFailed = -25293

    /// Local Network privacy has no API. Inferred from the Bonjour registration of our own listener:
    /// PolicyDenied (-65570) = denied; registered = allowed; anything else = can't tell.
    static func localNetwork(_ i: DiagInput) -> (state: PermState, why: String) {
        guard PermProbe.hasLocalNetworkPrivacy else { return (.unknown, "Local Network privacy does not exist before macOS 15") }
        guard i.running else { return (.unknown, "not the running helper: no Bonjour listener to learn from") }
        if i.bonjourErrorCode == WebServer.policyDenied { return (.denied, "Bonjour registration failed with PolicyDenied (-65570)") }
        if i.bonjour == "registered" { return (.granted, "Bonjour registration succeeded") }
        if i.noBonjour || i.bonjour == "off" { return (.unknown, "Bonjour is off, nothing to infer from") }
        return (.unknown, "Bonjour registration is \(i.bonjour); no verdict yet")
    }

    /// Effective permission states for the Setup window and the features (Local Network is inferred, the rest are raw API answers).
    static func permStates(_ i: DiagInput) -> [Perm: PermState] {
        var s = Dictionary(uniqueKeysWithValues: Perm.allCases.map { ($0, i.perms[$0]?.state ?? .unknown) })
        s[.localNetwork] = localNetwork(i).state
        return s
    }

    static func permFeat(_ p: Perm, _ st: PermState, enabled: Bool) -> Feat {
        let code = "\(p.rawValue)-not-granted"
        switch (st, p) {
        case (.granted, _): return .ok()
        case (.unknown, _): return .unknown(p == .localNetwork ? "macOS has no API for this" : "no app to ask yet", by: nil)
        case (_, .accessibility): return .blocked(code, fix: "grant-accessibility")
        case (_, .bluetooth): return st == .denied ? .blocked(code, fix: "open-bluetooth-settings") : .blocked(code, fix: "grant-bluetooth")
        case (_, .localNetwork): return .blocked("local-network-denied", fix: "open-local-network-settings")
        case (_, .screenRecording): return enabled ? .blocked(code, fix: "grant-screen-recording") : .off("no feature uses it yet", fix: "grant-screen-recording")
        case (_, .automation): return enabled ? .blocked(code, fix: st == .denied ? "open-automation-settings" : "grant-automation") : .off("Labs › Context is off")
        }
    }

    static func build(_ i: DiagInput) -> [String: Any] {
        let st = permStates(i)
        let ln = localNetwork(i)
        var f: [String: Feat] = [:]
        let live = i.running

        // input
        if i.dryRun && !PermProbe.fakeAxDenied { f["input"] = .ok("dry run: input is logged, never injected") }
        else if st[.accessibility] != .granted { f["input"] = .blocked("accessibility-not-granted", fix: "grant-accessibility") }
        else if live && i.paused { f["input"] = .blocked("input-paused", fix: "resume-input") }
        else { f["input"] = .ok() }

        // keychain
        let k = i.keychain
        if k.memoryOnly { f["keychain"] = .off("memory store: nothing is kept between runs") }
        else if k.ephemeral && k.ephemeralReason == "reading" { f["keychain"] = .unknown("Reading Keychain…") }
        else if k.ephemeral && k.ephemeralReason == "write-failed" { f["keychain"] = .blocked("keychain-write-failed", fix: "repair-keychain", "Pairings aren't being saved: writing \(k.account) failed (status \(k.writeStatus ?? 0)); nothing is trusted automatically this run") }
        else if k.readResult == "unreadable" { f["keychain"] = .blocked("keychain-corrupt", fix: "repair-keychain", "Pairings aren't being saved: \(k.account) is corrupt") }
        else if k.readResult == "error" || k.ephemeral { f["keychain"] = .blocked("keychain-unreadable", fix: "repair-keychain", "Pairings aren't being saved: Keychain unavailable (read returned \(k.readStatus ?? 0), interactive read \(k.interactiveRead)); nothing is trusted automatically this run") }
        else if k.readResult == "found" || k.readResult == "notfound" { f["keychain"] = .ok(k.readResult == "found" ? "\(k.account) read" : "\(k.account) created") }
        else { f["keychain"] = .unknown("not read in this process") }

        // wifi / bonjour
        if !live { f["wifi"] = .unknown("this is not the running helper; ask it with the diag message or the Diagnostics window"); f["bonjour"] = f["wifi"] }
        else {
            switch i.listenerState {
            case "ready": f["wifi"] = .ok("listening on :\(i.listenerPort ?? i.port)")
            case "waiting", "failed":
                if i.bonjourErrorCode == WebServer.policyDenied { f["wifi"] = .blocked("local-network-denied", fix: "open-local-network-settings", "listener \(i.listenerState): \(i.listenerError ?? "")") }
                else { f["wifi"] = .blocked("listener-\(i.listenerState)", fix: i.listenerState == "failed" ? "free-port" : "restart-helper", i.listenerError) }
            case "none": f["wifi"] = .off("web server not started (no web root)")
            default: f["wifi"] = .unknown("listener is \(i.listenerState)")
            }
            if i.noBonjour { f["bonjour"] = .off("--no-bonjour") }
            else if i.bonjour == "registered" { f["bonjour"] = .ok() }
            else if i.bonjourErrorCode == WebServer.policyDenied { f["bonjour"] = .blocked("local-network-denied", fix: "open-local-network-settings", "PolicyDenied (-65570)") }
            else if i.bonjour == "failed" { f["bonjour"] = .blocked("bonjour-failed", fix: "restart-helper", "DNS-SD error \(i.bonjourErrorCode ?? 0)") }
            else { f["bonjour"] = .unknown("Bonjour is \(i.bonjour)") }
        }

        // bluetooth
        if !live {
            let pf = permFeat(.bluetooth, st[.bluetooth] ?? .unknown, enabled: true)
            f["bluetooth"] = pf.state == "ok" ? .unknown("permission granted; advertising needs the running helper") : pf
        }
        else if !i.bleEnabled { f["bluetooth"] = .off("--no-ble") }
        else if st[.bluetooth] == .denied { f["bluetooth"] = .blocked("bluetooth-denied", fix: "open-bluetooth-settings") }
        else if st[.bluetooth] != .granted { f["bluetooth"] = .blocked("bluetooth-not-granted", fix: "grant-bluetooth") }
        else if i.bleAdvertising { f["bluetooth"] = .ok("advertising") }
        else if let s = i.bleStatus, s.contains("off") { f["bluetooth"] = .blocked("bluetooth-off", fix: "turn-bluetooth-on", s) }
        else { f["bluetooth"] = .unknown(i.bleStatus ?? "not started") }

        // tailscale
        if !live { f["tailscale"] = .unknown("not the running helper") }
        else if !i.tailscaleAllowed { f["tailscale"] = .off("Allow Tailscale connections is off", fix: "enable-tailscale-setting") }
        else if i.tailnetName != nil || i.tailnetAddress != nil { f["tailscale"] = .ok(i.tailnetName ?? i.tailnetAddress) }
        else { f["tailscale"] = .off("allowed, but no tailnet address on this Mac") }

        // screen recording consumers
        func screenNeed(_ on: Bool, _ offDetail: String) -> Feat {
            if !on { return .off(offDetail, fix: "enable-lab") }
            switch st[.screenRecording] {
            case .granted?: return .ok()
            default: return .blocked("screen-recording-not-granted", fix: "grant-screen-recording",
                                     "CGPreflightScreenCaptureAccess=\(i.perms[.screenRecording]?.raw ?? "?")")
            }
        }
        f["spaceThumbnails"] = screenNeed(i.shareThumbnails, "Share Space thumbnails is off")
        f["labs.liveScreen"] = screenNeed(i.liveScreenLab, "Labs › Live screen is off")
        if !live { f["spaceThumbnails"] = .unknown("not the running helper"); f["labs.liveScreen"] = f["spaceThumbnails"] }

        // labs
        if !live { f["labs.context"] = .unknown("not the running helper"); f["labs.nowPlaying"] = f["labs.context"]; f["labs.tvMode"] = f["labs.context"]; f["labs.bridge"] = f["labs.context"] }
        else {
            if !i.contextLab { f["labs.context"] = .off("Labs › Context is off", fix: "enable-lab") }
            else {
                switch st[.automation] {
                case .granted?: f["labs.context"] = .ok()
                case .unknown?, nil: f["labs.context"] = .unknown("no scriptable browser is running, so Automation can't be read")
                default: f["labs.context"] = .blocked("automation-not-granted", fix: st[.automation] == .denied ? "open-automation-settings" : "grant-automation")
                }
            }
            f["labs.nowPlaying"] = i.nowPlayingOn ? (i.nowPlayingStatus == "off" ? .unknown("reader not running yet") : .ok("source: \(i.nowPlayingStatus)")) : .off("Share now playing is off in the menu", fix: "enable-lab")
            f["labs.tvMode"] = i.tvLab ? .ok(i.tvOn ? "TV mode is showing" : "enabled, not showing") : .off("Labs › TV mode is off", fix: "enable-lab")
            f["labs.bridge"] = !i.bridgeLab ? .off("Labs › Browser extension bridge is off", fix: "enable-lab") : i.bridgeConnected ? .ok("extension connected") : .ok("waiting for the browser extension")
        }

        var perms: [String: Any] = [:]
        for p in Perm.allCases {
            let c = i.perms[p]
            let enabled = p == .screenRecording ? (i.shareThumbnails || i.liveScreenLab) : p == .automation ? i.contextLab : true
            var m = permFeat(p, st[p] ?? .unknown, enabled: enabled).json
            m["api"] = c?.api ?? "none"; m["raw"] = c?.raw ?? "not checked"
            m["checkedAt"] = c?.at ?? NSNull(); m["ms"] = c?.ms ?? NSNull()
            m["apiState"] = PermProbe.name(c?.state ?? .unknown)
            m["detail"] = c?.detail ?? [:]
            if p == .localNetwork { m["inferred"] = true; m["inferredFrom"] = ln.why; m["applies"] = PermProbe.hasLocalNetworkPrivacy }
            perms[p.rawValue] = m
        }

        let kc: [String: Any] = [
            "service": k.service, "account": k.account, "otherAccounts": k.otherAccounts, "memoryOnly": k.memoryOnly,
            "readStatus": k.readStatus.map { $0 as Any } ?? NSNull(), "readResult": k.readResult,
            "writeStatus": k.writeStatus.map { $0 as Any } ?? NSNull(), "itemCount": k.itemCount.map { $0 as Any } ?? NSNull(),
            "ephemeral": k.ephemeral, "ephemeralReason": k.ephemeralReason ?? NSNull(), "interactiveRead": k.interactiveRead,
            "partitionAtWrite": k.partitionAtWrite ?? NSNull(), "expectedPartition": i.signing.expectedPartition ?? NSNull(),
            "partitionMatchesSigning": (k.partitionAtWrite != nil && i.signing.expectedPartition != nil) ? (k.partitionAtWrite == i.signing.expectedPartition) as Any : NSNull(),
        ]

        let transports: [String: Any] = [
            "wifi": ["listening": live ? i.listenerState == "ready" : NSNull(), "state": live ? i.listenerState : "unknown", "port": i.listenerPort ?? NSNull(),
                     "error": i.listenerError ?? NSNull(), "connections": i.connections, "accepted": i.accepted, "refused": i.refused] as [String: Any],
            "bonjour": ["state": live ? i.bonjour : "unknown", "errorCode": i.bonjourErrorCode ?? NSNull(), "type": "_mote._tcp", "disabled": i.noBonjour] as [String: Any],
            "ble": ["enabled": i.bleEnabled, "status": i.bleStatus ?? NSNull(), "advertising": i.bleAdvertising] as [String: Any],
            "tailscale": ["allowed": i.tailscaleAllowed, "name": i.tailnetName ?? NSNull(), "address": i.tailnetAddress ?? NSNull()] as [String: Any],
        ]

        let sessions: [[String: Any]] = i.sessions.map { s in
            ["deviceName": s.deviceName, "transport": s.transport, "authed": s.authed, "connectedS": s.connectedS,
             "protocol": ["helper": Diag.protocolVersion, "client": s.clientProto ?? NSNull(), "clientApp": s.clientApp ?? NSNull()] as [String: Any],
             "subscriptions": ["spaces": s.spaces, "context": s.context, "nowPlaying": s.nowPlaying, "bridge": s.bridge, "diag": s.diag] as [String: Any],
             "liveScreenViewer": s.liveScreenViewer]
        }

        let labs: [String: Any] = [
            "liveScreen": ["enabled": i.liveScreenLab, "streaming": i.liveScreenStreaming, "viewer": i.liveScreenViewer ?? NSNull()] as [String: Any],
            "context": ["enabled": i.contextLab] as [String: Any],
            "nowPlaying": ["enabled": i.nowPlayingOn, "source": i.nowPlayingStatus] as [String: Any],
            "tvMode": ["enabled": i.tvLab, "showing": i.tvOn] as [String: Any],
            "bridge": ["enabled": i.bridgeLab, "status": i.bridgeStatus, "connected": i.bridgeConnected] as [String: Any],
            "spaceThumbnails": ["enabled": i.shareThumbnails] as [String: Any],
            "instantPairing": ["enabled": i.instantPairing] as [String: Any],
        ]

        return [
            "t": "diag", "v": snapshotVersion, "at": i.at, "source": i.source, "running": live,
            "helper": ["version": i.version, "protocol": protocolVersion, "pid": i.pid, "uptimeS": i.uptimeS, "dryRun": i.dryRun, "headless": i.headless,
                       "memoryStore": i.memoryStore, "port": i.port, "hostId": i.hostId, "macOS": i.macOS, "arch": i.arch] as [String: Any],
            "update": i.update ?? NSNull(),
            "features": f.mapValues(\.json),
            "signing": i.signing.json,
            "permissions": perms,
            "keychain": kc,
            "transports": transports,
            "sessions": sessions,
            "labs": labs,
            "events": ["head": i.events.head, "buffered": i.events.count, "capacity": Events.capacity] as [String: Any],
        ]
    }

    static let protocolVersion = "1.5"
    static let started = Date()

    // MARK: collecting

    /// Permission states as the snapshot reports them (Local Network is the inferred one).
    static func states(fromSnapshot s: [String: Any]) -> [Perm: PermState] {
        let perms = s["permissions"] as? [String: Any] ?? [:]
        var out: [Perm: PermState] = [:]
        for p in Perm.allCases {
            let m = perms[p.rawValue] as? [String: Any] ?? [:]
            if p == .localNetwork {
                switch m["state"] as? String { case "ok"?: out[p] = .granted; case "blocked"?: out[p] = .denied; default: out[p] = .unknown }
            } else {
                switch m["apiState"] as? String { case "granted"?: out[p] = .granted; case "denied"?: out[p] = .denied; case "notDetermined"?: out[p] = .notDetermined; default: out[p] = .unknown }
            }
        }
        return out
    }

    private static var cachedSigning: SigningInfo?
    private static var cachedCount: (at: Date, n: Int?)?

    static func baseInput(config: Config, source: String) -> DiagInput {
        var i = DiagInput()
        i.source = source
        i.version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "dev"
        i.uptimeS = Int(Date().timeIntervalSince(started))
        let v = ProcessInfo.processInfo.operatingSystemVersion
        i.macOS = "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
        #if arch(arm64)
        i.arch = "arm64"
        #else
        i.arch = "x86_64"
        #endif
        i.dryRun = config.dryRun; i.headless = config.headless; i.memoryStore = config.memoryStore; i.port = Int(config.port)
        if cachedSigning == nil { cachedSigning = SigningInfo.current() }
        i.signing = cachedSigning!
        // The Setup window polls once a second: its checks are not logged one by one (the change events still are).
        for p in Perm.allCases { i.perms[p] = PermProbe.check(p, force: source != "setup" && source != "running") }
        i.keychain.service = config.keychainService
        i.keychain.memoryOnly = config.memoryStore && !config.usesFakeKeychain
        i.bleEnabled = config.ble
        i.noBonjour = config.noBonjour
        if !config.memoryStore && !config.usesFakeKeychain {
            i.keychain.partitionAtWrite = UserDefaults.standard.string(forKey: Vault.partitionKey(config.keychainService))
            i.keychain.writeStatus = KeychainBackend.lastWriteStatus.map(Int.init)
            if let c = cachedCount, Date().timeIntervalSince(c.at) < 30 { i.keychain.itemCount = c.n }
            else { let n = keychainItemCount(service: config.keychainService); cachedCount = (Date(), n); i.keychain.itemCount = n }
        }
        return i
    }

    /// `--diag`: nothing is running here, so runtime sections are unknown. Lists the service's accounts by attributes only (no
    /// data, no ACL, no prompt, never a write): is our own account there, which other accounts exist.
    static func cliSnapshot(config: Config) -> [String: Any] {
        var i = baseInput(config: config, source: "cli")
        i.running = false
        i.hostId = UserDefaults.standard.string(forKey: "hostId") ?? ""
        i.tailscaleAllowed = UserDefaults.standard.object(forKey: "allowTailscale") as? Bool ?? false
        i.shareThumbnails = UserDefaults.standard.bool(forKey: "shareSpaceThumbnails")
        i.liveScreenLab = UserDefaults.standard.bool(forKey: "labsLiveScreen")
        i.contextLab = UserDefaults.standard.bool(forKey: "labs.context.enabled")
        i.nowPlayingOn = (UserDefaults.standard.object(forKey: "nowPlaying.enabled") as? Bool) ?? true
        i.tvLab = UserDefaults.standard.bool(forKey: "labs.tvMode")
        i.bridgeLab = UserDefaults.standard.bool(forKey: "labsBridge")
        if !config.memoryStore && !config.usesFakeKeychain {
            // Attributes only (the list call is ACL-free, never prompts): is OUR account there, and which others exist.
            let own = Vault.account(for: i.signing.expectedPartition)
            i.keychain.account = own ?? ""
            do {
                let es = try KeychainBackend().list(service: config.keychainService)
                i.keychain.otherAccounts = es.map(\.account).filter { $0 != own }.sorted()
                let found = es.contains { $0.account == own }
                i.keychain.readStatus = found ? 0 : Int(errSecItemNotFound)
                i.keychain.readResult = found ? "found" : "notfound"
            } catch let e as KeychainError {
                i.keychain.readStatus = Int(e.status); i.keychain.readResult = "error"
            } catch { i.keychain.readResult = "error" }
        }
        i.events = (Events.shared.head, Events.shared.count)
        return build(i)
    }

    static func keychainItemCount(service: String) -> Int? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecMatchLimit as String: kSecMatchLimitAll, kSecReturnAttributes as String: true,
                                kSecUseAuthenticationUI as String: kSecUseAuthenticationUIFail]
        var out: CFTypeRef?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st == errSecItemNotFound { return 0 }
        return st == errSecSuccess ? (out as? [Any])?.count : nil
    }

    static func snapshot(hub: Hub, web: WebServer?, ble: BLEServer?, source: String) -> [String: Any] {
        var i = baseInput(config: hub.config, source: source)
        i.hostId = hub.settings.hostId
        i.paused = hub.paused; i.inputReady = hub.inputReady
        i.keychain.account = hub.vault.ownAccount ?? ""
        i.keychain.otherAccounts = hub.vault.otherAccounts
        i.keychain.readStatus = hub.vault.readStatus.map(Int.init)
        i.keychain.readResult = hub.vault.readResult
        i.keychain.ephemeral = hub.vault.ephemeral
        i.keychain.ephemeralReason = hub.vault.ephemeralReason
        i.keychain.interactiveRead = hub.vault.interactiveRead
        if let w = web {
            i.listenerState = w.listenerState; i.listenerError = w.listenerError; i.listenerPort = w.port.map(Int.init)
            i.connections = w.openConnections; i.accepted = w.accepted; i.refused = w.refused
            i.bonjour = w.bonjour; i.bonjourErrorCode = w.bonjourErrorCode
        }
        i.bleStatus = ble?.status; i.bleAdvertising = ble?.isAdvertising ?? false
        i.tailscaleAllowed = hub.settings.allowTailscale
        i.tailnetName = hub.settings.allowTailscale ? Tailnet.name : nil
        i.tailnetAddress = hub.settings.allowTailscale ? Host.tailnetIPv4 : nil
        let ctx = hub.labs.compactMap { $0 as? LabsContext }.first
        i.sessions = hub.sessions.map { s in
            var d = DiagSession(deviceName: s.deviceName, transport: s.transport, authed: s.authed, connectedS: Int(Date().timeIntervalSince(s.openedAt)),
                                clientProto: s.clientProto, clientApp: s.clientApp)
            d.spaces = s.spacesSub; d.diag = s.diagSubscribed
            if let ctx { let sub = ctx.subscription(s); d.context = sub.ctx; d.nowPlaying = sub.np }
            d.bridge = labsBridge?.isSubscribed(s) ?? false
            d.liveScreenViewer = hub.liveScreen.viewer === s
            return d
        }
        i.shareThumbnails = hub.settings.shareSpaceThumbnails
        i.liveScreenLab = hub.settings.labsLiveScreen; i.liveScreenStreaming = hub.liveScreen.streaming; i.liveScreenViewer = hub.liveScreen.streaming ? hub.liveScreen.viewerName : nil
        i.contextLab = ctx?.enabled ?? false; i.nowPlayingOn = ctx?.nowPlayingEnabled ?? false; i.nowPlayingStatus = ctx?.nowPlayingStatus ?? "off"
        i.tvLab = hub.tv.enabled; i.tvOn = hub.tv.on
        if let b = labsBridge { i.bridgeLab = b.enabled; i.bridgeStatus = b.status; i.bridgeConnected = b.connected }
        i.instantPairing = hub.instantPairing.enabled
        i.update = updater?.json
        i.events = (Events.shared.head, Events.shared.count)
        return build(i)
    }

    // MARK: readable view

    static func text(_ s: [String: Any]) -> String {
        func d(_ k: String, _ m: [String: Any]) -> [String: Any] { (m[k] as? [String: Any]) ?? [:] }
        var out = ["Mote Helper diagnostics (protocol \(protocolVersion), snapshot v\(s["v"] ?? "?"), source \(s["source"] ?? "?"))"]
        let h = d("helper", s)
        out.append("Helper \(h["version"] ?? "?") on macOS \(h["macOS"] ?? "?") \(h["arch"] ?? "") pid \(h["pid"] ?? "?") up \(h["uptimeS"] ?? 0)s\(h["dryRun"] as? Bool == true ? " (dry run)" : "")")
        out.append("")
        out.append("FEATURES")
        for (k, v) in d("features", s).sorted(by: { $0.key < $1.key }) {
            let m = (v as? [String: Any]) ?? [:]
            var line = "  \(k.padding(toLength: 18, withPad: " ", startingAt: 0)) \(m["state"] ?? "?")"
            if let b = m["blockedBy"] as? String { line += "  blockedBy=\(b)" }
            if let fx = m["fix"] as? String { line += "  fix=\(fx)" }
            if let dt = m["detail"] as? String { line += "  (\(dt))" }
            out.append(line)
        }
        out.append("")
        out.append("PERMISSIONS (raw API answer)")
        for p in Perm.allCases {
            let m = d(p.rawValue, d("permissions", s))
            out.append("  \(p.title.padding(toLength: 18, withPad: " ", startingAt: 0)) \(m["state"] ?? "?")  \(m["api"] ?? "") -> \(m["raw"] ?? "")")
            if let w = m["inferredFrom"] as? String { out.append("      inferred: \(w)") }
            for (dk, dv) in (m["detail"] as? [String: String] ?? [:]).sorted(by: { $0.key < $1.key }) { out.append("      \(dk): \(dv)") }
        }
        let sg = d("signing", s)
        out.append("")
        out.append("SIGNING  \(sg["identity"] ?? "?")  team \(sg["teamId"] as? String ?? "none")  cdhash \(sg["cdhash"] as? String ?? "none")")
        out.append("  identifier \(sg["identifier"] as? String ?? "?")  authorities \((sg["authorities"] as? [String] ?? []).joined(separator: " > "))")
        out.append("  designated requirement: \(sg["designatedRequirement"] as? String ?? "none")")
        out.append("  quarantined \(sg["quarantined"] ?? "?")  translocated \(sg["translocated"] ?? "?")  path \(sg["bundlePath"] ?? "")")
        let k = d("keychain", s)
        out.append("")
        out.append("KEYCHAIN  \(k["service"] ?? "")/\(k["account"] ?? "")  read \(k["readResult"] ?? "?") (status \(k["readStatus"] ?? "-"))  items \(k["itemCount"] ?? "?")")
        out.append("  partition at write \(k["partitionAtWrite"] as? String ?? "unknown")  expected for this build \(k["expectedPartition"] as? String ?? "?")")
        let t = d("transports", s)
        out.append("")
        out.append("TRANSPORTS")
        for n in ["wifi", "bonjour", "ble", "tailscale"] { out.append("  \(n): \(d(n, t))") }
        let ss = s["sessions"] as? [[String: Any]] ?? []
        out.append("")
        out.append("SESSIONS (\(ss.count))")
        for x in ss { out.append("  \(x["deviceName"] ?? "?") via \(x["transport"] ?? "?")  protocol \(d("protocol", x))  subs \(d("subscriptions", x))") }
        out.append("")
        out.append("LABS")
        for (k, v) in d("labs", s).sorted(by: { $0.key < $1.key }) { out.append("  \(k): \(v)") }
        out.append("")
        out.append("EVENTS  head \(d("events", s)["head"] ?? 0), buffered \(d("events", s)["buffered"] ?? 0)")
        return out.joined(separator: "\n")
    }

    static func json(_ s: [String: Any], pretty: Bool = true) -> String {
        let d = (try? JSONSerialization.data(withJSONObject: s, options: pretty ? [.prettyPrinted, .sortedKeys] : [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: d, as: UTF8.self)
    }
}

/// Menu › Diagnostics…: a readable view of the snapshot with Refresh and Copy (the JSON).
final class DiagnosticsWindow: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private let textView = NSTextView()
    private var last: [String: Any] = [:]
    var provider: () -> [String: Any] = { [:] }

    func show() {
        if window == nil {
            let scroll = NSScrollView(frame: NSRect(x: 0, y: 40, width: 720, height: 520))
            scroll.hasVerticalScroller = true; scroll.autoresizingMask = [.width, .height]
            textView.isEditable = false
            textView.font = NSFont.monospacedSystemFont(ofSize: 11, weight: .regular)
            textView.autoresizingMask = [.width]
            scroll.documentView = textView
            let copy = NSButton(title: "Copy", target: self, action: #selector(copyJSON))
            let refresh = NSButton(title: "Refresh", target: self, action: #selector(reload))
            copy.frame = NSRect(x: 12, y: 8, width: 80, height: 26); refresh.frame = NSRect(x: 100, y: 8, width: 80, height: 26)
            let content = NSView(frame: NSRect(x: 0, y: 0, width: 720, height: 560))
            content.autoresizesSubviews = true
            content.addSubview(scroll); content.addSubview(copy); content.addSubview(refresh)
            let w = NSWindow(contentRect: content.frame, styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
            w.title = "Mote Helper Diagnostics"; w.contentView = content; w.isReleasedWhenClosed = false; w.delegate = self; w.center()
            window = w
        }
        reload()
        ev(.ui, "diagnostics.shown")
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    @objc private func reload() { last = provider(); textView.string = Diag.text(last) }
    @objc private func copyJSON() {
        reload()
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(Diag.json(last), forType: .string)
        ev(.ui, "diagnostics.copied", ["bytes": Diag.json(last).utf8.count])
    }
    func windowWillClose(_ n: Notification) { window = nil }
}
