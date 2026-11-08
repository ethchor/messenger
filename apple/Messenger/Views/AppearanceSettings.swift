import PhotosUI
import SwiftUI

/// Settings → Appearance: your avatar and ink (both shared with your chats)
/// and the paper behind chats (this device only).
struct AppearanceSection: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    /// Profile: the avatar and ink everyone sees. Chats: the paper behind them.
    enum Part { case profile, chats }
    var part: Part = .profile

    private var avatar: AvatarDrawing? { AvatarDrawing(named: model.myProfile?.avatar) }

    var body: some View {
        Section {
            if part == .chats {
                preview
            }
            if part == .profile {
                AvatarStylePicker()
                    .padding(.vertical, 4)
                VStack(alignment: .leading, spacing: 10) {
                    Text("Your colour").font(.system(size: 16, weight: .semibold))
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 36, maximum: 44), spacing: 10)], spacing: 10) {
                        ForEach(Ink.allCases) { ink in
                            Button {
                                appearance.ink = ink
                                setProfile(avatar: avatar, ink: ink)
                            } label: {
                                Circle()
                                    .fill(ink.bubble)
                                    .frame(width: 30, height: 30)
                                    .padding(3)
                                    .overlay {
                                        if appearance.ink == ink {
                                            Circle().strokeBorder(ink.bubble, lineWidth: 2)
                                        }
                                    }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(ink.name)
                            .accessibilityAddTraits(appearance.ink == ink ? .isSelected : [])
                        }
                    }
                    Text("\(appearance.ink.name). Tints your bubbles and your profile card. Friends see your avatar in it too.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
            if part == .chats {
                // Three still papers, then the two that move: one row of five.
                VStack(alignment: .leading, spacing: 10) {
                    Text("Chat paper")
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5), spacing: 8) {
                        ForEach(Paper.allCases) { paper in
                            PaperTile(paper: paper, ink: appearance.ink, selected: appearance.paper == paper) {
                                appearance.paper = paper
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        } header: {
            Text(part == .profile ? "Avatar and ink" : "Appearance")
        } footer: {
            Text(part == .profile
                ? "Everyone in your chats sees your avatar, on a circle of your ink. Your ink also colours your bubbles."
                : "The paper is only on this device; a chat's info can give it its own.")
        }
    }

    /// Two bubbles on the paper, in the ink: what a chat will look like.
    private var preview: some View {
        VStack(spacing: 6) {
            HStack {
                Text("Same place as last time?")
                    .foregroundStyle(Palette.cream)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.incomingBubble, in: UnevenRoundedRectangle(topLeadingRadius: 22, bottomLeadingRadius: 8, bottomTrailingRadius: 22, topTrailingRadius: 22, style: .continuous))
                Spacer(minLength: 40)
            }
            HStack {
                Spacer(minLength: 40)
                Text("See you there at 8")
                    .foregroundStyle(appearance.ink.onBubble)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(appearance.ink.bubble, in: UnevenRoundedRectangle(topLeadingRadius: 22, bottomLeadingRadius: 22, bottomTrailingRadius: 8, topTrailingRadius: 22, style: .continuous))
            }
        }
        .font(.subheadline)
        .padding(12)
        .background {
            PaperView(paper: appearance.paper, ink: appearance.ink)
        }
        .environment(\.colorScheme, .dark)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Preview of a chat")
        .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
    }

    /// One avatar to pick, ringed in the ink when it's yours.
    private func choice<Content: View>(
        selected: Bool,
        label: String,
        @ViewBuilder content: () -> Content,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            content()
                .frame(width: 48, height: 48)
                .padding(4)
                .overlay {
                    Circle().strokeBorder(selected ? appearance.ink.color : .clear, lineWidth: 2.5)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func setProfile(avatar: AvatarDrawing?, ink: Ink) {
        Task { await model.setProfile(avatar: avatar, ink: ink) }
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

    var body: some View {
        let shown = style ?? current
        VStack(alignment: .leading, spacing: 14) {
            Picker("Avatar style", selection: Binding(get: { shown }, set: { style = $0 })) {
                Text("Sticker").tag(Style.sticker)
                Text("Photo").tag(Style.photo)
                Text("Initials").tag(Style.initials)
            }
            .pickerStyle(.segmented)
            switch shown {
            case .sticker:
                Text("Pick a sticker · \(AvatarDrawing.allCases.count) in the app")
                    .font(.system(size: 13)).foregroundStyle(Palette.secondary)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 56, maximum: 64), spacing: 10)], spacing: 10) {
                    ForEach(AvatarDrawing.allCases, id: \.self) { drawing in
                        let on = !hasPhoto && avatar == drawing
                        Button {
                            Task {
                                if hasPhoto { await model.setProfilePhoto(nil) }
                                await model.setProfile(avatar: drawing, ink: appearance.ink)
                            }
                        } label: {
                            AvatarArt(drawing: drawing, background: on ? appearance.ink.pastel : Palette.surface, stamp: true)
                                .frame(width: 56, height: 56)
                                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .strokeBorder(on ? Palette.text : Palette.line, lineWidth: on ? 2 : 1.5))
                                .offset(y: on ? -3 : 0)
                        }
                        .buttonStyle(PressScale())
                        .accessibilityLabel(drawing.title)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            case .photo:
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
                }
                Text("Your photo is encrypted like a message. Where and when it was taken is stripped before it leaves your device.")
                    .font(.system(size: 13)).foregroundStyle(Palette.secondary)
            case .initials:
                Text("Your initial on a stamp in your colour. Clean, and always readable.")
                    .font(.system(size: 13)).foregroundStyle(Palette.secondary)
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
        .animation(.easeInOut(duration: 0.2), value: shown)
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
                .frame(maxWidth: .infinity).frame(height: 46)
                .background(Palette.ink, in: Capsule())
            }
            .buttonStyle(PressScale())
            #if os(iOS)
            if CameraPicker.isAvailable {
                Button { shooting = true } label: {
                    Text("Take photo")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(maxWidth: .infinity).frame(height: 46)
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
