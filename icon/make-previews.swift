import AppKit

// Builds the README preview images on a dark background with the MyDrop mark at the bottom right.
// usage: make-previews <phone-home.png> <phone-settings.png> <mac-panel.png>
let a = CommandLine.arguments
func canvas(_ W: Int, _ H: Int, _ draw: (CGFloat, CGFloat) -> Void, to path: String) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let w = CGFloat(W), h = CGFloat(H)
    NSGradient(colors: [NSColor(red: 0.035, green: 0.035, blue: 0.04, alpha: 1), NSColor(red: 0.105, green: 0.105, blue: 0.125, alpha: 1)])!
        .draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: -35)
    NSGradient(colors: [NSColor.white.withAlphaComponent(0.09), NSColor.white.withAlphaComponent(0)])!
        .draw(fromCenter: NSPoint(x: w * 0.5, y: h * 0.55), radius: 0, toCenter: NSPoint(x: w * 0.5, y: h * 0.55), radius: h * 0.75, options: [])
    draw(w, h)
    // MyDrop mark, bottom right
    if let icon = NSImage(contentsOfFile: "icon/AppIcon.iconset/icon_256x256.png") {
        icon.draw(in: NSRect(x: w - 300, y: 44, width: 64, height: 64))
    }
    "MyDrop".draw(at: NSPoint(x: w - 224, y: 54), withAttributes: [.font: NSFont.systemFont(ofSize: 42, weight: .bold), .foregroundColor: NSColor.white])
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

func place(_ path: String, height: CGFloat, centerX: CGFloat, bottom: CGFloat) {
    guard let img = NSImage(contentsOfFile: path) else { print("missing \(path)"); return }
    let r = img.size.width / img.size.height
    img.draw(in: NSRect(x: centerX - height * r / 2, y: bottom, width: height * r, height: height), from: .zero, operation: .sourceOver, fraction: 1)
}

// One phone per image (portrait), so each screen can be shown on its own.
canvas(1080, 1350, { w, h in place(a[1], height: 1180, centerX: w * 0.5, bottom: 120) }, to: "assets/preview-android-home.png")
canvas(1080, 1350, { w, h in place(a[2], height: 1180, centerX: w * 0.5, bottom: 120) }, to: "assets/preview-android-settings.png")
canvas(1920, 1080, { w, h in place(a[3], height: 820, centerX: w * 0.5, bottom: 150) }, to: "assets/preview-mac.png")
print("ok")
