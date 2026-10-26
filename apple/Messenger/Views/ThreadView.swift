import SwiftUI

/// A thread: which chat, and the message it hangs off.
struct ThreadRef: Identifiable, Hashable {
    let conversationId: String
    let rootId: String

    var id: String { "\(conversationId)/\(rootId)" }
}

/// A thread on its own: the message it hangs off, the replies under it, and
/// a box to reply in. Replies here stay out of the chat.
struct ThreadView: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    @Environment(\.dismiss) private var dismiss
    let ref: ThreadRef

    @State private var draft = ""
    @State private var editing: MessageView?
    @State private var editText = ""
    @State private var reactingTo: MessageView?

    private var conversation: ConversationSummary? {
        model.conversations.first { $0.id == ref.conversationId }
    }

    private var messages: [MessageView] {
        model.threads[ref.rootId] ?? []
    }

    private var root: MessageView? {
        messages.first { $0.id == ref.rootId }
    }

    private var replies: [MessageView] {
        messages.filter { $0.id != ref.rootId }
    }

    private var isGroup: Bool {
        conversation?.kind == .group
    }

    private var canSend: Bool {
        guard let conversation else { return false }
        return conversation.active && !conversation.leaving
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let root {
                        row(root, run: MessageRun())
                        HStack(spacing: 10) {
                            Text(replies.count == 1 ? "1 reply" : "\(replies.count) replies")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .fixedSize()
                            VStack { Divider() }
                        }
                        .padding(.vertical, 12)
                    }
                    ForEach(Array(replies.enumerated()), id: \.element.id) { index, reply in
                        row(reply, run: MessageRun(at: index, in: replies))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .background {
                PaperView(paper: appearance.paper(for: ref.conversationId), ink: appearance.ink)
                    .ignoresSafeArea()
            }
            .safeAreaInset(edge: .bottom) {
                MessageComposer(text: $draft, placeholder: canSend ? "Reply in thread" : "You can't reply here", onSend: send)
                    .background(.bar)
                    .disabled(!canSend)
            }
            .navigationTitle(conversation.map { "Thread in \($0.title)" } ?? "Thread")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task(id: ref) {
                await model.loadThread(ref)
                await model.markThreadRead(ref)
            }
            .onChange(of: replies.count) { _, _ in
                Task { await model.markThreadRead(ref) }
            }
            .sheet(item: $reactingTo) { message in
                EmojiPicker { emoji in
                    Task { await model.react(to: message, in: ref.conversationId, emoji: emoji) }
                }
            }
            .alert("Edit message", isPresented: editingShown) {
                TextField("Message", text: $editText)
                Button("Save") {
                    if let message = editing {
                        let text = editText
                        Task { await model.edit(message, in: ref.conversationId, text: text) }
                    }
                    editing = nil
                }
                Button("Cancel", role: .cancel) { editing = nil }
            }
        }
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 520, minHeight: 520, idealHeight: 640)
        #endif
    }

    private func row(_ message: MessageView, run: MessageRun) -> some View {
        MessageRow(
            message: message,
            isGroup: isGroup,
            run: run,
            onReact: { emoji in Task { await model.react(to: message, in: ref.conversationId, emoji: emoji) } },
            onMoreReactions: { reactingTo = message },
            onEdit: {
                editText = message.text
                editing = message
            },
            onDelete: { Task { await model.delete(message, in: ref.conversationId) } }
        )
        .id(message.id)
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        draft = ""
        Task { await model.replyInThread(text, to: ref) }
    }
}

/// Under a message with a thread: who replied, how many, and what's new.
struct ThreadSummaryRow: View {
    @Environment(AppModel.self) private var model
    let thread: ThreadSummary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                HStack(spacing: -6) {
                    ForEach(thread.participants.reversed(), id: \.self) { name in
                        Avatar(title: name, size: 20)
                            .overlay(Circle().stroke(Color.messengerBackground, lineWidth: 1.5))
                    }
                }
                Text(thread.replyCount == 1 ? "1 reply" : "\(thread.replyCount) replies")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
                if thread.unreadCount > 0 {
                    Circle().fill(.tint).frame(width: 6, height: 6)
                    Text("\(thread.unreadCount) new")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tint)
                } else {
                    Text(shortTime(thread.lastReplyAtMs))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.incomingBubble.opacity(0.9), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
    }

    private var accessibilityText: String {
        let replies = thread.replyCount == 1 ? "1 reply" : "\(thread.replyCount) replies"
        let new = thread.unreadCount > 0 ? ", \(thread.unreadCount) new" : ""
        return "Thread: \(replies)\(new), from \(thread.participants.joined(separator: ", "))"
    }
}

/// The text box and send button under a chat or a thread.
struct MessageComposer: View {
    @Binding var text: String
    let placeholder: String
    let onSend: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(placeholder, text: $text, axis: .vertical)
                .lineLimit(1...6)
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 18))
                .onSubmit(onSend)
            Button(action: onSend) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 30))
            }
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel("Send")
        }
        .padding(8)
    }
}
