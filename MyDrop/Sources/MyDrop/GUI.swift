import SwiftUI
import AppKit
import UniformTypeIdentifiers
import ServiceManagement

// MARK: - System feedback (Apple's own sounds and trackpad haptics)

enum Feedback {
    private static let sys = "/System/Library/Components/CoreAudio.component/Contents/SharedSupport/SystemSounds/system/"
    private static var current: NSSound?

    static func play(_ path: String, fallback: String) {
        let sound = FileManager.default.fileExists(atPath: path)
            ? NSSound(contentsOfFile: path, byReference: true) : NSSound(named: fallback)
        current = sound
        sound?.play()
    }
    static func started()  { play(sys + "acknowledgment_sent.caf", fallback: "Tink");  haptic(.levelChange) }
    static func finished() { play(sys + "SentMessage.caf", fallback: "Glass");         haptic(.generic) }
    static func failed()   { play("/System/Library/Sounds/Basso.aiff", fallback: "Basso"); haptic(.generic) }
    static func dropped()  { haptic(.alignment) }

    static func haptic(_ p: NSHapticFeedbackManager.FeedbackPattern) {
        NSHapticFeedbackManager.defaultPerformer.perform(p, performanceTime: .now)
    }
}

extension Notification.Name { static let mydropPanelShown = Notification.Name("mydropPanelShown") }

// MARK: - Model

/// Nearby devices, files to send and transfer state.
final class SendModel: ObservableObject, OutboundAppDelegate {
    static let shared = SendModel()
    var onDone: (() -> Void)?

    @Published var devices: [RemoteDeviceInfo] = []
    @Published var files: [URL] = []
    @Published var status = ""
    @Published var progress: Double? = nil
    @Published var sending = false
    @Published var activeDeviceID: String? = nil
    @Published var qrURL: String? = nil
    @Published var showMore = false
    @Published var syncClipboardOn = UserDefaults.standard.bool(forKey: "syncClipboard")
    var lastClipboard = ""
    private var clipTimer: Timer?
    private var lastChange = NSPasteboard.general.changeCount

    func toggleClipboardSync() {
        syncClipboardOn.toggle()
        UserDefaults.standard.set(syncClipboardOn, forKey: "syncClipboard")
        updateClipTimer()
    }

    /// Watches the Mac's clipboard and sends new text to a trusted phone. Text we just received is not sent back.
    func updateClipTimer() {
        clipTimer?.invalidate(); clipTimer = nil
        guard syncClipboardOn else { return }
        lastChange = NSPasteboard.general.changeCount
        clipTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.checkClipboard() }
    }

    private func checkClipboard() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChange else { return }
        lastChange = pb.changeCount
        guard let text = pb.string(forType: .string), !text.isEmpty, text != lastClipboard, !sending,
              let device = devices.first(where: { isTrusted($0) }) else { return }
        lastClipboard = text
        sendClipboard(to: device)
    }
    @Published var done = false
    @Published var startTick = 0
    @Published var trusted: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "trustedDevices") ?? [])
    private var autoSendPending = false

    func start() {
        NearbyConnectionManager.shared.addOutboundAppDelegate(self)
        NearbyConnectionManager.shared.startDeviceDiscovery()
    }

    // Trust
    func key(for d: RemoteDeviceInfo) -> String { d.keyFingerprint ?? d.name ?? d.id ?? "" }
    func isTrusted(_ d: RemoteDeviceInfo) -> Bool { trusted.contains(key(for: d)) }
    func toggleTrust(_ d: RemoteDeviceInfo) {
        let k = key(for: d)
        if trusted.contains(k) { trusted.remove(k) } else { trusted.insert(k) }
        UserDefaults.standard.set(Array(trusted), forKey: "trustedDevices")
        autoSendIfPossible()
    }

    // Files
    func add(_ urls: [URL]) {
        for u in urls where !files.contains(u) { files.append(u) }
        Feedback.dropped()
    }

    /// Files that arrive from Finder go straight to a trusted phone, no clicking.
    func addFromFinder(_ urls: [URL]) {
        if !sending { files = []; status = "" }
        add(urls)
        autoSendPending = true
        autoSendIfPossible()
    }

    private func autoSendIfPossible() {
        guard autoSendPending, !sending, !files.isEmpty,
              let d = devices.first(where: { isTrusted($0) }) else { return }
        autoSendPending = false
        send(to: d)
    }

    func send(to device: RemoteDeviceInfo) {
        guard let id = device.id, !files.isEmpty, !sending else { return }
        sending = true
        done = false
        startTick += 1
        Feedback.started()
        activeDeviceID = id
        progress = 0
        status = "Waiting for \(device.name ?? "device")…"
        NearbyConnectionManager.shared.startOutgoingTransfer(
            deviceID: id, delegate: self, urls: files, textToSend: nil,
            receiverAuthenticationPolicy: .none)
    }

    func toggleQR() {
        if qrURL == nil {
            WebShare.shared.start { url in DispatchQueue.main.async { self.qrURL = url } }
        } else {
            WebShare.shared.stop(); qrURL = nil
        }
    }

    /// Sends whatever text is on the Mac's clipboard to a device.
    func sendClipboard(to device: RemoteDeviceInfo) {
        guard let id = device.id, !sending, let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            status = "Your clipboard has no text"; return
        }
        sending = true; activeDeviceID = id; done = false; startTick += 1
        Feedback.started()
        status = "Sending clipboard…"
        NearbyConnectionManager.shared.startOutgoingTransfer(
            deviceID: id, delegate: self, urls: [], textToSend: text, receiverAuthenticationPolicy: .none)
    }

    func cancel() {
        showMore = false
        if qrURL != nil { toggleQR() }
        autoSendPending = false
        if sending, let id = activeDeviceID { NearbyConnectionManager.shared.cancelTransfer(id: id) }
        sending = false; progress = nil; activeDeviceID = nil; files = []; status = ""
    }

    // MARK: OutboundAppDelegate
    func addDevice(device: RemoteDeviceInfo) {
        DispatchQueue.main.async {
            if let i = self.devices.firstIndex(where: { $0.id == device.id }) { self.devices[i] = device }
            else { self.devices.append(device) }
            self.autoSendIfPossible()
        }
    }
    func removeDevice(id: String) { DispatchQueue.main.async { self.devices.removeAll { $0.id == id } } }
    func startTransferWithQrCode(device: RemoteDeviceInfo) {}
    func connectionWasEstablished(pinCode: String) {
        DispatchQueue.main.async { self.status = "Accept on your phone" }
    }
    func connectionFailed(error: Error) {
        DispatchQueue.main.async {
            self.sending = false; self.progress = nil; self.activeDeviceID = nil
            self.status = "Couldn't send. Try again."
            Feedback.failed()
        }
    }
    func transferAccepted() { DispatchQueue.main.async { self.status = "Sending…" } }
    func transferProgress(progress: Double) {
        DispatchQueue.main.async { self.progress = progress; self.status = "Sending \(Int(progress * 100))%" }
    }
    func transferFinished() {
        DispatchQueue.main.async {
            self.sending = false; self.progress = 1; self.done = true; self.status = "Sent"
            Feedback.finished()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                self.progress = nil; self.activeDeviceID = nil; self.files = []; self.status = ""; self.done = false
                self.onDone?()
            }
        }
    }
}

// MARK: - Views

struct GlassBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content.glassEffect(.regular, in: .rect(cornerRadius: 30))
        } else {
            content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
        }
    }
}

struct GlassCapsule: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content.glassEffect(.regular.interactive(), in: .capsule)
        } else {
            content.background(.thinMaterial, in: Capsule())
        }
    }
}

struct DeviceCell: View {
    @ObservedObject var model: SendModel
    let device: RemoteDeviceInfo

    private var isActive: Bool { model.activeDeviceID == device.id }

    /// Apple SF Symbols: a neutral smartphone (not an iPhone) since this is an Android phone.
    private var symbolName: String {
        switch device.type {
        case .computer: return "laptopcomputer"
        case .tablet: return "ipad"
        default: return "smartphone"
        }
    }

    var body: some View {
        Button { model.send(to: device) } label: {
            VStack(spacing: 10) {
                ZStack {
                    Circle().fill(.primary.opacity(isActive ? 0.14 : 0.07))
                    Image(systemName: isActive && model.done ? "checkmark" : symbolName)
                        .font(.system(size: 34, weight: .light))
                        .foregroundStyle(isActive && model.done ? Color.green : Color.primary.opacity(0.85))
                        .contentTransition(.symbolEffect(.replace))
                        .symbolEffect(.bounce, value: isActive ? model.startTick : 0)
                        .symbolEffect(.pulse, isActive: isActive && model.sending && (model.progress ?? 0) < 0.02)
                    if isActive, let p = model.progress {
                        Circle()
                            .trim(from: 0, to: max(p, 0.04))
                            .stroke(model.done ? Color.green : Color.accentColor, style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .animation(.easeOut(duration: 0.25), value: p)
                    }
                    if model.isTrusted(device) {
                        Image(systemName: "checkmark.seal.fill")
                            .font(.system(size: 15))
                            .foregroundStyle(Color.accentColor)
                            .background(Circle().fill(.background).padding(2))
                            .offset(x: 25, y: -25)
                    }
                }
                .frame(width: 76, height: 76)
                Text(device.name ?? "Device")
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .frame(maxWidth: 110)
            }
        }
        .buttonStyle(.plain)
        .disabled(model.files.isEmpty || (model.sending && !isActive))
        .contextMenu {
            Button("Send Clipboard") { model.sendClipboard(to: device) }
            Button("Browse Files on This Phone") { PhoneFiles.shared.browse() }
            Divider()
            Button(model.isTrusted(device) ? "Stop Trusting This Device" : "Always Trust This Device") {
                model.toggleTrust(device)
            }
        }
    }
}

struct ContentView: View {
    @ObservedObject var model: SendModel
    var onClose: () -> Void
    @State private var targeted = false
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var fileTitle: String {
        switch model.files.count {
        case 0: return "Drop files here"
        case 1: return model.files[0].lastPathComponent
        default: return "\(model.files.count) files"
        }
    }

    private func popIn() {
        shown = false
        DispatchQueue.main.async {
            withAnimation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.42, dampingFraction: 0.74)) { shown = true }
        }
    }

    var body: some View {
        VStack(spacing: 18) {
            HStack(spacing: 8) {
                Image(nsImage: NSApp.applicationIconImage)
                    .resizable()
                    .frame(width: 24, height: 24)
                Text("MyDrop").font(.system(size: 17, weight: .bold))
            }

            HStack(spacing: 8) {
                if let first = model.files.first {
                    Image(nsImage: NSWorkspace.shared.icon(forFile: first.path))
                        .resizable().frame(width: 22, height: 22)
                }
                Text(fileTitle)
                    .font(.system(size: 14))
                    .foregroundStyle(model.files.isEmpty ? .secondary : .primary)
                    .lineLimit(1).truncationMode(.middle)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Group {
                if model.showMore {
                    MoreMenu(model: model)
                } else if let url = model.qrURL, let qr = WebShare.qrImage(for: url) {
                    HStack(spacing: 16) {
                        Image(nsImage: qr).interpolation(.none).resizable().frame(width: 104, height: 104)
                            .padding(8).background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Scan to send or get files").font(.system(size: 14, weight: .semibold))
                            Text("No app needed. Works in any phone browser on this Wi-Fi.").font(.system(size: 12)).foregroundStyle(.secondary)
                            Button("Open Share folder") { NSWorkspace.shared.open(WebShare.shared.folder) }.buttonStyle(.link).font(.system(size: 12))
                        }
                    }
                } else if model.devices.isEmpty {
                    VStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Looking for your phone…")
                            .font(.system(size: 13)).foregroundStyle(.secondary)
                    }
                } else {
                    HStack(alignment: .top, spacing: 22) {
                        ForEach(model.devices, id: \.id) { DeviceCell(model: model, device: $0) }
                    }
                }
            }
            .frame(maxWidth: .infinity, minHeight: 112)

            HStack {
                if model.status.isEmpty && !model.sending {
                    if model.qrURL != nil {
                        Button("Hide QR") { model.toggleQR() }
                            .buttonStyle(.plain).font(.system(size: 13)).foregroundStyle(.secondary)
                    } else {
                        Button {
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { model.showMore.toggle() }
                        } label: {
                            Image(systemName: model.showMore ? "chevron.left" : "ellipsis")
                                .font(.system(size: 14, weight: .semibold))
                                .frame(width: 44, height: 34)
                                .contentShape(Capsule())
                                .contentTransition(.symbolEffect(.replace))
                        }
                        .buttonStyle(.plain)
                        .modifier(GlassCapsule())
                        .help(model.showMore ? "Back" : "More")
                    }
                } else {
                    Text(model.status)
                        .contentTransition(.numericText())
                        .animation(.snappy, value: model.status)
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button {
                    model.cancel(); onClose()
                } label: {
                    Text(model.sending ? "Cancel" : "Close")
                        .font(.system(size: 14, weight: .medium))
                        .padding(.horizontal, 18).padding(.vertical, 7)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .modifier(GlassCapsule())
                .keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(width: 400, height: 300)
        .modifier(GlassBackground())
        .shadow(color: .black.opacity(0.28), radius: 22, x: 0, y: 10)
        .scaleEffect(shown || reduceMotion ? 1 : 0.88)
        .opacity(shown ? 1 : 0)
        .onReceive(NotificationCenter.default.publisher(for: .mydropPanelShown)) { _ in popIn() }
        .onAppear { popIn() }
        .overlay(
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .strokeBorder(Color.accentColor.opacity(targeted ? 0.9 : 0), lineWidth: 2)
        )
        .onDrop(of: [UTType.fileURL], isTargeted: $targeted) { providers in
            for p in providers {
                p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    if let d = item as? Data, let u = URL(dataRepresentation: d, relativeTo: nil) {
                        DispatchQueue.main.async { model.add([u]) }
                    }
                }
            }
            return true
        }
        .padding(36)      // room for the shadow so it isn't clipped by the window
    }
}

// MARK: - App

final class GlassPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    static var current: AppDelegate?
    var panel: NSPanel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        AppDelegate.current = self
        NSApp.setActivationPolicy(.accessory)      // background app, no Dock icon
        NSApp.servicesProvider = self              // right-click → Services → Send to Phone
        NSUpdateDynamicServices()
        SendModel.shared.onDone = { [weak self] in self?.hidePanel() }
        SendModel.shared.start()                   // keep discovering the phone in the background
        SendModel.shared.updateClipTimer()
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { PhoneMirror.shared.startIfEnabled() }
        Receiver.shared.start()                    // be visible so phones can send to this Mac
        if #available(macOS 13.0, *) { try? SMAppService.mainApp.register() }   // start at login
    }

    func application(_ app: NSApplication, open urls: [URL]) {
        var files: [URL] = []
        for u in urls {
            if u.scheme == "mydrop" {
                // Handed over by the Share-menu extension: mydrop://send?files=<base64 JSON list of paths>
                if let b64 = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "files" })?.value,
                   let data = Data(base64Encoded: b64),
                   let paths = try? JSONSerialization.jsonObject(with: data) as? [String] {
                    files += paths.map { URL(fileURLWithPath: $0) }
                }
            } else {
                files.append(u)
            }
        }
        guard !files.isEmpty else { return }
        SendModel.shared.addFromFinder(files)
        showPanel()
    }

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = pboard.readObjects(forClasses: [NSURL.self],
                                      options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        DispatchQueue.main.async {
            SendModel.shared.addFromFinder(urls)
            self.showPanel()
        }
    }

    /// Opening MyDrop from Applications or Spotlight shows the panel, so it can be used without sharing a file first.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showPanel()
        return true
    }

    func hidePanel() { panel?.orderOut(nil) }

    func showPanel() {
        if panel == nil {
            let p = GlassPanel(contentRect: NSRect(x: 0, y: 0, width: 472, height: 372),
                               styleMask: [.borderless, .fullSizeContentView],
                               backing: .buffered, defer: false)
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = false      // the glass draws its own shadow; the window's square one showed as a black outline
            p.level = .floating
            p.isMovableByWindowBackground = true
            p.animationBehavior = .utilityWindow
            p.hidesOnDeactivate = false
            p.isReleasedWhenClosed = false
            let host = NSHostingView(rootView: ContentView(model: SendModel.shared, onClose: { [weak self] in self?.hidePanel() }))
            host.frame = NSRect(x: 0, y: 0, width: 472, height: 372)
            p.contentView = host
            p.center()
            panel = p
        }
        NSApp.activate(ignoringOtherApps: true)
        panel?.makeKeyAndOrderFront(nil)
        NotificationCenter.default.post(name: .mydropPanelShown, object: nil)
    }
}

struct MyDropApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        SwiftUI.Settings { EmptyView() }   // background app: no menu bar icon, no Dock icon
    }
}

/// The "more" screen: clean, large buttons in the middle.
struct MoreMenu: View {
    @ObservedObject var model: SendModel
    @State private var mirrorOn = PhoneMirror.shared.isOn

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            tile("smartphone", "Phone files", on: nil) {
                model.showMore = false
                PhoneFiles.shared.browse()
            }
            tile("qrcode", "Share by QR", on: nil) {
                model.showMore = false
                model.toggleQR()
            }
            tile("doc.on.clipboard", "Clipboard sync", on: model.syncClipboardOn) { model.toggleClipboardSync() }
            tile("bell.badge", "Phone alerts", on: mirrorOn) { PhoneMirror.shared.toggle(); mirrorOn = PhoneMirror.shared.isOn }
        }
        .frame(maxWidth: .infinity)
        .transition(.scale(scale: 0.94).combined(with: .opacity))
    }

    private func tile(_ symbol: String, _ title: String, on: Bool?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 9) {
                ZStack(alignment: .topTrailing) {
                    ZStack {
                        Circle().fill(.primary.opacity(on == true ? 0.16 : 0.08))
                        Image(systemName: symbol).font(.system(size: 26, weight: .light)).foregroundStyle(.primary.opacity(0.9))
                    }
                    .frame(width: 68, height: 68)
                    if on == true {
                        Circle().fill(Color.green).frame(width: 12, height: 12).overlay(Circle().stroke(.background, lineWidth: 2)).offset(x: -2, y: 2)
                    }
                }
                Text(title).font(.system(size: 12, weight: .medium)).multilineTextAlignment(.center).lineLimit(2).frame(width: 74)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
