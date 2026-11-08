// Draws the DiscRip app icon (a disc on a blue rounded square) into an .iconset folder.
// Usage: swift scripts/tools/make_icon.swift <output.iconset>
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments[1])
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func draw(_ px: Int) -> Data {
    let s = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                               bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let inset = s * 0.1
    let body = NSRect(x: inset, y: inset, width: s - 2 * inset, height: s - 2 * inset)
    let bg = NSBezierPath(roundedRect: body, xRadius: body.width * 0.225, yRadius: body.width * 0.225)
    NSGradient(colors: [NSColor(srgbRed: 0.04, green: 0.52, blue: 1.0, alpha: 1),
                        NSColor(srgbRed: 0.0, green: 0.32, blue: 0.84, alpha: 1)])!.draw(in: bg, angle: -90)
    let c = NSPoint(x: s / 2, y: s / 2)
    let r = body.width * 0.34
    let disc = NSBezierPath(ovalIn: NSRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    NSGradient(colors: [NSColor(white: 0.98, alpha: 1), NSColor(white: 0.80, alpha: 1),
                        NSColor(white: 0.95, alpha: 1)])!.draw(in: disc, angle: 35)
    // grooves
    NSColor(white: 0.6, alpha: 0.35).setStroke()
    for k in stride(from: 0.45, through: 0.95, by: 0.1) {
        let rr = r * CGFloat(k)
        let g = NSBezierPath(ovalIn: NSRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        g.lineWidth = max(s / 512, 0.5)
        g.stroke()
    }
    let hole = r * 0.2
    NSColor(srgbRed: 0.0, green: 0.36, blue: 0.88, alpha: 1).setFill()
    NSBezierPath(ovalIn: NSRect(x: c.x - hole, y: c.y - hole, width: 2 * hole, height: 2 * hole)).fill()
    // "rip" arrow
    let a = NSBezierPath()
    let ax = c.x + r * 0.62, ay = c.y - r * 0.62, aw = r * 0.5
    a.move(to: NSPoint(x: ax - aw * 0.5, y: ay + aw * 0.2))
    a.line(to: NSPoint(x: ax, y: ay - aw * 0.35))
    a.line(to: NSPoint(x: ax + aw * 0.5, y: ay + aw * 0.2))
    a.lineWidth = s * 0.035
    a.lineCapStyle = .round
    a.lineJoinStyle = .round
    NSColor.white.setStroke()
    let badge = NSBezierPath(ovalIn: NSRect(x: ax - aw * 0.75, y: ay - aw * 0.75, width: aw * 1.5, height: aw * 1.5))
    NSColor(srgbRed: 0.0, green: 0.36, blue: 0.88, alpha: 1).setFill()
    badge.fill()
    a.stroke()
    let stem = NSBezierPath()
    stem.move(to: NSPoint(x: ax, y: ay + aw * 0.45))
    stem.line(to: NSPoint(x: ax, y: ay - aw * 0.3))
    stem.lineWidth = s * 0.035
    stem.lineCapStyle = .round
    stem.stroke()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for (name, px) in [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64), ("128x128", 128),
                   ("128x128@2x", 256), ("256x256", 256), ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)] {
    try! draw(px).write(to: out.appendingPathComponent("icon_\(name).png"))
}
