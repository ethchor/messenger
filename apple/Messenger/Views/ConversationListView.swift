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
    @State private var confirm: ChatConfirm?

    enum Sheet: String, Identifiable {
        case newChat, newGroup, settings, map
        var id: String { rawValue }
    }

    var body: some View {
        @Bindable var model = model
        let shown = model.conversations.filter { filter.includes($0) }
        NavigationSplitView {
            // A locked chat opens only after Face ID, Touch ID or the passcode.
            List(selection: Binding(
                get: { model.selectedConversationId },
                set: { id in
                    guard let id, let chat = model.conversations.first(where: { $0.id == id }), chat.locked else {
                        model.selectedConversationId = id
                        return
                    }
                    Task {
                        if await lock.unlockChat(named: chat.title) {
                            model.selectedConversationId = id
                        }
                    }
                }
            )) {
                #if os(iOS)
                // The recovery key is urgent, so it leads; the other tips
                // wait under the chats instead of pushing them down.
                if let tip = currentTip, tip == .recoveryKey {
                    tipSection(tip)
                }
                #endif
                Section {
                    if !model.isOnline {
                        Label("Offline. Retrying…", systemImage: "wifi.slash")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(shown, id: \.id) { conversation in
                        NavigationLink(value: conversation.id) {
                            ConversationRow(conversation: conversation)
                        }
                        .chatSwipeActions(conversation, model: model, confirm: $confirm)
                        .contextMenu { ChatActionsMenu(conversation: conversation, confirm: $confirm) }
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))
                    }
                    if shown.isEmpty && !model.conversations.isEmpty {
                        Text(filter.emptyText)
                            .font(.subheadline)
                            .foregroundStyle(Palette.secondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 30)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                } header: {
                    if !model.conversations.isEmpty {
                        Text(filter.listTitle)
                            .font(.display(22))
                            .displayTracking(22)
                            .foregroundStyle(Palette.text)
                            .textCase(nil)
                            .padding(.leading, 12)
                            .padding(.top, 4)
                    }
                } footer: {
                    VStack(spacing: 8) {
                        if showingArchived {
                            Text("Archived chats stay here when new messages come.")
                        }
                        if !model.conversations.isEmpty {
                            Label("Only the people in a chat can read it", systemImage: "lock.fill")
                                .font(.caption)
                        }
                    }
                    .foregroundStyle(Palette.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 12)
                }
                #if os(iOS)
                if let tip = currentTip, tip != .recoveryKey {
                    tipSection(tip)
                }
                #endif
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
            .scrollContentBackground(.hidden)
            .background { HomeGround().ignoresSafeArea() }
            .safeAreaInset(edge: .top, spacing: 0) { listHeader }
            #if os(iOS)
            .contentMargins(.bottom, 90, for: .scrollContent)
            .toolbar(.hidden, for: .navigationBar)
            #endif
            #if os(macOS)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ProfileRow(selected: model.showingSettings) {
                    model.selectedConversationId = nil
                    model.showingSettings = true
                }
            }
            #endif
            .navigationTitle(showingArchived ? "Archived" : "Chats")
            .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 420)
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
                NewGroupView()
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
            HStack(spacing: 8) {
                Text("Chats")
                    .font(.display(34))
                    .displayTracking(34)
                    .foregroundStyle(Palette.text)
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
                RoundButton(systemImage: "camera", label: "Camera") {
                    // The simulator has no camera: the library stands in.
                    if CameraPicker.isAvailable { usingCamera = true } else { usingLibrary = true }
                }
                newMenu
            }
            .padding(.leading, 20)
            .padding(.trailing, 16)
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
            Palette.home
                .mask {
                    LinearGradient(colors: [.black, .black, .black.opacity(0)], startPoint: .top, endPoint: .bottom)
                }
                .ignoresSafeArea()
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

    /// A tip in a section of its own, so the chats' card keeps its rounded
    /// corners instead of running behind it.
    private func tipSection(_ tip: ChatListTip) -> some View {
        Section {
            tipCard(tip)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
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

    var body: some View {
        HStack(spacing: 14) {
            Avatar(title: conversation.title, isGroup: conversation.kind == .group, size: 54, stamp: true)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(conversation.title)
                        .font(.system(size: 17, weight: .semibold))
                        .lineLimit(1)
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
                    Spacer(minLength: 8)
                    Text(shortTime(conversation.lastActivityMs))
                        .font(.system(size: 13, weight: hasUnread ? .semibold : .regular))
                        .foregroundStyle(hasUnread ? AnyShapeStyle(.tint) : AnyShapeStyle(Palette.secondary))
                }
                HStack(spacing: 8) {
                    Text(preview)
                        .font(.system(size: 15, weight: hasUnread ? .semibold : .regular))
                        .foregroundStyle(hasUnread ? Palette.text : Palette.secondary)
                        .italic(conversation.lastMessage == nil)
                        .lineLimit(1)
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
        .padding(.horizontal, 4)
        .animation(.spring(duration: 0.34, bounce: 0.4), value: conversation.unread)
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
        AvatarFace(
            drawing: isGroup ? nil : AvatarDrawing(named: model.profiles[title]?.avatar),
            ink: ink,
            letter: String(title.prefix(1)).uppercased(),
            isGroup: isGroup,
            size: size,
            stamp: stamp
        )
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

    var body: some View {
        Group {
            if let drawing {
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
            stamp: stamp
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
    @State private var tab = MainTab.chats

    var body: some View {
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
