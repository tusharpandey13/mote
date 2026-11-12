import Foundation
import SystemConfiguration

/// Command-line options. The .app runs with none; tests use --dry-run --headless.
struct Config {
    var dryRun = false        // log input instead of injecting it
    var headless = false      // no menu-bar UI (tests)
    var openPairing = false   // open the pairing window at launch and print the code
    var openSetup = false     // --setup: open the Setup window at launch (install.sh, "Relaunch helper")
    var ble = true
    var port: UInt16 = 8790
    var keychainService = "dev.mote.helper"
    var secretsDir: URL? = nil // --secrets-dir DIR: keep the Vault's items as files in DIR, no Keychain, no prompt (tests; refused without --dry-run)
    var memoryStore = false   // keep paired devices in memory only (tests)
    var allowTailscale: Bool? = nil   // --allow-tailscale: force the toggle on for this run (tests)
    var webRoot: URL? = nil
    var stdinControl = false  // tests: read pair/revoke/pause/resume commands from stdin (needs --dry-run)
    var labsTv = false        // --labs-tv: force Labs › TV mode on for this run (tests)
    var fakeSpaces: [Int]? = nil // tests: simulate displays with N,M,... Spaces and generated thumbnails (needs --dry-run)
    var instantPairing: Bool? = nil    // instant pairing (docs/pairing.md) is on by default; --no-instant-pairing turns it off
                                       // for this run, --labs-pairing (old name, tests) forces it on
    var requireApproval = false        // --require-approval: force "Require approval for new devices" on (tests)
    var fakeApprove: Bool? = nil       // tests: --fake-approve (Allow) / --fake-approve-deny (Deny) every prompt (needs --dry-run)
    var fakeProximity = false          // tests: answer `labs.pair.prox` with the BLE proximity token (needs --dry-run)
    var fakeBtPair: String? = nil      // tests: `bt.pair` without real Bluetooth: "ok" (--fake-bt-pair) or a failure reason
                                       // (--fake-bt-pair-fail REASON) (needs --dry-run)
    var fakeTailscaleOwner: String? = nil // tests: every peer is a tailnet node owned by this ("self" = this Mac's owner) (needs --dry-run)
    var fakeContext: String? = nil // Labs tests: read the frontmost-app context from this JSON file (needs --dry-run)
    var fakeNowPlaying = false     // Labs tests: a synthetic now-playing session (needs --dry-run)
    var fakeAxDenied = false   // tests: Accessibility reads as not granted, even in --dry-run (U16; needs --dry-run)
    var updateURL: String? = nil     // tests: --update-url URL, the GitHub "latest release" endpoint stand-in (needs --dry-run)
    var updateCheckNow = false       // tests: --update-check-now ignores the once-a-day limit (needs --dry-run)
    var fakeScreen = false    // tests: Labs live screen streams synthetic frames of a fake display (needs --dry-run; turns the lab on)
    var fakeKeychain: FakeKeychainState? = nil // tests: simulate the Keychain accounts: found|notfound|partitionmismatch|denied|error (needs --dry-run)
    var recordSystem: String? = nil // --record-system FILE: write the real OS's answers (fixture JSON) and exit; no prompts, no UI
    var diag = false          // --diag: print the diagnostics snapshot (JSON) and exit; no prompts, no UI, no writes
    var dataDir: URL? = nil   // --data-dir DIR: where the running helper writes diag.json / diag-events.jsonl (default ~/Library/Application Support/dev.mote.helper; none with --memory-store)
    var eventsFile: String? = nil // --events FILE: append every event as one JSON line
    var noBonjour = false     // chaos: don't advertise _mote._tcp (stands in for blocked multicast; needs --dry-run)
    var fakeNowPlayingReader: String? = nil // chaos: run this executable as the now-playing reader instead of perl (needs --dry-run)

    /// `--fake-keychain` is honoured only with `--dry-run` (it replaces the real Keychain item with a simulation).
    var usesFakeKeychain: Bool { dryRun && fakeKeychain != nil }
    /// --secrets-dir is honoured only with --dry-run; main.swift exits before anything starts otherwise.
    var usesSecretsDir: Bool { dryRun && secretsDir != nil }
    var secretsDirRefused: Bool { secretsDir != nil && !dryRun }

    static func parse(_ args: [String]) -> Config {
        var c = Config()
        var it = args.dropFirst().makeIterator()
        while let a = it.next() {
            switch a {
            case "--dry-run": c.dryRun = true
            case "--headless": c.headless = true
            case "--open-pairing": c.openPairing = true
            case "--setup": c.openSetup = true
            case "--no-ble": c.ble = false
            case "--memory-store": c.memoryStore = true
            case "--allow-tailscale": c.allowTailscale = true
            case "--stdin-control": c.stdinControl = true
            case "--fake-screen": c.fakeScreen = true
            case "--fake-ax-denied": c.fakeAxDenied = true
            case "--update-url": c.updateURL = it.next()
            case "--update-check-now": c.updateCheckNow = true
            case "--labs-tv": c.labsTv = true
            case "--labs-pairing": c.instantPairing = true
            case "--no-instant-pairing": c.instantPairing = false
            case "--require-approval": c.requireApproval = true
            case "--fake-proximity": c.fakeProximity = true
            case "--fake-bt-pair": c.fakeBtPair = "ok"
            case "--fake-bt-pair-fail": if let v = it.next() { c.fakeBtPair = v }
            case "--fake-approve": c.fakeApprove = true
            case "--fake-approve-deny": c.fakeApprove = false
            case "--fake-tailscale-owner": if let v = it.next() { c.fakeTailscaleOwner = v }
            case "--fake-spaces":
                if let v = it.next() {
                    let ns = v.split(separator: ",").compactMap { Int($0) }.prefix(Spaces.maxDisplays).map { min(max($0, 1), Spaces.maxDesktops) }
                    if !ns.isEmpty { c.fakeSpaces = Array(ns) }
                }
            case "--fake-context": c.fakeContext = it.next()
            case "--fake-nowplaying": c.fakeNowPlaying = true
            case "--no-bonjour": c.noBonjour = true
            case "--fake-keychain": if let v = it.next() { c.fakeKeychain = FakeKeychainState(arg: v) }
            case "--diag": c.diag = true
            case "--record-system": c.recordSystem = it.next()
            case "--data-dir": if let v = it.next() { c.dataDir = URL(fileURLWithPath: v, isDirectory: true) }
            case "--events": c.eventsFile = it.next()
            case "--fake-nowplaying-reader": c.fakeNowPlayingReader = it.next()
            case "--port": if let v = it.next(), let p = UInt16(v) { c.port = p }
            case "--secrets-dir": if let v = it.next() { c.secretsDir = URL(fileURLWithPath: v, isDirectory: true) }
            case "--keychain-service": if let v = it.next() { c.keychainService = v }
            case "--web-root": if let v = it.next() { c.webRoot = URL(fileURLWithPath: v) }
            case "-h", "--help":
                print("""
                MoteHelper --record-system FILE   (write the OS's permission/Keychain/signing/discovery answers as a test fixture and exit; never prompts)
                MoteHelper --diag [--data-dir DIR]   (print the running helper's diagnostics snapshot, else this process's own, as JSON and exit; never prompts)
                MoteHelper [--setup] [--dry-run] [--headless] [--open-pairing] [--no-ble] [--port N]
                           [--memory-store | --secrets-dir DIR] [--keychain-service NAME] [--web-root DIR] [--allow-tailscale] [--events FILE] [--data-dir DIR]
                          [--stdin-control] [--fake-spaces N[,M...]] [--labs-tv] [--fake-context FILE] [--fake-nowplaying] [--fake-screen] [--fake-ax-denied] [--update-url URL] [--update-check-now]   (tests, need --dry-run; port 0 = any free port)
                          [--no-instant-pairing | --labs-pairing] [--require-approval]   (instant pairing: off / force on; approval prompts)
                          [--fake-proximity] [--fake-approve | --fake-approve-deny] [--fake-tailscale-owner ID|timeout]   (instant pairing tests, need --dry-run)
                          [--fake-bt-pair | --fake-bt-pair-fail REASON]   (Bluetooth HID setup without Bluetooth, needs --dry-run)
                          [--fake-keychain found|notfound|partitionmismatch|denied|error]   (simulated Keychain read, needs --dry-run)
                          [--no-bonjour] [--fake-nowplaying-reader EXE]   (chaos, need --dry-run)
                """)
                exit(0)
            default: break // ignore launch-services noise (-psn_..., -NS...)
            }
        }
        return c
    }
}

/// Persisted helper settings (UserDefaults). With --memory-store nothing is read or written,
/// so tests get a fresh random hostId and the defaults.
final class Settings {
    private let defaults: UserDefaults?
    /// Stable per-Mac id (16 hex chars), sent in `hello.hostId` and the Bonjour TXT `id`. Lets
    /// clients recognise this Mac across LAN IP, .local and tailnet addresses.
    let hostId: String
    var onChange: (() -> Void)?

    /// "Allow Tailscale connections": accept tailnet peers (100.64/10, fd7a:115c:a1e0::/48) and
    /// MagicDNS (*.ts.net) or tailnet-literal Host headers. Default OFF when never set (protocol.md section 4;
    /// before 0.7 an unset value meant on). Menu: "Allow Tailscale connections". --memory-store (tests): off unless --allow-tailscale.
    var allowTailscale: Bool {
        didSet {
            guard allowTailscale != oldValue else { return }
            defaults?.set(allowTailscale, forKey: "allowTailscale")
            log("tailscale connections \(allowTailscale ? "allowed" : "refused")")
            ev(.lifecycle, "setting", ["key": "allowTailscale", "on": allowTailscale])
            onChange?()
        }
    }

    /// "Share Space thumbnails": capture and serve Space thumbnails (protocol 1.3). Needs Screen Recording,
    /// so default off: turned on from the Setup window or Advanced, which ask for the permission then.
    /// --fake-spaces (tests) turns it on.
    var shareSpaceThumbnails: Bool {
        didSet {
            guard shareSpaceThumbnails != oldValue else { return }
            defaults?.set(shareSpaceThumbnails, forKey: "shareSpaceThumbnails")
            log("space thumbnails \(shareSpaceThumbnails ? "shared" : "not shared")")
            ev(.lifecycle, "setting", ["key": "shareSpaceThumbnails", "on": shareSpaceThumbnails])
            onChange?()
        }
    }

    /// Labs › "Live screen" (docs/labs/live-screen.md): stream the active display to an open phone viewer. Default off.
    var labsLiveScreen: Bool {
        didSet {
            guard labsLiveScreen != oldValue else { return }
            defaults?.set(labsLiveScreen, forKey: "labsLiveScreen")
            log("labs: live screen \(labsLiveScreen ? "on" : "off")")
            ev(.labs, "toggle", ["lab": "liveScreen", "on": labsLiveScreen])
            onChange?()
        }
    }

    init(config: Config) {
        defaults = config.memoryStore || config.usesSecretsDir ? nil : UserDefaults.standard
        let idFile = config.usesSecretsDir ? config.secretsDir?.appendingPathComponent("hostId") : nil   // --secrets-dir: hostId survives restarts like the Keychain items
        if let f = idFile, let id = (try? String(contentsOf: f, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines), id.count == 16 {
            hostId = id
        } else if let f = idFile {
            hostId = randomHex(8)
            try? FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? hostId.write(to: f, atomically: true, encoding: .utf8)
        } else if let id = defaults?.string(forKey: "hostId"), id.count == 16 {
            hostId = id
        } else {
            hostId = randomHex(8)
            defaults?.set(hostId, forKey: "hostId")
        }
        let storedTs = defaults?.object(forKey: "allowTailscale") as? Bool
        allowTailscale = config.allowTailscale ?? storedTs ?? false
        if config.allowTailscale == nil, defaults != nil, storedTs == nil {
            // Unset (never chosen): off since 0.7. Recorded so an upgraded user can see why tailnet phones stopped connecting.
            ev(.lifecycle, "setting", ["key": "allowTailscale", "on": false, "default": true])
        }
        shareSpaceThumbnails = (defaults?.object(forKey: "shareSpaceThumbnails") as? Bool) ?? (config.dryRun && config.fakeSpaces != nil)
        labsLiveScreen = (config.fakeScreen && config.dryRun) || (defaults?.bool(forKey: "labsLiveScreen") ?? false)
    }
}

/// Best-effort MagicDNS name of this Mac ("mac.tailnet.ts.net"), read from the Tailscale CLI when
/// it is installed. Detection runs off the main queue; `name` is the cached last result.
enum Tailnet {
    private(set) static var name: String?
    private static var running = false
    static let candidates = [
        "/Applications/Tailscale.app/Contents/MacOS/Tailscale", "/opt/homebrew/bin/tailscale", "/usr/local/bin/tailscale",
    ]

    /// Re-detects in the background; calls `done` on the main queue when the name changed.
    static func refresh(_ done: @escaping () -> Void) {
        guard !running else { return }
        running = true
        DispatchQueue.global(qos: .utility).async {
            let found = detect()
            DispatchQueue.main.async {
                running = false
                if found != name { name = found; done() }
            }
        }
    }

    private static func detect() -> String? {
        guard let bin = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else { return nil }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: bin)
        p.arguments = ["status", "--json", "--peers=false"]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return nil }
        let killer = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 3, execute: killer)
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        killer.cancel()
        guard let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let me = j["Self"] as? [String: Any], let dns = me["DNSName"] as? String else { return nil }
        let n = dns.hasSuffix(".") ? String(dns.dropLast()) : dns
        return n.lowercased().hasSuffix(".ts.net") ? n : nil
    }
}

enum Host {
    /// Bonjour host name, e.g. "Tushars-MacBook-Pro" (the URL is http://<this>.local:8790).
    static var localName: String {
        (SCDynamicStoreCopyLocalHostName(nil) as String?) ?? ProcessInfo.processInfo.hostName.replacingOccurrences(of: ".local", with: "")
    }
    /// Human name, e.g. "Tushar's MacBook Pro".
    static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? localName
    }
    /// IPv4 addresses on up, non-loopback interfaces (en*, bridge*), for the IP fallback URL.
    static var lanIPv4: [String] { ipv4 { name, _ in name.hasPrefix("en") || name.hasPrefix("bridge") } }

    /// This Mac's Tailscale address: an IPv4 in 100.64/10 on a utun interface.
    static var tailnetIPv4: String? {
        ipv4 { name, b in name.hasPrefix("utun") && b.count == 4 && b[0] == 100 && (64...127).contains(b[1]) }.first
    }

    private static func ipv4(_ keep: (String, [UInt8]) -> Bool) -> [String] {
        var out: [String] = []
        var ifa: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifa) == 0, let first = ifa else { return out }
        defer { freeifaddrs(ifa) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  (i.ifa_flags & UInt32(IFF_UP)) != 0, (i.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            let name = String(cString: i.ifa_name)
            let bytes = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { p in
                withUnsafeBytes(of: p.pointee.sin_addr) { [UInt8]($0) }
            }
            guard keep(name, bytes) else { continue }
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 {
                out.append(String(cString: buf))
            }
        }
        return out
    }
}

func log(_ s: String) {
    let line = "[mote] " + s + "\n"
    FileHandle.standardError.write(line.data(using: .utf8)!)
}
