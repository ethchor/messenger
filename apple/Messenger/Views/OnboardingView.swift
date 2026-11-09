import SwiftUI

// MARK: - Look

/// Onboarding's paper: warm ground, faint grid, and the stamp outline.
private struct OnboardTones {
    let dark: Bool
    var ground: Color { dark ? Color(rgb: 0x0F0F11) : Color(rgb: 0xF7F5EE) }
    var grid: Color { dark ? Color(rgb: 0x18181C) : Color(rgb: 0xE9E6DA) }
    var field: Color { dark ? Color(rgb: 0x1A1A1D) : .white }
    var text: Color { dark ? Color(rgb: 0xF3F2EE) : Color(rgb: 0x141414) }
    var sub: Color { dark ? Color(rgb: 0xA3A1A9) : Color(rgb: 0x4A4840) }
    var ink: Color { dark ? Color(rgb: 0x1F1F23) : Color(rgb: 0x141414) }
    var off: Color { dark ? Color(rgb: 0x3A3A40) : Color(rgb: 0xE2DFD3) }
    var ok: Color { dark ? Color(rgb: 0x5DD08A) : Color(rgb: 0x166534) }
    var bad: Color { dark ? Color(rgb: 0xFF8A7A) : Color(rgb: 0x9A3412) }
    var seg: Color { dark ? Color(rgb: 0x2A2A2F) : Color(rgb: 0xE9E6DC) }
    var segThumb: Color { dark ? Color(rgb: 0x4A4A52) : .white }
    var stampLine: Color { dark ? Color(rgb: 0xF3F2EE) : Color(rgb: 0x141414) }
}

private struct OnboardGround: View {
    let t: OnboardTones
    var body: some View {
        ZStack { t.ground; GridPaper(line: t.grid) }.ignoresSafeArea()
    }
}

/// The stickers to pick from at sign-up, each on its own colour.
/// All of them, fox first; the tile colours take turns.
private let onboardStickers: [(AvatarDrawing, UInt32)] = {
    let tiles: [UInt32] = [0xFFD23F, 0xF4A6D0, 0xC3B5FF, 0x5DC584]
    let all = [AvatarDrawing.fox] + AvatarDrawing.allCases.filter { $0 != .fox }
    return all.enumerated().map { ($1, tiles[$0 % tiles.count]) }
}()

/// A box of stickers that scrolls on its own, about three rows tall.
private struct StickerBox<Content: View>: View {
    let t: OnboardTones
    let count: Int
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Pick a sticker · \(count) in the app")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(t.sub)
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 58, maximum: 72), spacing: 8)], spacing: 8) {
                    content
                }
                .padding(10)
            }
            .scrollIndicators(.visible)
            .frame(height: 3 * 66 + 20)
            .background(t.field.opacity(0.6), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(t.dark ? Color(rgb: 0x2E2E33) : Color(rgb: 0xD8D4C6), lineWidth: 1))
        }
    }
}

/// A rounded-square sticker with a dark outline.
private struct StampFace: View {
    let t: OnboardTones
    var drawing: AvatarDrawing?
    var initial = "V"
    var background: Color
    var side: CGFloat
    var radius: CGFloat
    var outline: CGFloat = 2

    var body: some View {
        ZStack {
            background
            if let drawing {
                AvatarArt(drawing: drawing, background: .clear, stamp: true)
                    .frame(width: side * 0.8, height: side * 0.8)
            } else {
                Text(initial)
                    .font(.display(side * 0.42, weight: 700))
                    .foregroundStyle(Color(rgb: 0x141414))
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(t.stampLine, lineWidth: outline))
    }
}

/// The big pill: marigold or ink, with an arrow in a circle at its end.
private struct ArrowPill: View {
    let title: String
    var dark = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).font(.system(size: 17, weight: .semibold))
                Spacer()
                Image(systemName: "arrow.right")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(dark ? Color(rgb: 0x141414) : Palette.marigold)
                    .frame(width: 44, height: 44)
                    .background(dark ? Palette.marigold : Color(rgb: 0x141414), in: Circle())
            }
            .padding(.leading, 24)
            .padding(.trailing, 8)
            .frame(height: 60)
            .foregroundStyle(dark ? Color(rgb: 0xF7F5EE) : Color(rgb: 0x141414))
            .background(dark ? Color(rgb: 0x141414) : Palette.marigold, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
    }
}

/// A full-width pill: marigold when it can go, muted when it can't.
private struct BigButton: View {
    let t: OnboardTones
    let title: String
    var enabled = true
    var busy = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            ZStack {
                if busy { ProgressView().tint(Color(rgb: 0x141414)) } else { Text(title) }
            }
            .font(.system(size: 17, weight: .semibold))
            .frame(maxWidth: .infinity)
            .frame(height: 60)
            .foregroundStyle(enabled ? Color(rgb: 0x141414) : (t.dark ? Color(rgb: 0x8C8A92) : Color(rgb: 0x6B6860)))
            .background(enabled ? Palette.marigold : (t.dark ? Color(rgb: 0x2A2A2F) : Color(rgb: 0xE2DFD3)), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
        .disabled(!enabled || busy)
    }
}

/// An outlined pill.
private struct OutlineButton: View {
    let t: OnboardTones
    let title: String
    var height: CGFloat = 54
    var dashed = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .foregroundStyle(t.text)
                .overlay(Capsule().stroke(t.text, style: StrokeStyle(lineWidth: 1.5, dash: dashed ? [5, 4] : [])))
                .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
    }
}

/// The iPhone-style switch in green, for onboarding's two toggles.
private struct GreenSwitch: View {
    let t: OnboardTones
    let label: String
    @Binding var on: Bool
    var body: some View {
        Button {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.6)) { on.toggle() }
        } label: {
            Capsule()
                .fill(on ? Palette.green : t.off)
                .frame(width: 51, height: 31)
                .overlay(alignment: on ? .trailing : .leading) {
                    Circle().fill(.white).frame(width: 27, height: 27).padding(2)
                        .shadow(color: .black.opacity(0.15), radius: 4, y: 3)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(on ? "On" : "Off")
        .accessibilityAddTraits(.isToggle)
    }
}

// MARK: - Flow

/// Sign-up, recovery and linking, as in the design: a sticker welcome,
/// then a name and a face, the recovery key, and a done card.
struct OnboardingView: View {
    enum Step: Equatable { case welcome, name, recover, link }

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var step = Step.welcome
    @State private var forward = true

    var body: some View {
        let t = OnboardTones(dark: scheme == .dark)
        ZStack(alignment: .top) {
            OnboardGround(t: t)
            Group {
                switch step {
                case .welcome: WelcomeStep(t: t, go: go)
                case .name: NameStep(t: t)
                case .recover: RecoverStep(t: t)
                case .link: LinkStep(t: t)
                }
            }
            .id(step)
            .transition(.asymmetric(
                insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
                removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)))
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            // The steps sit in a 520 pt column: a scroll bar would sit in the
            // middle of a wide window.
            .scrollIndicators(.hidden)

            OnboardTopBar(t: t, canBack: step != .welcome, dots: step == .name ? 0 : nil) { go(.welcome, forward: false) }
        }
        .designFields()
    }

    private func go(_ next: Step, forward: Bool = true) {
        self.forward = forward
        withAnimation(.timingCurve(0.2, 0.85, 0.2, 1, duration: 0.36)) { step = next }
    }
}

/// Back, and the three step dots while signing up.
private struct OnboardTopBar: View {
    let t: OnboardTones
    let canBack: Bool
    /// The current step of three, when it's sign-up.
    let dots: Int?
    let back: () -> Void

    var body: some View {
        HStack {
            if canBack {
                Button(action: back) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(t.text)
                        .frame(width: 44, height: 44)
                        .background(t.ground, in: Circle())
                        .overlay(Circle().stroke(t.text, lineWidth: 1.5))
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Back")
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
            Spacer()
            if let dots { StepDots(t: t, current: dots) }
            Spacer()
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }
}

private struct StepDots: View {
    let t: OnboardTones
    let current: Int
    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<3) { i in
                Capsule().fill(t.text)
                    .frame(width: i == current ? 26 : 8, height: 8)
                    .opacity(i <= current ? 1 : 0.25)
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.6), value: current)
        .accessibilityElement()
        .accessibilityLabel("Step \(current + 1) of 3")
    }
}

// MARK: - Welcome

private struct WelcomeStep: View {
    let t: OnboardTones
    let go: (OnboardingView.Step, Bool) -> Void
    @State private var shown = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StickerCollage(t: t)
                .frame(width: 342, height: 320)
                .frame(maxWidth: .infinity)
                .accessibilityHidden(true)
            Text("Every chat, just between you.")
                .font(.display(40))
                .tracking(-1)
                .lineSpacing(-4)
                .foregroundStyle(t.text)
                .padding(.top, 8)
                .riseIn(shown, delay: 0.1)
            Text("Messages lock on your phone and unlock only on theirs. Our server carries them without reading a word.")
                .font(.system(size: 16))
                .lineSpacing(4)
                .foregroundStyle(t.sub)
                .padding(.top, 12)
                .riseIn(shown, delay: 0.18)
            Spacer(minLength: 20)
            VStack(spacing: 10) {
                ArrowPill(title: "Let's go") { go(.name, true) }
                OutlineButton(t: t, title: "I have a recovery key") { go(.recover, true) }
                Button { go(.link, true) } label: {
                    Text("Use the account from my phone")
                        .font(.system(size: 15, weight: .semibold))
                        .underline()
                        .foregroundStyle(t.text)
                        .frame(height: 40)
                }
                .buttonStyle(.plain)
            }
            .riseIn(shown, delay: 0.26)
        }
        .padding(.horizontal, 24)
        .padding(.top, 64)
        .padding(.bottom, 24)
        .onAppear { shown = true }
    }
}

/// Five stickers, four shapes and two little bubbles, floating.
private struct StickerCollage: View {
    let t: OnboardTones
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .topLeading) {
            stamp(.cat, 0xF4A6D0, side: 118, radius: 30, at: CGPoint(x: 6, y: 12), delay: 0, period: 5)
            stamp(.fox, 0xFFD23F, side: 98, radius: 26, at: CGPoint(x: 146, y: 0), delay: 0.08, period: 6.5)
            stamp(.owl, 0xDCE6FA, side: 92, radius: 26, at: CGPoint(x: 250, y: 74), delay: 0.16, period: 5)
            stamp(.bear, 0x5DC584, side: 104, radius: 28, at: CGPoint(x: 34, y: 162), delay: 0.24, period: 6.5)
            stamp(.rabbit, 0xFFFFFF, side: 118, radius: 30, at: CGPoint(x: 166, y: 140), delay: 0.32, period: 5)
            ShapeSticker(kind: .sparkle, size: 56, delay: 0.45).offset(x: 282, y: 204)
            ShapeSticker(kind: .squiggle, size: 38, delay: 0.55).offset(x: 262, y: 12)
            ShapeSticker(kind: .star, size: 30, delay: 0.62).offset(x: 0, y: 132)
            ShapeSticker(kind: .bolt, size: 38, delay: 0.7).offset(x: 150, y: 270)
            bubble("Kal milte hain?", fill: Color(rgb: 0x141414), text: Color(rgb: 0xF7F5EE), tail: .bottomLeading, delay: 0.55)
                .offset(x: 112, y: 112)
            bubble("Pakka!", fill: Palette.marigold, text: Color(rgb: 0x141414), tail: .bottomTrailing, delay: 0.75, outline: true)
                .offset(x: 240, y: 272)
        }
        .frame(width: 342, height: 320, alignment: .topLeading)
        .onAppear { shown = true }
    }

    private func stamp(_ drawing: AvatarDrawing, _ color: UInt32, side: CGFloat, radius: CGFloat, at: CGPoint, delay: Double, period: Double) -> some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let y = reduceMotion ? 0 : -5 * sin(.pi * (context.date.timeIntervalSinceReferenceDate + delay * 3).truncatingRemainder(dividingBy: period) / period)
            StampFace(t: t, drawing: drawing, background: Color(rgb: color), side: side, radius: radius)
                .offset(y: y)
        }
        .frame(width: side, height: side)
        .popIn(shown, delay: delay)
        .offset(x: at.x, y: at.y)
    }

    private func bubble(_ text: String, fill: Color, text color: Color, tail: UnitPoint, delay: Double, outline: Bool = false) -> some View {
        let shape = UnevenRoundedRectangle(
            topLeadingRadius: 17, bottomLeadingRadius: tail == .bottomLeading ? 5 : 17,
            bottomTrailingRadius: tail == .bottomTrailing ? 5 : 17, topTrailingRadius: 17, style: .continuous)
        return Text(text)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 14)
            .frame(height: 34)
            .background(fill, in: shape)
            .overlay(shape.stroke(Color(rgb: 0x141414), lineWidth: outline ? 1.5 : 0))
            .fixedSize()
            .popIn(shown, delay: delay)
    }
}

// MARK: - Name and face

/// Kept until sign-up finishes, then shared as the profile.
@MainActor
enum PendingFace {
    static var drawing: AvatarDrawing?
    /// A photo instead, as a square JPEG.
    static var photo: Data?
    static var set = false
}

private struct NameStep: View {
    let t: OnboardTones
    @Environment(AppModel.self) private var model
    @State private var sticker = 0
    /// 0 sticker, 1 photo, 2 initial.
    @State private var kind = 0
    @State private var photo: Data?
    @State private var working = false
    private var useInitial: Bool { kind == 2 }
    @State private var name = ""
    @State private var showServer = false
    @State private var shake = 0
    @State private var availability = NameAvailability.idle

    private var cleaned: String { name.trimmingCharacters(in: .whitespaces).lowercased() }
    private var valid: Bool { cleaned.range(of: "^[a-z][a-z0-9_.]{2,31}$", options: .regularExpression) != nil }

    private var status: (String, Color) {
        let n = cleaned
        if n.isEmpty { return ("3 to 32 characters, starting with a letter. Then letters, numbers, dots or underscores.", t.sub) }
        if !(n.first?.isLetter ?? false) { return ("Start with a letter", t.bad) }
        if n.count < 3 { return ("A little longer: at least 3 characters", t.bad) }
        if n.count > 32 { return ("A little shorter: 32 characters at most", t.bad) }
        if !valid { return ("Only letters, numbers, dots and underscores", t.bad) }
        switch availability {
        case .free(let name) where name == n: return ("@\(n) is all yours. Nice one", t.ok)
        case .taken(let name) where name == n: return ("@\(n) is taken. How about @\(n)_\(n.count * 7 % 90 + 10)?", t.bad)
        case .unknown(let name) where name == n: return ("Couldn't check right now. We'll check when you continue.", t.sub)
        default: return ("Checking @\(n)…", t.sub)
        }
    }

    /// Free, as far as the server knows: valid and not taken (or not checkable).
    private var nameOk: Bool {
        guard valid else { return false }
        if case .taken(let name) = availability, name == cleaned { return false }
        return true
    }

    var body: some View {
        @Bindable var model = model
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Pick a name and a face.")
                    .font(.display(36))
                    .tracking(-0.7)
                    .foregroundStyle(t.text)
                HStack(spacing: 16) {
                    Group {
                        if kind == 1, let photo, let image = PlatformImage(data: photo) {
                            Image(platformImage: image).resizable().scaledToFill()
                                .frame(width: 96, height: 96)
                                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(t.stampLine, lineWidth: 2))
                        } else if kind == 1 {
                            StampFace(t: t, drawing: nil, initial: String((cleaned.first ?? "v")).uppercased(),
                                      background: Color(rgb: 0xDCE6FA), side: 96, radius: 28)
                        } else {
                            StampFace(t: t, drawing: useInitial ? nil : onboardStickers[sticker].0,
                                      initial: String((cleaned.first ?? "v")).uppercased(),
                                      background: useInitial ? Palette.marigold : Color(rgb: onboardStickers[sticker].1),
                                      side: 96, radius: 28)
                        }
                    }
                    .animation(.easeInOut(duration: 0.3), value: sticker)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("This is how you show up in every chat. Change the face any time.")
                            .font(.system(size: 14.5))
                            .foregroundStyle(t.sub)
                            .fixedSize(horizontal: false, vertical: true)
                        Segmented(t: t, labels: ["Sticker", "Photo", "Initial"], selection: $kind)
                    }
                }
                if useInitial {
                    Text("Your initial on a marigold stamp. Pick another colour any time.")
                        .font(.system(size: 14)).foregroundStyle(t.sub)
                } else if kind == 1 {
                    ProfilePhotoButtons(working: $working) { jpeg in photo = jpeg }
                    Text("Your photo is encrypted like a message. Where and when it was taken is stripped before it leaves your phone.")
                        .font(.system(size: 13)).foregroundStyle(t.sub)
                } else {
                    // Every sticker, in a box that scrolls: about three rows show.
                    StickerBox(t: t, count: onboardStickers.count) {
                        ForEach(Array(onboardStickers.enumerated()), id: \.offset) { i, item in
                                Button {
                                    withAnimation(.spring(response: 0.3, dampingFraction: 0.55)) { sticker = i }
                                } label: {
                                    let on = i == sticker
                                    AvatarArt(drawing: item.0, background: .clear, stamp: true)
                                        .frame(width: 44, height: 44)
                                        .frame(width: 58, height: 58)
                                        .background(on ? Color(rgb: item.1) : t.field, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                                            .stroke(on ? t.stampLine : (t.dark ? Color(rgb: 0x2E2E33) : Color(rgb: 0xD8D4C6)), lineWidth: on ? 2 : 1.5))
                                        .offset(y: on ? -3 : 0)
                                }
                                .buttonStyle(PressScale())
                                .accessibilityLabel(item.0.title)
                                .accessibilityAddTraits(i == sticker ? .isSelected : [])
                            }
                    }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Username").font(.system(size: 14, weight: .semibold)).foregroundStyle(t.text)
                    HStack(spacing: 3) {
                        Text("@").font(.system(size: 18)).foregroundStyle(t.sub)
                        TextField("", text: $name, prompt: Text("yourname").foregroundStyle(t.sub.opacity(0.7)))
                            .font(.system(size: 18))
                            .foregroundStyle(t.text)
                            .textContentType(.username)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                            .onSubmit { submit() }
                            .onChange(of: name) { _, new in
                                let low = new.lowercased().replacingOccurrences(of: " ", with: "")
                                if low != new { name = low }
                            }
                        if case .free(let n) = availability, n == cleaned, valid {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(t.ok)
                                .transition(.scale.combined(with: .opacity))
                        } else if valid, case .checking = availability {
                            ProgressView().controlSize(.small)
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 58)
                    .background(t.field, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(cleaned.isEmpty ? t.text : (!nameOk ? t.bad : (isFree ? t.ok : t.text)), lineWidth: 2))
                    .modifier(Shake(amount: CGFloat(shake)))
                    .animation(.default, value: valid)
                }
                Text(status.0)
                    .font(.system(size: 14))
                    .foregroundStyle(status.1)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Friends find you by your username, so make it one they'll recognise. It's yours for good: it can't be changed, only freed if you delete your account.")
                    .font(.system(size: 14))
                    .lineSpacing(3)
                    .foregroundStyle(Color(rgb: 0x141414))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.vanishAccent, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                ServerLine(t: t, expanded: $showServer)

                BigButton(t: t, title: "Continue", enabled: nameOk, busy: model.busy) { submit() }
            }
            .padding(.horizontal, 24)
            .padding(.top, 68)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        // Live: ask the server's directory a moment after typing stops.
        .task(id: cleaned + "|" + model.serverURL) {
            let name = cleaned
            guard valid else { availability = .idle; return }
            availability = .checking
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            let result = await NameAvailability.check(name, server: model.serverURL)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) { availability = result }
        }
    }

    private var isFree: Bool {
        if case .free(let n) = availability { return n == cleaned }
        return false
    }

    private func submit() {
        guard nameOk else {
            withAnimation(.linear(duration: 0.4)) { shake += 1 }
            return
        }
        let drawing = kind == 0 ? onboardStickers[sticker].0 : nil
        PendingFace.drawing = drawing
        PendingFace.photo = kind == 1 ? photo : nil
        PendingFace.set = true
        Task { await model.register(username: cleaned) }
    }
}

/// "Server: qchat.qwantarc.com · Use my own", opening to a field.
private struct ServerLine: View {
    let t: OnboardTones
    @Binding var expanded: Bool
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 8) {
            HStack(spacing: 4) {
                Text("Server: \(URL(string: model.serverURL)?.host() ?? model.serverURL) ·")
                    .foregroundStyle(t.sub)
                Button("Use my own") { expanded = true }
                    .buttonStyle(.plain)
                    .fontWeight(.semibold)
                    .foregroundStyle(t.text)
            }
            .font(.system(size: 13))
            .frame(maxWidth: .infinity)
        }
        .sheet(isPresented: $expanded) { OwnServerView() }
    }
}

private struct Segmented: View {
    let t: OnboardTones
    let labels: [String]
    @Binding var selection: Int
    @Namespace private var thumb

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                Button {
                    withAnimation(.spring(response: 0.34, dampingFraction: 0.7)) { selection = i }
                } label: {
                    Text(label)
                        .font(.system(size: 13.5, weight: i == selection ? .semibold : .medium))
                        .foregroundStyle(t.text)
                        .frame(maxWidth: .infinity)
                        .frame(height: 32)
                        .background {
                            if i == selection {
                                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(t.segThumb)
                                    .shadow(color: .black.opacity(0.12), radius: 4, y: 3)
                                    .matchedGeometryEffect(id: "thumb", in: thumb)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(i == selection ? .isSelected : [])
            }
        }
        .padding(2)
        .background(t.seg, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct Shake: GeometryEffect {
    var amount: CGFloat
    var animatableData: CGFloat {
        get { amount }
        set { amount = newValue }
    }
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 8 * sin(amount * .pi * 4), y: 0))
    }
}

// MARK: - Recover

private struct RecoverStep: View {
    let t: OnboardTones
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var key = ""
    @State private var tried = false

    private var cleanKey: String { key.lowercased().filter { $0.isLetter || $0.isNumber } }
    private var nameOk: Bool { name.trimmingCharacters(in: .whitespaces).count >= 3 }
    private var keyOk: Bool { cleanKey.count == 64 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 14) {
                    Image(systemName: "key.fill")
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(Color(rgb: 0x141414))
                        .frame(width: 64, height: 64)
                        .background(Palette.vanishAccent, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(t.stampLine, lineWidth: 2))
                    Text("Welcome back.")
                        .font(.display(36))
                        .tracking(-0.7)
                        .foregroundStyle(t.text)
                }
                Text("Enter your username and the recovery key you wrote down. Old messages can't come back, and your contacts will see a new safety number for you.")
                    .font(.system(size: 15.5)).lineSpacing(3).foregroundStyle(t.sub)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Username").font(.system(size: 14, weight: .semibold)).foregroundStyle(t.text)
                    HStack(spacing: 3) {
                        Text("@").font(.system(size: 18)).foregroundStyle(t.sub)
                        TextField("", text: $name, prompt: Text("yourname").foregroundStyle(t.sub.opacity(0.7)))
                            .font(.system(size: 18)).foregroundStyle(t.text)
                            .textContentType(.username)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 56)
                    .background(t.field, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(t.text, lineWidth: 2))
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Recovery key").font(.system(size: 14, weight: .semibold)).foregroundStyle(t.text)
                    TextField("", text: $key, prompt: Text("abcd efgh ijkl mnop …").foregroundStyle(Color(rgb: 0xF7F5EE).opacity(0.45)), axis: .vertical)
                        .font(.system(size: 16, design: .monospaced))
                        .lineLimit(3...4)
                        .foregroundStyle(Color(rgb: 0xF7F5EE))
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .padding(16)
                        .background(t.ink, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .stroke(tried && !keyOk ? t.bad : (keyOk ? t.ok : t.text), lineWidth: 2))
                }
                Text(statusText)
                    .font(.system(size: 14))
                    .foregroundStyle(tried && !keyOk ? t.bad : (keyOk ? t.ok : t.sub))
                Spacer(minLength: 10)
                BigButton(t: t, title: "Recover my account", enabled: nameOk && keyOk, busy: model.busy) {
                    tried = true
                    guard nameOk && keyOk else { return }
                    Task { await model.recover(username: name.trimmingCharacters(in: .whitespaces).lowercased(), recoveryKey: key) }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 68)
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private var statusText: String {
        if keyOk { return "That looks like a whole key." }
        if cleanKey.isEmpty { return "64 letters and numbers. Spaces don't matter." }
        return "\(cleanKey.count) of 64 characters so far."
    }
}

// MARK: - Link

private struct LinkStep: View {
    let t: OnboardTones
    @Environment(AppModel.self) private var model
    @State private var code: String?
    @State private var error: String?
    @State private var secondsLeft = 600

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Link this device.")
                    .font(.display(36)).tracking(-0.7).foregroundStyle(t.text)
                (Text("On your phone, open ") + Text("You › Linked devices › Link a device").bold().foregroundColor(t.text) + Text(" and scan this code."))
                    .font(.system(size: 15.5)).lineSpacing(3).foregroundStyle(t.sub)
                ZStack {
                    if let code {
                        QRCodeImage(text: code)
                            .frame(width: 189, height: 189)
                        Image(systemName: "key.fill")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(Color(rgb: 0x141414))
                            .frame(width: 46, height: 46)
                            .background(Palette.marigold, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color(rgb: 0x141414), lineWidth: 2))
                    } else if let error {
                        Text(error).foregroundStyle(t.bad).frame(width: 189, height: 189)
                    } else {
                        ProgressView().frame(width: 189, height: 189)
                    }
                }
                .padding(18)
                .background(.white, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).stroke(Color(rgb: 0x141414), lineWidth: 2))
                .background(Color(rgb: 0x141414), in: RoundedRectangle(cornerRadius: 30, style: .continuous).offset(y: 7))
                .frame(maxWidth: .infinity)
                if code != nil {
                    HStack(spacing: 10) {
                        PingDot()
                        Text(secondsLeft > 0 ? "Waiting for your phone · \(secondsLeft / 60):\(String(format: "%02d", secondsLeft % 60))" : "This code expired. Go back for a fresh one")
                            .font(.system(size: 14.5, weight: .semibold))
                            .foregroundStyle(t.text)
                            .monospacedDigit()
                    }
                    .frame(maxWidth: .infinity)
                    if let code {
                        Button { Clipboard.copy(code) } label: {
                            Label("Copy the code instead", systemImage: "doc.on.doc").font(.footnote.weight(.semibold))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(t.text)
                        .frame(maxWidth: .infinity)
                    }
                }
                Text("The code lasts 10 minutes. Your phone approves, then sends your chats across, sealed so the server can't open them.")
                    .font(.system(size: 13)).lineSpacing(2).foregroundStyle(t.sub)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.top, 68)
            .padding(.bottom, 24)
        }
        .task {
            do {
                code = try await model.beginLink()
                await model.waitForLink()
            } catch {
                self.error = error.userMessage
            }
        }
        .task(id: code) {
            guard code != nil else { return }
            secondsLeft = 600
            while secondsLeft > 0 {
                try? await Task.sleep(for: .seconds(1))
                secondsLeft -= 1
            }
        }
    }
}

/// A green dot with a ring pulsing out of it.
struct PingDot: View {
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ZStack {
            Circle().fill(Palette.green).scaleEffect(pulse ? 2 : 1).opacity(pulse ? 0 : 0.55)
            Circle().fill(Color(rgb: 0x2E9E5B))
        }
        .frame(width: 10, height: 10)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeOut(duration: 2).repeatForever(autoreverses: false)) { pulse = true }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Recovery key and done

/// Step 2 of 3 at sign-up ("Meet your recovery key"), then the done card.
/// From Settings, the same key page after making a new key.
struct RecoveryKeyView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(\.colorScheme) private var scheme
    let recoveryKey: String
    /// Shown from Settings after creating a new key, rather than at sign-up.
    var isReplacement = false
    var onConfirmed: (() -> Void)?
    @State private var wroteItDown = false
    @State private var revealed = true
    @State private var copied = false
    @State private var done = false

    private var groups: [String] {
        recoveryKey.split(separator: " ").map(String.init)
    }

    var body: some View {
        let t = OnboardTones(dark: scheme == .dark)
        ZStack(alignment: .top) {
            OnboardGround(t: t)
            Group {
                if done { DoneStep(t: t) } else { keyPage(t) }
            }
            .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .leading).combined(with: .opacity)))
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            if !isReplacement {
                OnboardTopBar(t: t, canBack: false, dots: done ? 2 : 1) {}
            }
        }
        .task {
            // The face picked at sign-up goes out as the profile, once.
            if !isReplacement, PendingFace.set {
                PendingFace.set = false
                await model.setProfile(avatar: PendingFace.drawing, ink: .marigold)
                if let photo = PendingFace.photo { await model.setProfilePhoto(photo) }
            }
        }
    }

    private func keyPage(_ t: OnboardTones) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(isReplacement ? "Your new recovery key." : "Meet your recovery key.")
                    .font(.display(36)).tracking(-0.7).foregroundStyle(t.text)
                Text(isReplacement
                     ? "Your old recovery key no longer works. Write this one down and keep it somewhere offline; it's the only way to move your account to a new device."
                     : "Lose every device and this gets your account back. Only you have it, not even we keep a copy, so write it down and keep it somewhere offline.")
                    .font(.system(size: 15.5)).lineSpacing(3).foregroundStyle(t.sub)
                VStack(alignment: .leading, spacing: 16) {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 11) {
                        ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                            Text(group).font(.system(size: 17, design: .monospaced)).tracking(1)
                        }
                    }
                    .foregroundStyle(Color(rgb: 0xF7F5EE))
                    .blur(radius: revealed ? 0 : 7)
                    .textSelection(.enabled)
                    .accessibilityHidden(!revealed)
                    HStack(spacing: 8) {
                        Button {
                            Clipboard.copy(recoveryKey)
                            withAnimation { copied = true }
                        } label: {
                            Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Color(rgb: 0x141414))
                                .padding(.horizontal, 18)
                                .frame(height: 44)
                                .background(copied ? Palette.green : Palette.marigold, in: Capsule())
                        }
                        .buttonStyle(PressScale())
                        Button {
                            withAnimation(.easeInOut(duration: 0.3)) { revealed.toggle() }
                        } label: {
                            Text(revealed ? "Hide" : "Show")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Color(rgb: 0xF7F5EE))
                                .padding(.horizontal, 18)
                                .frame(height: 44)
                                .overlay(Capsule().stroke(Color(rgb: 0x4A4840), lineWidth: 1.5))
                        }
                        .buttonStyle(PressScale())
                    }
                }
                .padding(20)
                .background(alignment: .topTrailing) {
                    // Two marigold rings peeking in at the corner, like a key's bow.
                    ZStack {
                        Circle().stroke(Palette.marigold, lineWidth: 2).frame(width: 80, height: 80)
                        Circle().stroke(Palette.marigold, lineWidth: 2).frame(width: 28, height: 28)
                    }
                    .frame(width: 120, height: 120)
                    .offset(x: 24, y: -28)
                    .accessibilityHidden(true)
                }
                .background(t.ink, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))

                HStack(spacing: 14) {
                    Text("I've written it down").font(.system(size: 16)).foregroundStyle(t.text)
                    Spacer()
                    GreenSwitch(t: t, label: "I've written my recovery key down", on: $wroteItDown)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(t.field, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(t.text, lineWidth: 1.5))

                Spacer(minLength: 10)
                Text("Lose it later? Make a new one in Settings and the old one stops working at once.")
                    .font(.system(size: 13)).foregroundStyle(t.sub)
                    .multilineTextAlignment(.center).frame(maxWidth: .infinity)
                BigButton(t: t, title: "Continue", enabled: wroteItDown, busy: model.busy) {
                    if isReplacement {
                        Task {
                            await model.confirmRecoveryKeySaved()
                            if model.unconfirmedRecoveryKey == nil { onConfirmed?() }
                        }
                    } else {
                        withAnimation(.timingCurve(0.2, 0.85, 0.2, 1, duration: 0.36)) { done = true }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, isReplacement ? 16 : 68)
            .padding(.bottom, 24)
        }
    }
}

/// "You're in": your face on its colour, app lock, and Start a chat.
private struct DoneStep: View {
    let t: OnboardTones
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @State private var lockOn = true

    var body: some View {
        let name = model.account?.username ?? ""
        let drawing = PendingFace.drawing ?? model.myProfile.flatMap { AvatarDrawing(named: $0.avatar) }
        let color = drawing.flatMap { d in onboardStickers.first { $0.0 == d }?.1 } ?? 0xFFD23F
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 18) {
                Group {
                    if let data = PendingFace.photo, let image = PlatformImage(data: data) {
                        Image(platformImage: image).resizable().scaledToFill()
                            .frame(width: 118, height: 118)
                            .clipShape(RoundedRectangle(cornerRadius: 34, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 34, style: .continuous).stroke(Color(rgb: 0x141414), lineWidth: 2))
                    } else {
                        StampFace(t: OnboardTones(dark: false), drawing: drawing, initial: String(name.first ?? "v").uppercased(),
                                  background: .white, side: 118, radius: 34)
                    }
                }
                .popIn(true, delay: 0)
                Text("Welcome to the club, @\(name)")
                    .font(.display(36, weight: 700))
                    .tracking(-0.7)
                Text("All set. Every chat from here is end-to-end encrypted. Who's first?")
                    .font(.system(size: 15))
                    .lineSpacing(2)
            }
            .foregroundStyle(Color(rgb: 0x141414))
            .padding(26)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(alignment: .topTrailing) {
                ZStack(alignment: .topTrailing) {
                    ShapeSticker(kind: .sparkle, size: 62, delay: 0.25).padding([.top, .trailing], 22)
                    ShapeSticker(kind: .heart, size: 36, delay: 0.35).padding(.top, 56).padding(.trailing, 104)
                    ShapeSticker(kind: .squiggle, size: 34, delay: 0.45).padding(.top, 108).padding(.trailing, 36)
                    ShapeSticker(kind: .clover, size: 30, delay: 0.55).padding(.top, 12).padding(.trailing, 130)
                }
            }
            .background(Color(rgb: color), in: RoundedRectangle(cornerRadius: 36, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 36, style: .continuous))

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Lock with \(lock.methodName)").font(.system(size: 16, weight: .semibold)).foregroundStyle(t.text)
                    Text("Only you can open Messenger").font(.system(size: 13.5)).foregroundStyle(t.sub)
                }
                Spacer()
                GreenSwitch(t: t, label: "Lock with \(lock.methodName)", on: $lockOn)
            }
            .padding(16)
            .background(t.field, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(t.text, lineWidth: 1.5))

            Spacer()
            ArrowPill(title: "Start a chat", dark: true) {
                Task {
                    // On by default, as designed; turning it on asks for Face ID once.
                    if lockOn && !lock.enabled { await lock.setEnabled(true) }
                    await model.confirmRecoveryKeySaved()
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 68)
        .padding(.bottom, 24)
    }

    private var deviceWord: String {
        #if os(macOS)
        "Mac"
        #else
        "phone"
        #endif
    }
}

// MARK: - Motion helpers

private extension View {
    /// Pops in from small with an overshoot after `delay`.
    func popIn(_ on: Bool, delay: Double) -> some View { modifier(PopIn(on: on, delay: delay)) }
    /// Fades and rises 14 pt into place after `delay`.
    func riseIn(_ on: Bool, delay: Double) -> some View { modifier(RiseIn(on: on, delay: delay)) }
}

private struct PopIn: ViewModifier {
    let on: Bool
    let delay: Double
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .scaleEffect(shown || reduceMotion ? 1 : 0.4)
            .opacity(shown || reduceMotion ? 1 : 0)
            .onAppear {
                guard on else { return }
                withAnimation(.spring(response: 0.42, dampingFraction: 0.55).delay(delay)) { shown = true }
            }
            .onChange(of: on) { _, now in
                if now { withAnimation(.spring(response: 0.42, dampingFraction: 0.55).delay(delay)) { shown = true } }
            }
    }
}

private struct RiseIn: ViewModifier {
    let on: Bool
    let delay: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .opacity(on || reduceMotion ? 1 : 0)
            .offset(y: on || reduceMotion ? 0 : 14)
            .animation(.timingCurve(0.2, 0.9, 0.25, 1, duration: 0.5).delay(delay), value: on)
    }
}


/// Whether a username is free, from the server's public directory:
/// 404 is free, 200 is taken; anything else, we can't tell yet.
enum NameAvailability: Equatable {
    case idle, checking
    case free(String), taken(String), unknown(String)

    static func check(_ name: String, server: String) async -> NameAvailability {
        var base = server.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + "/v1/directory/" + name) else { return .unknown(name) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 6
        request.cachePolicy = .reloadIgnoringLocalCacheData
        guard let (_, response) = try? await URLSession.shared.data(for: request),
              let status = (response as? HTTPURLResponse)?.statusCode else { return .unknown(name) }
        switch status {
        case 404: return .free(name)
        case 200: return .taken(name)
        default: return .unknown(name)
        }
    }
}
