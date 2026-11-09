import SwiftUI

/// The app lock, as designed (R-More › States › App lock): the splash's paper
/// and Q, breathing, with a marigold padlock at its corner. Face ID asks on
/// its own when the screen appears. While it looks, the buttons give way to
/// a scanning tile; a miss shakes the Q; a match drops the padlock, turns
/// the key, and the shapes burst out as at launch, then the chats open.
struct LockView: View {
    @Environment(AppLock.self) private var lock
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Phase { case idle, scanning, failed, welcome }
    @State private var phase = Phase.idle
    @State private var shakes = 0
    @State private var breathe = false

    private var dark: Bool { scheme == .dark }
    private var ground: Color { dark ? Color(rgb: 0x111110) : Color(rgb: 0xF7F5EE) }
    private var ink: Color { dark ? Color(rgb: 0xEBE8E2) : Color(rgb: 0x141414) }
    private var sub: Color { dark ? Color(rgb: 0xA3A1A9) : Color(rgb: 0x5F5E66) }

    private var title: String {
        switch phase {
        case .failed: "That didn't look like you"
        case .welcome: "Welcome back"
        default: "Locked, just for you"
        }
    }

    private var message: String {
        switch phase {
        case .scanning: "Looking for you…"
        case .failed: "Try again, or use your passcode"
        case .welcome: "Your chats are ready"
        case .idle: "Your chats open with \(lock.methodName)."
        }
    }

    var body: some View {
        GeometryReader { geo in
            let center = CGPoint(x: geo.size.width / 2, y: geo.size.height * 0.415)
            ZStack {
                ground
                GridPaper(line: dark ? Color(rgb: 0x17171B) : Color(rgb: 0xECE9DE))
                    .mask(RadialGradient(colors: [.clear, .black], center: UnitPoint(x: 0.5, y: 0.44), startRadius: geo.size.height * 0.08, endRadius: geo.size.height * 0.42))

                if phase == .welcome {
                    ForEach(Array(SplashView.burst.enumerated()), id: \.offset) { i, item in
                        ShapeSticker(kind: item.0, size: item.1, delay: item.3 - 0.25, phase: Double(i) * -0.6,
                                     from: CGSize(width: -item.2.width, height: -item.2.height), fromScale: 0.15)
                            .position(x: center.x + item.2.width, y: center.y + item.2.height)
                    }
                }

                QMark(ink: ink, played: phase == .welcome)
                    .frame(width: 140, height: 140)
                    .scaleEffect(breathe && phase != .welcome && !reduceMotion ? 0.97 : 1)
                    .keyframeAnimator(initialValue: CGFloat(0), trigger: shakes) { view, x in
                        view.offset(x: reduceMotion ? 0 : x)
                    } keyframes: { _ in
                        KeyframeTrack {
                            LinearKeyframe(-10, duration: 0.06)
                            LinearKeyframe(9, duration: 0.08)
                            LinearKeyframe(-6, duration: 0.08)
                            LinearKeyframe(4, duration: 0.08)
                            SpringKeyframe(0, duration: 0.2)
                        }
                    }
                    .overlay(alignment: .topTrailing) {
                        if phase != .welcome {
                            Padlock().offset(x: -14, y: 14).transition(.scale(scale: 0.3).combined(with: .opacity))
                        }
                    }
                    .position(center)
                    .accessibilityHidden(true)

                VStack(spacing: 8) {
                    Text(title)
                        .font(.display(30, weight: 700))
                        .tracking(-0.8)
                        .foregroundStyle(ink)
                        .multilineTextAlignment(.center)
                        .contentTransition(.opacity)
                    Text(message)
                        .font(.system(size: 16))
                        .foregroundStyle(sub)
                        .multilineTextAlignment(.center)
                        .contentTransition(.opacity)
                    Group {
                        switch phase {
                        case .scanning:
                            ScanTile(symbol: lock.methodSymbol, ink: ink, dark: dark)
                                .padding(.top, 22)
                        case .welcome:
                            EmptyView()
                        case .idle, .failed:
                            VStack(spacing: 10) {
                                Button { Task { await attempt(passcode: false) } } label: {
                                    Label(phase == .failed ? "Try again" : "Unlock with \(lock.methodName)", systemImage: lock.methodSymbol)
                                        .font(.system(size: 16, weight: .bold))
                                        .foregroundStyle(Palette.ink)
                                        .frame(maxWidth: 300, minHeight: 52)
                                        .background(Palette.marigold, in: Capsule())
                                }
                                .buttonStyle(PressScale())
                                Button { Task { await attempt(passcode: true) } } label: {
                                    Text("Use passcode")
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(ink)
                                        .frame(maxWidth: 300, minHeight: 44)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.top, 26)
                        }
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
                }
                .padding(.horizontal, 28)
                .frame(width: geo.size.width)
                .position(x: center.x, y: center.y + 200)

                VStack {
                    Spacer()
                    Label("End-to-end encrypted", systemImage: "lock.fill")
                        .font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(sub)
                        .padding(.bottom, 38)
                }
            }
        }
        .ignoresSafeArea()
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: phase)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) { breathe = true }
        }
        // Face ID asks on its own when the screen appears.
        .task { await attempt(passcode: false) }
    }

    private func attempt(passcode: Bool) async {
        guard phase != .welcome, phase != .scanning else { return }
        phase = .scanning
        switch await lock.attemptUnlock(passcode: passcode) {
        case .ok:
            phase = .welcome
            try? await Task.sleep(for: .milliseconds(reduceMotion ? 300 : 1100))
            lock.finishUnlock()
        case .failed:
            phase = .failed
            shakes += 1
        case .cancelled:
            phase = .idle
        }
    }
}

/// The marigold padlock on the Q's corner.
private struct Padlock: View {
    var body: some View {
        Image(systemName: "lock.fill")
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(Palette.ink)
            .frame(width: 38, height: 38)
            .background(Palette.marigold, in: Circle())
            .overlay(Circle().strokeBorder(Palette.ink, lineWidth: 2))
            .background(Circle().fill(Palette.ink).offset(y: 3))
    }
}

/// While Face ID looks: a rounded tile with its glyph and a green line
/// that sweeps up and down.
private struct ScanTile: View {
    let symbol: String
    let ink: Color
    let dark: Bool
    @State private var sweep = false

    var body: some View {
        RoundedRectangle(cornerRadius: 26, style: .continuous)
            .fill(dark ? Color(rgb: 0x1C1C1F) : .white)
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(ink.opacity(0.12), lineWidth: 1))
            .frame(width: 92, height: 92)
            .overlay {
                Image(systemName: symbol)
                    .font(.system(size: 40, weight: .regular))
                    .foregroundStyle(ink)
            }
            .overlay(alignment: .top) {
                Capsule()
                    .fill(Color(rgb: 0x2EBD6B))
                    .frame(width: 60, height: 3)
                    .shadow(color: Color(rgb: 0x2EBD6B).opacity(0.7), radius: 4)
                    .offset(y: sweep ? 76 : 14)
            }
            .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
            .onAppear {
                withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { sweep = true }
            }
            .accessibilityLabel("Checking it's you")
    }
}
