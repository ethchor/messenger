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

    enum Sheet: String, Identifiable {
        case newChat, newGroup, settings, map
        var id: String { rawValue }
    }

    var body: some View {
        @Bindable var model = model
        NavigationSplitView {
            List(selection: $model.selectedConversationId) {
                #if os(iOS)
                // A section of its own, so the chats' section keeps its
                // rounded top instead of starting behind the card.
                if let tip = currentTip {
                    Section {
                        tipCard(tip)
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                    }
                }
                #endif
                Section {
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
            }
            .overlay {
                if model.conversations.isEmpty {
                    EmptyState(
                        illustration: .chats,
                        title: "No chats yet",
                        text: "Start one with someone's username. They need to have opened Messenger once."
                    ) {
                        Button("New chat") { sheet = .newChat }
                            .buttonStyle(.borderedProminent)
                    }
                }
            }
            #if os(macOS)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ProfileRow(selected: model.showingSettings) {
                    model.selectedConversationId = nil
                    model.showingSettings = true
                }
            }
            #endif
            .navigationTitle("Chats")
            .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 420)
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
                #endif
                #if os(iOS)
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        // The simulator has no camera: the library stands in.
                        if CameraPicker.isAvailable { usingCamera = true } else { usingLibrary = true }
                    } label: {
                        Label("Camera", systemImage: "camera")
                    }
                }
                #endif
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button { sheet = .newChat } label: { Label("New chat", systemImage: "person") }
                        Button { sheet = .newGroup } label: { Label("New group", systemImage: "person.3") }
                    } label: {
                        Label("New", systemImage: "square.and.pencil")
                    }
                }
                #if os(iOS)
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
            } else if model.showingSettings {
                NavigationStack {
                    SettingsView()
                        .frame(maxWidth: 640)
                        .frame(maxWidth: .infinity)
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
        guard let text = conversation.lastMessage else {
            return conversation.active ? "No messages yet" : "This chat has ended"
        }
        if conversation.kind == .group, let sender = conversation.lastSender, !sender.isEmpty {
            return conversation.lastInThread ? "\(sender) in a thread: \(text)" : "\(sender): \(text)"
        }
        return conversation.lastInThread ? "In a thread: \(text)" : text
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
                            .font(.system(size: size * 0.44, weight: .medium))
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

/// Your own avatar: the drawing you picked, or your initial, on your ink.
struct MyAvatar: View {
    @Environment(AppModel.self) private var model
    @Environment(Appearance.self) private var appearance
    var size: CGFloat = 40

    var body: some View {
        Group {
            if let drawing = AvatarDrawing(named: model.myProfile?.avatar) {
                AvatarArt(drawing: drawing, background: appearance.ink.pastel)
            } else {
                ZStack {
                    Circle().fill(appearance.ink.pastel)
                    Text(String((model.account?.username ?? "?").prefix(1)).uppercased())
                        .font(.system(size: size * 0.44, weight: .medium))
                        .foregroundStyle(appearance.ink.deep)
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
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
/// The iPhone's tabs: chats, the map, and you (your profile and settings).
struct MainTabs: View {
    @Environment(AppModel.self) private var model
    @State private var tab = Tab.chats

    enum Tab { case chats, map, you }

    @Environment(Appearance.self) private var appearance

    /// Your avatar as a picture: a tab can only show an image. Reading the
    /// profile and the ink here draws it again when either changes.
    private var avatarImage: UIImage? {
        _ = (model.myProfile?.avatar, model.account?.username, appearance.ink)
        let renderer = ImageRenderer(content: MyAvatar(size: 28).environment(model).environment(appearance))
        renderer.scale = 3
        return renderer.uiImage?.withRenderingMode(.alwaysOriginal)
    }

    private var unread: Int {
        model.conversations.reduce(0) { $0 + Int($1.unread) }
    }

    var body: some View {
        TabView(selection: $tab) {
            ConversationListView()
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                .badge(unread)
                .tag(Tab.chats)
            PeopleMapView()
                .tabItem { Label("Map", systemImage: model.sharedLocations.isEmpty ? "map" : "map.fill") }
                .tag(Tab.map)
            NavigationStack {
                SettingsView()
            }
            .tabItem {
                Label {
                    Text("You")
                } icon: {
                    if let avatar = avatarImage {
                        Image(uiImage: avatar).renderingMode(.original)
                    } else {
                        Image(systemName: "person.crop.circle")
                    }
                }
            }
            .tag(Tab.you)
        }
    }
}
#endif

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
