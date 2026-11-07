// swiftc -O helper/Sources/MoteHelper/Proximity.swift helper/tests/proximity/main.swift -o /tmp/prox-test && /tmp/prox-test
import Foundation

var failed = 0
func check(_ c: Bool, _ msg: String) { print((c ? "PASS " : "FAIL ") + msg); if !c { failed += 1 } }

let secret = Data(repeating: 7, count: 32)
var t: TimeInterval = 1_700_000_015   // middle of a window
let p = Proximity(hostId: "0123456789abcdef", secret: secret, now: { Date(timeIntervalSince1970: t) })
let w = p.window()
check(w == Int64(1_700_000_015 / 30), "window = floor(unix / 30)")

let cur = p.wire()
let parts = cur.split(separator: ":")
check(parts.count == 2 && parts[0] == "\(w)" && parts[1].count == 32 && parts[1].allSatisfy { "0123456789abcdef".contains($0) },
      "wire form <w>:<32 lower-case hex>")
check(p.wire() == p.wire(window: w), "deterministic per window")
check(p.wire(window: w) != p.wire(window: w + 1), "differs per window")

// skew
check(p.verify(cur) == .ok, "same window ok")
check(p.verify(p.wire(window: w - 1)) == .ok, "w-1 ok (skew)")
check(p.verify(p.wire(window: w + 1)) == .ok, "w+1 ok (skew)")
check(p.verify(p.wire(window: w - 2)) == .stale, "w-2 stale")
check(p.verify(p.wire(window: w + 2)) == .stale, "w+2 stale")

// single use
check(p.verify(cur) == .replay, "second use of the same token is a replay")
check(p.verify(p.wire(window: w + 1)) == .replay, "w+1 replay after use")

// forgeries
let other = Proximity(hostId: "0123456789abcdef", secret: Data(repeating: 8, count: 32), now: { Date(timeIntervalSince1970: t) })
check(p.verify(other.wire()) == .bad, "wrong secret bad")
let otherHost = Proximity(hostId: "fedcba9876543210", secret: secret, now: { Date(timeIntervalSince1970: t) })
check(p.verify(otherHost.wire(window: w + 3)) == .bad, "wrong host bad")
var tampered = Array(p.wire(window: w - 3))
tampered[tampered.count - 1] = tampered.last == "0" ? "1" : "0"
check(p.verify(String(tampered)) == .bad, "tampered token bad")
let tk = String(parts[1])
check(p.verify("\(w + 5):\(tk)") == .bad, "token moved to another window bad")
for junk in ["", ":", "abc", "\(w)", "\(w):", ":\(tk)", "\(w):\(tk.uppercased())", "\(w):\(tk.prefix(31))", "\(w):\(tk)0",
             "+\(w):\(tk)", "\(w):\(tk):x", "x\(w):\(tk)", String(repeating: "9", count: 40) + ":\(tk)"] {
    check(p.verify(junk) == .bad, "malformed '\(junk.prefix(24))' bad")
}

// a token read at window W is accepted at W+1, stale at W+2
t = 1_800_000_000
let q = Proximity(hostId: "h", secret: secret, now: { Date(timeIntervalSince1970: t) })
let W = q.window()
let late = q.wire()
t += 30
check(q.window() == W + 1 && q.verify(late) == .ok, "token from W accepted at W+1")
t += 30
check(q.verify(q.wire(window: W)) == .stale, "token from W stale at W+2")

// pruning: spent windows older than current-1 are dropped
t = 1_900_000_000
let r = Proximity(hostId: "h", secret: secret, now: { Date(timeIntervalSince1970: t) })
let R = r.window()
_ = r.verify(r.wire(window: R - 1)); _ = r.verify(r.wire()); _ = r.verify(r.wire(window: R + 1))
check(r.usedCount == 3, "three spent windows tracked")
t += 60   // now R+2: R-1 and R are older than current-1
_ = r.verify(r.wire())
check(r.usedCount == 2, "pruned to windows >= current-1 (\(r.usedCount))")

// random secret per process
let a = Proximity(hostId: "h"), b = Proximity(hostId: "h")
check(a.wire() != b.wire(), "random secrets differ")

print(failed == 0 ? "ALL PASS" : "\(failed) FAILED")
exit(failed == 0 ? 0 : 1)
