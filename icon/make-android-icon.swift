import AppKit

// White Apple paper-plane on transparent background, for the Android adaptive icon and notification icon.
func plane(canvas: Int, fraction: CGFloat, shift: CGFloat = 0) -> Data {
    let s = CGFloat(canvas)
    let img = NSImage(size: NSSize(width: s, height: s))
    img.lockFocus()
    let cfg = NSImage.SymbolConfiguration(pointSize: s * fraction, weight: .semibold)
        .applying(NSImage.SymbolConfiguration(paletteColors: [.white]))
    if let sym = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: nil)?.withSymbolConfiguration(cfg) {
        let w = sym.size.width, h = sym.size.height
        sym.draw(in: NSRect(x: s / 2 - w / 2 - s * shift, y: s / 2 - h / 2 + s * shift, width: w, height: h))
    }
    img.unlockFocus()
    return NSBitmapImageRep(data: img.tiffRepresentation!)!.representation(using: .png, properties: [:])!
}
let res = CommandLine.arguments[1]
// 432px = 108dp at xxxhdpi. The plane stays well inside the 66dp safe zone.
try! plane(canvas: 432, fraction: 0.36, shift: 0.012).write(to: URL(fileURLWithPath: "\(res)/drawable-nodpi/ic_launcher_foreground.png"))
try! plane(canvas: 96, fraction: 0.78, shift: 0.01).write(to: URL(fileURLWithPath: "\(res)/drawable-nodpi/ic_stat_send.png"))
print("ok")
