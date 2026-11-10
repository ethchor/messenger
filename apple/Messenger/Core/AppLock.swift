import LocalAuthentication
import Observation
import SwiftUI
#if os(macOS)
import AppKit
#endif

/// App lock and screen privacy. These are settings of this device, kept in
/// UserDefaults: they protect the device, not the account.
@MainActor
@Observable
final class AppLock {
    enum Timeout: Int, CaseIterable, Identifiable {
        case immediately = 0
        case oneMinute = 60
        case fiveMinutes = 300
        case fifteenMinutes = 900
        case oneHour = 3600

        var id: Int { rawValue }

        var label: String {
            switch self {
            case .immediately: return "Immediately"
            case .oneMinute: return "After 1 minute"
            case .fiveMinutes: return "After 5 minutes"
            case .fifteenMinutes: return "After 15 minutes"
            case .oneHour: return "After 1 hour"
            }
        }
    }

    private enum Key {
        static let enabled = "appLock.enabled"
        static let timeout = "appLock.timeout"
        static let hideContent = "privacy.hideInAppSwitcher"
        static let blockCapture = "privacy.blockScreenCapture"
        static let panicWipe = "privacy.panicWipe"
    }

    private(set) var enabled: Bool
    private(set) var timeout: Timeout
    /// iPhone: cover the app in the app switcher.
    private(set) var hideContent: Bool
    /// Mac: keep windows out of screenshots, recordings and screen sharing.
    private(set) var blockScreenCapture: Bool
    /// Triple-tap "Chats" (⌥⇧⌘⌫ on the Mac) erases this device at once.
    private(set) var panicWipe: Bool
    private(set) var locked: Bool
    private(set) var authenticating = false
    var lastError: String?

    @ObservationIgnored private var leftAt: Date?
    @ObservationIgnored private let defaults = UserDefaults.standard

    init() {
        let defaults = UserDefaults.standard
        enabled = defaults.bool(forKey: Key.enabled)
        #if os(macOS)
        let defaultTimeout = Timeout.fiveMinutes
        #else
        let defaultTimeout = Timeout.immediately
        #endif
        timeout = (defaults.object(forKey: Key.timeout) as? Int).flatMap(Timeout.init(rawValue:)) ?? defaultTimeout
        hideContent = defaults.object(forKey: Key.hideContent) as? Bool ?? true
        blockScreenCapture = defaults.bool(forKey: Key.blockCapture)
        panicWipe = defaults.object(forKey: Key.panicWipe) as? Bool ?? true
        locked = defaults.bool(forKey: Key.enabled)
        #if os(macOS)
        // New windows (Settings, sheets) pick up the screen capture setting.
        NotificationCenter.default.addObserver(forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main) {
            [weak self] _ in
            MainActor.assumeIsolated { self?.applyWindowSharing() }
        }
        #endif
    }

    // MARK: - Settings

    /// Turning the lock on asks to authenticate first, so nobody locks
    /// themselves out with a method they can't use.
    func setEnabled(_ on: Bool) async {
        if on {
            guard await authenticate(reason: "Turn on app lock") else { return }
        }
        enabled = on
        defaults.set(on, forKey: Key.enabled)
        if !on { locked = false }
    }

    func setTimeout(_ value: Timeout) {
        timeout = value
        defaults.set(value.rawValue, forKey: Key.timeout)
    }

    func setHideContent(_ on: Bool) {
        hideContent = on
        defaults.set(on, forKey: Key.hideContent)
    }

    func setBlockScreenCapture(_ on: Bool) {
        blockScreenCapture = on
        defaults.set(on, forKey: Key.blockCapture)
        applyWindowSharing()
    }

    func setPanicWipe(_ on: Bool) {
        panicWipe = on
        defaults.set(on, forKey: Key.panicWipe)
    }

    /// Back to defaults, for a device whose account was removed.
    func reset() {
        for key in [Key.enabled, Key.timeout, Key.hideContent, Key.blockCapture, Key.panicWipe] {
            defaults.removeObject(forKey: key)
        }
        enabled = false
        locked = false
        blockScreenCapture = false
        hideContent = true
        panicWipe = true
        applyWindowSharing()
    }

    // MARK: - Locking

    /// Whether this device can check who's there (biometrics or passcode).
    var isAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// The device has no passcode (any more), as opposed to Face ID being busy.
    private var passcodeRemoved: Bool {
        var error: NSError?
        if LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) { return false }
        return error?.domain == LAErrorDomain && error?.code == LAError.Code.passcodeNotSet.rawValue
    }

    /// "Face ID", "Touch ID", "Optic ID" or the device passcode.
    var methodName: String {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return "passcode" }
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        default:
            #if os(macOS)
            return "your password"
            #else
            return "passcode"
            #endif
        }
    }

    var methodSymbol: String {
        let context = LAContext()
        // biometryType is only filled in after canEvaluatePolicy.
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return "lock.open" }
        switch context.biometryType {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        case .opticID: return "opticid"
        default: return "lock.open"
        }
    }

    func unlock() async {
        guard locked, !authenticating else { return }
        if passcodeRemoved {
            // Taking the passcode off takes the passcode, so the owner did
            // it. Turn the lock off rather than lock them out for good.
            enabled = false
            defaults.set(false, forKey: Key.enabled)
            locked = false
            leftAt = nil
            return
        }
        if await authenticate(reason: "Unlock Messenger") {
            locked = false
            leftAt = nil
        }
    }

    enum Attempt { case ok, failed, cancelled }

    /// One go at unlocking, for the lock screen: Face ID (or Touch ID) on
    /// its own, or with `passcode` the device passcode too. Doesn't open the
    /// app; the screen plays its welcome first, then calls `finishUnlock`.
    func attemptUnlock(passcode: Bool) async -> Attempt {
        guard locked, !authenticating else { return .cancelled }
        if passcodeRemoved {
            enabled = false
            defaults.set(false, forKey: Key.enabled)
            return .ok
        }
        authenticating = true
        defer { authenticating = false }
        let context = LAContext()
        let policy: LAPolicy = passcode || !context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
            ? .deviceOwnerAuthentication : .deviceOwnerAuthenticationWithBiometrics
        if policy == .deviceOwnerAuthenticationWithBiometrics {
            // Our own "Use passcode" button is right there.
            context.localizedFallbackTitle = ""
        }
        do {
            let ok = try await context.evaluatePolicy(policy, localizedReason: "Unlock Messenger")
            lastError = nil
            return ok ? .ok : .failed
        } catch let error as LAError where error.code == .userCancel || error.code == .appCancel || error.code == .systemCancel || error.code == .userFallback {
            lastError = nil
            return .cancelled
        } catch {
            lastError = error.localizedDescription
            return .failed
        }
    }

    func finishUnlock() {
        locked = false
        leftAt = nil
    }

    /// The app went to the background (or, on the Mac, another app came to
    /// the front).
    func appWentAway() {
        guard !authenticating, leftAt == nil else { return }
        leftAt = Date()
    }

    func appCameBack() {
        guard !authenticating else { return }
        defer { leftAt = nil }
        guard enabled, let leftAt else { return }
        if Date().timeIntervalSince(leftAt) >= TimeInterval(timeout.rawValue) {
            locked = true
        }
    }

    /// Face ID, Touch ID or the passcode, to open a locked chat.
    func unlockChat(named name: String) async -> Bool {
        chatUnlock = .checking
        let ok = await authenticate(reason: "Open your chat with \(name)")
        guard ok else {
            chatUnlock = nil
            return false
        }
        // A beat of "Unlocked" before the chat slides in.
        chatUnlock = .unlocked
        try? await Task.sleep(for: .milliseconds(500))
        chatUnlock = nil
        return true
    }

    /// The card over the app while a locked chat checks it's you.
    enum ChatUnlock { case checking, unlocked }
    var chatUnlock: ChatUnlock?

    /// Face ID, Touch ID or the passcode, for something only the owner may
    /// do (approving a new device). True where the device has no passcode.
    func confirmOwner(reason: String) async -> Bool {
        guard isAvailable else { return true }
        return await authenticate(reason: reason)
    }

    private func authenticate(reason: String) async -> Bool {
        authenticating = true
        defer { authenticating = false }
        let context = LAContext()
        do {
            let ok = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            lastError = nil
            return ok
        } catch let error as LAError where error.code == .userCancel || error.code == .appCancel || error.code == .systemCancel {
            lastError = nil
            return false
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    // MARK: - Screen capture (Mac)

    func applyWindowSharing() {
        #if os(macOS)
        for window in NSApplication.shared.windows {
            window.sharingType = blockScreenCapture ? .none : .readOnly
        }
        #endif
    }
}

/// Covers the app in the app switcher, so recent messages don't show there.
/// What the app switcher shows when "Hide in app switcher" is on: the
/// splash's final frame, held still. Android shows the same.
struct PrivacyCover: View {
    var body: some View {
        SplashView()
            .environment(\.holdStill, true)
            .allowsHitTesting(false)
    }
}

extension View {
    /// Puts the lock screen (and, on iPhone, the app-switcher cover) over a
    /// window's content.
    func lockable() -> some View {
        modifier(LockableModifier())
    }
}

private struct LockableModifier: ViewModifier {
    @Environment(AppLock.self) private var lock
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.overlay {
            if lock.locked {
                LockView()
            } else if coverContent {
                PrivacyCover()
            }
        }
    }

    private var coverContent: Bool {
        #if os(iOS)
        return lock.hideContent && scenePhase != .active && !lock.authenticating
        #else
        return false
        #endif
    }
}
