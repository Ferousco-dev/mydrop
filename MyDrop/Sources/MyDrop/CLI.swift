import Foundation
import AppKit

/// Tiny command-line front end for the QuickDrop transfer engine.
///   MyDrop list                       – show nearby Android devices
///   MyDrop send "<device>" files...   – send files to the device whose name contains <device>
final class Sender: OutboundAppDelegate {
    let wanted: String?
    let urls: [URL]
    var devices: [String: RemoteDeviceInfo] = [:]
    var started = false

    init(wanted: String?, urls: [URL]) { self.wanted = wanted; self.urls = urls }

    func addDevice(device: RemoteDeviceInfo) {
        guard let id = device.id else { return }
        devices[id] = device
        print("found: \(device.name ?? "?")  [\(id)]")
        guard !urls.isEmpty, !started, let wanted,
              (device.name ?? "").localizedCaseInsensitiveContains(wanted) else { return }
        started = true
        print("sending \(urls.count) file(s) to \(device.name ?? id)…")
        NearbyConnectionManager.shared.startOutgoingTransfer(
            deviceID: id, delegate: self, urls: urls, textToSend: nil,
            receiverAuthenticationPolicy: .none)
    }
    func removeDevice(id: String) { devices[id] = nil }
    func startTransferWithQrCode(device: RemoteDeviceInfo) {}
    func connectionWasEstablished(pinCode: String) { print("connected, PIN \(pinCode) – accept on the phone") }
    func connectionFailed(error: Error) { print("FAILED: \(error)"); exit(1) }
    func transferAccepted() { print("accepted, sending…") }
    func transferProgress(progress: Double) { print(String(format: "progress %.0f%%", progress * 100)) }
    func transferFinished() { print("done"); exit(0) }
}

func runCLI() -> Never {
    let args = Array(CommandLine.arguments.dropFirst())
    let mode = args.first ?? "list"
    if mode == "browse" { PhoneFiles.shared.browse(); RunLoop.main.run() }
    if mode == "serve" { WebShare.shared.start { print($0); fflush(stdout) }; RunLoop.main.run() }
    let sender: Sender
    switch mode {
    case "send" where args.count >= 3:
        sender = Sender(wanted: args[1], urls: args.dropFirst(2).map { URL(fileURLWithPath: $0) })
    default:
        sender = Sender(wanted: nil, urls: [])
    }
    NearbyConnectionManager.shared.addOutboundAppDelegate(sender)
    NearbyConnectionManager.shared.startDeviceDiscovery()
    DispatchQueue.main.asyncAfter(deadline: .now() + (mode == "list" ? 8 : 3600)) {
        print(mode == "list" ? "(end of scan)" : "timed out"); exit(mode == "list" ? 0 : 2)
    }
    RunLoop.main.run()
    exit(0)
}
