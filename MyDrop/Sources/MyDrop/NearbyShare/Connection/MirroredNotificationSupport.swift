//
//  MirroredNotificationSupport.swift
//  QuickDrop
//

import Foundation
import CryptoKit
import UserNotifications

final class MirroredNotificationPresenter {

    static let shared = MirroredNotificationPresenter()

    private let center = UNUserNotificationCenter.current()
    private let queue = DispatchQueue(label: "MirroredNotificationPresenter")
    private var pendingNotifications: [(Sharing_Nearby_MirroredNotificationMetadata, String?)] = []
    private var authorizationRequestInFlight = false
    private init() {}

    func present(metadata: Sharing_Nearby_MirroredNotificationMetadata, senderDeviceName: String?) {
        queue.async {
            self.postNotificationIfAuthorized(metadata: metadata, senderDeviceName: senderDeviceName)
        }
    }

    private func postNotificationIfAuthorized(metadata: Sharing_Nearby_MirroredNotificationMetadata, senderDeviceName: String?) {
        center.getNotificationSettings { settings in
            self.queue.async {
                self.handleAuthorizationStatus(
                    settings.authorizationStatus,
                    metadata: metadata,
                    senderDeviceName: senderDeviceName
                )
            }
        }
    }

    private func handleAuthorizationStatus(
        _ status: UNAuthorizationStatus,
        metadata: Sharing_Nearby_MirroredNotificationMetadata,
        senderDeviceName: String?
    ) {
        switch status {
        case .authorized, .provisional, .ephemeral:
            post(metadata: metadata, senderDeviceName: senderDeviceName)
        case .notDetermined:
            pendingNotifications.append((metadata, senderDeviceName))
            requestAuthorizationIfNeeded()
        case .denied:
            log("[MirroredNotificationPresenter] Notifications are denied in system settings; cannot mirror.")
        @unknown default:
            log("[MirroredNotificationPresenter] Unknown notification authorization status; skipping mirrored notification.")
        }
    }

    private func requestAuthorizationIfNeeded() {
        if authorizationRequestInFlight {
            return
        }

        authorizationRequestInFlight = true

        DispatchQueue.main.async {
            self.center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
                self.queue.async {
                    self.authorizationRequestInFlight = false

                    if let error = error {
                        log("[MirroredNotificationPresenter] Authorization request failed: \(error.localizedDescription)")
                    }

                    guard granted else {
                        log("[MirroredNotificationPresenter] Authorization request not granted; dropping queued mirrored notifications.")
                        self.pendingNotifications.removeAll()
                        return
                    }

                    let queued = self.pendingNotifications
                    self.pendingNotifications.removeAll()
                    for (queuedMetadata, queuedSender) in queued {
                        self.post(metadata: queuedMetadata, senderDeviceName: queuedSender)
                    }
                }
            }
        }
    }

    private func post(metadata: Sharing_Nearby_MirroredNotificationMetadata, senderDeviceName: String?) {
        let senderName = senderDeviceName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasSenderName = senderName?.isEmpty == false
        let displaySender = hasSenderName ? senderName! : "NotificationSyncFallbackSenderAndroid".localized()
        let identifierSender = hasSenderName ? senderName! : "android"
        let cleanAppName = metadata.appName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanTitle = metadata.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanBody = metadata.body.trimmingCharacters(in: .whitespacesAndNewlines)
        let notificationKey = metadata.notificationKey.trimmingCharacters(in: .whitespacesAndNewlines)

        let content = UNMutableNotificationContent()
        content.title = cleanTitle.isEmpty ? (cleanAppName.isEmpty ? "NotificationSyncFallbackTitle".localized() : cleanAppName) : cleanTitle
        content.body = cleanBody
        content.subtitle = cleanAppName.isEmpty ? displaySender : "\(cleanAppName) · \(displaySender)"
        content.sound = .default
        content.threadIdentifier = "quickdrop-mirror-\(sanitizeIdentifierComponent(identifierSender))"

        let identifier = makeIdentifier(sender: identifierSender, notificationKey: notificationKey)
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        center.add(request) { error in
            if let error = error {
                log("[MirroredNotificationPresenter] Could not post notification: \(error.localizedDescription)")
            } else {
                DispatchQueue.main.async {
                    Settings.sharedInstance.incomingNotificationCount += 1
                    let count = Settings.sharedInstance.incomingNotificationCount
                    log("[MirroredNotificationPresenter] Posted mirrored notification (\(identifier)). Current count: \(count)")
                }
            }
        }
    }

    private func makeIdentifier(sender: String, notificationKey: String) -> String {
        let raw = "\(sender)|\(notificationKey)"
        let digest = SHA256.hash(data: Data(raw.utf8))
        let prefix = digest.prefix(12).map { String(format: "%02x", $0) }.joined()
        return "quickdrop-mirror-\(prefix)"
    }

    private func sanitizeIdentifierComponent(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let scalarView = value.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }
        let sanitized = String(scalarView)
        return sanitized.isEmpty ? "android" : sanitized
    }
}
