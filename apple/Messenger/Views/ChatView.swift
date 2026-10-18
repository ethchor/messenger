import SwiftUI

struct ChatView: View {
    @Environment(AppModel.self) private var model
    let conversationId: String

    @State private var draft = ""
    @State private var replyTo: MessageView?
    @State private var editing: MessageView?
    @State private var editText = ""
    @State private var showInfo = false

    private var conversation: ConversationSummary? {
        model.conversations.first { $0.id == conversationId }
    }

    private var messages: [MessageView] {
        model.messages[conversationId] ?? []
    }

    private var canSend: Bool {
        guard let conversation else { return false }
        return conversation.active && !conversation.leaving
    }

    private var composerPlaceholder: String {
        guard let conversation else { return "Message" }
        if conversation.leaving { return "Leaving this chat…" }
        if !conversation.active {
            return conversation.kind == .direct ? "This chat has ended" : "You're no longer in this group"
        }
        return "Message"
    }

    private var typingNames: [String] {
        model.typing[conversationId] ?? []
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(messages, id: \.id) { message in
                        MessageRow(
                            message: message,
                            showSender: conversation?.kind == .group,
                            onReply: { replyTo = message },
                            onReact: { emoji in Task { await model.react(to: message, in: conversationId, emoji: emoji) } },
                            onEdit: {
                                editText = message.text
                                editing = message
                            },
                            onDelete: { Task { await model.delete(message, in: conversationId) } }
                        )
                        .id(message.id)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: messages.last?.id) { _, last in
                guard let last else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last, anchor: .bottom) }
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 0) {
                if !typingNames.isEmpty {
                    TypingIndicator(names: typingNames, isGroup: conversation?.kind == .group)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 4)
                        .transition(.opacity)
                }
                composer
            }
            .animation(.easeInOut(duration: 0.2), value: typingNames)
        }
        .navigationTitle(conversation?.title ?? "")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showInfo = true } label: { Label("Info", systemImage: "info.circle") }
            }
        }
        .sheet(isPresented: $showInfo) {
            ConversationInfoView(conversationId: conversationId)
        }
        .task(id: conversationId) {
            await model.loadMessages(conversationId)
            await model.markRead(conversationId)
        }
        .onChange(of: messages.count) { _, _ in
            Task { await model.markRead(conversationId) }
        }
        .onChange(of: draft) { _, text in
            model.setTyping(!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, in: conversationId)
        }
        .onDisappear { model.setTyping(false, in: conversationId) }
        // Typing indicators expire on their own; look again after a while.
        .task(id: typingNames) {
            guard !typingNames.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 6_500_000_000)
            await model.refreshTyping(conversationId)
        }
        .alert("Edit message", isPresented: editingShown) {
            TextField("Message", text: $editText)
            Button("Save") {
                if let message = editing {
                    let text = editText
                    Task { await model.edit(message, in: conversationId, text: text) }
                }
                editing = nil
            }
            Button("Cancel", role: .cancel) { editing = nil }
        }
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if let replyTo {
                HStack(spacing: 8) {
                    Rectangle().fill(Color.accentColor).frame(width: 3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Replying to \(replyTo.outgoing ? "yourself" : replyTo.sender)")
                            .font(.caption.bold())
                        Text(replyTo.text)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button { self.replyTo = nil } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                }
                .frame(height: 36)
                .padding(.horizontal, 12)
                .padding(.top, 8)
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField(composerPlaceholder, text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 18))
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .keyboardShortcut(.return, modifiers: .command)
                .accessibilityLabel("Send")
            }
            .padding(8)
        }
        .background(.bar)
        .disabled(!canSend)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        let reply = replyTo?.id
        replyTo = nil
        Task { await model.send(text, to: conversationId, replyTo: reply) }
    }
}

struct MessageRow: View {
    let message: MessageView
    let showSender: Bool
    var onReply: () -> Void
    var onReact: (String?) -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void

    static let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    private var myReaction: String? {
        message.reactions.first(where: \.mine)?.emoji
    }

    var body: some View {
        if message.system {
            Text(message.text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        } else {
            HStack(alignment: .bottom) {
                if message.outgoing { Spacer(minLength: 48) }
                VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 3) {
                    if showSender && !message.outgoing {
                        Text(message.sender)
                            .font(.caption.bold())
                            .foregroundStyle(.secondary)
                            .padding(.leading, 12)
                    }
                    bubble
                    if !message.reactions.isEmpty {
                        reactions
                    }
                }
                if !message.outgoing { Spacer(minLength: 48) }
            }
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let reply = message.replyTo {
                VStack(alignment: .leading, spacing: 1) {
                    Text(reply.sender).font(.caption.bold())
                    Text(reply.text).font(.caption).lineLimit(2)
                }
                .padding(6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.black.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            }
            Text(message.deleted ? "Message deleted" : message.text)
                .italic(message.deleted)
                .textSelection(.enabled)
            HStack(spacing: 4) {
                if message.edited && !message.deleted { Text("edited") }
                Text(shortTime(message.sentAtMs))
                statusIcon
            }
            .font(.caption2)
            .opacity(0.7)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .foregroundStyle(message.outgoing ? Color.white : Color.primary)
        .background(
            message.outgoing ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(Color.secondary.opacity(0.15)),
            in: RoundedRectangle(cornerRadius: 18)
        )
        .contextMenu { menu }
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch message.status {
        case .sending:
            Image(systemName: "clock")
        case .failed:
            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
        case .sent:
            Image(systemName: "checkmark")
                .accessibilityLabel("Sent")
        case .read:
            // Two overlapping ticks.
            HStack(spacing: -5) {
                Image(systemName: "checkmark")
                Image(systemName: "checkmark")
            }
            .fontWeight(.bold)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Read")
        case .received:
            EmptyView()
        }
    }

    @ViewBuilder
    private var menu: some View {
        if !message.deleted {
            Button { onReply() } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") }
            Menu {
                ForEach(Self.quickReactions, id: \.self) { emoji in
                    Button(emoji) { onReact(emoji) }
                }
                if myReaction != nil {
                    Button("Remove my reaction") { onReact(nil) }
                }
            } label: {
                Label("React", systemImage: "face.smiling")
            }
            Button { Clipboard.copy(message.text) } label: { Label("Copy", systemImage: "doc.on.doc") }
            if message.outgoing && !message.readBy.isEmpty {
                Text("Read by \(message.readBy.joined(separator: ", "))")
            }
            if message.outgoing {
                Button { onEdit() } label: { Label("Edit", systemImage: "pencil") }
                Button(role: .destructive) { onDelete() } label: { Label("Delete for everyone", systemImage: "trash") }
            }
        }
    }

    private var reactions: some View {
        HStack(spacing: 4) {
            ForEach(message.reactions, id: \.emoji) { reaction in
                Button {
                    onReact(reaction.mine ? nil : reaction.emoji)
                } label: {
                    Text(reaction.count > 1 ? "\(reaction.emoji) \(reaction.count)" : reaction.emoji)
                        .font(.caption)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            Capsule().fill(reaction.mine ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.15))
                        )
                }
                .buttonStyle(.plain)
                .help(reaction.usernames.joined(separator: ", "))
            }
        }
    }
}

/// "alice is typing…" with three pulsing dots.
struct TypingIndicator: View {
    let names: [String]
    let isGroup: Bool

    private var text: String {
        switch names.count {
        case 0: return ""
        case 1: return isGroup ? "\(names[0]) is typing" : "typing"
        case 2: return "\(names[0]) and \(names[1]) are typing"
        default: return "\(names.count) people are typing"
        }
    }

    var body: some View {
        HStack(spacing: 6) {
            TimelineView(.animation(minimumInterval: 0.3)) { context in
                let step = Int(context.date.timeIntervalSinceReferenceDate / 0.3) % 3
                HStack(spacing: 3) {
                    ForEach(0..<3, id: \.self) { index in
                        Circle()
                            .frame(width: 6, height: 6)
                            .opacity(index == step ? 1 : 0.35)
                    }
                }
            }
            Text(text)
                .font(.caption)
        }
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(names.count == 1 ? "\(names[0]) is typing" : "\(names.joined(separator: ", ")) are typing")
    }
}
