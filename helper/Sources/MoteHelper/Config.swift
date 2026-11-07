import Foundation
import SystemConfiguration

/// Command-line options. The .app runs with none; tests use --dry-run --headless.
struct Config {
    var dryRun = false        // log input instead of injecting it
    var headless = false      // no menu-bar UI (tests)
    var openPairing = false   // open the pairing window at launch and print the code
    var ble = true
    var port: UInt16 = 8790
    var keychainService = "dev.mote.helper"
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
    var bonjour = true        // --no-bonjour: don't advertise _mote._tcp (tests: keeps a test helper invisible to real phones on the LAN)
    var fakeScreen = false    // tests: Labs live screen streams synthetic frames of a fake display (needs --dry-run; turns the lab on)

    static func parse(_ args: [String]) -> Config {
        var c = Config()
        var it = args.dropFirst().makeIterator()
        while let a = it.next() {
            switch a {
            case "--dry-run": c.dryRun = true
            case "--headless": c.headless = true
            case "--open-pairing": c.openPairing = true
            case "--no-ble": c.ble = false
            case "--no-bonjour": c.bonjour = false
            case "--memory-store": c.memoryStore = true
            case "--allow-tailscale": c.allowTailscale = true
            case "--stdin-control": c.stdinControl = true
            case "--fake-screen": c.fakeScreen = true
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
            case "--port": if let v = it.next(), let p = UInt16(v) { c.port = p }
            case "--keychain-service": if let v = it.next() { c.keychainService = v }
            case "--web-root": if let v = it.next() { c.webRoot = URL(fileURLWithPath: v) }
            case "-h", "--help":
                print("""
                MoteHelper [--dry-run] [--headless] [--open-pairing] [--no-ble] [--port N]
                           [--memory-store] [--keychain-service NAME] [--web-root DIR] [--allow-tailscale]
                          [--stdin-control] [--no-bonjour] [--fake-spaces N[,M...]] [--labs-tv] [--fake-context FILE] [--fake-nowplaying] [--fake-screen]   (tests, need --dry-run; port 0 = any free port)
                          [--no-instant-pairing | --labs-pairing] [--require-approval]   (instant pairing: off / force on; approval prompts)
                          [--fake-proximity] [--fake-approve | --fake-approve-deny] [--fake-tailscale-owner ID]   (instant pairing tests, need --dry-run)
                          [--fake-bt-pair | --fake-bt-pair-fail REASON]   (Bluetooth HID setup without Bluetooth, needs --dry-run)
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
    /// MagicDNS (*.ts.net) or tailnet-literal Host headers. Default on (an explicit off is kept);
    /// with --memory-store (tests) off unless --allow-tailscale.
    var allowTailscale: Bool {
        didSet {
            guard allowTailscale != oldValue else { return }
            defaults?.set(allowTailscale, forKey: "allowTailscale")
            log("tailscale connections \(allowTailscale ? "allowed" : "refused")")
            onChange?()
        }
    }

    /// "Share Space thumbnails": capture and serve Space thumbnails (protocol 1.3). Default on;
    /// it only has an effect once Screen Recording is granted.
    var shareSpaceThumbnails: Bool {
        didSet {
            guard shareSpaceThumbnails != oldValue else { return }
            defaults?.set(shareSpaceThumbnails, forKey: "shareSpaceThumbnails")
            log("space thumbnails \(shareSpaceThumbnails ? "shared" : "not shared")")
            onChange?()
        }
    }

    /// Labs › "Live screen" (docs/labs/live-screen.md): stream the active display to an open phone viewer. Default off.
    var labsLiveScreen: Bool {
        didSet {
            guard labsLiveScreen != oldValue else { return }
            defaults?.set(labsLiveScreen, forKey: "labsLiveScreen")
            log("labs: live screen \(labsLiveScreen ? "on" : "off")")
            onChange?()
        }
    }

    init(config: Config) {
        defaults = config.memoryStore ? nil : UserDefaults.standard
        if let id = defaults?.string(forKey: "hostId"), id.count == 16 {
            hostId = id
        } else {
            hostId = randomHex(8)
            defaults?.set(hostId, forKey: "hostId")
        }
        allowTailscale = config.allowTailscale ?? defaults.map { $0.object(forKey: "allowTailscale") as? Bool ?? true } ?? false
        shareSpaceThumbnails = (defaults?.object(forKey: "shareSpaceThumbnails") as? Bool) ?? true
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
