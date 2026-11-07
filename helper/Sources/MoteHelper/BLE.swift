import CoreBluetooth
import Foundation

/// Transport B: a GATT peripheral so a phone browser (Web Bluetooth) can connect without Wi-Fi.
/// Same JSON as the WebSocket, fragmented: byte 0 = flags (bit 0: more follows), rest = UTF-8 slice.
final class BLEServer: NSObject, CBPeripheralManagerDelegate {
    static let service = CBUUID(string: "3ABAD8F4-B472-41FC-BFC5-6F645F33564B")
    static let rxUUID = CBUUID(string: "0B43B8F5-113A-4034-8055-9F5B26E475E8")   // client writes
    static let txUUID = CBUUID(string: "B0904884-8E1D-4296-86A3-40FFF3FAFB55")   // helper notifies

    private let hub: Hub
    private var pm: CBPeripheralManager!
    private var tx: CBMutableCharacteristic?
    private var sessions: [UUID: Session] = [:]
    private var inbox: [UUID: Data] = [:]
    private var outbox: [UUID: [Data]] = [:]
    private var centrals: [UUID: CBCentral] = [:]
    private(set) var status = "starting"
    var onChange: (() -> Void)?

    init(hub: Hub) {
        self.hub = hub
        super.init()
        pm = CBPeripheralManager(delegate: self, queue: .main)
    }

    private func setStatus(_ s: String) { status = s; log("ble: \(s)"); onChange?() }

    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        switch p.state {
        case .poweredOn: publish()
        case .poweredOff: setStatus("Bluetooth is off")
        case .unauthorized: setStatus("not allowed (System Settings > Privacy > Bluetooth)")
        case .unsupported: setStatus("unsupported on this Mac")
        default: setStatus("unavailable")
        }
    }

    /// (Re)adds the GATT service. Labs instant pairing adds its read-only info characteristic.
    func publish() {
        guard pm.state == .poweredOn else { return }
        let rx = CBMutableCharacteristic(type: BLEServer.rxUUID, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
        let tx = CBMutableCharacteristic(type: BLEServer.txUUID, properties: [.notify], value: nil, permissions: [.readable])
        let svc = CBMutableService(type: BLEServer.service, primary: true)
        svc.characteristics = [rx, tx] + (hub.instantPairing.enabled ? [LabsPairing.infoCharacteristic()] : [])
        self.tx = tx
        pm.stopAdvertising()
        pm.removeAllServices()
        pm.add(svc)
    }

    /// Labs: reads of the instant-pairing info characteristic (long reads come with an offset).
    func peripheralManager(_ p: CBPeripheralManager, didReceiveRead r: CBATTRequest) {
        guard r.characteristic.uuid == LabsPairing.infoUUID, hub.instantPairing.enabled else { p.respond(to: r, withResult: .readNotPermitted); return }
        let v = hub.instantPairing.infoData(offset: r.offset)
        guard r.offset <= v.count else { p.respond(to: r, withResult: .invalidOffset); return }
        r.value = v.subdata(in: r.offset..<v.count)
        p.respond(to: r, withResult: .success)
    }

    func peripheralManager(_ p: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error { setStatus("failed: \(error.localizedDescription)"); return }
        p.startAdvertising([CBAdvertisementDataLocalNameKey: "Mote", CBAdvertisementDataServiceUUIDsKey: [BLEServer.service]])
    }

    func peripheralManagerDidStartAdvertising(_ p: CBPeripheralManager, error: Error?) {
        setStatus(error.map { "advertising failed: \($0.localizedDescription)" } ?? "advertising")
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didSubscribeTo c: CBCharacteristic) {
        guard c.uuid == BLEServer.txUUID else { return }
        open(central)
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom c: CBCharacteristic) {
        guard c.uuid == BLEServer.txUUID else { return }
        drop(central.identifier)
    }

    private func open(_ central: CBCentral) {
        drop(central.identifier)
        let id = central.identifier
        centrals[id] = central
        let s = Session(hub: hub, transport: "BLE", peer: "ble:\(id.uuidString)",
                        send: { [weak self] d in self?.send(d, to: id) },
                        close: { [weak self] in self?.forget(id) })
        sessions[id] = s
        s.start()
    }

    private func drop(_ id: UUID) {
        sessions[id]?.ended()
        forget(id)
    }

    /// A peripheral can't disconnect a central; forgetting the session means its writes are ignored until it resubscribes.
    private func forget(_ id: UUID) {
        sessions[id] = nil; inbox[id] = nil; outbox[id] = nil; centrals[id] = nil
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        var result: CBATTError.Code = .success
        for r in requests {
            guard r.characteristic.uuid == BLEServer.rxUUID else { result = .writeNotPermitted; continue }
            guard r.offset == 0 else { result = .invalidOffset; continue }
            let id = r.central.identifier
            guard let s = sessions[id], let v = r.value, !v.isEmpty else { continue }
            var buf = inbox[id, default: Data()]
            buf.append(v.dropFirst())
            if buf.count > 65536 { log("ble: oversized message, resetting \(id)"); drop(id); continue }
            if v[v.startIndex] & 0x01 != 0 { inbox[id] = buf } else { inbox[id] = nil; s.handle(buf) }
        }
        if let first = requests.first { p.respond(to: first, withResult: result) }
    }

    private func send(_ data: Data, to id: UUID) {
        guard let central = centrals[id] else { return }
        let n = max(19, central.maximumUpdateValueLength - 1)
        var off = 0
        repeat {
            let end = min(off + n, data.count)
            var f = Data([end < data.count ? 1 : 0])
            f.append(data[off..<end])
            outbox[id, default: []].append(f)
            off = end
        } while off < data.count
        pump()
    }

    private func pump() {
        guard let tx else { return }
        for (id, frames) in outbox {
            guard let central = centrals[id] else { continue }
            var rest = frames[...]
            while let f = rest.first, pm.updateValue(f, for: tx, onSubscribedCentrals: [central]) { rest = rest.dropFirst() }
            outbox[id] = rest.isEmpty ? nil : Array(rest)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) { pump() }
}
