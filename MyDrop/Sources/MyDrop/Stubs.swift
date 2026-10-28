import Foundation
import AppKit

// Minimal replacements for the helpers that lived in the private LUI package.

func log(_ items: Any..., separator: String = " ") {
    let line = items.map { "\($0)" }.joined(separator: separator)
    FileHandle.standardError.write(Data((line + "\n").utf8))
}

extension String {
    func localized() -> String { NSLocalizedString(self, comment: "") }
}

func runAfter(seconds: Double, _ block: @escaping () -> Void) {
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: block)
}

func isMac() -> Bool { true }
func isiPadOrMac() -> Bool { true }

extension Data {
    func ensureLength(length: Int) -> Data {
        if count == length { return self }
        if count > length { return suffix(length) }
        return Data(repeating: 0, count: length - count) + self
    }
}

extension Date {
    func isOlderThan(seconds: Double) -> Bool { Date().timeIntervalSince(self) > seconds }
}

enum EXIFUtils {
    static func applyTimestamps(at url: URL) {}
    static func isImageFile(at url: URL) -> Bool { false }
    static func isVideoFile(at url: URL) -> Bool { false }
}

extension String {
    func localized(with args: CVarArg...) -> String {
        String(format: NSLocalizedString(self, comment: ""), arguments: args)
    }
}

/// Stand-in for the app's settings store, backed by UserDefaults.
final class Settings {
    static let sharedInstance = Settings()
    private let ud = UserDefaults.standard

    var automaticallyAcceptFiles: Bool {
        get { ud.bool(forKey: "automaticallyAcceptFiles") }
        set { ud.set(newValue, forKey: "automaticallyAcceptFiles") }
    }
    var endpointID: String? {
        get { ud.string(forKey: "endpointID") }
        set { ud.set(newValue, forKey: "endpointID") }
    }
    var incomingNotificationCount: Int {
        get { ud.integer(forKey: "incomingNotificationCount") }
        set { ud.set(newValue, forKey: "incomingNotificationCount") }
    }
    var incomingTransmissionCount: Int {
        get { ud.integer(forKey: "incomingTransmissionCount") }
        set { ud.set(newValue, forKey: "incomingTransmissionCount") }
    }
    var saveFolderBookmark: Data? {
        get { ud.data(forKey: "saveFolderBookmark") }
        set { ud.set(newValue, forKey: "saveFolderBookmark") }
    }
}

/// Stand-in for the in-app purchase manager. Reports the free tier, like the original.
final class IAPManager {
    static let sharedInstance = IAPManager()
    /// MyDrop has no paid tier, so the free-tier receiving limit inherited from the original engine does not apply.
    /// Set this to false to bring that limit back.
    var plusVersionState = true
}

final class ClipboardSyncCoordinator {
    static let shared = ClipboardSyncCoordinator()
    func suppressNextClipboardSync(for text: String) {}
}

final class ClipboardSyncNotificationPresenter {
    static let shared = ClipboardSyncNotificationPresenter()
    func present(clipboardText: String, senderDeviceName: String?) {}
}

struct AttachmentDetails {
    var urls: [URL] = []
    var text: String? = nil
}
