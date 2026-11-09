import SwiftUI

/// Launch: the Q turns the key. The ring clicks, the tail turns, our shapes
/// spill out, then the app takes over at about 1.2 s, never later than the
/// app needs. A slow start gets a calm status line; a failed one stays
/// and explains, with Try again.
struct SplashView: View {
    /// The core failed to open, with the error for support.
    var failure: String?
    /// The core is still opening after the animation.
    var slow = false
    var retry: () -> Void = {}

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.holdStill) private var holdStill
    private var reduceMotion: Bool { systemReduceMotion || holdStill }
    @State private var played = false
    @State private var statusLine: String?

    private var dark: Bool { scheme == .dark }
    private var ground: Color { dark ? Color(rgb: 0x111110) : Color(rgb: 0xF7F5EE) }
    private var ink: Color { dark ? Color(rgb: 0xEBE8E2) : Color(rgb: 0x141414) }
    private var sub: Color { dark ? Color(rgb: 0xA3A1A9) : Color(rgb: 0x5F5E66) }

    /// The six shapes that spill out: kind, size, centre offset from the Q, delay.
    private static let burst: [(ShapeKind, CGFloat, CGSize, Double)] = [
        (.sparkle, 44, CGSize(width: -71, height: -92), 0.30),
        (.heart, 54, CGSize(width: 60, height: -97), 0.37),
        (.bolt, 42, CGSize(width: 128, height: 1), 0.44),
        (.star, 30, CGSize(width: 104, height: 75), 0.51),
        (.clover, 40, CGSize(width: -103, height: 74), 0.58),
        (.squiggle, 50, CGSize(width: -130, height: -3), 0.65),
    ]

    var body: some View {
        GeometryReader { geo in
            let center = CGPoint(x: geo.size.width / 2, y: geo.size.height * 0.415)
            ZStack {
                ground
                // The grid, clear around the mark.
                GridPaper(line: dark ? Color(rgb: 0x17171B) : Color(rgb: 0xECE9DE))
                    .mask(RadialGradient(colors: [.clear, .black], center: UnitPoint(x: 0.5, y: 0.44), startRadius: geo.size.height * 0.08, endRadius: geo.size.height * 0.42))
                    .opacity(played || reduceMotion ? 1 : 0)
                    .animation(.easeOut(duration: 0.5).delay(0.3), value: played)

                ForEach(Array(Self.burst.enumerated()), id: \.offset) { i, item in
                    ShapeSticker(kind: item.0, size: item.1, delay: item.3, phase: Double(i) * -0.6,
                                 from: CGSize(width: -item.2.width, height: -item.2.height), fromScale: 0.15)
                        .position(x: center.x + item.2.width, y: center.y + item.2.height)
                }

                QMark(ink: ink, played: played)
                    .frame(width: 140, height: 140)
                    .position(center)
                    .accessibilityLabel("Messenger")

                VStack(spacing: 8) {
                    Text("Messenger")
                        .font(.display(38, weight: 700))
                        .tracking(-1.1)
                        .foregroundStyle(ink)
                        .rise(played, delay: 0.42)
                    Text("Every chat, just between you.")
                        .font(.system(size: 16))
                        .foregroundStyle(sub)
                        .rise(played, delay: 0.58)
                    if let statusLine, failure == nil {
                        VStack(spacing: 10) {
                            TypingDots(color: ink)
                            Text(statusLine).font(.system(size: 14, weight: .semibold)).foregroundStyle(sub)
                        }
                        .padding(.top, 60)
                        .transition(.opacity.combined(with: .scale(scale: 0.96)))
                    }
                    if let failure {
                        FailureCard(message: failure, ink: ink, sub: sub, dark: dark, retry: retry)
                            .padding(.top, 50)
                            .transition(.opacity.combined(with: .offset(y: 10)))
                    }
                }
                .padding(.horizontal, 20)
                .frame(width: geo.size.width)
                .position(x: center.x, y: center.y + 116)
                .offset(y: 50)

                VStack {
                    Spacer()
                    Label("End-to-end encrypted", systemImage: "lock.fill")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(sub)
                        .opacity(played || reduceMotion ? 1 : 0)
                        .animation(.easeOut(duration: 0.5).delay(0.7), value: played)
                        .padding(.bottom, 38)
                }
            }
        }
        .ignoresSafeArea()
        .onAppear { played = true }
        .task(id: slow) {
            guard slow else { statusLine = nil; return }
            try? await Task.sleep(for: .milliseconds(100))
            withAnimation { statusLine = "Unlocking your chats…" }
            try? await Task.sleep(for: .seconds(2))
            withAnimation { statusLine = "Almost there. Big chats take a moment" }
        }
        .animation(.spring(response: 0.5, dampingFraction: 0.85), value: failure)
    }
}

/// The mark: a ring with a marigold-orange tail, like a key in a lock.
private struct QMark: View {
    let ink: Color
    let played: Bool
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.holdStill) private var holdStill
    private var reduceMotion: Bool { systemReduceMotion || holdStill }

    var body: some View {
        Canvas { ctx, size in
            let k = size.width / 100
            ctx.scaleBy(x: k, y: k)
            ctx.stroke(Path(ellipseIn: CGRect(x: 49 - 23.5, y: 49 - 23.5, width: 47, height: 47)), with: .color(ink), lineWidth: 8)
        }
        .keyframeAnimator(initialValue: 1.0, trigger: played) { view, s in
            view.scaleEffect(reduceMotion ? 1 : s)
        } keyframes: { _ in
            KeyframeTrack {
                CubicKeyframe(1, duration: 0.06)
                CubicKeyframe(0.9, duration: 0.19)
                CubicKeyframe(1.07, duration: 0.22)
                SpringKeyframe(1, duration: 0.23)
            }
        }
        .overlay {
            Canvas { ctx, size in
                let k = size.width / 100
                ctx.scaleBy(x: k, y: k)
                var tail = Path()
                tail.move(to: CGPoint(x: 58.8, y: 58.8))
                tail.addLine(to: CGPoint(x: 73.6, y: 73.6))
                ctx.stroke(tail, with: .color(Color(rgb: 0xEC7B53)), style: StrokeStyle(lineWidth: 11.5, lineCap: .round))
            }
            .keyframeAnimator(initialValue: CGFloat(0), trigger: played) { view, d in
                view.offset(x: reduceMotion ? 0 : d, y: reduceMotion ? 0 : d)
            } keyframes: { _ in
                KeyframeTrack {
                    CubicKeyframe(0, duration: 0.02)
                    CubicKeyframe(-7 * 1.4, duration: 0.19)
                    CubicKeyframe(3 * 1.4, duration: 0.2)
                    SpringKeyframe(0, duration: 0.23)
                }
            }
        }
    }
}

private struct FailureCard: View {
    let message: String
    let ink: Color
    let sub: Color
    let dark: Bool
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Couldn't open your chats").font(.system(size: 17, weight: .bold)).foregroundStyle(ink)
            Text("Give it another go. If it keeps happening, restarting your device usually sorts it out.")
                .font(.system(size: 14)).foregroundStyle(sub).fixedSize(horizontal: false, vertical: true)
            Text(message).font(.system(size: 12, design: .monospaced)).foregroundStyle(sub).textSelection(.enabled)
            Button("Try again", action: retry)
                .buttonStyle(.marigold)
                .padding(.top, 4)
        }
        .padding(18)
        .frame(maxWidth: 420, alignment: .leading)
        .background(dark ? Color(rgb: 0x1C1C1E) : .white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(dark ? Color(rgb: 0x2A2A2F) : Color(rgb: 0xE4E1D8)))
    }
}

/// Three dots that breathe in turn.
struct TypingDots: View {
    var color: Color
    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 6) {
                ForEach(0..<3) { i in
                    let p = (t - Double(i) * 0.15).truncatingRemainder(dividingBy: 1.1) / 1.1
                    let up = p < 0.4 ? p / 0.4 : p < 0.8 ? 1 - (p - 0.4) / 0.4 : 0
                    Circle().fill(color).frame(width: 6, height: 6)
                        .opacity(0.25 + 0.75 * up)
                        .offset(y: -3 * up)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// A faint square grid, 28 pt.
struct GridPaper: View {
    var line: Color
    var body: some View {
        Canvas { ctx, size in
            var p = Path()
            var x: CGFloat = 0
            while x <= size.width { p.addRect(CGRect(x: x, y: 0, width: 1, height: size.height)); x += 28 }
            var y: CGFloat = 0
            while y <= size.height { p.addRect(CGRect(x: 0, y: y, width: size.width, height: 1)); y += 28 }
            ctx.fill(p, with: .color(line))
        }
        .accessibilityHidden(true)
    }
}

private extension View {
    /// Fades and rises 14 pt into place after `delay`.
    func rise(_ on: Bool, delay: Double) -> some View {
        modifier(Rise(on: on, delay: delay))
    }
}

private struct Rise: ViewModifier {
    let on: Bool
    let delay: Double
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.holdStill) private var holdStill
    private var reduceMotion: Bool { systemReduceMotion || holdStill }
    func body(content: Content) -> some View {
        content
            .opacity(on || reduceMotion ? 1 : 0)
            .offset(y: on || reduceMotion ? 0 : 14)
            .animation(.timingCurve(0.2, 0.9, 0.25, 1, duration: 0.5).delay(delay), value: on)
    }
}
