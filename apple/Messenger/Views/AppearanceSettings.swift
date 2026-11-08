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
                VStack(alignment: .leading, spacing: 10) {
                    Text("Avatar")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 56, maximum: 64), spacing: 10)], spacing: 10) {
                        choice(selected: avatar == nil, label: "Your initial") {
                            ZStack {
                                Circle().fill(appearance.ink.pastel)
                                Text(String((model.account?.username ?? "?").prefix(1)).uppercased())
                                    .font(.system(size: 22, weight: .medium))
                                    .foregroundStyle(appearance.ink.deep)
                            }
                        } action: {
                            setProfile(avatar: nil, ink: appearance.ink)
                        }
                        ForEach(AvatarDrawing.allCases, id: \.self) { drawing in
                            choice(selected: avatar == drawing, label: drawing.title) {
                                AvatarArt(drawing: drawing, background: appearance.ink.pastel)
                            } action: {
                                setProfile(avatar: drawing, ink: appearance.ink)
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
                VStack(alignment: .leading, spacing: 10) {
                    Text("Ink")
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
                    Text(appearance.ink.name)
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
