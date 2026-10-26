import SwiftUI

struct ConversationListView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(Appearance.self) private var appearance
    @State private var sheet: Sheet?
    @State private var dismissedTips = ChatListTip.dismissed

    enum Sheet: String, Identifiable {
        case newChat, newGroup, settings, map
        var id: String { rawValue }
    }

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            List(selection: $model.selectedConversationId) {
                if let tip = currentTip {
                    tipCard(tip)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                }
                if !model.isOnline {
                    Label("Offline. Retrying…", systemImage: "wifi.slash")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.conversations, id: \.id) { conversation in
                    NavigationLink(value: conversation.id) {
                        ConversationRow(conversation: conversation)
                    }
                }
            }
            .overlay {
                if model.conversations.isEmpty {
                    EmptyState(
                        artwork: .illustrationChats,
                        title: "No chats yet",
                        text: "Start one with someone's username. They need to have opened Messenger once."
                    ) {
                        Button("New chat") { sheet = .newChat }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
            .navigationTitle("Chats")
            .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 420)
            .refreshable { await model.syncNow() }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { sheet = .map } label: {
                        Label("Map", systemImage: model.sharedLocations.isEmpty ? "map" : "map.fill")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { sheet = .newChat } label: { Label("New chat", systemImage: "person") }
                        Button { sheet = .newGroup } label: { Label("New group", systemImage: "person.3") }
                    } label: {
                        Label("New", systemImage: "square.and.pencil")
                    }
                }
                #if os(iOS)
                ToolbarItem(placement: .topBarLeading) {
                    Button { sheet = .settings } label: { Label("Settings", systemImage: "gearshape") }
                }
                // Panic wipe: triple-tap the title.
                ToolbarItem(placement: .principal) {
                    Text("Chats")
                        .font(.headline)
                        .contentShape(Rectangle())
                        .onTapGesture(count: 3) {
                            guard lock.panicWipe else { return }
                            Task {
                                await model.wipeThisDevice()
                                lock.reset()
                            }
                        }
                }
                #endif
            }
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        } detail: {
            if let id = model.selectedConversationId {
                ChatView(conversationId: id)
                    .id(id)
            } else {
                EmptyState(artwork: .mark, title: "Pick a chat", text: "Everything here is end-to-end encrypted: only the people in a chat can read it.")
            }
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .newChat:
                NewChatView()
            case .newGroup:
                NewGroupView()
            case .settings:
                NavigationStack { SettingsView() }
            case .map:
                PeopleMapView()
            }
        }
    }
}

extension ConversationListView {
    /// The suggestion worth showing now, if any.
    private var currentTip: ChatListTip? {
        guard !model.conversations.isEmpty else { return nil }
        let open = ChatListTip.allCases.filter { !dismissedTips.contains($0.rawValue) }
        return open.first { tip in
            switch tip {
            case .recoveryKey: model.unconfirmedRecoveryKey != nil
            case .avatar: model.myProfile == nil
            case .safetyNumbers: model.conversations.contains { $0.kind == .direct && $0.active }
            }
        }
    }

    @ViewBuilder
    private func tipCard(_ tip: ChatListTip) -> some View {
        let dismiss = {
            tip.dismiss()
            dismissedTips = ChatListTip.dismissed
        }
        switch tip {
        case .recoveryKey:
            HelperCard(
                artwork: .illustrationKey,
                title: "Write down your recovery key",
                text: "It's the only way back into your account on a new device. Nobody else has a copy.",
                onDismiss: dismiss
            ) { openSettings("Show it") }
        case .avatar:
            HelperCard(
                art: .avatars([.fox, .panda, .cat], background: appearance.ink.pastel),
                title: "Pick an avatar",
                text: "Choose a drawing and an ink. Everyone in your chats sees them; the server doesn't.",
                onDismiss: dismiss
            ) { openSettings("Choose") }
        case .safetyNumbers:
            HelperCard(
                artwork: .illustrationSafety,
                title: "Check your safety numbers",
                text: "Compare them in person with the people you talk to most. If they match, nobody is in the middle.",
                onDismiss: dismiss
            ) { openSettings("Show me") }
        }
    }

    @ViewBuilder
    private func openSettings(_ title: String) -> some View {
        #if os(macOS)
        SettingsLink { Text(title) }
            .buttonStyle(.borderless)
        #else
        Button(title) { sheet = .settings }
            .buttonStyle(.borderless)
        #endif
    }
}

struct ConversationRow: View {
    let conversation: ConversationSummary

    private var preview: String {
        if conversation.leaving { return "Leaving…" }
        guard let text = conversation.lastMessage else {
            return conversation.active ? "No messages yet" : "This chat has ended"
        }
        if conversation.kind == .group, let sender = conversation.lastSender, !sender.isEmpty {
            return "\(sender): \(text)"
        }
        return text
    }

    var body: some View {
        HStack(spacing: 12) {
            Avatar(title: conversation.title, isGroup: conversation.kind == .group)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(conversation.title)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                    Text(shortTime(conversation.lastActivityMs))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text(preview)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    Spacer()
                    if conversation.unread > 0 {
                        Text("\(conversation.unread)")
                            .font(.caption2.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(.tint))
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// Someone's avatar: the drawing they picked, or the first letter of their
/// name, on the pastel of their ink. Groups get a pair of figures.
struct Avatar: View {
    @Environment(AppModel.self) private var model
    let title: String
    var isGroup = false
    var size: CGFloat = 40

    var body: some View {
        let ink = model.ink(for: title)
        Group {
            if !isGroup, let drawing = AvatarDrawing(named: model.profiles[title]?.avatar) {
                AvatarArt(drawing: drawing, background: ink.pastel)
            } else {
                ZStack {
                    Circle().fill(ink.pastel)
                    if isGroup {
                        Image(systemName: "person.2.fill")
                            .font(.system(size: size * 0.34, weight: .medium))
                    } else {
                        Text(title.prefix(1).uppercased())
                            .font(.system(size: size * 0.44, weight: .medium, design: .serif))
                    }
                }
                .foregroundStyle(ink.deep)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// "14:05" today, "12 Oct" otherwise.
func shortTime(_ milliseconds: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    if Calendar.current.isDateInToday(date) {
        return date.formatted(date: .omitted, time: .shortened)
    }
    return date.formatted(.dateTime.day().month(.abbreviated))
}
