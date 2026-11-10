import SwiftUI

// Map pins, as designed (R-Club Map tab, R-More empty Map and Share live
// location): a sticker balloon, Find My style. The face (photo, animal or
// initial) sits in a 4 pt ring that runs down into a 9 pt tail; the tip is
// the exact position. Under the tip: a soft ground shadow and a dot in the
// person's ink (blue for you), a pulsing halo while they share live, and a
// frosted label. Pins drop in, and lift a little when pressed.

struct BalloonPin<Face: View>: View {
    /// The face's size, 38 to 54 pt.
    var size: CGFloat = 44
    /// The person's ink: the ground dot and the live halo.
    var ink: Color
    var live = false
    /// You: a blue ground dot.
    var isMe = false
    /// Under the tip, e.g. "Mira · 2.4 km".
    var label: String?
    var pressed = false
    /// Drops in when it first appears.
    var dropIn = true
    @ViewBuilder var face: Face

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var landed = false

    static var ring: CGFloat { 4 }
    static var tail: CGFloat { 9 }

    private var dark: Bool { scheme == .dark }
    private var dotColour: Color { isMe ? Color(rgb: 0x3D6BFF) : ink }

    var body: some View {
        let outer = size + Self.ring * 2
        ZStack(alignment: .top) {
            BalloonShape(tail: Self.tail)
                .fill(dark ? Color(rgb: 0x2C2C30) : .white)
                .overlay(BalloonShape(tail: Self.tail).stroke(dark ? Color.white.opacity(0.16) : Color(rgb: 0x141414).opacity(0.08), lineWidth: 1))
                .frame(width: outer, height: outer + Self.tail)
                .shadow(color: dark ? .black.opacity(0.6) : Color(rgb: 0x141414).opacity(0.22), radius: dark ? 6 : 5, y: 6)
            face
                .frame(width: size, height: size)
                .clipShape(Circle())
                .padding(Self.ring)
        }
        .frame(width: outer, height: outer + Self.tail)
        .scaleEffect(pressed ? 1.06 : 1, anchor: .bottom)
        .offset(y: pressed ? -3 : 0)
        .scaleEffect(landed ? 1 : 0.7, anchor: .bottom)
        .offset(y: landed ? 0 : -22)
        .opacity(landed ? 1 : 0)
        // The ground, the halo and the label hang below the tip, outside
        // the pin's frame, so the frame's bottom is the exact position.
        .background(alignment: .bottom) { ground.offset(y: 3) }
        .overlay(alignment: .bottom) {
            if let label {
                PinLabel(text: label, live: live)
                    .fixedSize()
                    .offset(y: 26)
            }
        }
        .animation(.spring(response: 0.25, dampingFraction: 0.6), value: pressed)
        .onAppear {
            guard !landed else { return }
            if dropIn && !reduceMotion {
                withAnimation(.timingCurve(0.3, 1.5, 0.5, 1, duration: 0.6)) { landed = true }
            } else {
                landed = true
            }
        }
    }

    private var ground: some View {
        ZStack {
            if live && !reduceMotion {
                LiveHalo(colour: ink)
            }
            Ellipse()
                .fill(Color.black.opacity(dark ? 0.45 : 0.18))
                .frame(width: 14, height: 6)
                .blur(radius: 1)
            Circle()
                .fill(dotColour)
                .frame(width: 6, height: 6)
                .overlay(Circle().strokeBorder(.white.opacity(dark ? 0 : 0.9), lineWidth: 1))
        }
        .frame(width: 32, height: 16)
    }
}

/// The balloon: a circle that runs down into a short rounded tail.
struct BalloonShape: Shape {
    var tail: CGFloat

    func path(in rect: CGRect) -> Path {
        let d = rect.width
        let r = d / 2
        let c = CGPoint(x: rect.midX, y: rect.minY + r)
        let tip = CGPoint(x: rect.midX, y: rect.minY + d + tail)
        // Where the tail leaves the circle, either side of the bottom.
        let spread = Angle.degrees(28)
        let left = CGPoint(x: c.x - r * sin(spread.radians), y: c.y + r * cos(spread.radians))
        let right = CGPoint(x: c.x + r * sin(spread.radians), y: c.y + r * cos(spread.radians))
        var p = Path()
        p.addArc(center: c, radius: r, startAngle: .degrees(90) - spread, endAngle: .degrees(90) + spread, clockwise: true)
        p.addQuadCurve(to: tip, control: CGPoint(x: left.x + r * 0.12, y: tip.y - tail * 0.35))
        p.addQuadCurve(to: right, control: CGPoint(x: right.x - r * 0.12, y: tip.y - tail * 0.35))
        p.closeSubpath()
        return p
    }
}

/// A ring in the person's ink that spreads out from the ground and fades,
/// every 2.2 s, while they share live.
private struct LiveHalo: View {
    let colour: Color
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSince(start).truncatingRemainder(dividingBy: 2.2) / 2.2
            Ellipse()
                .fill(colour.opacity(0.35 * (1 - t)))
                .frame(width: 32, height: 16)
                .scaleEffect(0.6 + 1.8 * t)
        }
        .allowsHitTesting(false)
    }
}

/// "Mira · 2.4 km" on frosted glass, with a green dot while live.
struct PinLabel: View {
    let text: String
    let live: Bool

    var body: some View {
        HStack(spacing: 5) {
            if live {
                Circle().fill(Color(rgb: 0x2E9E5B)).frame(width: 6, height: 6)
            }
            Text(text).font(.system(size: 11.5, weight: .semibold))
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 8)
        .frame(height: 22)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(Palette.line.opacity(0.6), lineWidth: 0.5))
    }
}
