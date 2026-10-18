import UserNotifications

/// Notification Service Extension (iPhone only).
///
/// The server's pushes carry no content. In Phase 1 this extension opens the
/// shared database (App Group, raw-key SQLCipher), fetches and decrypts the
/// new messages within the 30 s / 24 MB limits, and fills in the text. Until
/// then it shows a generic notification.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttempt: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        content.title = "Messenger"
        content.body = "New message"
        content.sound = .default
        bestAttempt = content
        contentHandler(content)
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler, let bestAttempt {
            contentHandler(bestAttempt)
        }
    }
}
