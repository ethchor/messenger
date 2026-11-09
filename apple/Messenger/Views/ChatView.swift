import SwiftUI
#if os(iOS)
import UIKit
#endif

struct ChatView: View {
    #if os(macOS)
    private let wideChat = true
    #else
    @Environment(\.horizontalSizeClass) private var chatSizeClass
    private var wideChat: Bool { chatSizeClass == .regular }
    #endif
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    let conversationId: String

    @State private var draft = ""
    @State private var replyTo: MessageView?
    @State private var editing: MessageView?
    @State private var editText = ""
    @State private var showInfo = false
    @State private var reactingTo: MessageView?
    @State private var tray = AttachmentTray()
    @State private var opened = OpenedAttachment()
    /// How far a swipe up past the last message has gone, 0 to 1.
    @State private var vanishPull: CGFloat = 0
    @State private var atBottom = false
    /// The pull counts only if it began at the end of the chat.
    @State private var pullTracking: Bool?
    @State private var pull = VanishPullFeel()

    private var inVanish: Bool { conversation?.vanishMode == true }

    /// Vanish mode is for 1:1 chats still going.
    private var canVanish: Bool {
        conversation?.kind == .direct && canSend && conversation?.blocked == false
    }

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
            MessageScroll {
                LazyVStack(spacing: 0) {
                    ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                        MessageRow(
                            message: message,
                            conversationId: conversationId,
                            isGroup: conversation?.kind == .group,
                            run: MessageRun(at: index, in: messages),
                            onReply: { replyTo = message },
                            onReplyInThread: { openThread(message) },
                            onOpenThread: { openThread(message) },
                            onReact: { emoji in Task { await model.react(to: message, in: conversationId, emoji: emoji) } },
                            onMoreReactions: { reactingTo = message },
                            onEdit: {
                                editText = message.text
                                editing = message
                            },
                            onDelete: { Task { await model.delete(message, in: conversationId) } },
                            onOpenAttachment: { opened.open($0, of: message, in: conversationId) }
                        )
                        .id(message.id)
                    }
                    // Seen when the list is scrolled to its end, where a
                    // swipe up turns vanish mode on or off.
                    Color.clear
                        .frame(height: 1)
                        .onAppear { atBottom = true }
                        .onDisappear { atBottom = false }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { value in
                        // Decided once, at the start: at the end of the chat,
                        // and going up more than sideways.
                        if pullTracking == nil {
                            pullTracking = canVanish && atBottom && value.translation.height < 0
                                && abs(value.translation.height) > abs(value.translation.width) * 1.5
                        }
                        guard pullTracking == true else { return }
                        vanishPull = pull.progress(forDistance: -value.translation.height)
                    }
                    .onEnded { _ in
                        if pullTracking == true && pull.armed {
                            let on = !inVanish
                            Task { await model.setVanishMode(conversationId, on) }
                        }
                        pullTracking = nil
                        pull.reset()
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { vanishPull = 0 }
                    }
            )
            .overlay(alignment: .bottom) {
                if vanishPull > 0 {
                    VanishPullHint(progress: vanishPull, turningOn: !inVanish)
                        .padding(.bottom, 8)
                        .transition(.opacity)
                }
            }
            .overlay {
                if messages.isEmpty, let conversation, conversation.active {
                    VStack(spacing: 14) {
                        ZStack(alignment: .topLeading) {
                            ShapeSticker(kind: .bubble, size: 101, delay: 0.02).offset(x: 11, y: 0)
                            ShapeSticker(kind: .heart, size: 42, delay: 0.22).offset(x: 92, y: -20)
                            ShapeSticker(kind: .sparkle, size: 22, delay: 0.34, phase: -1).offset(x: 0, y: -16)
                        }
                        .frame(width: 140, height: 101, alignment: .bottomLeading)
                        Text("Nothing here yet. Go on, break the ice.")
                            .font(.system(size: 14.5))
                            .foregroundStyle(Palette.cream.opacity(0.75))
                        Text(conversation.kind == .direct
                             ? "Only you and \(conversation.title) can read what's sent here."
                             : "Only the people in \(conversation.title) can read what's sent here.")
                            .font(.system(size: 12.5))
                            .foregroundStyle(Palette.cream.opacity(0.5))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 30)
                    }
                }
            }
            .onChange(of: messages.last?.id) { _, last in
                guard let last else { return }
                withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last, anchor: .bottom) }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if inVanish {
                VanishBanner { Task { await model.setVanishMode(conversationId, false) } }
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
        // The messages and the composer sit on a dark sheet, in both modes,
        // with the chat's paper on it.
        .background {
            ZStack {
                inVanish ? Palette.vanishSheet : Palette.sheet
                if !inVanish {
                    PaperView(
                        paper: appearance.paper(for: conversationId),
                        ink: appearance.ink,
                        pulse: messages.last.map { LivePulse(id: $0.id, outgoing: $0.outgoing) },
                        background: Palette.sheet
                    )
                }
            }
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: 30, topTrailingRadius: 30, style: .continuous))
            .ignoresSafeArea(edges: .bottom)
        }
        .environment(\.colorScheme, .dark)
        .padding(.top, 6)
        // Wide screens: the sheet sits in from the edges, as designed.
        .padding(.horizontal, wideChat ? 16 : 0)
        .safeAreaInset(edge: .top, spacing: 0) {
            if !model.isOnline {
                Label("No connection. Messages send when you're back", systemImage: "wifi.slash")
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(Color(rgb: 0x5A3E00))
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .background(Color(rgb: 0xFFE3A3), in: Capsule())
                    .padding(.horizontal, 14)
                    .padding(.bottom, 4)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: model.isOnline)
        .background { HomeGround(vanish: inVanish).ignoresSafeArea() }
        .navigationTitle(conversation?.title ?? "")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar(.hidden, for: .tabBar)
        #endif
        .toolbar {
            ToolbarItem(placement: .principal) {
                ChatHeader(conversation: conversation, typing: !typingNames.isEmpty) { showInfo = true }
            }
            #if os(macOS)
            ToolbarItem(placement: .primaryAction) {
                Button { showInfo.toggle() } label: { Label("Chat details", systemImage: "sidebar.right") }
                    .help("Chat details")
            }
            #endif
        }
        #if os(macOS)
        // The Mac: details as a third column beside the chat.
        .inspector(isPresented: $showInfo) {
            ConversationInfoView(conversationId: conversationId)
                .inspectorColumnWidth(min: 280, ideal: 300, max: 360)
        }
        #else
        .sheet(isPresented: $showInfo) {
            ConversationInfoView(conversationId: conversationId)
        }
        #endif
        .sheet(item: threadShown) { ref in
            ThreadView(ref: ref)
        }
        .sheet(item: $reactingTo) { message in
            EmojiPicker { emoji in
                Task { await model.react(to: message, in: conversationId, emoji: emoji) }
            }
        }
        .attachmentViewers($opened)
            .onChange(of: model.replyRequest) { _, id in
                guard let id, let message = model.message(id, in: conversationId) else { return }
                replyTo = message
                model.replyRequest = nil
            }
        // Vanish mode is dark, as on Instagram.
        .modifier(DarkWhen(on: inVanish))
        .animation(.easeInOut(duration: 0.3), value: inVanish)
        .onDisappear {
            Task { await model.closeChat(conversationId) }
        }
        #if os(iOS)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            if inVanish { Task { await model.reportScreenshot(conversationId) } }
        }
        #endif
        .task(id: model.pendingCapture?.conversationId) {
            if let capture = model.pendingCapture, capture.conversationId == conversationId {
                model.pendingCapture = nil
                tray.add(capture.source)
            }
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
        .onDisappear {
            model.setTyping(false, in: conversationId)
            // Full-screen covers (the camera, the photo viewer) also hide the
            // chat; the files stay unless the chat was left.
            if model.selectedConversationId != conversationId { tray.clear() }
        }
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

    private func openThread(_ message: MessageView) {
        model.openThread = ThreadRef(conversationId: conversationId, rootId: message.id)
    }

    /// The thread open over this chat, if any (a notification can open one too).
    private var threadShown: Binding<ThreadRef?> {
        Binding(
            get: { model.openThread?.conversationId == conversationId ? model.openThread : nil },
            set: { model.openThread = $0 }
        )
    }

    private var editingShown: Binding<Bool> {
        Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })
    }

    private var composer: some View {
        VStack(spacing: 0) {
            // Someone you blocked: no sending until you unblock them.
            if let conversation, conversation.blocked {
                HStack {
                    Text("You blocked \(conversation.title).")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("Unblock") { Task { await model.setBlocked(conversation.title, false) } }
                }
                .font(.subheadline)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            } else {
                composerBody
            }
        }
    }

    private var composerBody: some View {
        VStack(spacing: 0) {
            if let replyTo {
                HStack(spacing: 8) {
                    Image(systemName: "arrowshape.turn.up.left.fill")
                        .foregroundStyle(Palette.marigold)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Replying to \(replyTo.outgoing ? "yourself" : replyTo.sender)")
                            .font(.caption.bold())
                            .foregroundStyle(Palette.marigold)
                        Text(replyTo.previewText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button { self.replyTo = nil } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 12)
                .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .padding(.horizontal, 10)
                .padding(.top, 6)
            }
            MessageComposer(
                text: $draft,
                placeholder: composerPlaceholder,
                tray: tray,
                conversationId: conversationId,
                onSend: send,
                onVoiceNote: sendVoiceNote
            )
        }
        .disabled(!canSend)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let files = tray.take()
        guard !text.isEmpty || !files.isEmpty else { return }
        draft = ""
        let reply = replyTo?.id
        replyTo = nil
        if files.isEmpty {
            Task { await model.send(text, to: conversationId, replyTo: reply) }
        } else {
            Task { await model.sendMedia(files, caption: text, to: conversationId, replyTo: reply) }
        }
    }

    private func sendVoiceNote(_ note: VoiceNote) {
        let reply = replyTo?.id
        replyTo = nil
        Task { await model.sendMedia([MediaPreparation.voiceNote(note)], caption: "", to: conversationId, replyTo: reply) }
    }
}

extension MessageView: Identifiable {}

/// Messages, oldest first, in a scroll view that starts at the newest. The
/// list is at least as tall as the scroll view, so a short chat sits just
/// above the composer: left to the scroll view's bottom anchor, the space
/// above it would become an inset, which macOS 27 covers like the toolbar,
/// hiding the messages under it.
struct MessageScroll<Content: View>: View {
    @ViewBuilder var content: Content
    @State private var height: CGFloat = 0

    var body: some View {
        ScrollView {
            content
                .frame(minHeight: height, alignment: .bottom)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        .defaultScrollAnchor(.bottom)
    }
}

/// Where a message sits among consecutive messages from the same person.
struct MessageRun {
    var first = true
    var last = true
}

extension MessageRun {
    /// Where `messages[index]` sits in its run.
    init(at index: Int, in messages: [MessageView]) {
        func sameSender(_ a: MessageView, _ b: MessageView) -> Bool {
            !a.system && !b.system && a.outgoing == b.outgoing && a.sender == b.sender
        }
        let message = messages[index]
        let previous = index > 0 ? messages[index - 1] : nil
        let next = index + 1 < messages.count ? messages[index + 1] : nil
        self.init(
            first: previous.map { !sameSender($0, message) } ?? true,
            last: next.map { !sameSender(message, $0) } ?? true
        )
    }
}

struct MessageRow: View {
    @State private var forwarding = false
    @State private var retrying = false
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    let message: MessageView
    let conversationId: String
    let isGroup: Bool
    var run = MessageRun()
    /// Quote it in the chat's composer. Nil in a thread.
    var onReply: (() -> Void)?
    var onReplyInThread: (() -> Void)?
    /// Open the thread under it, if it has one. Nil in a thread.
    var onOpenThread: (() -> Void)?
    var onReact: (String?) -> Void
    /// Choose any emoji.
    var onMoreReactions: () -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void
    /// Show a photo or video in the viewer, or open a file.
    var onOpenAttachment: (AttachmentView) -> Void = { _ in }

    static let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🙏"]
    @State private var heartBurst = 0

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
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .overlay(Capsule().strokeBorder(Color.secondary.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [3, 3])))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        } else {
            HStack(alignment: .bottom, spacing: 6) {
                if message.outgoing {
                    Spacer(minLength: 48)
                    #if os(macOS)
                    hoverActions
                    #endif
                }
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
                        let ink = model.ink(for: message.sender)
                        HStack(spacing: 6) {
                            Text(message.sender.prefix(1).uppercased())
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(ink.deep)
                                .frame(width: 20, height: 20)
                                .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(ink.pastel))
                            Text(message.sender)
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(ink.color)
                        .padding(.leading, 4)
                        .accessibilityElement(children: .combine)
                    }
                    bubble
                        // Double-tap: a heart, with a little burst of them.
                        .simultaneousGesture(TapGesture(count: 2).onEnded {
                            guard !message.deleted else { return }
                            if myReaction != "❤️" { onReact("❤️") }
                            heartBurst += 1
                        })
                        .overlay { HeartBurst(trigger: heartBurst) }
                        .sensoryFeedback(.impact(weight: .light), trigger: heartBurst)
                        .transition(.asymmetric(
                            insertion: .scale(scale: 0.92, anchor: message.outgoing ? .bottomTrailing : .bottomLeading)
                                .combined(with: .offset(y: 14))
                                .combined(with: .opacity),
                            removal: .opacity
                        ))
                    if !message.reactions.isEmpty {
                        reactions
                            .padding(.top, -12)
                            .padding(.horizontal, 10)
                    }
                    if let thread = message.thread, let onOpenThread {
                        ThreadSummaryRow(thread: thread, action: onOpenThread)
                            .padding(.top, 2)
                    }
                }
                if !message.outgoing {
                    #if os(macOS)
                    hoverActions
                    #endif
                    Spacer(minLength: 48)
                }
            }
            .padding(.top, run.first ? 8 : 2)
            #if os(iOS)
            .modifier(SwipeToAct(action: message.deleted ? nil : (onReplyInThread ?? onReply)))
            #else
            .contentShape(Rectangle())
            .onHover { hovering = $0 }
            #endif
        }
    }

    #if os(macOS)
    @State private var hovering = false

    /// On the Mac, pointing at a message shows what can be done with it,
    /// beside the bubble: react, reply in a thread, and the rest.
    @ViewBuilder
    private var hoverActions: some View {
        if !message.deleted {
            HStack(spacing: 2) {
                Menu {
                    ForEach(Self.quickReactions, id: \.self) { emoji in
                        Button(emoji) { onReact(emoji) }
                    }
                    Button { onMoreReactions() } label: { Label("More…", systemImage: "face.smiling") }
                } label: {
                    Image(systemName: "face.smiling")
                }
                .help("React")
                if let onReplyInThread {
                    Button(action: onReplyInThread) { Image(systemName: "bubble.left.and.text.bubble.right") }
                        .help("Reply in thread")
                } else if let onReply {
                    Button(action: onReply) { Image(systemName: "arrowshape.turn.up.left") }
                        .help("Reply")
                }
                Menu { menu } label: { Image(systemName: "ellipsis") }
                    .help("More")
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.borderless)
            .fixedSize()
            .foregroundStyle(.secondary)
            .padding(.bottom, 4)
            .opacity(hovering ? 1 : 0)
            .allowsHitTesting(hovering)
            .accessibilityHidden(!hovering)
        }
    }
    #endif

    /// Rounded all round, except where it joins the next or previous bubble
    /// from the same person.
    private var bubbleShape: UnevenRoundedRectangle {
        let big: CGFloat = 22
        let small: CGFloat = 8
        let top = run.first ? big : small
        // The corner by the tail stays tight.
        return message.outgoing
            ? UnevenRoundedRectangle(topLeadingRadius: big, bottomLeadingRadius: big, bottomTrailingRadius: small, topTrailingRadius: top, style: .continuous)
            : UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: small, bottomTrailingRadius: big, topTrailingRadius: big, style: .continuous)
    }

    private var visual: [AttachmentView] {
        message.deleted ? [] : message.attachments.filter(\.isVisual)
    }

    private var others: [AttachmentView] {
        message.deleted ? [] : message.attachments.filter { !$0.isVisual }
    }

    /// Pictures with nothing else: the time goes on them.
    private var timeOnPictures: Bool {
        !visual.isEmpty && others.isEmpty && message.text.isEmpty && !message.deleted
    }

    private func ref(_ attachment: AttachmentView) -> AttachmentRef {
        AttachmentRef(conversationId: conversationId, messageId: message.id, position: attachment.position)
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 4) {
            if message.forwarded && !message.deleted {
                Label("Forwarded", systemImage: "arrowshape.turn.up.right.fill")
                    .font(.system(size: 12, weight: .semibold))
                    .opacity(0.7)
            }
            if let reply = message.replyTo {
                VStack(alignment: .leading, spacing: 1) {
                    Text(reply.sender).font(.caption.bold())
                    Text(reply.text).font(.caption).lineLimit(2)
                }
                .padding(6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            }
            if !visual.isEmpty {
                // Nearly to the bubble's edge.
                MediaGrid(items: visual, conversationId: conversationId, messageId: message.id, onOpen: onOpenAttachment)
                    .overlay(alignment: .bottomTrailing) {
                        if timeOnPictures {
                            footer
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(.black.opacity(0.4), in: Capsule())
                                .padding(6)
                        }
                    }
                    .padding(.horizontal, -9)
                    .padding(.top, message.replyTo == nil ? -5 : 0)
                    .padding(.bottom, timeOnPictures ? -5 : 0)
            }
            ForEach(others, id: \.position) { attachment in
                if attachment.kind == .voice {
                    VoiceNoteRow(attachment: attachment, ref: ref(attachment), outgoing: message.outgoing)
                } else {
                    FileRow(attachment: attachment, ref: ref(attachment), outgoing: message.outgoing) {
                        onOpenAttachment(attachment)
                    }
                }
            }
            if message.deleted || !message.text.isEmpty {
                // Not selectable: selecting text takes the long press (and
                // the Mac's right-click) that opens the message's menu.
                // The menu has Copy.
                Text(message.deleted ? "Message deleted" : message.text)
                    .italic(message.deleted)
            }
            if !timeOnPictures {
                footer
            }
        }
        .frame(width: visual.isEmpty ? nil : MediaGrid.columnWidth, alignment: .leading)
        .padding(.leading, message.outgoing ? 14 : 14)
        .padding(.trailing, 12)
        .padding(.top, 8)
        .padding(.bottom, 7)
        .foregroundStyle(message.outgoing ? appearance.ink.onBubble : Palette.cream)
        .background {
            if message.deleted {
                bubbleShape.strokeBorder(Color.incomingBubble, lineWidth: 1)
            } else if message.outgoing {
                bubbleShape.fill(appearance.ink.bubble)
            } else {
                bubbleShape.fill(Color.incomingBubble)
            }
        }
        .contextMenu { menu }
        .onTapGesture {
            if message.outgoing && message.status == .failed { retrying = true }
        }
        .confirmationDialog("Couldn't send", isPresented: $retrying, titleVisibility: .visible) {
            Button("Try again") {
                model.sendPendingMedia()
                Task { await model.syncNow() }
                model.showToast(model.isOnline ? "Sending" : "It’ll go as soon as you’re online")
            }
            Button("Delete", role: .destructive) { onDelete() }
        } message: {
            Text("It stayed on this device. Try again, or delete it.")
        }
        .sheet(isPresented: $forwarding) {
            ForwardSheet(conversationId: conversationId, message: message)
        }
    }

    private var footer: some View {
        HStack(spacing: 4) {
            if message.edited && !message.deleted { Text("edited") }
            if message.outgoing && message.status == .failed {
                Text("Couldn't send. Tap to retry").foregroundStyle(Color(rgb: 0xFF8A7A)).fontWeight(.semibold)
            } else if message.outgoing && message.status == .sending && !model.isOnline {
                Text("Waiting to send")
            } else {
                Text(shortTime(message.sentAtMs))
            }
            statusIcon
        }
        .font(.caption2)
        .opacity(0.7)
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
            if let onReply {
                Button { onReply() } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") }
            }
            if let onReplyInThread {
                Button { onReplyInThread() } label: { Label("Reply in thread", systemImage: "bubble.left.and.text.bubble.right") }
            }
            Menu {
                ForEach(Self.quickReactions, id: \.self) { emoji in
                    Button(emoji) { onReact(emoji) }
                }
                Button { onMoreReactions() } label: { Label("More…", systemImage: "face.smiling") }
                if myReaction != nil {
                    Button("Remove my reaction") { onReact(nil) }
                }
            } label: {
                Label("React", systemImage: "face.smiling")
            }
            if !message.text.isEmpty {
                Button { Clipboard.copy(message.text); model.showToast("Copied") } label: { Label("Copy", systemImage: "doc.on.doc") }
            }
            if !message.vanish && !message.system {
                Button { forwarding = true } label: { Label("Forward", systemImage: "arrowshape.turn.up.right") }
            }
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
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(reaction.mine ? Palette.marigold : Palette.cream))
                        .overlay(Capsule().strokeBorder(Palette.sheet, lineWidth: 2.5))
                        .transition(.scale(scale: 0.4).combined(with: .opacity))
                }
                .buttonStyle(.plain)
                .help(reaction.usernames.joined(separator: ", "))
            }
            Button(action: onMoreReactions) {
                Image(systemName: "plus")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Palette.cream))
                    .overlay(Capsule().strokeBorder(Palette.sheet, lineWidth: 2.5))
            }
            .buttonStyle(.plain)
            .foregroundStyle(Palette.ink)
            .accessibilityLabel("Add a reaction")
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

#if os(iOS)
/// Swipe a message to the right to act on it: in a chat, reply in its thread.
/// The drag is shared with the scroll view and only counts when it's mostly
/// sideways, so scrolling still works.
struct SwipeToAct: ViewModifier {
    let action: (() -> Void)?
    /// Resets by itself when the drag ends or the scroll view takes it over.
    @GestureState private var offset: CGFloat = 0

    private static let trigger: CGFloat = 60

    func body(content: Content) -> some View {
        if let action {
            content
                .offset(x: offset)
                .background(alignment: .leading) {
                    Image(systemName: "bubble.left.and.text.bubble.right")
                        .foregroundStyle(.secondary)
                        .scaleEffect(offset >= Self.trigger ? 1.15 : 0.8)
                        .opacity(Double(min(offset / Self.trigger, 1)))
                        .padding(.leading, 4)
                }
                .animation(.spring(duration: 0.25), value: offset == 0)
                .sensoryFeedback(.impact(weight: .light), trigger: offset >= Self.trigger) { _, armed in armed }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 20)
                        .updating($offset) { value, offset, _ in
                            let x = value.translation.width
                            guard x > 0, x > abs(value.translation.height) * 1.5 else { return }
                            offset = min(x, Self.trigger * 1.4)
                        }
                        .onEnded { value in
                            let x = value.translation.width
                            if x >= Self.trigger, x > abs(value.translation.height) * 1.5 { action() }
                        }
                )
        } else {
            content
        }
    }
}
#endif

/// Forces dark colours while `on`, and leaves the system's choice otherwise.
struct DarkWhen: ViewModifier {
    let on: Bool
    @Environment(\.colorScheme) private var scheme

    // One branch, so turning vanish mode on or off doesn't rebuild the
    // chat (which lost track of being at the end, and with it the pull).
    func body(content: Content) -> some View {
        content.environment(\.colorScheme, on ? .dark : scheme)
    }
}

/// Over a chat in vanish mode: what it means, and a way out.
struct VanishBanner: View {
    let turnOff: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "eye.slash")
                .foregroundStyle(Palette.vanishAccent)
            VStack(alignment: .leading, spacing: 1) {
                Text("Vanish mode").font(.subheadline.weight(.semibold))
                Text("Seen messages disappear when you close the chat.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(action: turnOff) {
                Text("Turn off")
                    .font(.system(size: 13.5, weight: .bold))
                    .foregroundStyle(Palette.ink)
                    .padding(.horizontal, 13)
                    .frame(height: 34)
                    .background(Capsule().fill(Palette.vanishAccent))
            }
            .buttonStyle(PressScale())
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Palette.vanishAccent.opacity(0.14)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Palette.vanishAccent.opacity(0.3)))
        .padding(.horizontal, 12)
        .padding(.top, 10)
    }
}

/// Shown while swiping up past the last message: a ring that fills, then
/// "Release".
struct VanishPullHint: View {
    let progress: CGFloat
    let turningOn: Bool

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                Circle().stroke(.secondary.opacity(0.3), lineWidth: 3)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(Palette.vanishAccent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 22, height: 22)
            Text(progress >= 1
                 ? "Release to turn \(turningOn ? "on" : "off") vanish mode"
                 : "Swipe up to turn \(turningOn ? "on" : "off") vanish mode")
                .font(.footnote.weight(.medium))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .scaleEffect(progress >= 1 ? 1.06 : 1)
        .animation(.spring(response: 0.25, dampingFraction: 0.5), value: progress >= 1)
    }
}

/// How the vanish pull feels: resistance like a rubber band, so it takes a
/// long, deliberate pull; light ticks that get firmer as the ring fills,
/// like tension in a string; a snap when it's armed; and a release if you
/// pull back. Letting go before it's armed does nothing.
struct VanishPullFeel {
    /// The finger travel at which the ring is full, after resistance.
    private let full: CGFloat = 120
    private(set) var armed = false
    private var lastTick = 0

    /// 0 to 1 for a pull of `distance` points.
    mutating func progress(forDistance distance: CGFloat) -> CGFloat {
        // Rubber band: easy at first, then stiffer. Full at about 230 pt.
        let resisted = 170 * (1 - exp(-max(0, distance) / 170))
        let p = min(1, resisted / full)
        let tick = Int(p * 10)
        #if os(iOS)
        if tick > lastTick && p < 1 {
            UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.25 + 0.6 * p)
        }
        if p >= 1 && !armed {
            armed = true
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 1)
        } else if armed && p < 0.9 {
            armed = false
            UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.6)
        }
        #else
        if p >= 1 { armed = true } else if p < 0.9 { armed = false }
        #endif
        lastTick = tick
        return p
    }

    mutating func reset() {
        armed = false
        lastTick = 0
    }
}


/// The top of a chat: their stamp, their name in display type, and what
/// they're doing. Tapping it opens the chat's details.
struct ChatHeader: View {
    @Environment(AppModel.self) private var model
    let conversation: ConversationSummary?
    let typing: Bool
    let action: () -> Void

    private var status: String {
        guard let conversation else { return "" }
        if !model.isOnline { return "Waiting for network" }
        if typing { return "typing…" }
        if conversation.vanishMode { return "Vanish mode" }
        if conversation.kind == .group { return "Group" }
        return conversation.active ? "End-to-end encrypted" : "This chat has ended"
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let conversation {
                    Avatar(title: conversation.title, isGroup: conversation.kind == .group, size: 40, stamp: true)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(conversation?.title ?? "")
                        .font(.display(19))
                        .displayTracking(19)
                        .lineLimit(1)
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(typing ? Color(light: 0x2E9E5B, dark: 0x5DD08A) : Palette.secondary)
                        .lineLimit(1)
                        .contentTransition(.opacity)
                }
            }
            .foregroundStyle(Palette.text)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(conversation?.title ?? "Chat"), \(status). Details")
        .animation(.easeOut(duration: 0.2), value: typing)
    }
}


/// Five hearts that float up and out from the middle of a bubble, then go.
struct HeartBurst: View {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start: Date?

    private static let flights: [(CGFloat, CGFloat, Double)] = [(-34, -46, -18), (-10, -60, 8), (14, -54, 20), (34, -40, 30), (0, -30, -6)]

    var body: some View {
        TimelineView(.animation(paused: start == nil)) { context in
            let t = start.map { context.date.timeIntervalSince($0) / 0.9 } ?? 1
            ZStack {
                if t < 1 {
                    ForEach(0..<Self.flights.count, id: \.self) { i in
                        let f = Self.flights[i]
                        let p = min(max(t - Double(i) * 0.04, 0), 1)
                        let ease = 1 - pow(1 - p, 3)
                        Text("❤️")
                            .font(.system(size: 22))
                            .scaleEffect(0.3 + 0.8 * ease)
                            .rotationEffect(.degrees(f.2 * ease))
                            .offset(x: f.0 * ease, y: f.1 * ease)
                            .opacity(p < 0.25 ? p / 0.25 : 1 - (p - 0.25) / 0.75)
                    }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: trigger) { _, _ in
            guard !reduceMotion else { return }
            start = .now
            Task {
                try? await Task.sleep(for: .seconds(1))
                start = nil
            }
        }
    }
}
