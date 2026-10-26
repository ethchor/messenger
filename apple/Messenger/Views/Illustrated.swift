import SwiftUI

/// What a helper card shows beside its words.
enum HelperArt {
    /// A line drawing, in the ink.
    case drawing(Artwork)
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
        artwork: Artwork,
        title: String,
        text: String,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder actions: () -> Actions = { EmptyView() }
    ) {
        self.init(art: .drawing(artwork), title: title, text: text, onDismiss: onDismiss, actions: actions)
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
        case .drawing(let artwork):
            LineArt(artwork: artwork, paper: .incomingBubble)
                .foregroundStyle(.tint)
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
        HStack(alignment: .top, spacing: 14) {
            picture
                .frame(width: 72, height: 54)
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.headline)
                    .wrappingInListRow()
                Text(text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .wrappingInListRow()
                actions
                    .padding(.top, 2)
            }
            Spacer(minLength: 0)
            if let onDismiss {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
        }
        .padding(14)
        .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
    let artwork: Artwork
    let title: String
    let text: String
    @ViewBuilder var action: Action

    init(artwork: Artwork, title: String, text: String, @ViewBuilder action: () -> Action = { EmptyView() }) {
        self.artwork = artwork
        self.title = title
        self.text = text
        self.action = action()
    }

    var body: some View {
        VStack(spacing: 14) {
            LineArt(artwork: artwork)
                .foregroundStyle(.tint)
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
