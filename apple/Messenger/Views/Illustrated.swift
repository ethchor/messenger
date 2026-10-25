import SwiftUI

/// A short explanation with a drawing, for the moments that need one: a
/// serif title in the app's own voice, a plain sentence or two, and maybe a
/// button. Drawn on its own paper so it reads apart from the list around it.
struct HelperCard<Actions: View>: View {
    let artwork: Artwork
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
        self.artwork = artwork
        self.title = title
        self.text = text
        self.onDismiss = onDismiss
        self.actions = actions()
    }

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            LineArt(artwork: artwork, paper: .incomingBubble)
                .foregroundStyle(.tint)
                .frame(width: 72, height: 54)
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.system(.headline, design: .serif))
                Text(text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
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
                .font(.system(.title2, design: .serif).weight(.semibold))
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
