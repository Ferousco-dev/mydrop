import Foundation
import AppKit
import UserNotifications

/// Receiving on the Mac: shows an AirDrop-style notification when a device wants to send files,
/// saves them to the Downloads folder and tells you when they arrive.
final class Receiver: NSObject, InboundAppDelegate, UNUserNotificationCenterDelegate {
    static let shared = Receiver()

    private let center = UNUserNotificationCenter.current()
    private var pending: [String: RemoteDeviceInfo] = [:]

    func start() {
        center.delegate = self
        let actions = [
            UNNotificationAction(identifier: "accept", title: "Accept", options: []),
            UNNotificationAction(identifier: "trust", title: "Always Accept", options: []),
            UNNotificationAction(identifier: "decline", title: "Decline", options: [.destructive]),
        ]
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "incoming", actions: actions, intentIdentifiers: [], options: []),
        ])
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        NearbyConnectionManager.shared.addInboundAppDelegate(self)
        NearbyConnectionManager.shared.becomeVisible()
    }

    // MARK: Trusted senders (shared with the send panel's list)

    private var trusted: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: "trustedDevices") ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: "trustedDevices") }
    }
    private func isTrusted(_ d: RemoteDeviceInfo) -> Bool {
        trusted.contains(d.keyFingerprint ?? "") || trusted.contains(d.name ?? "")
    }

    // MARK: InboundAppDelegate

    func obtainUserConsent(transfer: TransferMetadata, device: RemoteDeviceInfo) {
        if isTrusted(device) {
            NearbyConnectionManager.shared.submitUserConsent(transferID: transfer.id, accept: true, trustDevice: false)
            return
        }
        pending[transfer.id] = device
        let what = transfer.files.isEmpty ? "some text" : (transfer.files.count == 1 ? transfer.files[0].name : "\(transfer.files.count) files")
        let content = UNMutableNotificationContent()
        content.title = "\(device.name ?? "A device") wants to send you \(what)"
        content.body = transfer.pinCode.map { "Check that the code is \($0)" } ?? "Tap to respond"
        content.categoryIdentifier = "incoming"
        content.userInfo = ["transfer": transfer.id]
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        Feedback.started()
        // If notifications are switched off for MyDrop, ask with a window instead so a request can never go unseen.
        center.getNotificationSettings { [weak self] settings in
            guard let self else { return }
            if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional {
                self.center.add(UNNotificationRequest(identifier: "in-\(transfer.id)", content: content, trigger: nil))
            } else {
                DispatchQueue.main.async { self.askWithWindow(id: transfer.id, device: device, title: content.title, detail: content.body) }
            }
        }
    }

    private func askWithWindow(id: String, device: RemoteDeviceInfo, title: String, detail: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = detail
        alert.addButton(withTitle: "Accept")
        alert.addButton(withTitle: "Always Accept")
        alert.addButton(withTitle: "Decline")
        let answer = alert.runModal()
        if answer == .alertThirdButtonReturn {
            NearbyConnectionManager.shared.submitUserConsent(transferID: id, accept: false, trustDevice: false)
        } else {
            if answer == .alertSecondButtonReturn { trusted.insert(device.keyFingerprint ?? device.name ?? "") }
            NearbyConnectionManager.shared.submitUserConsent(transferID: id, accept: true, trustDevice: false)
        }
        pending[id] = nil
    }

    func obtainedUserConsentAutomatically(transfer: TransferMetadata, device: RemoteDeviceInfo) {}

    func transferProgress(connectionID: String, progress: Double) {}

    func connectionWasTerminated(connectionID: String, from device: RemoteDeviceInfo?, savedFiles: [URL],
                                 wasPlainTextTransfer: Bool, wasClipboardSync: Bool, error: Error?) {
        center.removeDeliveredNotifications(withIdentifiers: ["in-\(connectionID)"])
        pending[connectionID] = nil
        if savedFiles.isEmpty {
            // Text sent from a phone: the engine already put it on the clipboard.
            guard wasPlainTextTransfer, error == nil, let text = NSPasteboard.general.string(forType: .string) else { return }
            SendModel.shared.lastClipboard = text          // so it isn't sent straight back
            let note = UNMutableNotificationContent()
            note.title = "Copied from \(device?.name ?? "another device")"
            note.body = String(text.prefix(120))
            note.sound = .default
            center.add(UNNotificationRequest(identifier: "done-\(connectionID)", content: note, trigger: nil))
            Feedback.finished()
            return
        }
        let content = UNMutableNotificationContent()
        content.title = savedFiles.count == 1 ? "Received \(savedFiles[0].lastPathComponent)" : "Received \(savedFiles.count) files"
        content.body = "From \(device?.name ?? "another device") · click to show in Finder"
        content.sound = .default
        content.userInfo = ["path": savedFiles[0].path]
        center.add(UNNotificationRequest(identifier: "done-\(connectionID)", content: content, trigger: nil))
        Feedback.finished()
    }

    func showPlusScreen() {}

    // MARK: Notification buttons

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let id = info["transfer"] as? String {
            let device = pending[id]
            switch response.actionIdentifier {
            case "decline":
                NearbyConnectionManager.shared.submitUserConsent(transferID: id, accept: false, trustDevice: false)
            case "trust", "accept", UNNotificationDefaultActionIdentifier:
                if response.actionIdentifier == "trust", let device {
                    trusted.insert(device.keyFingerprint ?? device.name ?? "")
                }
                NearbyConnectionManager.shared.submitUserConsent(transferID: id, accept: true, trustDevice: false)
            default: break
            }
        } else if let path = info["path"] as? String {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
        }
        done()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }
}
