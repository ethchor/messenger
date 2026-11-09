import SwiftUI

// Mac and iPad, as designed (R-Large): the chat list becomes a sidebar with
// the phone's cards and shapes at sidebar sizes, the chat opens beside it on
// its dark sheet, and the tabs sit in a small pill at the sidebar's foot.
// Below about 700 pt wide (Slide Over, split screen) it's the phone layout.

extension EnvironmentValues {
    /// Sidebar and detail side by side: the Mac, and iPad at regular width.
    @Entry var wideLayout = false
}

/// The sidebar's width: 340 pt on the Mac, 360 on an iPad in landscape and
/// 320 in portrait.
struct SidebarWidth: ViewModifier {
    func body(content: Content) -> some View {
        #if os(macOS)
        content.navigationSplitViewColumnWidth(min: 300, ideal: 340, max: 420)
        #else
        content.navigationSplitViewColumnWidth(min: 300, ideal: UIScreen.main.bounds.width > UIScreen.main.bounds.height ? 360 : 320, max: 400)
        #endif
    }
}

/// Pinned chats in a sidebar: two cards side by side, 112 pt tall.
struct CompactPinnedCards: View {
    @Environment(AppModel.self) private var model
    let chats: [ConversationSummary]
    @Binding var confirm: ChatConfirm?
    @Binding var held: HeldChat?
    let open: (ConversationSummary) -> Void

    var body: some View {
        HStack(spacing: 10) {
            ForEach(Array(chats.prefix(2).enumerated()), id: \.element.id) { i, chat in
                let selected = model.selectedConversationId == chat.id
                VStack(alignment: .leading, spacing: 0) {
                    Avatar(title: chat.title, isGroup: chat.kind == .group, size: 38, stamp: true)
                        .overlay(RoundedRectangle(cornerRadius: 13, style: .continuous).strokeBorder(Palette.ink, lineWidth: 1.5))
                    Spacer(minLength: 6)
                    Text(chat.title)
                        .font(.display(16))
                        .lineLimit(2)
                        .foregroundStyle(Palette.ink)
                }
                .padding(12)
                .frame(maxWidth: .infinity, minHeight: 112, maxHeight: 112, alignment: .topLeading)
                .background(Color(rgb: PinnedCard.colour(for: chat, model: model)), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    ShapeSticker(kind: PinnedCard.shape(for: chat, model: model), size: 38, delay: 0.1 + Double(i) * 0.05).padding(8)
                }
                .overlay {
                    if selected {
                        RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Palette.text, lineWidth: 3).padding(-3)
                    }
                }
                .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .onTapGesture { open(chat) }
                .chatActions(chat, confirm: $confirm, held: $held)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { open(chat) }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }
}

/// The tabs at the foot of the sidebar: Chats, Map and You in a small pill.
struct SidebarTabs: View {
    @Environment(AppModel.self) private var model
    let openMap: () -> Void

    var body: some View {
        HStack(spacing: 2) {
            tab("Chats", on: !model.showingSettings) { model.showingSettings = false }
            tab("Map", on: false, action: openMap)
            tab("You", on: model.showingSettings) {
                model.selectedConversationId = nil
                model.showingSettings = true
            }
        }
        .padding(4)
        .background(Palette.ink, in: Capsule())
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 16)
        .animation(.spring(duration: 0.36, bounce: 0.25), value: model.showingSettings)
    }

    private func tab(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(on ? Palette.ink : Palette.cream)
                .padding(.horizontal, on ? 18 : 16)
                .frame(height: 40)
                .background(on ? Palette.marigold : .clear, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}
