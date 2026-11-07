import CryptoKit
import Foundation
import Network

/// Transport A: one TCP port serving the web remote (static files) and the WebSocket at /ws.
/// Hand-rolled HTTP/1.1 + RFC 6455 framing so there are no dependencies.
final class WebServer {
    static let maxConnections = 32
    static let maxPerPeer = 8   // browsers open ~6 parallel connections per host

    private let hub: Hub
    private let root: URL
    private var listener: NWListener?
    private var conns: [ObjectIdentifier: HTTPConnection] = [:]
    private(set) var status = "starting"
    private(set) var port: UInt16?   // bound port, once listening (differs from config with --port 0)
    var onChange: (() -> Void)?

    init(hub: Hub, root: URL) { self.hub = hub; self.root = root.standardizedFileURL }

    func start() throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        params.prohibitedInterfaceTypes = [.cellular]
        let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: hub.config.port)!)
        // TXT: id = stable hostId (match saved hosts without connecting), v = protocol major.
        if hub.config.noBonjour && hub.config.dryRun {
            log("wifi: bonjour off (--no-bonjour)")   // chaos: stands in for a network that blocks multicast
        } else {
            l.service = NWListener.Service(name: Host.computerName, type: "_mote._tcp", domain: nil,
                                           txtRecord: NWTXTRecord(["id": hub.settings.hostId, "v": "1"]))
        }
        l.stateUpdateHandler = { [weak self, weak l] st in
            guard let self else { return }
            switch st {
            case .ready: self.port = l?.port?.rawValue; self.status = "listening on :\(l?.port?.rawValue ?? self.hub.config.port)"   // real port with --port 0
            case .failed(let e): self.status = "failed: \(e)"
            default: break
            }
            log("wifi: \(self.status)")
            self.onChange?()
        }
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        l.start(queue: .main)
        listener = l
    }

    private func accept(_ c: NWConnection) {
        guard case .hostPort(let host, _) = c.endpoint, isLocalAddress(host, tailnet: hub.settings.allowTailscale) else {
            log("wifi: refused non-LAN peer \(c.endpoint)")
            c.cancel(); return
        }
        let peer = "\(host)".components(separatedBy: "%").first ?? "\(host)"
        let fromPeer = conns.values.filter { $0.peer == peer }.count
        guard conns.count < WebServer.maxConnections, fromPeer < WebServer.maxPerPeer else {
            log("wifi: connection limit, refusing \(peer)")
            c.cancel(); return
        }
        let h = HTTPConnection(conn: c, peer: peer, server: self)
        h.tailnet = !isLocalAddress(host, tailnet: false)
        conns[ObjectIdentifier(h)] = h
        h.start()
    }

    /// Turning "Allow Tailscale connections" off also drops the tailnet peers already connected.
    func dropTailnetPeers() {
        for h in conns.values where h.tailnet { h.conn.cancel() }
    }

    fileprivate func finished(_ h: HTTPConnection) { conns[ObjectIdentifier(h)] = nil }
    fileprivate var hubRef: Hub { hub }

    /// Maps a URL path to a file under the web root; nil for anything outside it.
    fileprivate func file(for path: String) -> URL? {
        var p = path.components(separatedBy: "?")[0].removingPercentEncoding ?? ""
        if p.hasSuffix("/") { p += "index.html" }
        guard p.hasPrefix("/"), !p.contains(".."), !p.contains("\0") else { return nil }
        let u = root.appendingPathComponent(String(p.dropFirst())).standardizedFileURL
        guard u.path.hasPrefix(root.path + "/") else { return nil }
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: u.path, isDirectory: &isDir), !isDir.boolValue else { return nil }
        return u
    }
}

private final class HTTPConnection {
    let conn: NWConnection
    let peer: String
    var tailnet = false   // peer is in a Tailscale range (only accepted while the toggle is on)
    unowned let server: WebServer
    private var buf = Data()
    private var session: Session?
    private var wsMessage = Data()
    private var done = false
    private var unsent = 0                 // WebSocket bytes queued on the connection, not yet taken by the kernel
    static let maxUnsent = 1 << 20

    init(conn: NWConnection, peer: String, server: WebServer) { self.conn = conn; self.peer = peer; self.server = server }

    func start() {
        conn.stateUpdateHandler = { [weak self] st in
            switch st {
            // A failed connection (e.g. peer RST) still holds its socket until it is cancelled.
            case .failed: self?.conn.cancel()
            case .cancelled: self?.finish()
            default: break
            }
        }
        conn.start(queue: .main)
        receive()
        // Slowloris guard: the HTTP request (not the WebSocket) must arrive within 10 s.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            if let self, self.session == nil, !self.done { self.conn.cancel() }
        }
    }

    private func finish() {
        guard !done else { return }
        done = true
        session?.ended()
        server.finished(self)
    }

    private func receive() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, err in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buf.append(data)
                if self.session == nil { self.parseHTTP() } else { self.parseFrames() }
            }
            if complete || err != nil { self.conn.cancel(); return }
            if !self.done { self.receive() }
        }
    }

    // MARK: HTTP

    private func parseHTTP() {
        guard let end = buf.range(of: Data("\r\n\r\n".utf8)) else {
            if buf.count > 8192 { respond(431, "Request Header Fields Too Large") }
            return
        }
        let head = String(decoding: buf[..<end.lowerBound], as: UTF8.self)
        buf.removeSubrange(..<end.upperBound)
        var lines = head.components(separatedBy: "\r\n")
        let reqLine = lines.removeFirst().split(separator: " ")
        guard reqLine.count == 3 else { respond(400, "Bad Request"); return }
        var headers: [String: String] = [:]
        for l in lines {
            guard let i = l.firstIndex(of: ":") else { continue }
            headers[l[..<i].trimmingCharacters(in: .whitespaces).lowercased()] = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces)
        }
        let method = String(reqLine[0]), path = String(reqLine[1])
        let host = headers["host"] ?? ""
        // DNS-rebinding guard: only names that point at this Mac on the LAN.
        guard hostAllowed(host) else { respond(421, "Misdirected Request"); return }

        if path.components(separatedBy: "?")[0] == "/ws" {
            upgrade(headers: headers, host: host)
            return
        }
        guard method == "GET" || method == "HEAD" else { respond(405, "Method Not Allowed"); return }
        guard let file = server.file(for: path), let body = try? Data(contentsOf: file) else { respond(404, "Not Found"); return }
        respond(200, "OK", type: mime(file.pathExtension), body: method == "HEAD" ? Data() : body, length: body.count, host: host)
    }

    private func hostAllowed(_ hostHeader: String) -> Bool {
        hostHeaderAllowed(hostHeader, tailnet: server.hubRef.settings.allowTailscale)
    }

    private func respond(_ code: Int, _ reason: String, type: String = "text/plain; charset=utf-8", body: Data? = nil, length: Int? = nil, host: String = "") {
        let b = body ?? Data("\(code) \(reason)\n".utf8)
        var h = "HTTP/1.1 \(code) \(reason)\r\nContent-Type: \(type)\r\nContent-Length: \(length ?? b.count)\r\n"
        h += "Cache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
        if type.hasPrefix("text/html") {
            h += "Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
            h += "connect-src 'self' ws://\(host); script-src 'self'; frame-ancestors 'none'\r\n"
        }
        h += "Connection: close\r\n\r\n"
        conn.send(content: Data(h.utf8) + b, completion: .contentProcessed { [weak self] _ in self?.conn.cancel() })
    }

    private func mime(_ ext: String) -> String {
        switch ext.lowercased() {
        case "html": return "text/html; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "js": return "text/javascript; charset=utf-8"
        case "json", "webmanifest": return "application/json"
        case "svg": return "image/svg+xml"
        case "png": return "image/png"
        case "woff2": return "font/woff2"
        case "txt", "md": return "text/plain; charset=utf-8"
        default: return "application/octet-stream"
        }
    }

    // MARK: WebSocket

    private func upgrade(headers: [String: String], host: String) {
        guard headers["upgrade"]?.lowercased() == "websocket", let key = headers["sec-websocket-key"] else {
            respond(400, "Bad Request"); return
        }
        // Cross-site WebSocket hijacking guard: a browser page must be our own origin. Native clients send no Origin.
        if let origin = headers["origin"], origin.lowercased() != "http://\(host.lowercased())" {
            log("wifi: refused WebSocket from origin \(origin)")
            respond(403, "Forbidden"); return
        }
        let accept = Data(Insecure.SHA1.hash(data: Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
        let resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n"
        conn.send(content: Data(resp.utf8), completion: .contentProcessed { _ in })
        let s = Session(hub: server.hubRef, transport: tailnet ? "Tailscale" : "Wi-Fi", peer: peer,
                        send: { [weak self] d in self?.sendFrame(opcode: 1, payload: d) },
                        close: { [weak self] in self?.sendFrame(opcode: 8, payload: Data([0x03, 0xE8])); self?.conn.cancel() })
        s.backlog = { [weak self] in self?.unsent ?? 0 }
        session = s
        s.start()
        if !buf.isEmpty { parseFrames() }
    }

    private func parseFrames() {
        while true {
            let b = [UInt8](buf.prefix(14))
            guard b.count >= 2 else { return }
            let fin = b[0] & 0x80 != 0, opcode = b[0] & 0x0f, masked = b[1] & 0x80 != 0
            var len = UInt64(b[1] & 0x7f), off = 2
            if len == 126 { guard b.count >= 4 else { return }; len = UInt64(b[2]) << 8 | UInt64(b[3]); off = 4 }
            else if len == 127 {
                guard b.count >= 10 else { return }
                len = b[2..<10].reduce(0) { $0 << 8 | UInt64($1) }; off = 10
            }
            // Clients must mask (RFC 6455 5.1); cap message size at 64 KiB.
            guard masked, len <= 65536, wsMessage.count + Int(len) <= 65536 else { protocolClose(); return }
            guard buf.count >= off + 4 + Int(len) else { return }
            let mask = [UInt8](buf[buf.startIndex + off ..< buf.startIndex + off + 4])
            var payload = [UInt8](buf[buf.startIndex + off + 4 ..< buf.startIndex + off + 4 + Int(len)])
            for i in payload.indices { payload[i] ^= mask[i & 3] }
            buf.removeFirst(off + 4 + Int(len))
            switch opcode {
            case 0, 1, 2:
                wsMessage.append(contentsOf: payload)
                if fin { let m = wsMessage; wsMessage = Data(); session?.handle(m) }
            case 8: sendFrame(opcode: 8, payload: Data()); conn.cancel(); return
            case 9: sendFrame(opcode: 10, payload: Data(payload))
            case 10: break
            default: protocolClose(); return
            }
        }
    }

    private func protocolClose() {
        sendFrame(opcode: 8, payload: Data([0x03, 0xEA]))
        conn.cancel()
    }

    private func sendFrame(opcode: UInt8, payload: Data) {
        guard !done else { return }
        var h = Data([0x80 | opcode])
        if payload.count < 126 { h.append(UInt8(payload.count)) }
        else if payload.count <= 0xffff { h.append(126); h.append(UInt8(payload.count >> 8)); h.append(UInt8(payload.count & 0xff)) }
        else { h.append(127); for i in (0..<8).reversed() { h.append(UInt8((UInt64(payload.count) >> (UInt64(i) * 8)) & 0xff)) } }
        // A peer that doesn't read (e.g. floods pings) would make NWConnection queue replies without bound.
        let n = h.count + payload.count
        unsent += n
        guard unsent <= HTTPConnection.maxUnsent else {
            log("wifi: \(peer) isn't reading replies, closing")
            conn.cancel(); return
        }
        conn.send(content: h + payload, completion: .contentProcessed { [weak self] _ in self?.unsent -= n })
    }
}
