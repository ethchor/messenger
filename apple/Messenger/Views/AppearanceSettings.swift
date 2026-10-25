import SwiftUI

/// Settings → Appearance: your avatar and ink (both shared with your chats)
/// and the paper behind chats (this device only).
struct AppearanceSection: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance

    private var avatar: AvatarDrawing? { AvatarDrawing(named: model.myProfile?.avatar) }

    var body: some View {
        Section {
            preview
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
                                .fill(ink.color)
                                .frame(width: 30, height: 30)
                                .padding(3)
                                .overlay {
                                    if appearance.ink == ink {
                                        Circle().strokeBorder(ink.color, lineWidth: 2)
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
            VStack(alignment: .leading, spacing: 10) {
                Text("Chat paper")
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 64, maximum: 80), spacing: 10)], spacing: 10) {
                    ForEach(Paper.allCases) { paper in
                        PaperTile(paper: paper, ink: appearance.ink, selected: appearance.paper == paper) {
                            appearance.paper = paper
                        }
                    }
                }
            }
            .padding(.vertical, 4)
        } header: {
            Text("Appearance")
        } footer: {
            Text("Everyone in your chats sees your avatar, on a circle of your ink. The paper is only on this device; a chat's info can give it its own.")
        }
    }

    /// Two bubbles on the paper, in the ink: what a chat will look like.
    private var preview: some View {
        VStack(spacing: 6) {
            HStack {
                Text("Same place as last time?")
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 16))
                Spacer(minLength: 40)
            }
            HStack {
                Spacer(minLength: 40)
                Text("See you there at 8")
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(appearance.ink.bubble, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .font(.subheadline)
        .padding(12)
        .background {
            PaperView(paper: appearance.paper, ink: appearance.ink)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
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
                PaperView(paper: paper, ink: ink)
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
        Section {
            Picker("Chat paper", selection: Binding(
                get: { appearance.chatPapers[conversationId] },
                set: { appearance.setPaper($0, for: conversationId) }
            )) {
                Text("Same as other chats (\(appearance.paper.name))").tag(Paper?.none)
                ForEach(Paper.allCases) { paper in
                    Text(paper.name).tag(Paper?.some(paper))
                }
            }
        } footer: {
            Text("Only on this device.")
        }
    }
}
