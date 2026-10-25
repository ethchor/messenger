import Foundation
import Observation

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
    static let defaultServerURL = "http://localhost:8080"
    private static let serverURLKey = "serverURL"

    var phase: Phase = .starting
    var account: AccountInfo?
    var conversations: [ConversationSummary] = []
    var selectedConversationId: String?
    /// Messages of the open conversation (and recently opened ones), oldest first.
    var messages: [String: [MessageView]] = [:]
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
    /// A chat to open once the app is ready (its notification was tapped while starting).
    @ObservationIgnored private var pendingOpen: String?
    /// Called once the account has left this device, to reset what the views keep.
    @ObservationIgnored var onSignedOut: (() -> Void)?
    private let core = CoreQueue()

    init() {
        serverURL = UserDefaults.standard.string(forKey: Self.serverURLKey) ?? Self.defaultServerURL
        location.onFix = { [weak self] fix in self?.sendLocation(fix) }
        notifications.onOpen = { [weak self] id in self?.openFromNotification(id) }
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
        do {
            try await open(serverURL: serverURL)
            try await loadAccount()
        } catch {
            phase = .failed(error.userMessage)
        }
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
            if let id = pendingOpen {
                pendingOpen = nil
                showConversation(id)
            }
            await loadSettings()
            await refreshLocations()
            await refreshProfiles()
            await setUpNotifications()
        }
        startSyncLoop()
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
        sharedLocations = []
        locationShares = []
        account = nil
        conversations = []
        messages = [:]
        typing = [:]
        selectedConversationId = nil
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
        for id in report.updatedConversations where id != selectedConversationId {
            messages[id] = nil
        }
        if let id = selectedConversationId {
            await loadMessages(id)
        }
        await showNotifications()
    }

    func refreshConversations() async {
        guard let client else { return }
        if let list = try? await core.run({ try client.conversations() }) {
            conversations = list
            notifications.setBadge(list.reduce(0) { $0 + Int($1.unread) })
        }
    }

    func loadMessages(_ conversationId: String) async {
        guard let client else { return }
        do {
            messages[conversationId] = try await core.run { try client.messages(conversationId: conversationId, limit: 500) }
        } catch {
            errorMessage = error.userMessage
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
        notifications.setBadge(conversations.reduce(0) { $0 + Int($1.unread) })
    }

    // MARK: - Profile

    func refreshProfiles() async {
        guard let client else { return }
        if let list = try? await core.run({ try client.profiles() }) {
            profiles = Dictionary(list.map { ($0.username, $0) }, uniquingKeysWith: { _, last in last })
        }
    }

    /// Our avatar and ink, as others see them.
    var myProfile: PersonProfile? {
        account.flatMap { profiles[$0.username] }
    }

    /// Sets our avatar (nil: our initial) and ink, and shares them with every chat.
    func setProfile(avatar: Artwork?, ink: Ink) async {
        let avatarName = avatar?.avatarName ?? ""
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
        // The open chat shows its messages, and the list shows them all.
        let open = selectedConversationId
        notifications.show(notes) { open == nil || $0 == open }
        #endif
    }

    /// A message notification was tapped.
    func openFromNotification(_ conversationId: String) {
        guard phase == .ready else {
            pendingOpen = conversationId
            return
        }
        Task {
            // The push may have brought a chat this screen hasn't seen yet.
            await refreshConversations()
            showConversation(conversationId)
        }
    }

    private func showConversation(_ id: String) {
        guard conversations.contains(where: { $0.id == id }) else { return }
        selectedConversationId = id
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

    func react(to message: MessageView, in conversationId: String, emoji: String?) async {
        let messageId = message.id
        await perform { try $0.react(conversationId: conversationId, messageId: messageId, emoji: emoji) }
        await loadMessages(conversationId)
    }

    func edit(_ message: MessageView, in conversationId: String, text: String) async {
        let messageId = message.id
        await perform { try $0.editMessage(conversationId: conversationId, messageId: messageId, text: text) }
        await loadMessages(conversationId)
    }

    func delete(_ message: MessageView, in conversationId: String) async {
        let messageId = message.id
        await perform { try $0.deleteMessage(conversationId: conversationId, messageId: messageId) }
        await loadMessages(conversationId)
        await refreshConversations()
    }

    // MARK: - Conversations (these throw so sheets can show the error inline)

    func startDirectChat(with username: String) async throws {
        let client = try requireClient()
        let id = try await core.run { try client.startDirectChat(username: username) }
        await refreshConversations()
        selectedConversationId = id
    }

    func createGroup(title: String, usernames: [String]) async throws {
        let client = try requireClient()
        let id = try await core.run { try client.createGroup(title: title, usernames: usernames) }
        await refreshConversations()
        selectedConversationId = id
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
