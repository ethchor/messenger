import SwiftUI

struct ConversationListView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @State private var sheet: Sheet?

    enum Sheet: String, Identifiable {
        case newChat, newGroup, settings
        var id: String { rawValue }
    }

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            List(selection: $model.selectedConversationId) {
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
                    ContentUnavailableView {
                        Label("No chats yet", systemImage: "bubble.left.and.bubble.right")
                    } description: {
                        Text("Start a chat with someone's username.")
                    } actions: {
                        Button("New chat") { sheet = .newChat }
                    }
                }
            }
            .navigationTitle("Chats")
            .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 420)
            .refreshable { await model.syncNow() }
            .toolbar {
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
                ContentUnavailableView("Pick a chat", systemImage: "bubble.left")
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
            }
        }
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
                            .background(Capsule().fill(Color.accentColor))
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct Avatar: View {
    let title: String
    var isGroup = false
    var size: CGFloat = 40

    private static let palette: [Color] = [.indigo, .teal, .orange, .pink, .purple, .blue, .green, .mint]

    private var color: Color {
        // Stable across launches (unlike hashValue).
        let sum = title.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        return Self.palette[sum % Self.palette.count]
    }

    var body: some View {
        ZStack {
            Circle().fill(color.gradient)
            if isGroup {
                Image(systemName: "person.3.fill")
                    .font(.system(size: size * 0.32))
                    .foregroundStyle(.white)
            } else {
                Text(title.prefix(1).uppercased())
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
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
