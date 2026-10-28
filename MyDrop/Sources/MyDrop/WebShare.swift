import Foundation
import Network
import AppKit
import CoreImage

/// A small web page on the local network for people who don't have the app.
/// Scanning the QR code opens it: they can send files to ~/Share and download what is in there.
/// It only runs while the QR code is on screen, and every address includes a random token.
final class WebShare {
    static let shared = WebShare()

    let folder = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Share")
    private var listener: NWListener?
    private var token = ""
    private let queue = DispatchQueue(label: "mydrop.webshare")

    var isRunning: Bool { listener != nil }

    func start(completion: @escaping (String) -> Void) {
        guard listener == nil else { return }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        token = String((0..<10).map { _ in "abcdefghijkmnpqrstuvwxyz23456789".randomElement()! })
        guard let l = try? NWListener(using: .tcp) else { return }
        l.newConnectionHandler = { [weak self] c in self?.handle(c) }
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard case .ready = state, let self, let port = l?.port?.rawValue else { return }
            completion("http://\(Self.localIP()):\(port)/\(self.token)/")
        }
        l.start(queue: queue)
        listener = l
    }

    func stop() { listener?.cancel(); listener = nil }

    // MARK: QR code

    static func qrImage(for text: String) -> NSImage? {
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(text.utf8), forKey: "inputMessage")
        f.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else { return nil }
        let rep = NSCIImageRep(ciImage: out)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }

    static func localIP() -> String {
        var address = "127.0.0.1"
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return address }
        defer { freeifaddrs(ifaddr) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard i.ifa_addr.pointee.sa_family == UInt8(AF_INET), String(cString: i.ifa_name).hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(i.ifa_addr, socklen_t(i.ifa_addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            address = String(cString: host)
            if String(cString: i.ifa_name) == "en0" { break }
        }
        return address
    }

    // MARK: HTTP

    private func handle(_ c: NWConnection) {
        c.start(queue: queue)
        read(c, buffer: Data())
    }

    private func read(_ c: NWConnection, buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, done, error in
            guard let self else { return }
            var buf = buffer
            if let data { buf.append(data) }
            if let split = buf.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buf[..<split.lowerBound], as: UTF8.self)
                let length = head.lowercased().components(separatedBy: "\r\n")
                    .first { $0.hasPrefix("content-length:") }.flatMap { Int($0.dropFirst(15).trimmingCharacters(in: .whitespaces)) } ?? 0
                let body = buf[split.upperBound...]
                if length > 500 << 20 { return self.send(c, 413, "text/plain", Data("Too large".utf8)) }
                if body.count >= length { return self.route(c, head: head, body: Data(body)) }
            }
            if done || error != nil { c.cancel(); return }
            self.read(c, buffer: buf)
        }
    }

    private func send(_ c: NWConnection, _ status: Int, _ type: String, _ body: Data, extra: String = "") {
        let head = "HTTP/1.1 \(status) OK\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\(extra)\r\n"
        c.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in c.cancel() })
    }

    private func route(_ c: NWConnection, head: String, body: Data) {
        let first = head.components(separatedBy: "\r\n")[0].split(separator: " ")
        guard first.count >= 2 else { return c.cancel() }
        let method = String(first[0]), path = String(first[1]).removingPercentEncoding ?? String(first[1])
        let prefix = "/\(token)/"
        guard path.hasPrefix(prefix) else { return send(c, 404, "text/plain", Data("Not found".utf8)) }
        let rest = String(path.dropFirst(prefix.count))

        if method == "POST", rest == "upload" {
            let boundary = head.components(separatedBy: "\r\n").first { $0.lowercased().hasPrefix("content-type:") }?
                .components(separatedBy: "boundary=").last ?? ""
            let saved = saveUploads(body, boundary: boundary)
            return send(c, 303, "text/plain", Data(), extra: "Location: \(prefix)?sent=\(saved)\r\n")
        }
        if rest.hasPrefix("f/") {
            let name = String(rest.dropFirst(2))
            let url = folder.appendingPathComponent((name as NSString).lastPathComponent)
            if let data = try? Data(contentsOf: url) {
                return send(c, 200, "application/octet-stream", data, extra: "Content-Disposition: attachment; filename=\"\(url.lastPathComponent)\"\r\n")
            }
            return send(c, 404, "text/plain", Data("Not found".utf8))
        }
        send(c, 200, "text/html; charset=utf-8", Data(page(sent: rest).utf8))
    }

    private func saveUploads(_ body: Data, boundary: String) -> Int {
        let delim = Data("--\(boundary)".utf8), crlf2 = Data("\r\n\r\n".utf8)
        var count = 0, pos = body.startIndex
        while let start = body.range(of: delim, in: pos..<body.endIndex) {
            let partStart = start.upperBound
            guard let next = body.range(of: delim, in: partStart..<body.endIndex) else { break }
            let part = body[partStart..<next.lowerBound]
            if let h = part.range(of: crlf2) {
                let header = String(decoding: part[..<h.lowerBound], as: UTF8.self)
                if let r = header.range(of: "filename=\""), let end = header[r.upperBound...].firstIndex(of: "\"") {
                    let name = (String(header[r.upperBound..<end]) as NSString).lastPathComponent
                    var content = part[h.upperBound...]
                    if content.suffix(2) == Data("\r\n".utf8) { content = content.dropLast(2) }
                    if !name.isEmpty {
                        var dest = folder.appendingPathComponent(name), n = 1
                        while FileManager.default.fileExists(atPath: dest.path) {
                            dest = folder.appendingPathComponent("\((name as NSString).deletingPathExtension) (\(n))." + (name as NSString).pathExtension); n += 1
                        }
                        if (try? Data(content).write(to: dest)) != nil { count += 1 }
                    }
                }
            }
            pos = next.lowerBound
        }
        return count
    }

    private func page(sent: String) -> String {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: folder.path))?.filter { !$0.hasPrefix(".") }.sorted() ?? []
        let rows = files.map { "<a class=row href=\"f/\($0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? $0)\">\($0)</a>" }.joined()
        let note = sent.hasPrefix("?sent=") ? "<p class=ok>Sent \(sent.dropFirst(6)) file(s) ✓</p>" : ""
        return """
        <!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>MyDrop</title>
        <style>body{margin:0;background:#000;color:#fff;font:16px -apple-system,Roboto,sans-serif;padding:24px}
        h1{font-size:32px;margin:8px 0 18px}.card{background:#1c1c1e;border-radius:24px;padding:18px;margin-bottom:14px}
        input[type=file]{width:100%;color:#8e8e93;margin-bottom:14px}button{background:#fff;color:#000;border:0;border-radius:99px;padding:12px 22px;font-weight:600;font-size:16px}
        .row{display:block;color:#fff;text-decoration:none;padding:12px 0;border-bottom:1px solid #2c2c2e;word-break:break-all}.ok{color:#30d158}.s{color:#8e8e93;font-size:14px}</style></head>
        <body><h1>MyDrop</h1>\(note)
        <div class=card><b>Send files to this Mac</b><p class=s>They land in the Share folder.</p>
        <form method=post action=upload enctype=multipart/form-data><input type=file name=f multiple required><button>Send</button></form></div>
        <div class=card><b>Files on this Mac</b>\(rows.isEmpty ? "<p class=s>Nothing shared yet. Put files in the Share folder.</p>" : rows)</div>
        </body></html>
        """
    }
}
