import AppKit

// Builds the README preview images on a dark background with the MyDrop mark at the bottom right.
// usage: make-previews <phone-home.png> <phone-settings.png> <mac-panel.png>
let a = CommandLine.arguments
let W = 1920, H = 1080

func canvas(_ draw: (CGFloat, CGFloat) -> Void, to path: String) {
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
        icon.draw(in: NSRect(x: w - 330, y: 52, width: 76, height: 76))
    }
    "MyDrop".draw(at: NSPoint(x: w - 238, y: 64), withAttributes: [.font: NSFont.systemFont(ofSize: 50, weight: .bold), .foregroundColor: NSColor.white])
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

func place(_ path: String, height: CGFloat, centerX: CGFloat, bottom: CGFloat) {
    guard let img = NSImage(contentsOfFile: path) else { print("missing \(path)"); return }
    let r = img.size.width / img.size.height
    img.draw(in: NSRect(x: centerX - height * r / 2, y: bottom, width: height * r, height: height), from: .zero, operation: .sourceOver, fraction: 1)
}

canvas({ w, h in
    place(a[1], height: 980, centerX: w * 0.36, bottom: 70)     // home screen
    place(a[2], height: 980, centerX: w * 0.64, bottom: 30)     // settings, a little higher
}, to: "assets/preview-android.png")

canvas({ w, h in
    place(a[3], height: 820, centerX: w * 0.5, bottom: 150)
}, to: "assets/preview-mac.png")
print("ok")
