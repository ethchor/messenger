import CoreLocation
import PhotosUI
import SwiftUI

struct ConversationListView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(Appearance.self) private var appearance
    @State private var sheet: Sheet?
    @State private var dismissedTips = ChatListTip.dismissed
    @State private var usingCamera = false
    #if os(iOS)
    @State private var usingLibrary = false
    @State private var libraryItems: [PhotosPickerItem] = []
    #endif
    /// Taken, and waiting for a chat to go to.
    @State private var captured: PendingCapture.Source?
    /// Which chats the list shows: all, unread, groups, or the archived ones.
    @State private var filter = ChatFilter.all
    private var showingArchived: Bool { filter == .archived }
    /// Searching chats by name, from the round magnifier.
    @State private var searching = false
    #if os(macOS)
    private let wide = true
    #else
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var wide: Bool { sizeClass == .regular }
    #endif
    @State private var query = ""
    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }
    /// Pinned chats get cards of their own while nothing narrows the list.
    private var showsPinned: Bool { filter == .all && trimmedQuery.isEmpty }
    @State private var confirm: ChatConfirm?
    @FocusState private var searchFocused: Bool
    /// The list has scrolled under the header: it turns to glass.
    @State private var scrolled = false
    /// Scrolled well down: the title shrinks out of the way.
    @State private var scrolledFar = false

    enum Sheet: String, Identifiable {
        case newChat, newGroup, settings, map
        var id: String { rawValue }
    }

    var body: some View {
        @Bindable var model = model
        let pins = showsPinned ? model.conversations.filter { $0.pinned && !$0.archived } : []
        let shown = model.conversations.filter { chat in
            filter.includes(chat)
                && (trimmedQuery.isEmpty || chat.title.localizedCaseInsensitiveContains(trimmedQuery))
                && !(showsPinned && chat.pinned && !pins.isEmpty)
        }
        NavigationSplitView {
            // A locked chat opens only after Face ID, Touch ID or the passcode.
            List(selection: Binding(
                get: { model.selectedConversationId },
                set: { id in
                    guard let id, let chat = model.conversations.first(where: { $0.id == id }) else {
                        model.selectedConversationId = id
                        return
                    }
                    open(chat)
                }
            )) {
                #if os(iOS)
                // The recovery key is urgent, so it leads; the other tips
                // wait under the chats instead of pushing them down.
                if let tip = currentTip, tip == .recoveryKey {
                    tipSection(tip)
                }
                #endif
                if !pins.isEmpty {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Pinned")
                            .font(.display(22))
                            .displayTracking(22)
                            .foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                        Spacer()
                        Text("Hold a chat to pin it")
                            .font(.system(size: 13.5))
                            .foregroundStyle(Palette.secondary)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 10)
                    .padding(.bottom, 4)
                    .clearRow()
                    pinnedCards(pins)
                }
                // Plain rows, not section headers and footers: those draw
                // the system's bands over the grid.
                if !model.conversations.isEmpty {
                    Text(trimmedQuery.isEmpty ? filter.listTitle : "Results")
                        .font(.display(22))
                        .displayTracking(22)
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.leading, 20)
                        .padding(.top, pins.isEmpty ? 10 : 22)
                        .padding(.bottom, 6)
                        .clearRow()
                }
                if !model.isOnline {
                    Label("Offline. Retrying…", systemImage: "wifi.slash")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Palette.secondary)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 6)
                        .clearRow()
                }
                ForEach(shown, id: \.id) { conversation in
                    row(conversation)
                        // The link sits underneath, so there's no chevron.
                        .background { NavigationLink(value: conversation.id) { EmptyView() }.opacity(0) }
                        .chatSwipeActions(conversation, model: model, confirm: $confirm)
                        .contextMenu { ChatActionsMenu(conversation: conversation, confirm: $confirm) }
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))
                }
                if shown.isEmpty && !model.conversations.isEmpty && !(showsPinned && !pins.isEmpty) {
                    VStack(spacing: 14) {
                        ZStack(alignment: .topLeading) {
                            ShapeSticker(kind: .bubble, size: 122, delay: 0.02).offset(x: 14, y: 0)
                            ShapeSticker(kind: .heart, size: 51, delay: 0.22).offset(x: 112, y: -26)
                            ShapeSticker(kind: .sparkle, size: 27, delay: 0.34, phase: -1).offset(x: 0, y: -22)
                        }
                        .frame(width: 170, height: 122, alignment: .bottomLeading)
                        .padding(.top, 30)
                        Text(trimmedQuery.isEmpty ? filter.emptyText : "Nobody called “\(trimmedQuery)” yet.")
                            .font(.system(size: 15))
                            .foregroundStyle(Palette.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, 24)
                    .clearRow()
                }
                if !model.conversations.isEmpty {
                    VStack(spacing: 8) {
                        if showingArchived {
                            Text("Archived chats stay here when new messages come.")
                        }
                        Label("Only the people in a chat can read it", systemImage: "lock.fill")
                    }
                    .font(.system(size: 12.5))
                    .foregroundStyle(Palette.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 18)
                    .clearRow()
                }
            }
            .overlay {
                if model.conversations.isEmpty {
                    EmptyState(
                        illustration: .chats,
                        title: "No chats yet",
                        text: "Start one with someone's username. They need to have opened Messenger once."
                    ) {
                        Button("New chat") { sheet = .newChat }
                            .buttonStyle(.marigold)
                    }
                }
            }
            .listStyle(.plain)
            .environment(\.defaultMinListRowHeight, 0)
            .scrollContentBackground(.hidden)
            .background { HomeGround().ignoresSafeArea() }
            .safeAreaInset(edge: .top, spacing: 0) { listHeader }
            .modifier(ScrolledFlag(scrolled: $scrolled, far: $scrolledFar))
            #if os(iOS)
            .contentMargins(.bottom, 90, for: .scrollContent)
            .toolbar(.hidden, for: .navigationBar)
            #endif
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if wide {
                    SidebarTabs { sheet = .map }
                }
            }
            .navigationTitle(showingArchived ? "Archived" : "Chats")
            #if os(macOS)
            .searchable(text: $query, isPresented: $searching, placement: .sidebar, prompt: "Search names")
            #endif
            .modifier(SidebarWidth())
            .background {
                // ⌘N new chat, ⌘F search, ⌘1–3 the tabs.
                Group {
                    Button("New chat") { sheet = .newChat }.keyboardShortcut("n", modifiers: [.command, .shift])
                    Button("Search") { withAnimation { searching = true } }.keyboardShortcut("f")
                    Button("Chats") { model.showingSettings = false }.keyboardShortcut("1")
                    Button("Map") { sheet = .map }.keyboardShortcut("2")
                    Button("You") { model.selectedConversationId = nil; model.showingSettings = true }.keyboardShortcut("3")
                }
                .opacity(0)
                .accessibilityHidden(true)
            }
            .onChange(of: model.composeRequest) { _, _ in sheet = .newChat }
            .chatConfirmation($confirm, model: model)
            .refreshable { await model.syncNow() }
            .toolbar {
                #if os(macOS)
                ToolbarItem(placement: .primaryAction) {
                    MacCameraButton(onMacCamera: { usingCamera = true }) { data in
                        captured = .photo(data)
                    }
                    .help("Take a photo")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { sheet = .map } label: {
                        Label("Map", systemImage: model.sharedLocations.isEmpty ? "map" : "map.fill")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    newMenu
                }
                #endif
            }
        } detail: {
            if let id = model.selectedConversationId {
                ChatView(conversationId: id)
                    .id(id)
            } else if model.showingSettings {
                NavigationStack {
                    ReadableWidth {
                        SettingsView()
                    }
                }
            } else {
                EmptyState(artwork: .mark, title: "Pick a chat", text: "Everything here is end-to-end encrypted: only the people in a chat can read it.") {
                    // A Mac sidebar row doesn't grow to fit wrapped text, which
                    // cut a tip to a line, and a card under the list stretched
                    // the window; here there's room.
                    #if os(macOS)
                    if let tip = currentTip {
                        tipCard(tip)
                            .frame(maxWidth: 420)
                            .padding(.top, 20)
                    }
                    #endif
                }
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $usingCamera) {
            CameraPicker { shot in
                // Once the camera has gone, so the sheet can come up.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { captured = .camera(shot) }
            }
            .ignoresSafeArea()
        }
        .photosPicker(isPresented: $usingLibrary, selection: $libraryItems, maxSelectionCount: MediaLimits.files, matching: .any(of: [.images, .videos]))
        .onChange(of: libraryItems) { _, items in
            guard !items.isEmpty else { return }
            libraryItems = []
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { captured = .library(items) }
        }
        #endif
        #if os(macOS)
        .sheet(isPresented: $usingCamera) {
            MacCameraView { data in
                // Once the camera window has gone, so the next sheet can come up.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { captured = .photo(data) }
            }
        }
        #endif
        .sheet(isPresented: Binding(get: { captured != nil }, set: { if !$0 { captured = nil } })) {
            SendToChatView { id in
                if let captured { model.pendingCapture = PendingCapture(conversationId: id, source: captured) }
                captured = nil
                model.showingSettings = false
                model.selectedConversationId = id
            }
        }
        .onChange(of: model.selectedConversationId) { _, id in
            if id != nil { model.showingSettings = false }
        }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .newChat:
                NewChatView()
            case .newGroup:
                NavigationStack { NewGroupView() }
            case .settings:
                NavigationStack { SettingsView() }
            case .map:
                PeopleMapView()
            }
        }
    }
}

/// The chips over the chat list.
enum ChatFilter: CaseIterable {
    case all, unread, groups, archived

    var title: String {
        switch self {
        case .all: "All"
        case .unread: "Unread"
        case .groups: "Groups"
        case .archived: "Archived"
        }
    }

    var listTitle: String {
        switch self {
        case .all: "Everyone"
        case .unread: "Unread"
        case .groups: "Groups"
        case .archived: "Archived"
        }
    }

    var emptyText: String {
        switch self {
        case .all: "No chats here yet."
        case .unread: "All caught up. Go do something fun."
        case .groups: "No groups yet. Tap + to start one."
        case .archived: "Nothing archived. Swipe a chat left to tuck it away."
        }
    }

    func includes(_ chat: ConversationSummary) -> Bool {
        switch self {
        case .all: !chat.archived
        case .unread: !chat.archived && (chat.unread > 0 || chat.markedUnread)
        case .groups: !chat.archived && chat.kind == .group
        case .archived: chat.archived
        }
    }
}

extension ConversationListView {
    /// The big title, the round buttons, and the filter chips.
    private var listHeader: some View {
        VStack(alignment: .leading, spacing: 12) {
            #if os(iOS)
            if searching {
                HStack(spacing: 8) {
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass").foregroundStyle(Palette.secondary)
                        TextField("Search names", text: $query)
                            .font(.system(size: 16))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .focused($searchFocused)
                            .submitLabel(.search)
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 46)
                    .background(Palette.surface, in: Capsule())
                    .overlay(Capsule().stroke(Palette.text, lineWidth: 1.5))
                    Button("Cancel") {
                        withAnimation(.easeOut(duration: 0.2)) { searching = false; query = "" }
                    }
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Palette.text)
                }
                .padding(.horizontal, 16)
                .transition(.opacity)
                .onAppear { searchFocused = true }
            } else {
            HStack(spacing: 8) {
                Text("Chats")
                    .font(.display(wide ? 30 : 34))
                    .displayTracking(wide ? 30 : 34)
                    .foregroundStyle(Palette.text)
                    .scaleEffect(scrolledFar ? 0.62 : 1, anchor: .leading)
                    .animation(.timingCurve(0.2, 0.85, 0.2, 1, duration: 0.32), value: scrolledFar)
                    .accessibilityAddTraits(.isHeader)
                    // Panic wipe: triple-tap the title.
                    .onTapGesture(count: 3) {
                        guard lock.panicWipe else { return }
                        Task {
                            await model.wipeThisDevice()
                            lock.reset()
                        }
                    }
                Spacer()
                RoundButton(systemImage: "magnifyingglass", label: "Search chats") {
                    withAnimation(.easeOut(duration: 0.2)) { searching = true }
                }
                RoundButton(systemImage: "camera", label: "Camera") {
                    // The simulator has no camera: the library stands in.
                    if CameraPicker.isAvailable { usingCamera = true } else { usingLibrary = true }
                }
                newMenu
            }
            .padding(.leading, 20)
            .padding(.trailing, 16)
            }
            #endif
            if !model.conversations.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(ChatFilter.allCases, id: \.self) { item in
                            FilterChip(
                                title: item.title,
                                count: count(for: item),
                                selected: filter == item
                            ) { filter = item }
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 2)
                }
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 6)
        .background {
            ZStack {
                if scrolled {
                    Rectangle().fill(.ultraThinMaterial)
                    Palette.home.opacity(0.82)
                } else {
                    Palette.home
                }
            }
            .mask {
                LinearGradient(colors: [.black, .black, .black.opacity(0)], startPoint: .top, endPoint: .bottom)
            }
            .padding(.bottom, scrolled ? -34 : 0)
            .ignoresSafeArea()
            .animation(.easeInOut(duration: 0.3), value: scrolled)
        }
    }

    /// Opens a chat; a locked one only after Face ID, Touch ID or the passcode.
    private func open(_ chat: ConversationSummary) {
        guard chat.locked else {
            model.selectedConversationId = chat.id
            return
        }
        Task {
            if await lock.unlockChat(named: chat.title) {
                model.selectedConversationId = chat.id
            }
        }
    }

    /// New chat or new group, behind the marigold +.
    private var newMenu: some View {
        Menu {
            Button { sheet = .newChat } label: { Label("New chat", systemImage: "person") }
            Button { sheet = .newGroup } label: { Label("New group", systemImage: "person.3") }
        } label: {
            #if os(iOS)
            RoundButtonFace(systemImage: "plus", filled: true)
            #else
            Label("New", systemImage: "square.and.pencil")
            #endif
        }
        .accessibilityLabel("New")
    }

    /// The number on a chip: unread chats, and archived ones.
    private func count(for item: ChatFilter) -> Int {
        switch item {
        case .unread: model.conversations.filter { ChatFilter.unread.includes($0) }.count
        case .archived: model.conversations.filter(\.archived).count
        case .all, .groups: 0
        }
    }

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
    private func pinnedCards(_ pins: [ConversationSummary]) -> some View {
        if wide {
            CompactPinnedCards(chats: pins, confirm: $confirm) { open($0) }
                .clearRow()
        } else {
            PinnedCards(chats: pins, confirm: $confirm) { open($0) }
                .clearRow()
        }
    }

    /// A chat in the list; in a sidebar, the open one sits on a soft tile.
    private func row(_ conversation: ConversationSummary) -> some View {
        let selected = wide && model.selectedConversationId == conversation.id
        let tile: Color = selected ? Color(light: 0xECE9DE, dark: 0x1F1F23) : .clear
        return ConversationRow(conversation: conversation, compact: wide)
            .background(tile, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    /// The recovery-key reminder, the one tip the list still shows.
    private func tipSection(_ tip: ChatListTip) -> some View {
        tipCard(tip)
            .padding(.horizontal, 8)
            .padding(.top, 8)
            .clearRow()
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
                illustration: .key,
                title: "Write down your recovery key",
                text: "Your only way back in on a new device.",
                onDismiss: dismiss
            ) { openSettings("Show it") }
        case .avatar:
            HelperCard(
                art: .avatars([.fox, .panda, .cat], background: appearance.ink.pastel),
                title: "Pick an avatar",
                text: "A drawing and an ink your chats will see.",
                onDismiss: dismiss
            ) { openSettings("Choose") }
        case .safetyNumbers:
            HelperCard(
                illustration: .safety,
                title: "Check your safety numbers",
                text: "Compare in person: if they match, nobody's in the middle.",
                onDismiss: dismiss
            ) { openSettings("Show me") }
        }
    }

    @ViewBuilder
    private func openSettings(_ title: String) -> some View {
        #if os(macOS)
        Button(title) {
            model.selectedConversationId = nil
            model.showingSettings = true
        }
        #else
        Button(title) { sheet = .settings }
        #endif
    }
}

struct ConversationRow: View {
    let conversation: ConversationSummary
    /// In a sidebar: smaller, as designed.
    var compact = false

    private var preview: String {
        if conversation.leaving { return "Leaving…" }
        // A locked chat keeps what's in it to itself.
        if conversation.locked { return "Locked chat" }
        guard let text = conversation.lastMessage else {
            return conversation.active ? "No messages yet" : "This chat has ended"
        }
        if conversation.kind == .group, let sender = conversation.lastSender, !sender.isEmpty {
            return conversation.lastInThread ? "\(sender) in a thread: \(text)" : "\(sender): \(text)"
        }
        return conversation.lastInThread ? "In a thread: \(text)" : text
    }

    private var hasUnread: Bool { conversation.unread > 0 }

    // Large text, as designed: everything grows with the text size, names
    // and previews get two lines, and at the largest sizes the time drops
    // below the name. Nothing overlaps or shrinks.
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .body) private var nameSize: CGFloat = 17
    @ScaledMetric(relativeTo: .body) private var previewSize: CGFloat = 15
    @ScaledMetric(relativeTo: .footnote) private var metaSize: CGFloat = 13
    @ScaledMetric(relativeTo: .body) private var avatarSize: CGFloat = 54

    private var largest: Bool { typeSize.isAccessibilitySize }

    var body: some View {
        HStack(alignment: largest ? .top : .center, spacing: 14) {
            Avatar(title: conversation.title, isGroup: conversation.kind == .group, size: compact ? min(avatarSize * 44 / 54, 64) : min(avatarSize, 72), stamp: true)
            VStack(alignment: .leading, spacing: 3) {
                if largest {
                    titleLine
                    time
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        titleLine
                        Spacer(minLength: 8)
                        time
                    }
                }
                HStack(spacing: 8) {
                    Text(preview)
                        .font(.system(size: compact ? previewSize * 13.5 / 15 : previewSize, weight: hasUnread ? .semibold : .regular))
                        .foregroundStyle(hasUnread ? Palette.text : Palette.secondary)
                        .italic(conversation.lastMessage == nil)
                        .lineLimit(2)
                    Spacer(minLength: 0)
                    // Muted chats count in grey.
                    if hasUnread {
                        UnreadBadge(count: Int(conversation.unread), muted: conversation.mutedUntilMs != nil)
                    } else if conversation.markedUnread {
                        Circle()
                            .fill(Palette.marigold)
                            .overlay(Circle().strokeBorder(Palette.ink, lineWidth: 1.5))
                            .frame(width: 13, height: 13)
                            .accessibilityLabel("Marked unread")
                    }
                }
            }
        }
        .foregroundStyle(Palette.text)
        .padding(.vertical, 9)
        .padding(.horizontal, compact ? 10 : 4)
        .animation(.spring(duration: 0.34, bounce: 0.4), value: conversation.unread)
    }

    private var titleLine: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(conversation.title)
                .font(.system(size: compact ? nameSize * 15 / 17 : nameSize, weight: compact ? .bold : .semibold))
                .lineLimit(2)
            if conversation.locked {
                Image(systemName: "lock.fill").font(.caption).foregroundStyle(Palette.secondary)
            }
            if conversation.mutedUntilMs != nil {
                Image(systemName: "bell.slash.fill").font(.caption).foregroundStyle(Palette.secondary)
            }
            if conversation.blocked {
                Image(systemName: "hand.raised.fill").font(.caption).foregroundStyle(Palette.secondary)
            }
            if conversation.pinned {
                Image(systemName: "pin.fill")
                    .font(.caption)
                    .foregroundStyle(Palette.secondary)
                    .rotationEffect(.degrees(45))
            }
        }
    }

    private var time: some View {
        Text(shortTime(conversation.lastActivityMs))
            .font(.system(size: metaSize, weight: hasUnread ? .semibold : .regular))
            .foregroundStyle(hasUnread ? AnyShapeStyle(.tint) : AnyShapeStyle(Palette.secondary))
            .fixedSize()
    }
}

/// Someone's avatar: the drawing they picked, or the first letter of their
/// name, on the pastel of their ink. Groups get a pair of figures.
struct Avatar: View {
    @Environment(AppModel.self) private var model
    let title: String
    var isGroup = false
    var size: CGFloat = 40
    /// A rounded square with an outline, as in the chat list and headers.
    var stamp = false

    var body: some View {
        let ink = model.ink(for: title)
        if isGroup {
            // Groups: their initials, in the colour they were made in.
            let colour = model.conversations.first { $0.kind == .group && $0.title == title }?.groupColour
            GroupStamp(title: title, colour: colour, fallback: ink, size: size, stamp: stamp)
        } else {
        AvatarFace(
            drawing: isGroup ? nil : AvatarDrawing(named: model.profiles[title]?.avatar),
            ink: ink,
            letter: String(title.prefix(1)).uppercased(),
            isGroup: isGroup,
            size: size,
            stamp: stamp,
            photo: isGroup ? nil : model.profilePhotos[title]
        )
        }
    }
}

/// A group's stamp: its initials on its colour's tint.
struct GroupStamp: View {
    let title: String
    let colour: GroupColour?
    let fallback: Ink
    let size: CGFloat
    var stamp = true

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: stamp ? size / 3 : size / 2, style: .continuous)
        Text(groupInitials(title))
            .font(.display(size * 0.36, weight: 700))
            .foregroundStyle(colour?.deep ?? fallback.color)
            .minimumScaleFactor(0.5)
            .frame(width: size, height: size)
            .background(colour?.tint ?? fallback.pastel, in: shape)
            .overlay(shape.strokeBorder(Palette.stampLine, lineWidth: stamp ? 1.5 : 0))
            .accessibilityHidden(true)
    }
}

/// A drawing, or a letter or a pair of figures, on an ink's pastel; round, or
/// a stamp.
struct AvatarFace: View {
    let drawing: AvatarDrawing?
    let ink: Ink
    let letter: String
    var isGroup = false
    let size: CGFloat
    var stamp = false
    /// A profile photo on this device, shown instead of the drawing.
    var photo: String?

    var body: some View {
        Group {
            if let photo, let image = ProfilePhotoCache.image(at: photo) {
                image
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(stamp ? AnyShape(RoundedRectangle(cornerRadius: size / 3, style: .continuous)) : AnyShape(Circle()))
            } else if let drawing {
                AvatarArt(drawing: drawing, background: ink.pastel, stamp: stamp)
            } else {
                ZStack {
                    if stamp {
                        RoundedRectangle(cornerRadius: size / 3, style: .continuous).fill(ink.pastel)
                    } else {
                        Circle().fill(ink.pastel)
                    }
                    if isGroup {
                        Image(systemName: "person.2.fill")
                            .font(.system(size: size * 0.34, weight: .medium))
                    } else {
                        Text(letter)
                            .font(.system(size: size * (stamp ? 0.36 : 0.44), weight: stamp ? .semibold : .medium))
                    }
                }
                .foregroundStyle(ink.deep)
            }
        }
        .frame(width: size, height: size)
        .overlay {
            if stamp {
                RoundedRectangle(cornerRadius: size / 3, style: .continuous)
                    .strokeBorder(Palette.stampLine, lineWidth: 1.5)
            }
        }
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

/// Your own avatar: the drawing you picked, or your initial, on your ink.
struct MyAvatar: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    var size: CGFloat = 40
    var stamp = false

    var body: some View {
        AvatarFace(
            drawing: AvatarDrawing(named: model.myProfile?.avatar),
            ink: appearance.ink,
            letter: String((model.account?.username ?? "?").prefix(1)).uppercased(),
            size: size,
            stamp: stamp,
            photo: model.account.flatMap { model.profilePhotos[$0.username] }
        )
    }
}

#if os(macOS)
/// You, at the foot of the chat list: opens your profile and settings in
/// the window.
struct ProfileRow: View {
    @Environment(AppModel.self) private var model
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                MyAvatar(size: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.account?.username ?? "")
                        .font(.headline)
                        .lineLimit(1)
                    Text(model.isOnline ? "Profile and settings" : "Offline")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "gearshape")
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(selected ? AnyShapeStyle(.tint.opacity(0.15)) : AnyShapeStyle(.clear), in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(8)
        .background(.bar)
        .accessibilityLabel("\(model.account?.username ?? "You"): profile and settings")
    }
}
#endif

#if os(iOS)
/// The iPhone's tabs: chats, the map, and you (your profile and settings),
/// switched by a floating pill. The pill hides while a chat is open.
struct MainTabs: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var tab = MainTab.chats

    var body: some View {
        if sizeClass == .regular {
            // iPad: the sidebar has the tabs, the chat opens beside it.
            ConversationListView()
        } else {
            phone
        }
    }

    private var phone: some View {
        TabView(selection: $tab) {
            ConversationListView()
                .toolbar(.hidden, for: .tabBar)
                .tag(MainTab.chats)
            PeopleMapView()
                .toolbar(.hidden, for: .tabBar)
                .tag(MainTab.map)
            NavigationStack {
                SettingsView()
            }
            .toolbar(.hidden, for: .tabBar)
            .tag(MainTab.you)
        }
        .overlay(alignment: .bottom) {
            if !(tab == .chats && model.selectedConversationId != nil) {
                FloatingTabBar(tab: $tab, unread: AppModel.badge(model.conversations))
                    .padding(.bottom, 8)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .animation(.spring(duration: 0.36, bounce: 0.2), value: model.selectedConversationId == nil)
    }
}
#endif

/// The three places at the foot of the app.
enum MainTab: Hashable {
    case chats, map, you

    var title: String {
        switch self {
        case .chats: "Chats"
        case .map: "Map"
        case .you: "You"
        }
    }

    var systemImage: String {
        switch self {
        case .chats: "bubble.left.and.bubble.right.fill"
        case .map: "map.fill"
        case .you: "person.crop.circle.fill"
        }
    }
}

/// A black pill of tabs: the chosen one is a marigold capsule with its name,
/// the others just icons. Chats carries the unread count.
struct FloatingTabBar: View {
    @Binding var tab: MainTab
    let unread: Int
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 2) {
            ForEach([MainTab.chats, .map, .you], id: \.self) { item in
                let active = item == tab
                Button {
                    withAnimation(.spring(duration: 0.42, bounce: 0.3)) { tab = item }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: item.systemImage)
                            .font(.system(size: 18, weight: .semibold))
                            .overlay(alignment: .topTrailing) {
                                if item == .chats && unread > 0 {
                                    Text(unread > 99 ? "99+" : "\(unread)")
                                        .font(.system(size: 11, weight: .bold))
                                        .foregroundStyle(Palette.ink)
                                        .padding(.horizontal, 5)
                                        .frame(minWidth: 18, minHeight: 18)
                                        .background(Capsule().fill(Palette.marigold))
                                        .overlay(Capsule().strokeBorder(Palette.pill, lineWidth: 2))
                                        .offset(x: 12, y: -8)
                                }
                            }
                        if active {
                            Text(item.title)
                                .font(.system(size: 15, weight: .semibold))
                                .transition(.opacity)
                        }
                    }
                    .foregroundStyle(active ? Palette.ink : Color(light: 0xCFCCC2, dark: 0xA3A1A9))
                    .padding(.leading, 14)
                    .padding(.trailing, active ? 18 : 14)
                    .frame(height: 50)
                    .background {
                        if active {
                            Capsule().fill(Palette.marigold).matchedGeometryEffect(id: "pill", in: pill)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(item.title)
                .accessibilityValue(item == .chats && unread > 0 ? "\(unread) unread" : "")
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(6)
        .background(Capsule().fill(Palette.pill))
        .overlay(Capsule().strokeBorder(Color(light: 0x141414, dark: 0x2E2E33), lineWidth: 1))
        .shadow(color: .black.opacity(0.28), radius: 16, y: 12)
    }
}

/// Where a photo from the camera button goes: pick a chat, and it opens with
/// the photo waiting above the text box.
struct SendToChatView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let onPick: (String) -> Void

    private var chats: [ConversationSummary] {
        model.conversations.filter { $0.active && !$0.leaving }
    }

    var body: some View {
        NavigationStack {
            List(chats, id: \.id) { conversation in
                Button {
                    dismiss()
                    onPick(conversation.id)
                } label: {
                    ConversationRow(conversation: conversation)
                }
                .buttonStyle(.plain)
            }
            .overlay {
                if chats.isEmpty {
                    ContentUnavailableView("No chats yet", systemImage: "bubble.left.and.bubble.right", description: Text("Start a chat first, then send it photos."))
                }
            }
            .navigationTitle("Send to")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(width: 380, height: 420)
        #endif
    }
}

/// Keeps a form to a readable width in a wide pane. The margins go inside the
/// scroll view, so it still fills the pane and the toolbar's blur reaches
/// both edges.
struct ReadableWidth<Content: View>: View {
    @ViewBuilder var content: Content
    @State private var width: CGFloat = 0
    private let readable: CGFloat = 640

    var body: some View {
        content
            .contentMargins(.horizontal, max(0, (width - readable) / 2), for: .scrollContent)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}


/// Pinned chats as big coloured cards, each with the shape that says how
/// it's doing: live location, muted, unread, a group, or someone you love.
struct PinnedCards: View {
    let chats: [ConversationSummary]
    @Binding var confirm: ChatConfirm?
    let open: (ConversationSummary) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(Array(chats.prefix(5).enumerated()), id: \.element.id) { i, chat in
                    PinnedCard(chat: chat, index: i)
                        .onTapGesture { open(chat) }
                        .contextMenu { ChatActionsMenu(conversation: chat, confirm: $confirm) }
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction { open(chat) }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 4)
        }
    }
}

struct PinnedCard: View {
    @Environment(AppModel.self) private var model
    let chat: ConversationSummary
    let index: Int
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let colors: [UInt32] = [0xF4A6D0, 0x5DC584, 0xFFD23F, 0xC9BDFF, 0xFFB48A]

    private var sharing: SharedLocation? { model.sharedLocations.first { $0.conversationId == chat.id } }
    private var live: Bool { sharing != nil }

    /// "Live · 2.4 km away", or just that they're sharing when we don't know where we are.
    private var liveText: String {
        guard let person = sharing, let me = lastKnownLocation(model.location) else { return "Sharing live location" }
        return "Live · " + distanceAway(me.distance(from: CLLocation(latitude: person.latitude, longitude: person.longitude)))
    }

    /// A group's colour; a person's ink, if they picked one; else steady
    /// from the chat's id.
    private var cardColour: UInt32 { Self.colour(for: chat, model: model) }

    static func colour(for chat: ConversationSummary, model: AppModel) -> UInt32 {
        if let colour = chat.groupColour { return colour.card }
        if chat.kind == .direct, let ink = Ink(named: model.profiles[chat.title]?.ink) { return ink.card }
        return colour(for: chat.id)
    }

    /// The shape that says how a pinned chat is doing.
    static func shape(for chat: ConversationSummary, model: AppModel) -> ShapeKind {
        if model.sharedLocations.contains(where: { $0.conversationId == chat.id }) { return .squiggle }
        if chat.mutedUntilMs != nil { return .moon }
        if chat.unread > 0 { return .bolt }
        if chat.kind == .group { return .clover }
        return .heart
    }

    /// The card's colour: steady for the chat, not its place in the row.
    /// FNV-1a over the id, as on Android.
    private static func colour(for id: String) -> UInt32 {
        var hash: UInt32 = 2_166_136_261
        for byte in id.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return colors[Int(hash % UInt32(colors.count))]
    }
    private var muted: Bool { chat.mutedUntilMs != nil }
    private var unread: Bool { chat.unread > 0 }

    private var shape: ShapeKind {
        if live { return .squiggle }
        if muted { return .moon }
        if unread { return .bolt }
        if chat.kind == .group { return .clover }
        return .heart
    }

    private var preview: String {
        if chat.locked { return "Locked chat" }
        guard let text = chat.lastMessage else { return chat.active ? "No messages yet" : "This chat has ended" }
        if chat.kind == .group, let sender = chat.lastSender, !sender.isEmpty { return "\(sender): \(text)" }
        return text
    }

    var body: some View {
        let bg = Color(rgb: cardColour)
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                Avatar(title: chat.title, isGroup: chat.kind == .group, size: 52, stamp: true)
                    .overlay(RoundedRectangle(cornerRadius: 17, style: .continuous).stroke(Palette.ink, lineWidth: 1.5))
                if unread {
                    Text("\(chat.unread)")
                        .font(.system(size: 12.5, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 7)
                        .frame(minWidth: 24, minHeight: 24)
                        .background(Palette.ink, in: Capsule())
                        .overlay(Capsule().stroke(bg, lineWidth: 2.5))
                        .offset(x: 9, y: -9)
                        .transition(.scale)
                }
                if live {
                    PingDot()
                        .scaleEffect(2.2)
                        .offset(x: 6, y: 36)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Spacer(minLength: 6)
            VStack(alignment: .leading, spacing: 3) {
                Text(chat.title)
                    .font(.display(20))
                    .lineLimit(2)
                    .lineSpacing(-2)
                if live {
                    HStack(spacing: 5) {
                        Circle().fill(Color(rgb: 0x1F7A45)).frame(width: 7, height: 7)
                        Text(liveText).font(.system(size: 12.5, weight: .semibold))
                    }
                }
                (Text(shortTime(chat.lastActivityMs)).fontWeight(.semibold) + Text(" · " + preview))
                    .font(.system(size: 13.5))
                    .lineLimit(live ? 1 : 2)
                    .opacity(0.82)
            }
        }
        .foregroundStyle(Palette.ink)
        .padding(14)
        .frame(width: 172, height: 172, alignment: .topLeading)
        .background(alignment: .topTrailing) {
            ShapeSticker(kind: shape, size: 58, delay: 0.15 + Double(index) * 0.08, phase: Double(index) * -0.7, tappable: false)
                .padding(10)
                .id(shape)
        }
        .background(bg, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .scaleEffect(shown || reduceMotion ? 1 : 0.94)
        .opacity(shown || reduceMotion ? 1 : 0)
        .offset(y: shown || reduceMotion ? 0 : 6)
        .onAppear {
            withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.34).delay(Double(index) * 0.06)) { shown = true }
        }
        .accessibilityLabel("\(chat.title)\(unread ? ", \(chat.unread) unread" : ""). \(preview)")
    }
}


/// Sets `scrolled` once a list has moved a few points (iOS 18 and macOS 15 on).
private extension View {
    /// A list row with nothing of the list's own: no background, no line, no insets.
    func clearRow() -> some View {
        listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets())
    }
}

private struct ScrolledFlag: ViewModifier {
    @Binding var scrolled: Bool
    @Binding var far: Bool
    func body(content: Content) -> some View {
        if #available(iOS 18, macOS 15, *) {
            content.onScrollGeometryChange(for: Int.self) { geo in
                let y = geo.contentOffset.y + geo.contentInsets.top
                return y > 44 ? 2 : (y > 4 ? 1 : 0)
            } action: { _, level in
                scrolled = level > 0
                far = level > 1
            }
        } else {
            content
        }
    }
}
