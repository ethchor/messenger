import SwiftUI

/// What a helper card shows beside its words.
enum HelperArt {
    /// A coloured scene.
    case illustration(Illustration)
    /// A few avatars, overlapping, on a pastel.
    case avatars([AvatarDrawing], background: Color)
}

/// A short explanation with a drawing, for the moments that need one: a
/// title, a plain sentence or two, and maybe a button. Drawn on its own
/// paper so it reads apart from the list around it.
struct HelperCard<Actions: View>: View {
    let art: HelperArt
    let title: String
    let text: String
    var onDismiss: (() -> Void)?
    @ViewBuilder var actions: Actions

    init(
        illustration: Illustration,
        title: String,
        text: String,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder actions: () -> Actions = { EmptyView() }
    ) {
        self.init(art: .illustration(illustration), title: title, text: text, onDismiss: onDismiss, actions: actions)
    }

    init(
        art: HelperArt,
        title: String,
        text: String,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder actions: () -> Actions = { EmptyView() }
    ) {
        self.art = art
        self.title = title
        self.text = text
        self.onDismiss = onDismiss
        self.actions = actions()
    }

    @ViewBuilder
    private var picture: some View {
        switch art {
        case .illustration(let illustration):
            IllustrationArt(illustration: illustration)
        case .avatars(let drawings, let background):
            // Left, right, then the middle one on top, a little raised.
            ZStack {
                ForEach([0, 2, 1].filter { $0 < drawings.count }, id: \.self) { index in
                    AvatarArt(drawing: drawings[index], background: background)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.incomingBubble).padding(-2))
                        .offset(x: CGFloat(index - 1) * 19, y: index == 1 ? -5 : 5)
                }
            }
        }
    }

    var body: some View {
        // A compact banner: the drawing, a line of title, a short sentence
        // and one pill button, with a small close button in the corner.
        HStack(alignment: .center, spacing: 12) {
            picture
                .frame(width: 72, height: 54)
                .scaleEffect(0.8)
                .frame(width: 58, height: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .wrappingInListRow()
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .wrappingInListRow()
                actions
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .controlSize(.small)
                    .tint(.accentColor)
                    .padding(.top, 5)
            }
            .padding(.trailing, onDismiss == nil ? 0 : 18)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(alignment: .topTrailing) {
            if let onDismiss {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(Color.primary.opacity(0.06)))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .padding(8)
                .accessibilityLabel("Dismiss")
            }
        }
    }
}

private extension View {
    /// On iPhone the cards sit in list rows, which would cut them to a line.
    /// On the Mac they don't, and a text that never shortens asks for a huge
    /// height when the window measures its smallest size, stretching it.
    @ViewBuilder
    func wrappingInListRow() -> some View {
        #if os(iOS)
        fixedSize(horizontal: false, vertical: true)
        #else
        self
        #endif
    }
}

/// What an empty screen says: a drawing, a title, what to do, and the
/// button that does it.
struct EmptyState<Action: View>: View {
    private enum Picture {
        case illustration(Illustration)
        /// A line drawing in the ink, like the Q.
        case line(Artwork)
    }

    private let picture: Picture
    let title: String
    let text: String
    @ViewBuilder var action: Action

    init(illustration: Illustration, title: String, text: String, @ViewBuilder action: () -> Action = { EmptyView() }) {
        self.picture = .illustration(illustration)
        self.title = title
        self.text = text
        self.action = action()
    }

    init(artwork: Artwork, title: String, text: String, @ViewBuilder action: () -> Action = { EmptyView() }) {
        self.picture = .line(artwork)
        self.title = title
        self.text = text
        self.action = action()
    }

    var body: some View {
        VStack(spacing: 14) {
            Group {
                switch picture {
                case .illustration(let illustration):
                    IllustrationArt(illustration: illustration)
                case .line(let artwork):
                    LineArt(artwork: artwork)
                        .foregroundStyle(.tint)
                }
            }
            .frame(width: 180, height: 135)
            Text(title)
                .font(.title2.weight(.semibold))
            Text(text)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
            action
                .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// One suggestion at a time above the chat list, until it's done or
/// dismissed.
enum ChatListTip: String, CaseIterable {
    case recoveryKey
    case avatar
    case safetyNumbers

    private static let dismissedKey = "tips.dismissed"

    static var dismissed: Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: dismissedKey) ?? [])
    }

    func dismiss() {
        var set = Self.dismissed
        set.insert(rawValue)
        UserDefaults.standard.set(Array(set), forKey: Self.dismissedKey)
    }

    static func reset() {
        UserDefaults.standard.removeObject(forKey: dismissedKey)
    }
}
