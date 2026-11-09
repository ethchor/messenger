import SwiftUI

/// Our own set of ten shapes. Each one means something, pops in with a
/// little overshoot, idles in its own way, and squashes when tapped.
/// They're decoration only: hidden from VoiceOver, never behind text.
enum ShapeKind: CaseIterable {
    /// People you love: pinned one-to-one chats, an empty chat list.
    case heart
    /// Something new: welcome, the splash, your profile card.
    case sparkle
    /// Activity: a pinned chat with unread messages.
    case bolt
    /// On the move: live location.
    case squiggle
    /// Quiet: muted chats, vanish mode.
    case moon
    /// A group.
    case clover
    /// Asterisk: welcome and the splash.
    case star
    /// Talk: an empty chat.
    case bubble
    /// Sealed: end-to-end encryption, linked devices.
    case seal
    /// Spare: places with nothing yet.
    case pebble

    /// The SVG path, in a 100 × 100 box.
    var path: String {
        switch self {
        case .heart: "M50 90C47 90 6 66 6 35C6 18 18 8 31 8C40 8 46 13 50 21C54 13 60 8 69 8C82 8 94 18 94 35C94 66 53 90 50 90Z"
        case .sparkle: "M50 4C53 33 67 47 96 50C67 53 53 67 50 96C47 67 33 53 4 50C33 47 47 33 50 4Z"
        case .bolt: "M60 6L22 56H47L39 94L79 40H54L62 6Z"
        case .squiggle: "M10 64C18 36 32 32 38 52C44 72 58 74 64 50C70 26 84 24 90 40"
        case .moon: "M62 6A45 45 0 1 0 94 62A34 34 0 0 1 62 6Z"
        case .clover: "M50 26A24 24 0 1 1 74 50A24 24 0 1 1 50 74A24 24 0 1 1 26 50A24 24 0 1 1 50 26Z"
        case .star: "M50 12V88M12 50H88M23 23L77 77M77 23L23 77"
        case .bubble: "M50 10C76 10 93 26 93 46C93 66 76 81 52 81C46 81 40 80 35 78L15 89L20 70C12 63 7 55 7 46C7 26 24 10 50 10Z"
        case .seal: "M50 10Q63.7 -1.1 70 15.4Q87.4 12.6 84.6 30Q101.1 36.3 90 50Q101.1 63.7 84.6 70Q87.4 87.4 70 84.6Q63.7 101.1 50 90Q36.3 101.1 30 84.6Q12.6 87.4 15.4 70Q-1.1 63.7 10 50Q-1.1 36.3 15.4 30Q12.6 12.6 30 15.4Q36.3 -1.1 50 10Z"
        case .pebble: "M53 8C79 9 94 27 92 51C90 76 71 92 46 92C22 92 8 75 8 52C9 27 27 7 53 8Z"
        }
    }

    /// Light, middle and deep stops of the body gradient, then the shade.
    var colors: (light: UInt32, mid: UInt32, deep: UInt32, shade: UInt32) {
        switch self {
        case .heart: (0xFFE0EE, 0xFF86BE, 0xEE3A8C, 0xB8125F)
        case .sparkle: (0xFFF6C4, 0xFFD84A, 0xFFA21F, 0xE86A00)
        case .bolt: (0xFFF3A6, 0xFFC524, 0xFF7A1A, 0xD94E00)
        case .squiggle: (0x86E3FF, 0x8F86FF, 0xFF7CCF, 0x5B3FD6)
        case .moon: (0xF1ECFF, 0xB4A4FF, 0x6A57E8, 0x3E2DB3)
        case .clover: (0xDDFFF3, 0x5EE3C1, 0x0E9F9A, 0x06706C)
        case .star: (0xFFE0DF, 0xFF7A7A, 0xE0313F, 0xA3111E)
        case .bubble: (0xE6F5FF, 0x7FC8FF, 0x3B7CFF, 0x1F4FD1)
        case .seal: (0xE2FFD3, 0x63E08F, 0x0FA968, 0x07734A)
        case .pebble: (0xFFE6DA, 0xFF9F7E, 0xF0582F, 0xB8350F)
        }
    }

    /// Stroked shapes draw a line; the rest are filled.
    var strokeWidth: CGFloat? {
        switch self {
        case .squiggle: 15
        case .star: 12
        default: nil
        }
    }

    fileprivate static var parsed: [ShapeKind: Path] = [:]
    var unitPath: Path {
        if let p = Self.parsed[self] { return p }
        let p = SVGPath.parse(path)
        Self.parsed[self] = p
        return p
    }
}

/// One shape: pops in after `delay`, idles, and squashes when tapped.
struct ShapeSticker: View {
    let kind: ShapeKind
    var size: CGFloat = 58
    var delay: Double = 0
    /// Idle phase offset, so neighbours don't move in step.
    var phase: Double = 0
    var tappable = false
    /// Where it pops in from, relative to where it lands.
    var from = CGSize(width: 0, height: 18)
    /// How small it starts.
    var fromScale: CGFloat = 0.01

    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.holdStill) private var holdStill
    private var reduceMotion: Bool { systemReduceMotion || holdStill }
    @State private var shown = false
    @State private var squash = 0

    var body: some View {
        Group {
            if reduceMotion {
                ShapeArt(kind: kind, size: size, t: 0)
            } else {
                TimelineView(.animation) { context in
                    ShapeArt(kind: kind, size: size, t: context.date.timeIntervalSinceReferenceDate + phase)
                }
            }
        }
        .frame(width: size, height: size)
        .keyframeAnimator(initialValue: CGSize(width: 1, height: 1), trigger: squash) { view, s in
            view.scaleEffect(x: s.width, y: s.height, anchor: .bottom)
        } keyframes: { _ in
            KeyframeTrack(\.width) {
                CubicKeyframe(1.28, duration: 0.14)
                CubicKeyframe(0.84, duration: 0.16)
                CubicKeyframe(1.06, duration: 0.15)
                SpringKeyframe(1, duration: 0.17)
            }
            KeyframeTrack(\.height) {
                CubicKeyframe(0.72, duration: 0.14)
                CubicKeyframe(1.18, duration: 0.16)
                CubicKeyframe(0.95, duration: 0.15)
                SpringKeyframe(1, duration: 0.17)
            }
        }
        .scaleEffect(shown || reduceMotion ? 1 : fromScale, anchor: UnitPoint(x: 0.5, y: 0.6))
        .offset(shown || reduceMotion ? .zero : from)
        .opacity(shown || reduceMotion ? 1 : 0)
        .contentShape(Rectangle())
        .onTapGesture { if tappable { squash += 1 } }
        .allowsHitTesting(tappable)
        .accessibilityHidden(true)
        .onAppear {
            guard !shown else { return }
            withAnimation(.spring(response: 0.55, dampingFraction: 0.55).delay(delay)) { shown = true }
        }
    }
}

/// The drawing itself at time `t`: body gradient, a soft light low-left and
/// a shade top-right, moved by the shape's idle motion.
private struct ShapeArt: View {
    let kind: ShapeKind
    let size: CGFloat
    let t: Double

    var body: some View {
        let m = idle(t)
        Canvas { ctx, canvas in
            let scale = canvas.width / 100
            ctx.scaleBy(x: scale, y: scale)
            let c = kind.colors
            let body = Gradient(stops: [
                .init(color: Color(rgb: c.light), location: 0),
                .init(color: Color(rgb: c.mid), location: 0.5),
                .init(color: Color(rgb: c.deep), location: 1),
            ])
            let light = Gradient(colors: [Color.white.opacity(0.55), Color.white.opacity(0)])
            let shade = Gradient(colors: [Color(rgb: c.shade).opacity(0.42), Color(rgb: c.shade).opacity(0)])
            var path = kind.unitPath
            if kind == .squiggle { path = path.trimmedPath(from: m.trimFrom, to: m.trimTo) }
            let shadings: [GraphicsContext.Shading] = [
                .linearGradient(body, startPoint: CGPoint(x: 8, y: 92), endPoint: CGPoint(x: 92, y: 8)),
                .radialGradient(light, center: CGPoint(x: 30, y: 74), startRadius: 0, endRadius: 46),
                .radialGradient(shade, center: CGPoint(x: 84, y: 16), startRadius: 0, endRadius: 52),
            ]
            for shading in shadings {
                if let w = kind.strokeWidth {
                    ctx.stroke(path, with: shading, style: StrokeStyle(lineWidth: w, lineCap: .round, lineJoin: .round))
                } else {
                    if kind == .bolt {
                        ctx.stroke(path, with: shading, style: StrokeStyle(lineWidth: 9, lineJoin: .round))
                    }
                    ctx.fill(path, with: shading)
                }
            }
        }
        .frame(width: size, height: size)
        .scaleEffect(x: m.sx, y: m.sy, anchor: m.anchor)
        .rotationEffect(.degrees(m.rotation))
        .offset(y: m.dy * size / 58)
        .brightness(m.bright)
    }

    struct Motion {
        var sx: CGFloat = 1, sy: CGFloat = 1, rotation: Double = 0, dy: CGFloat = 0
        var anchor: UnitPoint = .center, bright: Double = 0
        var trimFrom: CGFloat = 0, trimTo: CGFloat = 1
    }

    /// The idle loops from the design, as functions of time.
    private func idle(_ t: Double) -> Motion {
        var m = Motion()
        func phase(_ period: Double) -> Double { (t.truncatingRemainder(dividingBy: period) + period).truncatingRemainder(dividingBy: period) / period }
        func ease(_ x: Double) -> Double { x * x * (3 - 2 * x) }
        func seg(_ p: Double, _ keys: [(Double, Double)]) -> Double {
            for i in 1..<keys.count where p <= keys[i].0 {
                let (p0, v0) = keys[i - 1], (p1, v1) = keys[i]
                return v0 + (v1 - v0) * ease((p - p0) / max(p1 - p0, 0.0001))
            }
            return keys.last!.1
        }
        switch kind {
        case .heart:
            let s = seg(phase(2.6), [(0, 1), (0.1, 1.12), (0.2, 0.97), (0.3, 1.07), (0.45, 1), (1, 1)])
            m.sx = s; m.sy = s; m.anchor = UnitPoint(x: 0.5, y: 0.55)
        case .sparkle:
            let p = phase(3.8)
            m.rotation = seg(p, [(0, 0), (0.58, 0), (0.78, 90), (1, 90)])
            let s = seg(p, [(0, 1), (0.58, 1), (0.78, 1.16), (1, 1)])
            m.sx = s; m.sy = s
        case .bolt:
            let p = phase(3.4)
            let s = seg(p, [(0, 1), (0.76, 1), (0.8, 1.14), (0.85, 0.94), (0.9, 1.05), (1, 1)])
            m.sx = s; m.sy = s
            m.dy = seg(p, [(0, 0), (0.76, 0), (0.8, -4), (0.85, 0), (1, 0)])
            m.bright = seg(p, [(0, 0), (0.76, 0), (0.8, 0.15), (0.9, 0), (1, 0)])
        case .squiggle:
            let p = phase(4.2)
            if p < 0.38 { m.trimTo = CGFloat(ease(p / 0.38)) } else if p > 0.72 { m.trimFrom = CGFloat(ease((p - 0.72) / 0.28)) }
        case .moon:
            m.dy = -6 * sin(.pi * phase(6))
        case .clover:
            let p = phase(3.6)
            m.sx = seg(p, [(0, 1), (0.3, 1.05), (0.6, 0.97), (1, 1)])
            m.sy = seg(p, [(0, 1), (0.3, 0.95), (0.6, 1.04), (1, 1)])
            m.dy = seg(p, [(0, 0), (0.3, 0), (0.6, -4), (1, 0)])
            m.anchor = UnitPoint(x: 0.5, y: 0.9)
        case .star, .seal:
            m.rotation = 360 * phase(22)
        case .bubble, .pebble:
            let p = sin(.pi * phase(4.6))
            m.sx = 1 + 0.04 * p; m.sy = 1 - 0.03 * p; m.anchor = UnitPoint(x: 0.5, y: 0.9)
        }
        return m
    }
}

/// Just enough SVG path to read our shapes: M L H V C Q A Z, absolute.
enum SVGPath {
    static func parse(_ d: String) -> Path {
        var path = Path()
        var tokens: [String] = []
        var number = ""
        func flush() { if !number.isEmpty { tokens.append(number); number = "" } }
        for ch in d {
            if ch.isLetter { flush(); tokens.append(String(ch)) }
            else if ch == "-" { flush(); number = "-" }
            else if ch == " " || ch == "," { flush() }
            else if ch == "." && number.contains(".") { flush(); number = "." }
            else { number.append(ch) }
        }
        flush()
        var i = 0
        var cmd = "M"
        var cur = CGPoint.zero
        var start = CGPoint.zero
        func num() -> CGFloat { defer { i += 1 }; return CGFloat(Double(tokens[i]) ?? 0) }
        while i < tokens.count {
            if let c = tokens[i].first, c.isLetter { cmd = tokens[i]; i += 1; if cmd == "Z" { path.closeSubpath(); cur = start; continue } }
            switch cmd {
            case "M": cur = CGPoint(x: num(), y: num()); start = cur; path.move(to: cur); cmd = "L"
            case "L": cur = CGPoint(x: num(), y: num()); path.addLine(to: cur)
            case "H": cur.x = num(); path.addLine(to: cur)
            case "V": cur.y = num(); path.addLine(to: cur)
            case "C":
                let c1 = CGPoint(x: num(), y: num()), c2 = CGPoint(x: num(), y: num())
                cur = CGPoint(x: num(), y: num()); path.addCurve(to: cur, control1: c1, control2: c2)
            case "Q":
                let c1 = CGPoint(x: num(), y: num())
                cur = CGPoint(x: num(), y: num()); path.addQuadCurve(to: cur, control: c1)
            case "A":
                let rx = num(), ry = num(); _ = num()
                let large = num() != 0, sweep = num() != 0
                let end = CGPoint(x: num(), y: num())
                arc(&path, from: cur, to: end, r: min(rx, ry), large: large, sweep: sweep)
                cur = end
            default: i += 1
            }
        }
        return path
    }

    /// A circular SVG arc, from its endpoints to a centre and two angles.
    private static func arc(_ path: inout Path, from p0: CGPoint, to p1: CGPoint, r: CGFloat, large: Bool, sweep: Bool) {
        let mid = CGPoint(x: (p0.x + p1.x) / 2, y: (p0.y + p1.y) / 2)
        let dx = p1.x - p0.x, dy = p1.y - p0.y
        let chord = sqrt(dx * dx + dy * dy)
        let radius = max(r, chord / 2)
        let h = sqrt(max(radius * radius - chord * chord / 4, 0))
        // Unit normal to the chord; pick the side by the two flags.
        var nx = -dy / chord, ny = dx / chord
        if large == sweep { nx = -nx; ny = -ny }
        let center = CGPoint(x: mid.x + nx * h, y: mid.y + ny * h)
        let a0 = atan2(p0.y - center.y, p0.x - center.x)
        let a1 = atan2(p1.y - center.y, p1.x - center.x)
        // SVG sweep 1 = increasing angle in y-down space = clockwise on screen.
        path.addArc(center: center, radius: radius, startAngle: .radians(Double(a0)), endAngle: .radians(Double(a1)), clockwise: !sweep)
    }
}

extension EnvironmentValues {
    /// Shapes and the splash show their final frame, with no motion: the
    /// app-switcher cover.
    @Entry var holdStill = false
}
