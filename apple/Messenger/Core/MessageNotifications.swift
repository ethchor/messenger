import Foundation
import Observation
import UserNotifications
#if os(iOS)
import BackgroundTasks
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Notifications for new messages.
///
/// The app always writes notifications itself, from what it decrypted: after
/// each sync it posts one for every new message that isn't on screen. A push
/// only wakes the app; it comes from Apple's push service when the server has
/// an APNs key and this build is signed for push, which takes a paid Apple
/// developer account. Without push, the Mac shows notifications while
/// Messenger runs (also with its window closed), and the iPhone while
/// Messenger is open, sharing location, or checking in the background.
@MainActor
@Observable
final class MessageNotifications: NSObject {
    /// Whether pushes can wake this device.
    enum Push: Equatable {
        /// Not looked at yet, or notifications are off.
        case unknown
        /// The server has no APNs key, so it can't wake Apple devices.
        case serverCantPush
        /// This build isn't signed for push (a free Apple developer account).
        case notSigned
        case registering
        case on
        case failed(String)
    }

    static let shared = MessageNotifications()

    private(set) var enabled: Bool
    /// Names and message text in notifications, or just "New message".
    private(set) var previews: Bool
    private(set) var authorization: UNAuthorizationStatus = .notDetermined
    private(set) var push: Push = .unknown

    /// Opens the chat of a tapped notification.
    @ObservationIgnored var onOpen: ((String) -> Void)?
    /// A push arrived while the app runs: time to sync.
    @ObservationIgnored var onPush: (() -> Void)?
    /// Apple handed out a device token, for the server.
    @ObservationIgnored var onToken: ((Data) -> Void)?

    @ObservationIgnored private let center = UNUserNotificationCenter.current()
    private static let enabledKey = "notifications.enabled"
    private static let previewsKey = "notifications.previews"

    private override init() {
        let defaults = UserDefaults.standard
        enabled = defaults.object(forKey: Self.enabledKey) as? Bool ?? true
        previews = defaults.object(forKey: Self.previewsKey) as? Bool ?? true
        super.init()
        // Before launch finishes, so tapping a notification that launched the app is seen.
        center.delegate = self
    }

    // MARK: - Settings

    func setEnabled(_ on: Bool) {
        enabled = on
        UserDefaults.standard.set(on, forKey: Self.enabledKey)
        if !on {
            push = .unknown
            clearAll()
        }
    }

    func setPreviews(_ on: Bool) {
        previews = on
        UserDefaults.standard.set(on, forKey: Self.previewsKey)
    }

    /// The account left this device: nothing on screen, settings back to the start.
    func forget() {
        push = .unknown
        clearAll()
        UserDefaults.standard.removeObject(forKey: Self.enabledKey)
        UserDefaults.standard.removeObject(forKey: Self.previewsKey)
        enabled = true
        previews = true
    }

    // MARK: - Permission and push

    func refreshAuthorization() async {
        authorization = await center.notificationSettings().authorizationStatus
    }

    /// Asks once whether the app may show notifications.
    func requestAuthorizationIfNeeded() async {
        await refreshAuthorization()
        guard enabled, authorization == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        await refreshAuthorization()
    }

    /// Registers with Apple's push service, when the server can push (it has
    /// an APNs key). Apple answers through the app delegate.
    func registerForPushes(serverCanPush: Bool) {
        guard enabled else {
            push = .unknown
            return
        }
        guard serverCanPush else {
            push = .serverCantPush
            return
        }
        if push != .on { push = .registering }
        #if os(iOS)
        UIApplication.shared.registerForRemoteNotifications()
        #elseif os(macOS)
        NSApplication.shared.registerForRemoteNotifications()
        #endif
    }

    func didRegister(token: Data) {
        guard enabled else { return }
        onToken?(token)
    }

    func didFailToRegister(_ error: Error) {
        let text = error.localizedDescription
        // Builds without the push entitlement (a free account) end up here.
        push = text.contains("aps-environment") ? .notSigned : .failed(text)
    }

    /// The server took the token, or didn't.
    func tokenSent(_ error: String?) {
        push = error.map { .failed($0) } ?? .on
    }

    // MARK: - Showing and clearing

    /// Posts one notification per message, grouped by chat, leaving out the
    /// chats in `skipping` (on screen already).
    func show(_ notes: [MessageNotification], skipping: (String) -> Bool) {
        guard enabled else { return }
        let shown = notes.filter { !skipping($0.conversationId) }
        guard !shown.isEmpty else { return }
        removeDeliveredPushAlerts()
        for note in shown {
            let content = UNMutableNotificationContent()
            if previews {
                content.title = note.title
                if note.isGroup { content.subtitle = note.sender }
                content.body = note.text
            } else {
                // Nothing about who or what; the chat shows it once opened.
                content.title = "Messenger"
                content.body = "New message"
            }
            content.sound = .default
            content.threadIdentifier = note.conversationId
            content.userInfo = ["conversationId": note.conversationId]
            center.add(UNNotificationRequest(identifier: note.messageId, content: content, trigger: nil))
        }
    }

    /// The chat was opened: its notifications have done their job.
    func clear(conversationId: String) {
        Task {
            let delivered = await center.deliveredNotifications()
            let ids = delivered.filter { $0.request.content.threadIdentifier == conversationId }.map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }

    func clearAll() {
        center.removeAllDeliveredNotifications()
        setBadge(0)
    }

    /// Unread messages on the app icon.
    func setBadge(_ count: Int) {
        guard enabled || count == 0 else { return }
        center.setBadgeCount(count) { _ in }
    }

    /// "New message" alerts from pushes, once the app has shown the messages themselves.
    private func removeDeliveredPushAlerts() {
        Task {
            let delivered = await center.deliveredNotifications()
            let ids = delivered.filter { $0.request.trigger is UNPushNotificationTrigger }.map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
}

extension MessageNotifications: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        guard notification.request.trigger is UNPushNotificationTrigger else {
            return [.banner, .list, .sound]
        }
        // A wake-up while the app runs: it fetches and shows the messages itself.
        await MainActor.run { self.onPush?() }
        return []
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let conversationId = response.notification.request.content.userInfo["conversationId"] as? String
        await MainActor.run {
            if let conversationId {
                self.onOpen?(conversationId)
            } else {
                self.onPush?()
            }
        }
    }
}

/// Hands Apple's push registration results to `MessageNotifications`.
#if os(iOS)
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        _ = MessageNotifications.shared
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MessageNotifications.shared.didRegister(token: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        MessageNotifications.shared.didFailToRegister(error)
    }
}

/// iOS wakes the app now and then to check for messages, as it sees fit: the
/// way to get notifications on an iPhone without push.
enum BackgroundRefresh {
    static let identifier = "com.qwantarc.messenger.refresh"

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
#elseif os(macOS)
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ notification: Notification) {
        _ = MessageNotifications.shared
    }

    func application(_ application: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MessageNotifications.shared.didRegister(token: deviceToken)
    }

    func application(_ application: NSApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        MessageNotifications.shared.didFailToRegister(error)
    }
}
#endif
