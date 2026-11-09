import SwiftUI

/// Holding a chat in the list, as designed: a sheet with the chat's stamp,
/// name and status, then three cards (read, pin, archive · mute, lock ·
/// block or exit, clear, delete). Mute opens its choices in place, and the
/// red ones ask first, in the sheet.
struct ChatActionsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let conversation: ConversationSummary
    @State private var muteOpen = false
    @State private var confirm: ChatConfirm?

    private var isUnread: Bool { conversation.unread > 0 || conversation.markedUnread }

    private var status: String {
        if conversation.kind == .group { return "Group · \(conversation.memberCount) members" }
        return "@\(conversation.title)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Avatar(title: conversation.title, isGroup: conversation.kind == .group, size: 52, stamp: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(conversation.title).font(.display(21)).lineLimit(1)
                    Text(status).font(.system(size: 13.5)).foregroundStyle(Palette.secondary).lineLimit(1)
                }
            }
            .padding(.horizontal, 4)
            .padding(.top, 2)
            .padding(.bottom, 4)
            if let confirm {
                confirmStep(confirm)
            } else {
                cards
            }
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 16)
        .padding(.top, 22)
        .padding(.bottom, 20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.home.ignoresSafeArea())
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: muteOpen)
        .animation(.easeOut(duration: 0.2), value: confirm?.id)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        #else
        .frame(minWidth: 380, minHeight: 520)
        #endif
    }

    @ViewBuilder private var cards: some View {
        let id = conversation.id
        card {
            row(isUnread ? "Mark as read" : "Mark as unread", isUnread ? "envelope.open" : "envelope.badge") {
                Task { await model.setRead(id, isUnread) }
                dismiss()
            }
            if !conversation.archived {
                KitSeparator()
                row(conversation.pinned ? "Unpin" : "Pin", conversation.pinned ? "pin.slash" : "pin") {
                    Task { await model.setPinned(id, !conversation.pinned) }
                    dismiss()
                }
            }
            KitSeparator()
            row(conversation.archived ? "Unarchive" : "Archive", conversation.archived ? "tray.and.arrow.up" : "archivebox") {
                Task { await model.setArchived(id, !conversation.archived) }
                dismiss()
            }
        }
        card {
            row(conversation.mutedUntilMs == nil ? "Mute" : "Muted", "bell.slash", value: muteValue) { muteOpen.toggle() }
            if muteOpen {
                FlowRow(spacing: 6) {
                    muteChip("Off", on: conversation.mutedUntilMs == nil) { await model.unmute(id) }
                    muteChip("8 hours", on: false) { await model.mute(id, for: 8 * 3600) }
                    muteChip("1 week", on: false) { await model.mute(id, for: 7 * 24 * 3600) }
                    muteChip("Always", on: conversation.mutedUntilMs == mutedForever()) { await model.mute(id, for: nil) }
                }
                .padding(.leading, 54)
                .padding(.trailing, 14)
                .padding(.bottom, 12)
                .transition(.opacity)
            }
            KitSeparator()
            row(conversation.locked ? "Unlock chat" : "Lock chat", conversation.locked ? "lock.open" : "lock") {
                Task { await model.setLocked(id, !conversation.locked) }
                dismiss()
            }
        }
        card {
            if conversation.kind == .group {
                if conversation.active && !conversation.leaving {
                    row("Exit group", "rectangle.portrait.and.arrow.right", danger: true) { confirm = .exitGroup(conversation) }
                    KitSeparator()
                }
            } else if conversation.blocked {
                row("Unblock \(conversation.title)", "hand.raised.slash") {
                    Task { await model.setBlocked(conversation.title, false) }
                    dismiss()
                }
                KitSeparator()
            } else {
                row("Block \(conversation.title)", "hand.raised", danger: true) { confirm = .block(conversation) }
                KitSeparator()
            }
            row("Clear chat", "eraser", danger: true) { confirm = .clear(conversation) }
            KitSeparator()
            row("Delete chat", "trash", danger: true) { confirm = .delete(conversation) }
        }
        Text("These stay on this device. Nothing is sent to the server or to the people in the chat.")
            .font(.system(size: 12.5))
            .foregroundStyle(Palette.secondary)
            .padding(.horizontal, 8)
    }

    private var muteValue: String {
        guard let until = conversation.mutedUntilMs else { return "Off" }
        if until == mutedForever() { return "Always" }
        return "Until " + Date(timeIntervalSince1970: TimeInterval(until) / 1000).formatted(date: .abbreviated, time: .shortened)
    }

    private func confirmStep(_ confirm: ChatConfirm) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(confirm.title).font(.display(24))
            Text(confirm.message)
                .font(.system(size: 15))
                .lineSpacing(3)
                .foregroundStyle(Palette.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Button { self.confirm = nil } label: {
                    Text("Cancel")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .overlay(Capsule().strokeBorder(Palette.text, lineWidth: 1.5))
                }
                .buttonStyle(PressScale())
                Button { Task { await perform(confirm) } } label: {
                    Text(confirm.button)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(Color(rgb: 0xD92D20), in: Capsule())
                }
                .buttonStyle(PressScale())
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
        .transition(.opacity)
    }

    private func perform(_ confirm: ChatConfirm) async {
        switch confirm {
        case .block(let c): await model.setBlocked(c.title, true)
        case .clear(let c): await model.clearChat(c.id)
        case .delete(let c): await model.deleteChat(c.id)
        case .exitGroup(let c): try? await model.leave(c.id)
        }
        dismiss()
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Kit.cardLine))
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func row(_ label: String, _ symbol: String, value: String = "", danger: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: symbol)
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 24)
                Text(label)
                    .font(.system(size: 16, weight: .medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
                if !value.isEmpty {
                    Text(value).font(.system(size: 14)).foregroundStyle(Palette.secondary)
                }
            }
            .foregroundStyle(danger ? Palette.danger : Palette.text)
            .padding(.horizontal, 16)
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func muteChip(_ title: String, on: Bool, _ work: @escaping () async -> Void) -> some View {
        Button {
            Task { await work(); dismiss() }
        } label: {
            Text(title)
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(on ? Palette.home : Palette.text)
                .padding(.horizontal, 13)
                .frame(height: 34)
                .background(on ? Palette.text : .clear, in: Capsule())
                .overlay(Capsule().strokeBorder(on ? Palette.text : Palette.line, lineWidth: 1.5))
        }
        .buttonStyle(PressScale())
    }
}

/// A chat to show the actions sheet for.
struct HeldChat: Identifiable {
    let conversation: ConversationSummary
    var id: String { conversation.id }
}

extension View {
    /// Holding a chat: the actions sheet on iPhone and iPad, the menu on the Mac.
    func chatActions(_ conversation: ConversationSummary, confirm: Binding<ChatConfirm?>, held: Binding<HeldChat?>) -> some View {
        #if os(macOS)
        contextMenu { ChatActionsMenu(conversation: conversation, confirm: confirm) }
        #else
        simultaneousGesture(
            LongPressGesture(minimumDuration: 0.4).onEnded { _ in held.wrappedValue = HeldChat(conversation: conversation) }
        )
        .sensoryFeedback(.impact(weight: .medium), trigger: held.wrappedValue?.id == conversation.id)
        #endif
    }
}
