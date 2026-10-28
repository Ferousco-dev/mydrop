import Foundation
import AppKit
import UserNotifications

/// Shows the phone's notifications on the Mac. It asks the phone's small server for new ones every couple of seconds.
final class PhoneMirror {
    static let shared = PhoneMirror()

    private var timer: Timer?
    private var endpoint: (name: String, host: String, port: Int, code: String)?
    private var last = -1
    private var failures = 0

    var isOn: Bool { UserDefaults.standard.bool(forKey: "mirrorPhone") }

    func toggle() {
        let on = !isOn
        UserDefaults.standard.set(on, forKey: "mirrorPhone")
        on ? start() : stop()
    }

    func startIfEnabled() { if isOn { start() } }

    private func start() {
        stop()
        PhoneFiles.shared.browse { [weak self] name, host, port in
            guard let self else { return }
            guard let code = PhoneFiles.storedCode(for: name) ?? PhoneFiles.shared.askForCode(phone: name) else { return }
            self.endpoint = (name, host, port, code)
            self.last = -1; self.failures = 0
            DispatchQueue.main.async {
                self.timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.poll() }
                self.poll()
            }
        }
    }

    func stop() { timer?.invalidate(); timer = nil; endpoint = nil }

    private func poll() {
        guard let e = endpoint, let url = URL(string: "http://\(e.host):\(e.port)/_mydrop/notifications?after=\(last)") else { return }
        var req = URLRequest(url: url, timeoutInterval: 4)
        req.setValue("Basic " + Data("mydrop:\(e.code)".utf8).base64EncodedString(), forHTTPHeaderField: "Authorization")
        URLSession.shared.dataTask(with: req) { [weak self] data, response, _ in
            guard let self else { return }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401 {                     // wrong code: forget it and stop
                PhoneFiles.forgetCode(for: e.name)
                DispatchQueue.main.async { UserDefaults.standard.set(false, forKey: "mirrorPhone"); self.stop() }
                return
            }
            guard status == 200, let data, let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                self.failures += 1
                if self.failures >= 5 { DispatchQueue.main.async { if self.isOn { self.start() } } }   // phone moved: look it up again
                return
            }
            self.failures = 0
            PhoneFiles.store(code: e.code, for: e.name)
            if let items = obj["items"] as? [[String: Any]] {
                for i in items {
                    let c = UNMutableNotificationContent()
                    c.title = "\(i["app"] as? String ?? "Phone") · \(i["title"] as? String ?? "")"
                    c.body = i["text"] as? String ?? ""
                    c.sound = .default
                    UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
                }
            }
            if let l = obj["last"] as? Int { self.last = l }
        }.resume()
    }
}
