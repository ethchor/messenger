import Foundation

/// Runs blocking calls into the Rust core off the main thread.
///
/// Every core call blocks (the long-poll in `sync` for up to 25 s), so calls
/// go to a GCD queue instead of Swift's cooperative thread pool, which must
/// never be blocked.
final class CoreQueue: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.qwantarc.messenger.core", qos: .userInitiated, attributes: .concurrent)
    private let pollQueue = DispatchQueue(label: "com.qwantarc.messenger.sync", qos: .utility)

    func run<T>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result { try body() }) }
        }
    }

    /// For the long-poll loop, so it never takes a worker from `run`.
    func poll<T>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            pollQueue.async { continuation.resume(with: Result { try body() }) }
        }
    }
}

enum AppError: LocalizedError {
    case notReady

    var errorDescription: String? {
        switch self {
        case .notReady: return "The app is still starting."
        }
    }
}

extension Error {
    /// The message the Rust core wrote for this error, fit to show to people.
    var userMessage: String {
        guard let error = self as? MessengerError else { return localizedDescription }
        switch error {
        case .NotRegistered(let message), .AlreadyRegistered(let message), .UsernameTaken(let message),
             .UserNotFound(let message), .NoKeyPackages(let message), .InvalidInput(let message),
             .ConversationNotFound(let message), .NotAMember(let message), .BadRecoveryKey(let message),
             .Network(let message), .Server(let message), .Storage(let message), .Encryption(let message),
             .Internal(let message):
            return message
        }
    }

    var isNetworkError: Bool {
        if case .Network = self as? MessengerError { return true }
        return false
    }
}

enum AppFiles {
    /// Where the core keeps its database. Excluded from device backups: until
    /// keys are wrapped by the Secure Enclave, the database holds them.
    static func dataDirectory() throws -> URL {
        var url = try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Messenger", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        return url
    }
}
