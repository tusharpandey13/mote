import AppKit
import Foundation

/// Daily "is there a newer Mote Helper?" check against GitHub (U21). Reads the public `releases/latest` JSON (no auth, no
/// cookies, nothing sent but a generic User-Agent), at most once a day, and only ever shows a menu line that opens the
/// release page. It never downloads or installs anything. Failures are silent (an `update.check` event with the reason,
/// and `diag.update`). Menu: Advanced › "Check for updates daily" turns it off (UserDefaults `updateCheck`, default on).
/// The running helper's checker (set in main.swift; nil in --diag and in unit tests).
var updater: Updater?

final class Updater {
    static let defaultURL = "https://api.github.com/repos/tusharpandey13/mote/releases/latest"
    static let interval: TimeInterval = 24 * 3600
    /// Release pages we will open from the menu. The fake endpoint (dry-run tests) may name any https/http URL, but the
    /// helper never opens it in a dry run.
    static let pageHost = "github.com"

    private let defaults: UserDefaults?
    private let url: URL
    private let dryRun: Bool
    private let force: Bool
    private let current: String
    var onChange: (() -> Void)?

    private(set) var latest: String?
    private(set) var pageURL: URL?
    private(set) var checkedAt: Date?
    private(set) var lastResult = "never"
    private var running = false
    private var active = true
    private var timer: Timer?

    var enabled: Bool {
        didSet {
            guard enabled != oldValue else { return }
            defaults?.set(enabled, forKey: "updateCheck")
            ev(.lifecycle, "setting", ["key": "updateCheck", "on": enabled])
            if enabled { schedule() } else { timer?.invalidate(); timer = nil }
            onChange?()
        }
    }

    init(config: Config, version: String? = nil) {
        defaults = config.memoryStore ? nil : UserDefaults.standard
        dryRun = config.dryRun
        // A custom endpoint is a test seam: dry-run only, so a real helper only ever talks to GitHub.
        url = (config.dryRun ? config.updateURL.flatMap(URL.init(string:)) : nil) ?? URL(string: Updater.defaultURL)!
        force = config.dryRun && config.updateCheckNow
        current = version ?? (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
        enabled = (defaults?.object(forKey: "updateCheck") as? Bool) ?? true
        if let s = defaults?.object(forKey: "updateCheckedAt") as? Double { checkedAt = Date(timeIntervalSince1970: s) }
        if let l = defaults?.string(forKey: "updateLatest") { latest = l; pageURL = defaults?.string(forKey: "updatePage").flatMap(URL.init(string:)) }
        // Without a Bundle version (swift run, tests) or a dry run without a test endpoint, never touch the network.
        if config.dryRun && config.updateURL == nil { active = false }
    }

    var currentVersion: String { current }
    /// A newer release than this build is known.
    var available: Bool { latest.map { Updater.isNewer($0, than: current) } ?? false }
    var menuTitle: String? { available ? "Mote Helper \(latest!) is available" : nil }

    func start() {
        guard active else { return }
        ev(.lifecycle, "update.check", ["result": "scheduled", "enabled": enabled, "current": current])
        if enabled { schedule() }
    }

    private func schedule() {
        guard active, timer == nil else { return }
        // First look shortly after launch (never on the launch critical path), then re-evaluate hourly.
        DispatchQueue.main.asyncAfter(deadline: .now() + (force ? 0.2 : 20)) { [weak self] in self?.tick() }
        timer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in self?.tick() }
    }

    private func tick() {
        guard active, enabled, !running else { return }
        if !force, let c = checkedAt, Date().timeIntervalSince(c) < Updater.interval { return }
        running = true
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        req.setValue("MoteHelper/\(current)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.httpShouldHandleCookies = false
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpCookieStorage = nil
        URLSession(configuration: cfg).dataTask(with: req) { [weak self] data, resp, err in
            DispatchQueue.main.async { self?.finish(data: data, resp: resp, err: err) }
        }.resume()
    }

    private func finish(data: Data?, resp: URLResponse?, err: Error?) {
        running = false
        checkedAt = Date()
        defaults?.set(checkedAt!.timeIntervalSince1970, forKey: "updateCheckedAt")
        func fail(_ why: String) {
            lastResult = "error: \(why)"
            ev(.lifecycle, "update.check", ["result": "error", "reason": why, "current": current])
            onChange?()
        }
        if let err { return fail((err as NSError).code == NSURLErrorTimedOut ? "timeout" : "network \((err as NSError).code)") }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { return fail("http \(code)") }
        guard let data, data.count < 1_000_000, let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let tag = j["tag_name"] as? String, Updater.parse(tag) != nil else { return fail("bad response") }
        var page: URL?
        if let h = j["html_url"] as? String, let u = URL(string: h), u.scheme == "https", u.host == Updater.pageHost { page = u }
        latest = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        pageURL = page ?? URL(string: "https://github.com/tusharpandey13/mote/releases/latest")
        defaults?.set(latest, forKey: "updateLatest")
        defaults?.set(pageURL?.absoluteString, forKey: "updatePage")
        lastResult = "ok"
        ev(.lifecycle, "update.check", ["result": "ok", "latest": latest!, "current": current, "available": available])
        onChange?()
    }

    func openReleasePage() {
        ev(.ui, "menu.item", ["item": "openRelease"])
        guard !dryRun, let u = pageURL else { return }
        NSWorkspace.shared.open(u)
    }

    var json: [String: Any] {
        ["enabled": enabled, "current": current, "latest": latest ?? NSNull(), "available": available,
         "checkedAt": checkedAt.map { Int64($0.timeIntervalSince1970 * 1000) } ?? NSNull(), "last": lastResult]
    }

    /// "v1.2.3" / "1.2.3-rc1" -> [1,2,3]; nil when it has no leading number.
    static func parse(_ v: String) -> [Int]? {
        let s = v.hasPrefix("v") ? String(v.dropFirst()) : v
        let core = s.split(whereSeparator: { $0 == "-" || $0 == "+" }).first.map(String.init) ?? s
        let parts = core.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        guard !parts.isEmpty, !parts.contains(where: { $0 == nil }) else { return nil }
        return parts.map { $0! }
    }

    /// Strictly newer (a pre-release suffix on the same numbers counts as older than the plain release).
    static func isNewer(_ a: String, than b: String) -> Bool {
        guard let x = parse(a), let y = parse(b) else { return false }
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }
}
