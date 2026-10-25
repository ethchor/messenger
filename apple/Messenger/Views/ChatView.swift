import SwiftUI

struct ChatView: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
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
                LazyVStack(spacing: 0) {
                    ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                        MessageRow(
                            message: message,
                            isGroup: conversation?.kind == .group,
                            run: run(at: index),
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
            .overlay {
                if messages.isEmpty, let conversation, conversation.active {
                    EmptyState(
                        artwork: .illustrationChat,
                        title: "Say hello",
                        text: conversation.kind == .direct
                            ? "Only you and \(conversation.title) can read what's sent here."
                            : "Only the people in \(conversation.title) can read what's sent here."
                    )
                }
            }
            .background {
                PaperView(paper: appearance.paper(for: conversationId), ink: appearance.ink)
                    .ignoresSafeArea()
            }
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

    /// Where a message sits in a run of messages from the same person.
    private func run(at index: Int) -> MessageRun {
        func sameSender(_ a: MessageView, _ b: MessageView) -> Bool {
            !a.system && !b.system && a.outgoing == b.outgoing && a.sender == b.sender
        }
        let message = messages[index]
        let previous = index > 0 ? messages[index - 1] : nil
        let next = index + 1 < messages.count ? messages[index + 1] : nil
        return MessageRun(
            first: previous.map { !sameSender($0, message) } ?? true,
            last: next.map { !sameSender(message, $0) } ?? true
        )
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if let replyTo {
                HStack(spacing: 8) {
                    Rectangle().fill(.tint).frame(width: 3)
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
                    .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 18))
                    .onSubmit(send)
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
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

/// Where a message sits among consecutive messages from the same person.
struct MessageRun {
    var first = true
    var last = true
}

struct MessageRow: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    let message: MessageView
    let isGroup: Bool
    var run = MessageRun()
    var onReply: () -> Void
    var onReact: (String?) -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void

    static let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    private var myReaction: String? {
        message.reactions.first(where: \.mine)?.emoji
    }

    /// Others' messages in groups get a name and an avatar.
    private var showsSender: Bool { isGroup && !message.outgoing }

    var body: some View {
        if message.system {
            Text(message.text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        } else {
            HStack(alignment: .bottom, spacing: 6) {
                if message.outgoing { Spacer(minLength: 48) }
                if showsSender {
                    // Only the last of a run carries the avatar; the others keep its place.
                    if run.last {
                        Avatar(title: message.sender, size: 28)
                    } else {
                        Color.clear.frame(width: 28, height: 1)
                    }
                }
                VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 3) {
                    if showsSender && run.first {
                        Text(message.sender)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(model.ink(for: message.sender).color)
                            .padding(.leading, 12)
                    }
                    bubble
                    if !message.reactions.isEmpty {
                        reactions
                    }
                }
                if !message.outgoing { Spacer(minLength: 48) }
            }
            .padding(.top, run.first ? 8 : 2)
        }
    }

    /// Rounded all round, except where it joins the next or previous bubble
    /// from the same person.
    private var bubbleShape: UnevenRoundedRectangle {
        let big: CGFloat = 18
        let small: CGFloat = 6
        let top = run.first ? big : small
        let bottom = run.last ? big : small
        return message.outgoing
            ? UnevenRoundedRectangle(topLeadingRadius: big, bottomLeadingRadius: big, bottomTrailingRadius: bottom, topTrailingRadius: top)
            : UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom, bottomTrailingRadius: big, topTrailingRadius: big)
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
                .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
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
        .background(message.outgoing ? appearance.ink.bubble : Color.incomingBubble, in: bubbleShape)
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
                            Capsule().fill(reaction.mine ? AnyShapeStyle(appearance.ink.wash) : AnyShapeStyle(Color.incomingBubble))
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
