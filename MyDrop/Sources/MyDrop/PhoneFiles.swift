import Foundation
import AppKit

/// Finds a phone that is sharing its files (the "Let my Mac browse this phone" switch) and mounts it in Finder.
/// The pairing code is asked for once and kept in the Keychain.
final class PhoneFiles: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    static let shared = PhoneFiles()

    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private var finished = false

    private var onResolved: ((String, String, Int) -> Void)?

    /// With [then], only finds the phone and reports where it is, instead of mounting it.
    func browse(then: ((String, String, Int) -> Void)? = nil) {
        onResolved = then
        finished = false
        services = []
        let b = NetServiceBrowser()
        b.delegate = self
        browser = b
        b.searchForServices(ofType: "_mydropfiles._tcp.", inDomain: "local.")
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self, !self.finished else { return }
            self.finished = true
            self.browser?.stop()
            if self.onResolved != nil { return }
            self.alert("No phone is sharing its files",
                       "On the phone, open MyDrop → Settings and switch on “Let my Mac browse this phone”. Both devices need to be on the same Wi-Fi.")
        }
    }

    // MARK: Bonjour

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        services.append(service)
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard !finished, let host = Self.ipv4(sender.addresses ?? []) else { return }
        finished = true
        browser?.stop()
        if let cb = onResolved { cb(sender.name, host, sender.port); return }
        mount(name: sender.name, host: host, port: sender.port)
    }

    private static func ipv4(_ addresses: [Data]) -> String? {
        for data in addresses {
            let ok: String? = data.withUnsafeBytes { raw in
                guard let sa = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self), sa.pointee.sa_family == UInt8(AF_INET) else { return nil }
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(sa, socklen_t(data.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
                return String(cString: host)
            }
            if let ok { return ok }
        }
        return nil
    }

    // MARK: Mounting

    private func mount(name: String, host: String, port: Int) {
        var code = Self.storedCode(for: name)
        if code == nil {
            code = askForCode(phone: name)
            guard code != nil else { return }
        }
        let url = "http://mydrop:\(code!)@\(host):\(port)/"
        DispatchQueue.global().async {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            p.arguments = ["-e", "tell application \"Finder\"", "-e", "activate", "-e", "open (mount volume \"\(url)\")", "-e", "end tell"]
            let err = Pipe(); p.standardError = err; p.standardOutput = Pipe()
            try? p.run(); p.waitUntilExit()
            DispatchQueue.main.async {
                if p.terminationStatus == 0 {
                    Self.store(code: code!, for: name)
                    Feedback.finished()
                } else {
                    Self.forgetCode(for: name)
                    self.alert("Couldn't open \(name)", "The code may be wrong, or the phone stopped sharing. Check the code in MyDrop → Settings on the phone and try again.")
                }
            }
        }
    }

    func askForCode(phone: String) -> String? {
        if !Thread.isMainThread { var r: String?; DispatchQueue.main.sync { r = self.askForCode(phone: phone) }; return r }
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Browse \(phone)"
        a.informativeText = "Enter the code shown under “Let my Mac browse this phone” in MyDrop's settings on the phone. You only need to do this once."
        let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 220, height: 24))
        a.accessoryView = field
        a.addButton(withTitle: "Open")
        a.addButton(withTitle: "Cancel")
        guard a.runModal() == .alertFirstButtonReturn else { return nil }
        let code = field.stringValue.trimmingCharacters(in: .whitespaces)
        return code.isEmpty ? nil : code
    }

    private func alert(_ title: String, _ text: String) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert(); a.messageText = title; a.informativeText = text; a.runModal()
    }

    // MARK: Keychain (through the system tool)

    private static func security(_ args: [String]) -> (Int32, String) {
        let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/security"); p.arguments = args
        let out = Pipe(); p.standardOutput = out; p.standardError = Pipe()
        try? p.run(); p.waitUntilExit()
        return (p.terminationStatus, String(decoding: out.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines))
    }
    static func storedCode(for name: String) -> String? {
        let (s, out) = security(["find-generic-password", "-a", "mydrop", "-s", "MyDrop phone \(name)", "-w"])
        return s == 0 && !out.isEmpty ? out : nil
    }
    static func store(code: String, for name: String) {
        _ = security(["add-generic-password", "-U", "-a", "mydrop", "-s", "MyDrop phone \(name)", "-w", code])
    }
    static func forgetCode(for name: String) {
        _ = security(["delete-generic-password", "-a", "mydrop", "-s", "MyDrop phone \(name)"])
    }
}
