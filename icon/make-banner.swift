import AppKit

// Renders the GitHub banner / social preview image (1280x640).
let w: CGFloat = 1280, h: CGFloat = 640
let img = NSImage(size: NSSize(width: w, height: h))
img.lockFocus()
NSGradient(colors: [NSColor(red: 0.055, green: 0.055, blue: 0.063, alpha: 1), NSColor(red: 0.125, green: 0.125, blue: 0.145, alpha: 1)])!
    .draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: -35)
// soft glow behind the icon
NSGradient(colors: [NSColor.white.withAlphaComponent(0.10), NSColor.white.withAlphaComponent(0)])!
    .draw(fromCenter: NSPoint(x: 330, y: 330), radius: 0, toCenter: NSPoint(x: 330, y: 330), radius: 330, options: [])

if let icon = NSImage(contentsOfFile: "icon/AppIcon.iconset/icon_512x512.png") {
    icon.draw(in: NSRect(x: 130, y: 130, width: 400, height: 400))
}
func text(_ s: String, size: CGFloat, weight: NSFont.Weight, color: NSColor, at p: NSPoint) {
    s.draw(at: p, withAttributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color])
}
text("MyDrop", size: 132, weight: .bold, color: .white, at: NSPoint(x: 590, y: 340))
text("Send files between your Mac", size: 38, weight: .regular, color: NSColor(white: 0.78, alpha: 1), at: NSPoint(x: 596, y: 282))
text("and your Android phone.", size: 38, weight: .regular, color: NSColor(white: 0.78, alpha: 1), at: NSPoint(x: 596, y: 232))
text("Share menu  ·  Notifications  ·  Clipboard  ·  Phone files in Finder", size: 23, weight: .medium, color: NSColor(white: 0.52, alpha: 1), at: NSPoint(x: 596, y: 168))
img.unlockFocus()
let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "assets/banner.png"))
print("ok")
