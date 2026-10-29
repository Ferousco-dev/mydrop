import AppKit

// Play Store art: a 512x512 icon (square, full bleed, Google applies its own rounded mask) and a 1024x500 feature graphic.
func png(_ img: NSImage, _ path: String) {
    let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}
func plane(_ s: CGFloat, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat) {
    let cfg = NSImage.SymbolConfiguration(pointSize: size, weight: .semibold).applying(NSImage.SymbolConfiguration(paletteColors: [.white]))
    if let sym = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: nil)?.withSymbolConfiguration(cfg) {
        sym.draw(in: NSRect(x: x - sym.size.width / 2, y: y - sym.size.height / 2, width: sym.size.width, height: sym.size.height))
    }
}
func background(_ rect: NSRect) {
    NSGradient(colors: [NSColor(red: 0.165, green: 0.165, blue: 0.176, alpha: 1), NSColor(red: 0.020, green: 0.020, blue: 0.024, alpha: 1)])!.draw(in: rect, angle: -90)
}

let icon = NSImage(size: NSSize(width: 512, height: 512))
icon.lockFocus(); background(NSRect(x: 0, y: 0, width: 512, height: 512)); plane(512, 256 - 5, 256 + 3, 230); icon.unlockFocus()
png(icon, "assets/play-store/icon-512.png")

let w: CGFloat = 1024, h: CGFloat = 500
let fg = NSImage(size: NSSize(width: w, height: h))
fg.lockFocus()
NSGradient(colors: [NSColor(red: 0.055, green: 0.055, blue: 0.063, alpha: 1), NSColor(red: 0.125, green: 0.125, blue: 0.145, alpha: 1)])!.draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: -35)
let tile = NSRect(x: 92, y: 100, width: 300, height: 300)
let path = NSBezierPath(roundedRect: tile, xRadius: 68, yRadius: 68)
NSGraphicsContext.saveGraphicsState(); path.addClip(); background(tile); plane(300, tile.midX - 4, tile.midY + 2, 138); NSGraphicsContext.restoreGraphicsState()
func text(_ s: String, _ size: CGFloat, _ weight: NSFont.Weight, _ color: NSColor, _ p: NSPoint) {
    s.draw(at: p, withAttributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color])
}
text("MyDrop", 104, .bold, .white, NSPoint(x: 440, y: 272))
text("Send files between your", 33, .regular, NSColor(white: 0.78, alpha: 1), NSPoint(x: 444, y: 216))
text("Mac and Android phone.", 33, .regular, NSColor(white: 0.78, alpha: 1), NSPoint(x: 444, y: 172))
fg.unlockFocus()
png(fg, "assets/play-store/feature-graphic-1024x500.png")
print("ok")
