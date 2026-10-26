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

    @ObservationIgnored private var client: MessengerClient?
    @ObservationIgnored private var openedURL: String?
    @ObservationIgnored private var syncTask: Task<Void, Never>?
    private let core = CoreQueue()

    init() {
        serverURL = UserDefaults.standard.string(forKey: Self.serverURLKey) ?? Self.defaultServerURL
    }

    // MARK: - Startup and account

    func start() async {
        guard phase == .starting else { return }
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
        Task { await refreshConversations() }
        startSyncLoop()
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
            self.enterApp()
        }
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
                    guard let self else { return }
                    self.isOnline = true
                    delay = 1
                    await self.apply(report)
                } catch {
                    guard let self else { return }
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
        await refreshConversations()
        // Drop cached messages of conversations that changed in the background.
        for id in report.updatedConversations where id != selectedConversationId {
            messages[id] = nil
        }
        if let id = selectedConversationId {
            await loadMessages(id)
        }
    }

    func refreshConversations() async {
        guard let client else { return }
        if let list = try? await core.run({ try client.conversations() }) {
            conversations = list
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

    func markRead(_ conversationId: String) async {
        guard let client else { return }
        _ = try? await core.run { try client.markRead(conversationId: conversationId) }
        if let index = conversations.firstIndex(where: { $0.id == conversationId }) {
            conversations[index].unread = 0
        }
    }

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
