import Foundation
import Observation
#if os(iOS)
import UIKit
#endif

/// App state for the views. The Rust core owns the real state (keys, groups,
/// messages); this mirrors what's on screen and runs the sync loop.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable {
        case starting
        case onboarding
        case recoveryKey(String)
        case ready
        case failed(String)
    }

    /// The simulator and the Mac app reach a server running on this Mac here.
    static let defaultServerURL = "https://qchat.qwantarc.com"
    private static let serverURLKey = "serverURL"

    var phase: Phase = .starting
    var account: AccountInfo?
    var conversations: [ConversationSummary] = []
    var selectedConversationId: String?
    /// Your profile and settings, in the Mac window's detail pane in place of a chat.
    var showingSettings = false
    /// A photo or video from the chats' camera button, waiting for its chat to open.
    var pendingCapture: PendingCapture?
    /// Messages of the open conversation (and recently opened ones), oldest first.
    var messages: [String: [MessageView]] = [:]
    /// The thread on screen, if any.
    var openThread: ThreadRef?
    /// Threads opened lately, by the id of the message they hang off: that
    /// message, then the replies, oldest first.
    var threads: [String: [MessageView]] = [:]
    var errorMessage: String?
    var isOnline = true
    var busy = false
    var serverURL: String
    /// Read receipts and typing indicators (kept by the core).
    var privacy = PrivacySettings(readReceipts: true, typingIndicators: true)
    /// Who is typing, by conversation id.
    var typing: [String: [String]] = [:]
    /// A recovery key that hasn't been confirmed as written down yet.
    var unconfirmedRecoveryKey: String?
    /// The latest position of everyone sharing their location with us.
    var sharedLocations: [SharedLocation] = []
    /// The chats we share our own location in.
    var locationShares: [LocationShare] = []
    /// Everyone's avatar and ink that we know of, by username (ours too).
    var profiles: [String: PersonProfile] = [:]
    /// Profile photos on this device, by username.
    var profilePhotos: [String: String] = [:]
    /// A short confirmation at the foot of the screen, gone after 1.7 s.
    var toast: String?
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    /// Files on their way down.
    private(set) var fetching: Set<AttachmentRef> = []
    /// This device's position, while we share it anywhere.
    @ObservationIgnored let location = LocationService()
    /// Message notifications, and pushes that wake the app for them.
    @ObservationIgnored let notifications = MessageNotifications.shared

    @ObservationIgnored private var client: MessengerClient?
    @ObservationIgnored private var openedURL: String?
    @ObservationIgnored private var syncTask: Task<Void, Never>?
    @ObservationIgnored private var started = false
    /// False while the app is in the background or locked. Messages only
    /// count as read (and send read receipts) while someone could see them.
    @ObservationIgnored private var isVisible = true
    /// A chat (and maybe a thread in it) to open once the app is ready: its
    /// notification was tapped while starting.
    @ObservationIgnored private var pendingOpen: (conversation: String, thread: String?)?
    /// Called once the account has left this device, to reset what the views keep.
    @ObservationIgnored var onSignedOut: (() -> Void)?
    /// Files waiting for one of the few fetches that run at once.
    @ObservationIgnored private var fetchQueue: [AttachmentRef] = []
    @ObservationIgnored private var fetchesRunning = 0
    /// Uploads go one round at a time; asking during one asks for another.
    @ObservationIgnored private var uploading = false
    @ObservationIgnored private var uploadAgain = false
    private let core = CoreQueue()

    init() {
        serverURL = UserDefaults.standard.string(forKey: Self.serverURLKey) ?? Self.defaultServerURL
        location.onFix = { [weak self] fix in self?.sendLocation(fix) }
        notifications.onOpen = { [weak self] id, thread in self?.openFromNotification(id, thread: thread) }
        notifications.onPush = { [weak self] in Task { await self?.syncNow() } }
        notifications.onToken = { [weak self] token in self?.sendPushToken(token) }
        // iOS can launch the app in the background for a location update,
        // without any window: start here rather than from a view.
        Task { [weak self] in await self?.start() }
    }

    // MARK: - Startup and account

    func start() async {
        // Called at launch and again by the first window; runs once.
        guard phase == .starting, !started else { return }
        started = true
        AppFiles.removeTemporaryFiles()
        do {
            try await open(serverURL: serverURL)
            try await loadAccount()
        } catch {
            phase = .failed(error.userMessage)
        }
    }

    /// The splash's "Try again" after the core failed to open.
    func retryStart() async {
        guard case .failed = phase else { return }
        phase = .starting
        started = false
        await start()
    }

    /// Opens the core for the given server. The core keeps the server of a
    /// registered account, so this only matters before sign-up.
    private func open(serverURL: String) async throws {
        if client != nil, openedURL == serverURL { return }
        let directory = try AppFiles.dataDirectory().path
        client = nil
        client = try await core.run { try MessengerClient(dataDir: directory, serverUrl: serverURL) }
        openedURL = serverURL
    }

    private func requireClient() throws -> MessengerClient {
        guard let client else { throw AppError.notReady }
        return client
    }

    private func loadAccount() async throws {
        let client = try requireClient()
        let account = try await core.run { try client.account() }
        let pendingKey = try await core.run { try client.pendingRecoveryKey() }
        self.account = account
        guard let account else {
            phase = .onboarding
            return
        }
        serverURL = account.serverUrl
        if let pendingKey {
            phase = .recoveryKey(pendingKey)
        } else {
            enterApp()
        }
    }

    private func enterApp() {
        phase = .ready
        Task {
            await refreshConversations()
            if let open = pendingOpen {
                pendingOpen = nil
                showConversation(open.conversation, thread: open.thread)
            }
            await loadSettings()
            await refreshLocations()
            await refreshProfiles()
            // Our photo goes up again before it could expire on the server.
            if let client = self.client { _ = try? await core.run { try client.refreshProfilePhoto() } }
            await setUpNotifications()
        }
        startSyncLoop()
        // Files that didn't finish going up last time.
        sendPendingMedia()
    }

    private func loadSettings() async {
        guard let client else { return }
        if let settings = try? await core.run({ try client.privacySettings() }) {
            privacy = settings
        }
        unconfirmedRecoveryKey = try? await core.run { try client.pendingRecoveryKey() }
    }

    private func normalizedServerURL() throws -> String {
        var url = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while url.hasSuffix("/") { url.removeLast() }
        guard url.hasPrefix("http://") || url.hasPrefix("https://"), URL(string: url) != nil else {
            throw MessengerError.InvalidInput(message: "The server address should look like https://chat.example.com")
        }
        serverURL = url
        UserDefaults.standard.set(url, forKey: Self.serverURLKey)
        return url
    }

    func register(username: String) async {
        await whileBusy {
            try await self.open(serverURL: self.normalizedServerURL())
            let client = try self.requireClient()
            let registration = try await self.core.run { try client.register(username: username) }
            self.account = registration.account
            self.phase = .recoveryKey(registration.recoveryKey)
        }
    }

    func recover(username: String, recoveryKey: String) async {
        await whileBusy {
            try await self.open(serverURL: self.normalizedServerURL())
            let client = try self.requireClient()
            self.account = try await self.core.run { try client.recover(username: username, recoveryKey: recoveryKey) }
            self.enterApp()
        }
    }

    func confirmRecoveryKeySaved() async {
        await whileBusy {
            let client = try self.requireClient()
            try await self.core.run { try client.confirmRecoveryKeySaved() }
            self.unconfirmedRecoveryKey = nil
            if self.phase != .ready {
                self.enterApp()
            }
        }
    }

    // MARK: - Account settings (these throw so the settings screen can show errors inline)

    /// Replaces the recovery key. The old one stops working at once.
    func newRecoveryKey() async throws -> String {
        let client = try requireClient()
        let key = try await core.run { try client.newRecoveryKey() }
        unconfirmedRecoveryKey = key
        return key
    }

    /// Leaves every chat, deletes the account on the server and erases this
    /// device, then goes back to sign-up.
    func deleteAccount() async throws {
        let client = try requireClient()
        stopSyncLoop()
        do {
            try await core.run { try client.deleteAccount() }
        } catch {
            startSyncLoop()
            throw error
        }
        signedOut()
    }

    // MARK: - Linked devices

    /// This device's name, as other devices list it.
    static var deviceName: String {
        #if os(iOS)
        UIDevice.current.name
        #else
        Host.current().localizedName ?? "Mac"
        #endif
    }

    /// On a new device: the text for the QR code another device scans.
    func beginLink() async throws -> String {
        try await open(serverURL: normalizedServerURL())
        let client = try requireClient()
        let name = Self.deviceName
        return try await core.run { try client.beginLink(deviceName: name) }
    }

    /// On the new device: waits until the other device approves, then signs
    /// in. Returns when done or cancelled.
    func waitForLink() async {
        while !Task.isCancelled {
            do {
                let client = try requireClient()
                if try await core.run({ try client.finishLink() }) {
                    try await loadAccount()
                    return
                }
            } catch {
                errorMessage = error.userMessage
                return
            }
            try? await Task.sleep(for: .seconds(2))
        }
    }

    /// The name a device showing `code` gave itself. Throws if it isn't a link code.
    func linkRequestName(_ code: String) async throws -> String {
        let client = try requireClient()
        return try await core.run { try client.linkRequestName(code: code) }
    }

    func approveLink(_ code: String, withHistory: Bool) async throws {
        let client = try requireClient()
        try await core.run { try client.approveLink(code: code, withHistory: withHistory) }
        Task { await syncNow() }
    }

    func linkedDevices() async throws -> [LinkedDevice] {
        let client = try requireClient()
        return try await core.run { try client.devices() }
    }

    func removeDevice(_ deviceId: String) async throws {
        let client = try requireClient()
        try await core.run { try client.removeDevice(deviceId: deviceId) }
    }

    /// A new account key with the recovery key: every other device is signed
    /// out, and chats end until started again.
    func resetIdentity(recoveryKey: String) async throws {
        let client = try requireClient()
        stopSyncLoop()
        defer { startSyncLoop() }
        account = try await core.run { try client.resetIdentity(recoveryKey: recoveryKey) }
        await refreshConversations()
    }

    /// Leaves every chat and erases this device; the recovery key brings the
    /// account back.
    func removeFromDevice() async throws {
        let client = try requireClient()
        stopSyncLoop()
        do {
            try await core.run { try client.removeFromDevice() }
        } catch {
            startSyncLoop()
            throw error
        }
        signedOut()
    }

    /// Panic wipe: erases this device at once, without the network. The
    /// recovery key brings the account back, without the old messages.
    func wipeThisDevice() async {
        guard let client else { return }
        stopSyncLoop()
        do {
            try await core.run { try client.wipeThisDevice() }
        } catch {
            errorMessage = error.userMessage
        }
        signedOut()
    }

    private func signedOut() {
        location.setSharing(false)
        notifications.forget()
        VoicePlayer.shared.stop()
        AppFiles.removeTemporaryFiles()
        fetchQueue = []
        fetching = []
        sharedLocations = []
        locationShares = []
        account = nil
        conversations = []
        messages = [:]
        openThread = nil
        threads = [:]
        typing = [:]
        selectedConversationId = nil
        EmojiCatalog.reset()
        unconfirmedRecoveryKey = nil
        privacy = PrivacySettings(readReceipts: true, typingIndicators: true)
        profiles = [:]
        phase = .onboarding
        onSignedOut?()
    }

    func setPrivacy(_ settings: PrivacySettings) async {
        let previous = privacy
        privacy = settings
        if !settings.typingIndicators { typing = [:] }
        let ok = await perform { try $0.setPrivacySettings(settings: settings) }
        if !ok { privacy = previous }
    }

    // MARK: - Sync

    /// Long-polls the server forever, refreshing what's on screen after each
    /// round. Backs off while offline.
    private func startSyncLoop() {
        guard syncTask == nil, let client else { return }
        syncTask = Task { [weak self] in
            var delay: UInt64 = 1
            while !Task.isCancelled {
                guard let core = self?.core else { return }
                do {
                    let report = try await core.poll { try client.sync(waitMs: 25_000) }
                    guard let self, !Task.isCancelled else { return }
                    self.isOnline = true
                    delay = 1
                    await self.apply(report)
                } catch {
                    guard let self, !Task.isCancelled else { return }
                    if error.isNetworkError {
                        self.isOnline = false
                    } else {
                        self.errorMessage = error.userMessage
                    }
                    try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                    delay = min(delay * 2, 30)
                }
            }
        }
    }

    private func stopSyncLoop() {
        syncTask?.cancel()
        syncTask = nil
    }

    /// One round now, without waiting (pull to refresh, back from background).
    func syncNow() async {
        guard phase == .ready, let client else { return }
        do {
            let report = try await core.run { try client.sync(waitMs: 0) }
            isOnline = true
            await apply(report)
        } catch {
            if error.isNetworkError { isOnline = false } else { errorMessage = error.userMessage }
        }
    }

    private func apply(_ report: SyncReport) async {
        guard phase == .ready else { return }
        for id in report.typingChanged {
            await refreshTyping(id)
        }
        // Cheap, and it also notices shares that just ran out.
        await refreshLocations()
        if report.profilesChanged {
            await refreshProfiles()
        }
        await refreshConversations()
        // Drop cached messages of conversations that changed in the background.
        let elsewhere = report.updatedConversations.filter { $0 != selectedConversationId }
        for id in elsewhere {
            messages[id] = nil
        }
        if let id = selectedConversationId {
            await loadMessages(id)
        }
        if let ref = openThread {
            await loadThread(ref)
        }
        await showNotifications()
        if report.unsentMedia > 0 {
            sendPendingMedia()
        }
        if report.newMessages > 0 {
            await fetchNewMedia(in: elsewhere)
        }
    }

    func refreshConversations() async {
        guard let client else { return }
        if let list = try? await core.run({ try client.conversations() }) {
            conversations = list
            notifications.setBadge(Self.badge(list))
        }
    }

    func loadMessages(_ conversationId: String) async {
        guard let client else { return }
        do {
            let list = try await core.run { try client.messages(conversationId: conversationId, limit: 500) }
            messages[conversationId] = list
            fetchAutomatically(list, in: conversationId)
        } catch {
            errorMessage = error.userMessage
        }
    }

    func loadThread(_ ref: ThreadRef) async {
        guard let client else { return }
        do {
            let list = try await core.run {
                try client.threadMessages(conversationId: ref.conversationId, messageId: ref.rootId, limit: 500)
            }
            threads[ref.rootId] = list
            fetchAutomatically(list, in: ref.conversationId)
        } catch {
            errorMessage = error.userMessage
        }
    }

    /// A message on screen, in a chat or an open thread.
    func message(_ id: String, in conversationId: String) -> MessageView? {
        if let message = messages[conversationId]?.first(where: { $0.id == id }) {
            return message
        }
        return threads.values.lazy.compactMap { $0.first { $0.id == id } }.first
    }

    /// The thread is on screen: its replies are read.
    func markThreadRead(_ ref: ThreadRef) async {
        guard isVisible, let client else { return }
        let unread = messages[ref.conversationId]?.first { $0.id == ref.rootId }?.thread?.unreadCount ?? 0
        _ = try? await core.run { try client.markThreadRead(conversationId: ref.conversationId, messageId: ref.rootId) }
        if unread > 0 {
            // The "new" under the message goes, and so may the chat's count.
            await loadMessages(ref.conversationId)
            await refreshConversations()
        }
    }

    func refreshTyping(_ conversationId: String) async {
        guard let client else { return }
        let names = (try? await core.run { try client.typingUsers(conversationId: conversationId) }) ?? []
        typing[conversationId] = names.isEmpty ? nil : names
    }

    /// Tells the others we're typing (or stopped). The core throttles it.
    func setTyping(_ isTyping: Bool, in conversationId: String) {
        guard privacy.typingIndicators, let client else { return }
        Task { _ = try? await core.run { try client.setTyping(conversationId: conversationId, typing: isTyping) } }
    }

    /// The app came to the front and is unlocked, or went away or locked.
    func setVisible(_ visible: Bool) {
        let cameBack = visible && !isVisible
        isVisible = visible
        if cameBack, phase == .ready, let id = selectedConversationId {
            Task { await markRead(id) }
        }
    }

    func markRead(_ conversationId: String) async {
        guard isVisible, let client else { return }
        notifications.clear(conversationId: conversationId)
        _ = try? await core.run { try client.markRead(conversationId: conversationId) }
        if let index = conversations.firstIndex(where: { $0.id == conversationId }) {
            conversations[index].unread = 0
        }
        notifications.setBadge(Self.badge(conversations))
    }

    // MARK: - Profile

    func refreshProfiles() async {
        guard let client else { return }
        if let list = try? await core.run({ try client.profiles() }) {
            profiles = Dictionary(list.map { ($0.username, $0) }, uniquingKeysWith: { _, last in last })
            await refreshProfilePhotos(list)
        }
    }

    /// Fetches the photos of everyone who has one (the core keeps them), and
    /// forgets the ones that went away.
    private func refreshProfilePhotos(_ list: [PersonProfile]) async {
        guard let client else { return }
        for person in list {
            if !person.hasPhoto {
                if profilePhotos[person.username] != nil { profilePhotos[person.username] = nil }
                continue
            }
            let name = person.username
            if let path = try? await core.run({ try client.profilePhoto(username: name) }), profilePhotos[name] != path {
                profilePhotos[name] = path
            }
        }
    }

    /// Sets our profile photo from a square JPEG, or removes it (nil), and
    /// shares that with every chat. Returns whether it worked.
    @discardableResult
    func setProfilePhoto(_ jpeg: Data?) async -> Bool {
        var path: String?
        if let jpeg {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("profile-\(UUID().uuidString).jpg")
            guard (try? jpeg.write(to: url)) != nil else { return false }
            path = url.path
        }
        defer { if let path { try? FileManager.default.removeItem(atPath: path) } }
        let ok = await perform { try $0.setProfilePhoto(path: path) }
        if ok {
            if jpeg == nil, let me = account?.username { profilePhotos[me] = nil }
            await refreshProfiles()
        }
        return ok
    }

    /// Our avatar and ink, as others see them.
    var myProfile: PersonProfile? {
        account.flatMap { profiles[$0.username] }
    }

    /// Sets our avatar (nil: our initial) and ink, and shares them with every chat.
    func setProfile(avatar: AvatarDrawing?, ink: Ink) async {
        let avatarName = avatar?.rawValue ?? ""
        let inkName = ink.rawValue
        if await perform({ try $0.setProfile(avatar: avatarName, ink: inkName) }) {
            await refreshProfiles()
        }
    }

    // MARK: - Notifications

    /// Asks once to show notifications, and registers for pushes where the
    /// server and this build can (see `MessageNotifications`).
    func setUpNotifications() async {
        guard phase == .ready, notifications.enabled, let client else { return }
        await notifications.requestAuthorizationIfNeeded()
        let info = try? await core.run { try client.pushInfo() }
        notifications.registerForPushes(serverCanPush: info?.apns ?? false)
    }

    func setNotificationsEnabled(_ on: Bool) async {
        notifications.setEnabled(on)
        if on {
            await setUpNotifications()
        } else if let client {
            // The server forgets this device, and the chats stop waking it.
            _ = try? await core.run { try client.clearPushTarget() }
        }
    }

    private func sendPushToken(_ token: Data) {
        guard let client else { return }
        // Builds run from Xcode use Apple's development push service.
        #if DEBUG
        let sandbox = true
        #else
        let sandbox = false
        #endif
        Task {
            do {
                try await core.run { try client.setPushTarget(target: .apns(token: token, sandbox: sandbox)) }
                notifications.tokenSent(nil)
            } catch {
                notifications.tokenSent(error.userMessage)
            }
        }
    }

    /// New messages as notifications, unless they're on screen already.
    private func showNotifications() async {
        guard let client,
              let notes = try? await core.run({ try client.takeNotifications(limit: 50) }),
              !notes.isEmpty
        else { return }
        guard isVisible else {
            notifications.show(notes) { _ in false }
            return
        }
        #if os(macOS)
        // The window shows every chat, with its unread count.
        notifications.show(notes) { _ in true }
        #else
        // The open chat shows its messages, the open thread its replies, and
        // the list shows them all. A reply in a thread that isn't open still
        // gets a notification.
        let open = selectedConversationId
        let thread = openThread?.rootId
        notifications.show(notes) { note in
            open == nil || (note.conversationId == open && (note.threadRoot == nil || note.threadRoot == thread))
        }
        #endif
    }

    /// A message notification was tapped.
    func openFromNotification(_ conversationId: String, thread: String? = nil) {
        guard phase == .ready else {
            pendingOpen = (conversationId, thread)
            return
        }
        Task {
            // The push may have brought a chat this screen hasn't seen yet.
            await refreshConversations()
            showConversation(conversationId, thread: thread)
        }
    }

    private func showConversation(_ id: String, thread: String? = nil) {
        guard conversations.contains(where: { $0.id == id }) else { return }
        selectedConversationId = id
        openThread = thread.map { ThreadRef(conversationId: id, rootId: $0) }
    }

    #if os(iOS)
    /// iOS woke the app for a moment: fetch, so new messages get notifications.
    func backgroundRefresh() async {
        BackgroundRefresh.schedule()
        // Launched for this: give the core a moment to open.
        for _ in 0..<20 where phase == .starting {
            try? await Task.sleep(for: .milliseconds(250))
        }
        await syncNow()
    }
    #endif

    // MARK: - Messages

    func send(_ text: String, to conversationId: String, replyTo: String?) async {
        await perform { _ = try $0.sendText(conversationId: conversationId, text: text, replyTo: replyTo) }
        await loadMessages(conversationId)
        await refreshConversations()
    }

    /// Replies in a thread; the reply shows there, not in the chat.
    func replyInThread(_ text: String, to ref: ThreadRef) async {
        await perform { _ = try $0.replyInThread(conversationId: ref.conversationId, messageId: ref.rootId, text: text) }
        await loadThread(ref)
        await loadMessages(ref.conversationId)
        await refreshConversations()
    }

    func react(to message: MessageView, in conversationId: String, emoji: String?) async {
        let messageId = message.id
        await perform { try $0.react(conversationId: conversationId, messageId: messageId, emoji: emoji) }
        await reload(conversationId)
    }

    func edit(_ message: MessageView, in conversationId: String, text: String) async {
        let messageId = message.id
        await perform { try $0.editMessage(conversationId: conversationId, messageId: messageId, text: text) }
        await reload(conversationId)
    }

    func delete(_ message: MessageView, in conversationId: String) async {
        let messageId = message.id
        await perform { try $0.deleteMessage(conversationId: conversationId, messageId: messageId) }
        await reload(conversationId)
        await refreshConversations()
    }

    /// After a change to a message: the chat, and the thread open over it.
    private func reload(_ conversationId: String) async {
        await loadMessages(conversationId)
        if let ref = openThread, ref.conversationId == conversationId {
            await loadThread(ref)
        }
    }

    // MARK: - Files

    /// Sends prepared files, with `caption` as their text, maybe as a reply
    /// or (with `inThread`) in the thread under `replyTo`. The message shows
    /// at once; the files go up in the background. The core moves the files,
    /// and any it didn't take are deleted.
    func sendMedia(
        _ attachments: [NewAttachment],
        caption: String,
        to conversationId: String,
        replyTo: String?,
        inThread: Bool = false
    ) async {
        let sent = await perform {
            _ = try $0.sendMedia(
                conversationId: conversationId,
                caption: caption,
                attachments: attachments,
                replyTo: replyTo,
                inThread: inThread
            )
        }
        AppFiles.remove(attachments)
        await reload(conversationId)
        await refreshConversations()
        if sent { sendPendingMedia() }
    }

    /// Uploads files waiting to go and sends their messages. Network errors
    /// leave them for the next sync; the server refusing a file fails its
    /// message, which then shows as not sent.
    func sendPendingMedia() {
        guard let client else { return }
        guard !uploading else {
            uploadAgain = true
            return
        }
        uploading = true
        Task {
            #if os(iOS)
            // Long enough, usually, to finish if the app is sent away meanwhile.
            let background = BackgroundTime("Sending files")
            defer { background.end() }
            #endif
            repeat {
                uploadAgain = false
                do {
                    try await core.run { try client.sendPending() }
                } catch {
                    if !error.isNetworkError { errorMessage = error.userMessage }
                }
                if let id = selectedConversationId { await reload(id) }
                await refreshConversations()
            } while uploadAgain
            uploading = false
        }
    }

    /// Fetches a file someone sent. `first` puts it ahead of those waiting:
    /// someone tapped it.
    func download(_ ref: AttachmentRef, first: Bool = false) {
        if fetching.contains(ref) {
            if first, let index = fetchQueue.firstIndex(of: ref) {
                fetchQueue.remove(at: index)
                fetchQueue.insert(ref, at: 0)
            }
            return
        }
        fetching.insert(ref)
        if first {
            fetchQueue.insert(ref, at: 0)
        } else {
            fetchQueue.append(ref)
        }
        runFetches()
    }

    /// A few at a time: each holds a thread while it waits on the network.
    private func runFetches() {
        while fetchesRunning < 3, !fetchQueue.isEmpty, let client {
            let ref = fetchQueue.removeFirst()
            fetchesRunning += 1
            Task {
                do {
                    let path = try await core.run {
                        try client.downloadAttachment(
                            conversationId: ref.conversationId,
                            messageId: ref.messageId,
                            position: ref.position
                        )
                    }
                    updateAttachment(ref) {
                        $0.state = .ready
                        $0.path = path
                    }
                } catch {
                    if case .InvalidInput(let message) = error as? MessengerError {
                        // Gone from the server, damaged, or the message went.
                        updateAttachment(ref) {
                            $0.state = .failed
                            $0.error = message
                        }
                    } else if error.isNetworkError {
                        isOnline = false
                    }
                }
                fetching.remove(ref)
                fetchesRunning -= 1
                runFetches()
            }
        }
    }

    /// Photos and voice messages come down as soon as they're seen; videos
    /// and other files wait for a tap.
    private func fetchAutomatically(_ list: [MessageView], in conversationId: String) {
        for message in list where !message.deleted {
            for attachment in message.attachments
            where attachment.state == .remote && (attachment.kind == .photo || attachment.kind == .voice) {
                download(AttachmentRef(conversationId: conversationId, messageId: message.id, position: attachment.position))
            }
        }
    }

    /// New photos and voice messages in chats that aren't open, so they're
    /// there when the chat is.
    private func fetchNewMedia(in conversationIds: [String]) async {
        guard let client else { return }
        for id in conversationIds {
            if let recent = try? await core.run({ try client.messages(conversationId: id, limit: 30) }) {
                fetchAutomatically(recent, in: id)
            }
        }
    }

    /// Shows a file's new state where its message is on screen, without
    /// reading the whole chat again.
    private func updateAttachment(_ ref: AttachmentRef, _ change: (inout AttachmentView) -> Void) {
        func changed(_ list: [MessageView]) -> [MessageView]? {
            guard let m = list.firstIndex(where: { $0.id == ref.messageId }),
                  let a = list[m].attachments.firstIndex(where: { $0.position == ref.position })
            else { return nil }
            var list = list
            change(&list[m].attachments[a])
            return list
        }
        if let list = messages[ref.conversationId].flatMap(changed) {
            messages[ref.conversationId] = list
        }
        for (root, list) in threads {
            if let list = changed(list) { threads[root] = list }
        }
    }

    // MARK: - Conversations (these throw so sheets can show the error inline)

    func startDirectChat(with username: String) async throws {
        let client = try requireClient()
        let id = try await core.run { try client.startDirectChat(username: username) }
        await refreshConversations()
        selectedConversationId = id
    }

    func createGroup(title: String, usernames: [String], colour: String = "") async throws {
        let client = try requireClient()
        let id = try await core.run { try client.createGroup(title: title, usernames: usernames, colour: colour) }
        await refreshConversations()
        selectedConversationId = id
    }

    /// Renames a group; everyone in it sees the new name.
    func renameGroup(_ conversationId: String, to title: String) async throws {
        let client = try requireClient()
        try await core.run { try client.renameGroup(conversationId: conversationId, title: title) }
        await refreshConversations()
        await loadMessages(conversationId)
    }

    /// Sends a copy of a message to up to five chats.
    func forward(_ messageId: String, from conversationId: String, to chats: [String]) async throws {
        let client = try requireClient()
        _ = try await core.run { try client.forwardMessage(conversationId: conversationId, messageId: messageId, to: chats) }
        await refreshConversations()
        for chat in chats { await loadMessages(chat) }
    }

    /// The safety number was compared: it stops showing as changed.
    func markSafetyChecked(_ conversationId: String) async {
        guard let client else { return }
        _ = try? await core.run { try client.markSafetyChecked(conversationId: conversationId) }
        await refreshConversations()
    }

    func addMembers(_ usernames: [String], to conversationId: String) async throws {
        let client = try requireClient()
        try await core.run { try client.addMembers(conversationId: conversationId, usernames: usernames) }
        await refreshConversations()
        await loadMessages(conversationId)
    }

    func removeMember(_ username: String, from conversationId: String) async throws {
        let client = try requireClient()
        try await core.run { try client.removeMember(conversationId: conversationId, username: username) }
        await refreshConversations()
        await loadMessages(conversationId)
    }

    func members(of conversationId: String) async throws -> [MemberView] {
        let client = try requireClient()
        return try await core.run { try client.members(conversationId: conversationId) }
    }

    func safetyNumber(of conversationId: String) async throws -> SafetyNumber {
        let client = try requireClient()
        return try await core.run { try client.safetyNumber(conversationId: conversationId) }
    }

    /// Leaves a chat. The others see it once one of them is online; until
    /// then it shows as leaving.
    func leave(_ conversationId: String) async throws {
        let client = try requireClient()
        try await core.run { try client.leaveConversation(conversationId: conversationId) }
        await refreshConversations()
        await loadMessages(conversationId)
    }

    // MARK: - Location sharing

    func refreshLocations() async {
        guard let client else { return }
        if let list = try? await core.run({ try client.sharedLocations() }) {
            sharedLocations = list
        }
        if let shares = try? await core.run({ try client.locationShares() }) {
            locationShares = shares
            location.setSharing(!shares.isEmpty)
        }
    }

    func locationShare(in conversationId: String) -> LocationShare? {
        locationShares.first { $0.conversationId == conversationId }
    }

    /// Starts sharing our location in a chat, until `until` (nil: until we stop).
    func startSharingLocation(in conversationId: String, until: Date?) async throws {
        let client = try requireClient()
        let untilMs = until.map { Int64($0.timeIntervalSince1970 * 1000) }
        try await core.run { try client.startSharingLocation(conversationId: conversationId, untilMs: untilMs) }
        location.requestAccess()
        await refreshLocations()
        location.sendNow()
    }

    func stopSharingLocation(in conversationId: String) async throws {
        let client = try requireClient()
        try await core.run { try client.stopSharingLocation(conversationId: conversationId) }
        await refreshLocations()
    }

    private func sendLocation(_ fix: LocationFix) {
        guard let client else { return }
        Task { _ = try? await core.run { try client.updateLocation(fix: fix) } }
    }

    // MARK: - Helpers

    @discardableResult
    private func perform(_ action: @escaping @Sendable (MessengerClient) throws -> Void) async -> Bool {
        do {
            let client = try requireClient()
            try await core.run { try action(client) }
            return true
        } catch {
            errorMessage = error.userMessage
            return false
        }
    }

    private func whileBusy(_ work: () async throws -> Void) async {
        busy = true
        defer { busy = false }
        do {
            try await work()
        } catch {
            errorMessage = error.userMessage
        }
    }
}

/// Splits "alice, bob carol" into usernames.
func parseUsernames(_ text: String) -> [String] {
    text.split(whereSeparator: { $0 == "," || $0.isWhitespace })
        .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        .filter { !$0.isEmpty }
}

// MARK: - What this device does with each chat

extension AppModel {
    /// Unread chats for the app's badge: muted and archived ones don't count.
    static func badge(_ list: [ConversationSummary]) -> Int {
        list.filter { $0.mutedUntilMs == nil && !$0.archived }.reduce(0) { $0 + Int($1.unread) }
    }

    /// Runs one chat action, then shows the list as it is now.
    @discardableResult
    private func chatAction(_ action: @escaping @Sendable (MessengerClient) throws -> Void) async -> Bool {
        var ok = true
        do {
            let client = try requireClient()
            try await core.run { try action(client) }
        } catch {
            errorMessage = error.userMessage
            ok = false
        }
        await refreshConversations()
        return ok
    }

    func showToast(_ text: String) {
        toastTask?.cancel()
        toast = text
        toastTask = Task {
            try? await Task.sleep(for: .milliseconds(1700))
            guard !Task.isCancelled else { return }
            toast = nil
        }
    }

    func setPinned(_ id: String, _ pinned: Bool) async {
        if pinned && conversations.filter({ $0.pinned && !$0.archived }).count >= 5 {
            showToast("Five pins is the max. Unpin one first")
            return
        }
        if await chatAction({ try $0.setPinned(conversationId: id, pinned: pinned) }) {
            showToast(pinned ? "Pinned to the top" : "Unpinned")
        }
    }

    func setArchived(_ id: String, _ archived: Bool) async {
        if await chatAction({ try $0.setArchived(conversationId: id, archived: archived) }) {
            showToast(archived ? "Archived. It’s under the Archived filter" : "Back in your chats")
        }
    }

    /// Mutes for `duration` seconds, or for always with nil.
    func mute(_ id: String, for duration: TimeInterval?) async {
        let until: Int64 = duration.map { Int64((Date().timeIntervalSince1970 + $0) * 1000) } ?? mutedForever()
        if await chatAction({ try $0.muteConversation(conversationId: id, untilMs: until) }) {
            let label = switch duration {
            case nil: "Muted always"
            case 8 * 3600: "Muted for 8 hours"
            case 7 * 24 * 3600: "Muted for 1 week"
            default: "Muted"
            }
            showToast(label)
        }
    }

    func unmute(_ id: String) async {
        if await chatAction({ try $0.muteConversation(conversationId: id, untilMs: nil) }) {
            showToast("Unmuted")
        }
    }

    func setLocked(_ id: String, _ locked: Bool) async {
        await chatAction { try $0.setLocked(conversationId: id, locked: locked) }
    }

    func setRead(_ id: String, _ read: Bool) async {
        if read {
            if await chatAction({ try $0.markRead(conversationId: id) }) { showToast("Marked as read") }
            notifications.clear(conversationId: id)
        } else {
            if await chatAction({ try $0.markUnread(conversationId: id) }) { showToast("Marked as unread") }
        }
    }

    func setBlocked(_ username: String, _ blocked: Bool) async {
        if await chatAction({ try $0.setBlocked(username: username, blocked: blocked) }) {
            showToast(blocked ? "Blocked" : "Unblocked")
        }
    }

    func clearChat(_ id: String) async {
        await chatAction { try $0.clearConversation(conversationId: id) }
        await loadMessages(id)
    }

    func deleteChat(_ id: String) async {
        if selectedConversationId == id { selectedConversationId = nil }
        await chatAction { try $0.deleteConversation(conversationId: id) }
        messages[id] = nil
        notifications.clear(conversationId: id)
    }
}

// MARK: - Vanish mode

extension AppModel {
    func setVanishMode(_ id: String, _ on: Bool) async {
        do {
            let client = try requireClient()
            try await core.run { try client.setVanishMode(conversationId: id, on: on) }
        } catch {
            errorMessage = error.userMessage
        }
        await refreshConversations()
        await loadMessages(id)
    }

    /// The chat left the screen: seen vanish-mode messages go.
    func closeChat(_ id: String) async {
        guard let client else { return }
        _ = try? await core.run { try client.closeConversation(conversationId: id) }
        await loadMessages(id)
        await refreshConversations()
    }

    /// The user took a screenshot with this chat open.
    func reportScreenshot(_ id: String) async {
        guard let client else { return }
        _ = try? await core.run { try client.reportScreenshot(conversationId: id) }
        await loadMessages(id)
    }
}
