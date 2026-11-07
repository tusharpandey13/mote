import Foundation
import IOBluetooth

/// Bluetooth HID setup, started by a trusted phone (`bt.pair {name}` after it made itself discoverable):
/// a classic inquiry finds the phone by its Bluetooth name, then the Mac initiates pairing and confirms
/// the numeric comparison itself, so nothing needs a click on the Mac. Auto-confirm only for the device
/// found for the current request, within 120 s, while the requesting session is open and authenticated.
/// One request at a time. Main-queue only.
final class BtPair: NSObject, IOBluetoothDeviceInquiryDelegate, IOBluetoothDevicePairDelegate {
    static let window: TimeInterval = 120
    static let fakeAddress = "02:00:00:00:00:01"

    private unowned let hub: Hub
    private let fake: String?   // tests (dry-run): "ok" or the failure reason
    private let disabled: Bool  // dry-run + headless without a fake: never touch real Bluetooth
    private var req: (s: Session, name: String, gen: Int)?
    private var gen = 0
    private var inquiry: IOBluetoothDeviceInquiry?
    private var pairing: IOBluetoothDevicePair?
    private var target: IOBluetoothDevice?

    init(hub: Hub) {
        let c = hub.config
        if c.fakeBtPair != nil && !c.dryRun { log("--fake-bt-pair needs --dry-run; ignoring it") }
        fake = c.dryRun ? c.fakeBtPair : nil
        disabled = c.dryRun && c.headless && fake == nil
        self.hub = hub
        super.init()
    }

    /// `bt.info` / `bt.pair` from an authenticated session.
    func handle(_ t: String, _ m: [String: Any], from s: Session) {
        if t == "bt.info" { s.send(info()); return }
        let name = ((m["name"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 248 else { s.send(["t": "err", "code": "bad", "msg": "bt.pair needs a 'name'", "ref": t]); return }
        guard req == nil else { s.send(["t": "err", "code": "busy", "msg": "another Bluetooth pairing is running", "ref": t]); return }
        gen += 1
        let g = gen
        req = (s, name, g)
        log("bt: pair '\(name)' for \(s.deviceName)")
        DispatchQueue.main.asyncAfter(deadline: .now() + BtPair.window) { [weak self] in
            guard self?.req?.gen == g else { return }
            self?.finish("failed", reason: "timeout")
        }
        if let fake { runFake(fake, gen: g) } else if disabled { finish("failed", reason: "unavailable") } else { start(name) }
    }

    private func info() -> [String: Any] {
        if fake != nil { return ["t": "bt.info", "address": "02:00:00:00:00:00", "name": Host.computerName, "on": true] }
        guard !disabled, let hc = IOBluetoothHostController.default() else { return ["t": "bt.info", "address": NSNull(), "name": Host.computerName, "on": false] }
        let on = hc.powerState == kBluetoothHCIPowerStateON
        return ["t": "bt.info", "address": hc.addressAsString().map(normalized) ?? NSNull(), "name": hc.nameAsString() ?? Host.computerName, "on": on]
    }

    /// The requesting session went away (closed or revoked): stop, so nothing is confirmed for it.
    func ended(_ s: Session) {
        guard req?.s === s else { return }
        log("bt: pairing cancelled (phone went away)")
        cleanup()
    }

    // MARK: real Bluetooth

    private func start(_ name: String) {
        guard let hc = IOBluetoothHostController.default(), hc.powerState == kBluetoothHCIPowerStateON else {
            finish("failed", reason: "unavailable"); return
        }
        if let d = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice])?.first(where: matches) {
            finish("paired", address: d.addressString.map(normalized)); return
        }
        status("searching")
        let q = IOBluetoothDeviceInquiry(delegate: self)!
        q.inquiryLength = 12
        q.updateNewDeviceNames = true
        q.searchType = kIOBluetoothDeviceSearchClassic.rawValue
        inquiry = q
        let r = q.start()
        if r != kIOReturnSuccess { log("bt: inquiry failed to start: \(r)"); finish("failed", reason: "unavailable") }
    }

    private func matches(_ d: IOBluetoothDevice) -> Bool {
        guard let want = req?.name, let n = d.name else { return false }
        return n.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(want) == .orderedSame
    }

    private func consider(_ d: IOBluetoothDevice?) {
        guard let d, req != nil, target == nil, matches(d) else { return }
        target = d
        inquiry?.stop()
        let addr = d.addressString.map(normalized)
        if d.isPaired() { finish("paired", address: addr); return }
        status("pairing", address: addr)
        guard let p = IOBluetoothDevicePair(device: d) else { finish("failed", reason: "refused"); return }
        p.delegate = self
        pairing = p
        let r = p.start()
        if r != kIOReturnSuccess { log("bt: pairing failed to start: \(r)"); finish("failed", reason: "refused") }
    }

    @objc(deviceInquiryDeviceFound:device:)
    func deviceInquiryDeviceFound(_ sender: IOBluetoothDeviceInquiry!, device: IOBluetoothDevice!) {
        guard sender === inquiry else { return }
        consider(device)
    }

    @objc(deviceInquiryDeviceNameUpdated:device:devicesRemaining:)
    func deviceInquiryDeviceNameUpdated(_ sender: IOBluetoothDeviceInquiry!, device: IOBluetoothDevice!, devicesRemaining: UInt32) {
        guard sender === inquiry else { return }
        consider(device)
    }

    @objc(deviceInquiryComplete:error:aborted:)
    func deviceInquiryComplete(_ sender: IOBluetoothDeviceInquiry!, error: IOReturn, aborted: Bool) {
        guard sender === inquiry, target == nil, req != nil else { return }
        finish("failed", reason: "notfound")
    }

    /// True only for the pairing this request started, while its session is still open and authed.
    private func mayConfirm(_ sender: Any?) -> IOBluetoothDevicePair? {
        guard let p = sender as? IOBluetoothDevicePair, p === pairing, let r = req, r.s.isOpen, r.s.authed,
              let t = target, p.device() === t || p.device()?.addressString == t.addressString else { return nil }
        return p
    }

    @objc(devicePairingUserConfirmationRequest:numericValue:)
    func devicePairingUserConfirmationRequest(_ sender: Any!, numericValue: BluetoothNumericValue) {
        guard let p = mayConfirm(sender) else { (sender as? IOBluetoothDevicePair)?.replyUserConfirmation(false); return }
        log("bt: confirming pairing (\(String(format: "%06u", numericValue)))")
        p.replyUserConfirmation(true)
    }

    @objc(devicePairingUserPasskeyNotification:passkey:)
    func devicePairingUserPasskeyNotification(_ sender: Any!, passkey: BluetoothPasskey) {
        log("bt: passkey \(String(format: "%06u", passkey)) (entry on the phone; nothing to answer here)")
    }

    @objc(devicePairingPINCodeRequest:)
    func devicePairingPINCodeRequest(_ sender: Any!) {
        // Legacy PIN pairing needs a code typed on both sides; we can't make one up for the phone.
        log("bt: legacy PIN requested; can't answer it automatically")
        guard mayConfirm(sender) != nil else { return }
        finish("failed", reason: "refused")
    }

    @objc(devicePairingFinished:error:)
    func devicePairingFinished(_ sender: Any!, error: IOReturn) {
        guard let p = sender as? IOBluetoothDevicePair, p === pairing else { return }
        if error == kIOReturnSuccess { finish("paired", address: target?.addressString.map(normalized)) }
        else { log("bt: pairing finished with \(error)"); finish("failed", reason: "refused") }
    }

    // MARK: status

    private func runFake(_ f: String, gen g: Int) {
        status("searching")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            guard let self, self.req?.gen == g else { return }
            if f != "ok" { self.finish("failed", reason: f); return }
            self.status("pairing", address: BtPair.fakeAddress)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                guard let self, self.req?.gen == g else { return }
                self.finish("paired", address: BtPair.fakeAddress)
            }
        }
    }

    private func status(_ state: String, reason: String? = nil, address: String? = nil) {
        guard let r = req else { return }
        var m: [String: Any] = ["t": "bt.pair.status", "state": state]
        if let reason { m["reason"] = reason }
        if let address { m["address"] = address }
        r.s.send(m)
    }

    private func finish(_ state: String, reason: String? = nil, address: String? = nil) {
        guard req != nil else { return }
        log("bt: \(state)\(reason.map { " (\($0))" } ?? "")\(address.map { " \($0)" } ?? "")")
        status(state, reason: reason, address: address)
        cleanup()
    }

    private func cleanup() {
        req = nil
        let q = inquiry; inquiry = nil; q?.stop()
        if let p = pairing { pairing = nil; p.delegate = nil; p.stop() }
        target = nil
    }
}

/// "aa-bb-cc-dd-ee-ff" -> "AA:BB:CC:DD:EE:FF".
private func normalized(_ a: String) -> String { a.uppercased().replacingOccurrences(of: "-", with: ":") }
