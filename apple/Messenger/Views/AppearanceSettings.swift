import PhotosUI
import SwiftUI

/// You → Chats and appearance: theme, ink and paper, as designed.
struct LookPage: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance

    var body: some View {
        KitLabel("Theme")
        KitSegmented(
            labels: Appearance.Theme.allCases.map(\.title),
            selection: Binding(
                get: { Appearance.Theme.allCases.firstIndex(of: appearance.theme) ?? 2 },
                set: { appearance.theme = Appearance.Theme.allCases[$0] }
            ),
            large: true
        )
        .accessibilityLabel("Theme")
        KitLabel("Your ink · tints your bubbles, and how others see your avatar")
        InkSwatches(ink: inkBinding(model: model, appearance: appearance))
            .padding(.vertical, 8)
            .padding(.horizontal, 2)
        KitLabel("Chat paper")
        preview
        FlowRow(spacing: 8) {
            ForEach(Paper.allCases) { paper in
                PaperChip(paper: paper, selected: appearance.paper == paper) {
                    withAnimation(.easeOut(duration: 0.2)) { appearance.paper = paper }
                }
            }
        }
        .padding(.vertical, 4)
        Text("Live papers move gently and ripple when a message comes or goes. Pick a different paper for one chat from its details.")
            .font(.system(size: 13))
            .lineSpacing(2)
            .foregroundStyle(Palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
    }

    /// Two bubbles on the paper, in the ink: what a chat will look like.
    private var preview: some View {
        ZStack(alignment: .bottom) {
            PaperView(paper: appearance.paper, ink: appearance.ink)
            VStack(spacing: 6) {
                HStack {
                    Text("Kaia at 8:30?")
                        .foregroundStyle(Palette.cream)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(Color.incomingBubble, in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 5, bottomTrailingRadius: 16, topTrailingRadius: 16, style: .continuous))
                    Spacer(minLength: 40)
                }
                HStack {
                    Spacer(minLength: 40)
                    Text("Booked. Window table")
                        .foregroundStyle(appearance.ink.onBubble)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(appearance.ink.bubble, in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 5, topTrailingRadius: 16, style: .continuous))
                        .animation(.easeOut(duration: 0.3), value: appearance.ink)
                }
            }
            .font(.system(size: 14))
            .padding(12)
        }
        .frame(height: 150)
        .frame(maxWidth: .infinity)
        .background(Palette.sheet)
        .environment(\.colorScheme, .dark)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Palette.line, lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Preview of a chat on \(appearance.paper.name) paper")
    }
}

/// The ink, kept here and shared through the profile.
@MainActor
func inkBinding(model: AppModel, appearance: Appearance) -> Binding<Ink> {
    Binding(
        get: { appearance.ink },
        set: { ink in
            appearance.ink = ink
            Task { await model.setProfile(avatar: AvatarDrawing(named: model.myProfile?.avatar), ink: ink) }
        }
    )
}

/// You → Profile: how everyone sees you, the avatar style, and your colour.
struct ProfilePage: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    @State private var boing = 0

    private var cardColor: Color {
        appearance.ink == .marigold ? Palette.marigold : appearance.ink.pastel
    }

    var body: some View {
        hero
        AvatarStylePicker()
        KitCard(radius: 24, padding: 14) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Your colour").foregroundStyle(Palette.secondary)
                    Spacer()
                    Text(appearance.ink.name).foregroundStyle(Palette.text)
                }
                .font(.system(size: 13, weight: .semibold))
                InkSwatches(ink: inkBinding(model: model, appearance: appearance))
                Text("Tints your bubbles and your profile card. Friends see your avatar in it too.")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var hero: some View {
        VStack(spacing: 10) {
            MyAvatar(size: 120, stamp: true)
                .background(RoundedRectangle(cornerRadius: 38, style: .continuous).fill(.white))
                .overlay(RoundedRectangle(cornerRadius: 38, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                .background(RoundedRectangle(cornerRadius: 38, style: .continuous).fill(Palette.ink).offset(y: 7))
                .phaseAnimator([1.0, 0.86, 1.1, 1.0], trigger: boing) { view, scale in
                    view.scaleEffect(scale)
                } animation: { _ in .spring(duration: 0.22, bounce: 0.5) }
                .onTapGesture { boing += 1 }
                .accessibilityLabel("Your avatar")
                .accessibilityAddTraits(.isButton)
            Text("@\(model.account?.username ?? "")")
                .font(.display(28, weight: 700))
                .displayTracking(28)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.top, 6)
            Text("This is how everyone sees you")
                .font(.system(size: 13.5))
        }
        .foregroundStyle(Palette.ink)
        .frame(maxWidth: .infinity)
        .padding(.top, 22)
        .padding(.horizontal, 18)
        .padding(.bottom, 18)
        .background {
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .fill(cardColor)
                .overlay {
                    // Dots that fade in away from the avatar.
                    Canvas { context, size in
                        var dots = Path()
                        for x in stride(from: CGFloat(9), to: size.width, by: 18) {
                            for y in stride(from: CGFloat(9), to: size.height, by: 18) {
                                dots.addEllipse(in: CGRect(x: x - 1.6, y: y - 1.6, width: 3.2, height: 3.2))
                            }
                        }
                        context.fill(dots, with: .color(Palette.ink.opacity(0.11)))
                    }
                    .mask(RadialGradient(colors: [.clear, .black], center: UnitPoint(x: 0.5, y: 0.42), startRadius: 50, endRadius: 190))
                    .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
                }
        }
        .overlay(alignment: .topTrailing) {
            ShapeSticker(kind: .heart, size: 48, delay: 0.08, tappable: true).padding(.top, 18).padding(.trailing, 22)
        }
        .overlay(alignment: .topLeading) {
            ShapeSticker(kind: .sparkle, size: 32, delay: 0.18, tappable: true).padding(.top, 30).padding(.leading, 26)
        }
        .overlay(alignment: .bottomLeading) {
            ShapeSticker(kind: .clover, size: 36, delay: 0.28, tappable: true).padding(.bottom, 28).padding(.leading, 40)
        }
        .overlay(alignment: .bottomTrailing) {
            ShapeSticker(kind: .bolt, size: 26, delay: 0.36, phase: -1, tappable: true).padding(.bottom, 40).padding(.trailing, 44)
        }
        .animation(.easeInOut(duration: 0.3), value: appearance.ink)
    }
}

/// A small sample of a paper, to pick it.
struct PaperTile: View {
    let paper: Paper
    let ink: Ink
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                PaperView(paper: paper, ink: ink, still: true)
                    .environment(\.colorScheme, .dark)
                    .frame(height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .overlay {
                        RoundedRectangle(cornerRadius: 10)
                            .strokeBorder(selected ? ink.color : Color.secondary.opacity(0.3), lineWidth: selected ? 2 : 1)
                    }
                Text(paper.name)
                    .font(.caption)
                    .foregroundStyle(selected ? .primary : .secondary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(paper.name)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A chat's info → Chat paper: its own paper, or the one all chats use.
struct ChatPaperSection: View {
    @Environment(Appearance.self) private var appearance
    let conversationId: String

    var body: some View {
        let own = appearance.chatPapers[conversationId]
        let shown = own ?? appearance.paper
        Section {
            VStack(alignment: .leading, spacing: 12) {
                // A strip of the chat: two bubbles on its paper.
                ZStack {
                    PaperView(paper: shown, ink: appearance.ink, still: true)
                    HStack {
                        Text("Kal milte hain?")
                            .font(.system(size: 13))
                            .foregroundStyle(Palette.cream)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Color.incomingBubble, in: UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: 5, bottomTrailingRadius: 14, topTrailingRadius: 14))
                        Spacer()
                        Text("Pakka!")
                            .font(.system(size: 13))
                            .foregroundStyle(appearance.ink.onBubble)
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(appearance.ink.bubble, in: UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: 14, bottomTrailingRadius: 5, topTrailingRadius: 14))
                    }
                    .padding(.horizontal, 12)
                }
                .frame(height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .environment(\.colorScheme, .dark)
                .accessibilityHidden(true)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        FilterChip(title: "Same as others", count: 0, selected: own == nil) { appearance.setPaper(nil, for: conversationId) }
                        ForEach(Paper.allCases) { paper in
                            FilterChip(title: paper.name, count: 0, selected: own == paper) { appearance.setPaper(paper, for: conversationId) }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            .padding(.vertical, 4)
        } header: {
            InfoHeader(title: "Paper for this chat", detail: shown.name)
        } footer: {
            Text("Only on this device.")
        }
    }
}


/// Sticker, Photo or Initials: how you appear, with the choices for each.
struct AvatarStylePicker: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    enum Style: Int, CaseIterable { case sticker, photo, initials }
    @State private var style: Style?
    @State private var working = false

    private var me: String { model.account?.username ?? "" }
    private var hasPhoto: Bool { model.profilePhotos[me] != nil }
    private var avatar: AvatarDrawing? { AvatarDrawing(named: model.myProfile?.avatar) }
    private var current: Style { hasPhoto ? .photo : (avatar != nil ? .sticker : .initials) }

    /// The tile behind a chosen sticker, as on the design: four colours in turn.
    private static let tileColours = [Color(rgb: 0xFFD23F), Color(rgb: 0xF4A6D0), Color(rgb: 0xC3B5FF), Color(rgb: 0x5DC584)]
    private static let tileGround = Color(light: 0xF7F5EE, dark: 0x1F1F23)

    var body: some View {
        let shown = style ?? current
        KitSegmented(
            labels: ["Sticker", "Photo", "Initials"],
            selection: Binding(get: { shown.rawValue }, set: { style = Style(rawValue: $0) })
        )
        .padding(.top, 6)
        .accessibilityLabel("Avatar style")
        KitCard(radius: 24, padding: 14) {
            VStack(alignment: .leading, spacing: 10) {
                switch shown {
                case .sticker: stickers
                case .photo: photo
                case .initials: initials
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: shown)
    }

    @ViewBuilder private var stickers: some View {
        Text("Pick a sticker · \(AvatarDrawing.allCases.count) in the app")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Palette.secondary)
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
            ForEach(Array(AvatarDrawing.allCases.enumerated()), id: \.element) { index, drawing in
                let on = !hasPhoto && avatar == drawing
                Button {
                    Task {
                        if hasPhoto { await model.setProfilePhoto(nil) }
                        await model.setProfile(avatar: drawing, ink: appearance.ink)
                    }
                } label: {
                    AvatarArt(drawing: drawing, background: .clear)
                        .padding(10)
                        .aspectRatio(1, contentMode: .fit)
                        .background(on ? Self.tileColours[index % 4] : Self.tileGround, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .strokeBorder(on ? Palette.text : Palette.line, lineWidth: on ? 2 : 1.5))
                        .offset(y: on ? -3 : 0)
                        .animation(.spring(response: 0.25, dampingFraction: 0.55), value: on)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(drawing.title)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    @ViewBuilder private var photo: some View {
        ProfilePhotoButtons(working: $working) { jpeg in
            working = true
            await model.setProfilePhoto(jpeg)
            working = false
        }
        if hasPhoto {
            Button("Remove photo", role: .destructive) {
                Task { working = true; await model.setProfilePhoto(nil); working = false; style = .sticker }
            }
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Palette.danger)
            .buttonStyle(.plain)
        }
        Text("Your photo is encrypted like a message. Where and when it was taken is stripped before it leaves your device.")
            .font(.system(size: 13)).foregroundStyle(Palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private var initials: some View {
        Text("Your initial on a stamp in your colour. Clean, and always readable.")
            .font(.system(size: 13.5)).foregroundStyle(Palette.secondary)
            .fixedSize(horizontal: false, vertical: true)
        if current != .initials {
            Button("Use my initial") {
                Task {
                    if hasPhoto { await model.setProfilePhoto(nil) }
                    await model.setProfile(avatar: nil, ink: appearance.ink)
                }
            }
            .buttonStyle(.marigold)
        }
    }
}

/// "Choose photo" (dark) and "Take photo" (outlined): a square JPEG for a profile.
struct ProfilePhotoButtons: View {
    @Binding var working: Bool
    let use: (Data) async -> Void
    @State private var choosing = false
    @State private var picked: PhotosPickerItem?
    #if os(iOS)
    @State private var shooting = false
    #endif

    var body: some View {
        HStack(spacing: 8) {
            Button { choosing = true } label: {
                Group {
                    if working { ProgressView().tint(Palette.cream) } else { Text("Choose photo") }
                }
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Palette.cream)
                .frame(maxWidth: .infinity).frame(height: 48)
                .background(Palette.ink, in: Capsule())
            }
            .buttonStyle(PressScale())
            #if os(iOS)
            if CameraPicker.isAvailable {
                Button { shooting = true } label: {
                    Text("Take photo")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(maxWidth: .infinity).frame(height: 48)
                        .overlay(Capsule().stroke(Palette.text, lineWidth: 1.5))
                }
                .buttonStyle(PressScale())
            }
            #endif
        }
        .disabled(working)
        .photosPicker(isPresented: $choosing, selection: $picked, matching: .images)
        .onChange(of: picked) { _, item in
            guard let item else { return }
            picked = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let jpeg = ProfilePhotoMaker.jpeg(from: data) else { return }
                await use(jpeg)
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $shooting) {
            CameraPicker { shot in
                guard case .photo(let image) = shot, let data = image.jpegData(compressionQuality: 0.95),
                      let jpeg = ProfilePhotoMaker.jpeg(from: data) else { return }
                Task { await use(jpeg) }
            }
            .ignoresSafeArea()
        }
        #endif
    }
}
