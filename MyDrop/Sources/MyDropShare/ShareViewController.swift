import Cocoa

/// Share-menu entry. It only collects the chosen files and hands them to the MyDrop app,
/// which does the actual discovery and sending.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    override func loadView() { view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1)) }

    override func viewDidAppear() {
        super.viewDidAppear()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let group = DispatchGroup()
        let lock = NSLock()
        var paths: [String] = []

        for p in providers where p.hasItemConformingToTypeIdentifier("public.file-url") {
            group.enter()
            p.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, _ in
                var url: URL?
                if let d = item as? Data { url = URL(dataRepresentation: d, relativeTo: nil) }
                else if let u = item as? URL { url = u }
                if let url { lock.lock(); paths.append(url.path); lock.unlock() }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            if !paths.isEmpty,
               let json = try? JSONSerialization.data(withJSONObject: paths),
               var comps = URLComponents(string: "mydrop://send") {
                comps.queryItems = [URLQueryItem(name: "files", value: json.base64EncodedString())]
                if let url = comps.url { NSWorkspace.shared.open(url) }
            }
            self.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
