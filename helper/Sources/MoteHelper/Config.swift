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
    var webRoot: URL? = nil
    var stdinControl = false  // tests: read pair/revoke/pause/resume commands from stdin (needs --dry-run)

    static func parse(_ args: [String]) -> Config {
        var c = Config()
        var it = args.dropFirst().makeIterator()
        while let a = it.next() {
            switch a {
            case "--dry-run": c.dryRun = true
            case "--headless": c.headless = true
            case "--open-pairing": c.openPairing = true
            case "--no-ble": c.ble = false
            case "--memory-store": c.memoryStore = true
            case "--stdin-control": c.stdinControl = true
            case "--port": if let v = it.next(), let p = UInt16(v) { c.port = p }
            case "--keychain-service": if let v = it.next() { c.keychainService = v }
            case "--web-root": if let v = it.next() { c.webRoot = URL(fileURLWithPath: v) }
            case "-h", "--help":
                print("""
                MoteHelper [--dry-run] [--headless] [--open-pairing] [--no-ble] [--port N]
                           [--memory-store] [--keychain-service NAME] [--web-root DIR]
                           [--stdin-control]   (tests, needs --dry-run; port 0 = any free port)
                """)
                exit(0)
            default: break // ignore launch-services noise (-psn_..., -NS...)
            }
        }
        return c
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
    static var lanIPv4: [String] {
        var out: [String] = []
        var ifa: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifa) == 0, let first = ifa else { return out }
        defer { freeifaddrs(ifa) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  (i.ifa_flags & UInt32(IFF_UP)) != 0, (i.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
            let name = String(cString: i.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
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
