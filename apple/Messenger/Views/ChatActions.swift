import SwiftUI

/// The actions that need a second tap to confirm.
enum ChatConfirm: Identifiable {
    case block(ConversationSummary)
    case clear(ConversationSummary)
    case delete(ConversationSummary)
    case exitGroup(ConversationSummary)

    var id: String {
        switch self {
        case .block(let c): "block-" + c.id
        case .clear(let c): "clear-" + c.id
        case .delete(let c): "delete-" + c.id
        case .exitGroup(let c): "exit-" + c.id
        }
    }

    var title: String {
        switch self {
        case .block(let c): "Block \(c.title)?"
        case .clear: "Clear this chat?"
        case .delete(let c): c.kind == .group ? "Delete this group?" : "Delete this chat?"
        case .exitGroup(let c): "Exit \(c.title)?"
        }
    }

    var message: String {
        switch self {
        case .block:
            "Their messages won't reach this device, here or in groups, and you can't message them. They aren't told."
        case .clear:
            "Its messages, photos and files are deleted from this device. The chat stays, and the others keep their copies."
        case .delete(let c):
            c.active
                ? (c.kind == .group
                    ? "You'll leave the group, and it's deleted from this device."
                    : "The chat ends for both of you, and it's deleted from this device.")
                : "It's deleted from this device, with its messages and files."
        case .exitGroup:
            "You'll stop getting its messages. What's already here stays."
        }
    }

    var button: String {
        switch self {
        case .block: "Block"
        case .clear: "Clear chat"
        case .delete: "Delete"
        case .exitGroup: "Exit group"
        }
    }
}

/// Every action for a chat, for its context menu.
struct ChatActionsMenu: View {
    @Environment(AppModel.self) private var model
    let conversation: ConversationSummary
    @Binding var confirm: ChatConfirm?

    private var isUnread: Bool { conversation.unread > 0 || conversation.markedUnread }

    var body: some View {
        let id = conversation.id
        Button {
            Task { await model.setRead(id, isUnread) }
        } label: {
            Label(isUnread ? "Mark as read" : "Mark as unread", systemImage: isUnread ? "envelope.open" : "envelope.badge")
        }
        if !conversation.archived {
            Button {
                Task { await model.setPinned(id, !conversation.pinned) }
            } label: {
                Label(conversation.pinned ? "Unpin" : "Pin", systemImage: conversation.pinned ? "pin.slash" : "pin")
            }
        }
        Button {
            Task { await model.setArchived(id, !conversation.archived) }
        } label: {
            Label(conversation.archived ? "Unarchive" : "Archive", systemImage: conversation.archived ? "tray.and.arrow.up" : "archivebox")
        }
        MuteMenu(conversation: conversation)
        Button {
            Task { await model.setLocked(id, !conversation.locked) }
        } label: {
            Label(conversation.locked ? "Unlock chat" : "Lock chat", systemImage: conversation.locked ? "lock.open" : "lock")
        }
        Divider()
        if conversation.kind == .direct {
            if conversation.blocked {
                Button {
                    Task { await model.setBlocked(conversation.title, false) }
                } label: {
                    Label("Unblock \(conversation.title)", systemImage: "hand.raised.slash")
                }
            } else {
                Button(role: .destructive) {
                    confirm = .block(conversation)
                } label: {
                    Label("Block \(conversation.title)", systemImage: "hand.raised")
                }
            }
        }
        Button(role: .destructive) {
            confirm = .clear(conversation)
        } label: {
            Label("Clear chat", systemImage: "eraser")
        }
        if conversation.kind == .group && conversation.active && !conversation.leaving {
            Button(role: .destructive) {
                confirm = .exitGroup(conversation)
            } label: {
                Label("Exit group", systemImage: "rectangle.portrait.and.arrow.right")
            }
        }
        Button(role: .destructive) {
            confirm = .delete(conversation)
        } label: {
            Label("Delete chat", systemImage: "trash")
        }
    }
}

/// Mute for 8 hours, a week or always, or unmute.
struct MuteMenu: View {
    @Environment(AppModel.self) private var model
    let conversation: ConversationSummary

    var body: some View {
        let id = conversation.id
        if conversation.mutedUntilMs != nil {
            Button {
                Task { await model.unmute(id) }
            } label: {
                Label("Unmute", systemImage: "bell")
            }
        } else {
            Menu {
                Button("8 hours") { Task { await model.mute(id, for: 8 * 3600) } }
                Button("1 week") { Task { await model.mute(id, for: 7 * 24 * 3600) } }
                Button("Always") { Task { await model.mute(id, for: nil) } }
            } label: {
                Label("Mute", systemImage: "bell.slash")
            }
        }
    }
}

extension View {
    /// Asks before blocking, clearing, deleting or exiting, then does it.
    func chatConfirmation(_ confirm: Binding<ChatConfirm?>, model: AppModel) -> some View {
        confirmationDialog(
            confirm.wrappedValue?.title ?? "",
            isPresented: Binding(get: { confirm.wrappedValue != nil }, set: { if !$0 { confirm.wrappedValue = nil } }),
            titleVisibility: .visible,
            presenting: confirm.wrappedValue
        ) { action in
            Button(action.button, role: .destructive) {
                Task {
                    switch action {
                    case .block(let c): await model.setBlocked(c.title, true)
                    case .clear(let c): await model.clearChat(c.id)
                    case .delete(let c): await model.deleteChat(c.id)
                    case .exitGroup(let c): try? await model.leave(c.id)
                    }
                }
            }
        } message: { action in
            Text(action.message)
        }
    }

    /// Swipes on a chat row: read or unread and pin from the leading edge;
    /// archive and delete from the trailing edge.
    func chatSwipeActions(_ conversation: ConversationSummary, model: AppModel, confirm: Binding<ChatConfirm?>) -> some View {
        let id = conversation.id
        let unread = conversation.unread > 0 || conversation.markedUnread
        return self
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                Button {
                    Task { await model.setRead(id, unread) }
                } label: {
                    Label(unread ? "Read" : "Unread", systemImage: unread ? "envelope.open" : "envelope.badge")
                }
                .tint(Palette.vanishAccent)
                if !conversation.archived {
                    Button {
                        Task { await model.setPinned(id, !conversation.pinned) }
                    } label: {
                        Label(conversation.pinned ? "Unpin" : "Pin", systemImage: conversation.pinned ? "pin.slash" : "pin")
                    }
                    .tint(Palette.tilePink)
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) {
                    confirm.wrappedValue = .delete(conversation)
                } label: {
                    Label("Delete", systemImage: "trash")
                }
                Button {
                    Task { await model.setArchived(id, !conversation.archived) }
                } label: {
                    Label(conversation.archived ? "Unarchive" : "Archive", systemImage: "archivebox")
                }
                .tint(Palette.marigold)
            }
    }
}

/// A chat's info → mute, lock, pin and archive, as switches.
struct ChatSettingsSection: View {
    @Environment(AppModel.self) private var model
    let conversation: ConversationSummary

    var body: some View {
        let id = conversation.id
        Section {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Mute").font(.system(size: 16, weight: .semibold))
                    Spacer()
                    Text(muteLabel).font(.system(size: 13.5)).foregroundStyle(Palette.secondary)
                }
                let until = conversation.mutedUntilMs
                // Wraps rather than running off the edge of a narrow column.
                FlowRow(spacing: 6) {
                    FilterChip(title: "Off", selected: until == nil) { Task { await model.unmute(id) } }
                    FilterChip(title: "8 hours", selected: false) { Task { await model.mute(id, for: 8 * 3600) } }
                    FilterChip(title: "1 week", selected: false) { Task { await model.mute(id, for: 7 * 24 * 3600) } }
                    FilterChip(title: "Always", selected: until == mutedForever()) { Task { await model.mute(id, for: nil) } }
                }
                .padding(.vertical, 2)
            }
            .padding(.vertical, 4)
            Toggle(isOn: Binding(get: { conversation.locked }, set: { on in Task { await model.setLocked(id, on) } })) {
                Label("Lock chat", systemImage: "lock")
            }
            if conversation.kind == .direct && conversation.active && !conversation.blocked {
                Toggle(isOn: Binding(get: { conversation.vanishMode }, set: { on in Task { await model.setVanishMode(id, on) } })) {
                    Label("Vanish mode", systemImage: "eye.slash")
                }
            }
            if !conversation.archived {
                Toggle(isOn: Binding(get: { conversation.pinned }, set: { on in Task { await model.setPinned(id, on) } })) {
                    Label("Pin to top", systemImage: "pin")
                }
            }
            Toggle(isOn: Binding(get: { conversation.archived }, set: { on in Task { await model.setArchived(id, on) } })) {
                Label("Archive", systemImage: "archivebox")
            }
        } footer: {
            Text("Vanish mode is for both of you: messages disappear once seen and the chat is closed, and screenshots are announced. You can also swipe up at the end of the chat. The rest is only on this device; a locked chat opens with Face ID, Touch ID or your passcode.")
        }
    }

    private var muteLabel: String {
        guard let until = conversation.mutedUntilMs else { return "Off" }
        if until == mutedForever() { return "Always" }
        let date = Date(timeIntervalSince1970: TimeInterval(until) / 1000)
        return "Until " + date.formatted(date: .abbreviated, time: .shortened)
    }
}
