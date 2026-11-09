import SwiftUI

@main
struct MessengerApp: App {
    // Apple's push registration answers through the app delegate.
    #if os(iOS)
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #elseif os(macOS)
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #endif
    @State private var model = AppModel()
    @State private var lock = AppLock()
    @State private var appearance = Appearance()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .lockable()
                .environment(model)
                .environment(lock)
                .environment(appearance)
                .tint(appearance.ink.color)
                .toggleStyle(MarigoldToggleStyle())
                .preferredColorScheme(appearance.theme.colorScheme)
                .task {
                    model.onSignedOut = {
                        appearance.reset()
                        ChatListTip.reset()
                    }
                    await model.start()
                }
                .onAppear { lock.applyWindowSharing() }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        model.location.appWentToBackground(false)
                        lock.appCameBack()
                        Task {
                            await lock.unlock()
                            await model.syncNow()
                        }
                    case .background:
                        model.location.appWentToBackground(true)
                        lock.appWentAway()
                        #if os(iOS)
                        if model.notifications.enabled { BackgroundRefresh.schedule() }
                        #endif
                    case .inactive:
                        // On iPhone, .inactive is also Control Center or a
                        // system sheet; only the Mac counts it as leaving.
                        #if os(macOS)
                        lock.appWentAway()
                        #endif
                    @unknown default:
                        break
                    }
                }
        }
        #if os(iOS)
        .backgroundTask(.appRefresh(BackgroundRefresh.identifier)) {
            await model.backgroundRefresh()
        }
        #endif
        #if os(macOS)
        .defaultSize(width: 960, height: 680)
        .commands {
            // Settings live in the main window, not a window of their own.
            CommandGroup(replacing: .appSettings) {
                Button("Settings…") {
                    model.selectedConversationId = nil
                    model.showingSettings = true
                }
                .keyboardShortcut(",")
                .disabled(model.phase != .ready)
            }
            // Panic wipe on the Mac: one shortcut from anywhere in the app.
            CommandGroup(after: .appSettings) {
                Button("Erase This Device Now") {
                    Task {
                        await model.wipeThisDevice()
                        lock.reset()
                    }
                }
                .keyboardShortcut(.delete, modifiers: [.command, .option, .shift])
                .disabled(!lock.panicWipe || model.phase != .ready)
            }
        }
        #endif

    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(\.scenePhase) private var scenePhase
    /// The splash stays at least this long, then hands off.
    @State private var splashTimeUp = false
    @State private var splashGone = false

    var body: some View {
        ZStack {
            content
            // Confirmations: a dark pill above the tab bar.
            VStack {
                Spacer()
                if let toast = model.toast {
                    Text(toast)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Palette.home)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(Palette.text, in: Capsule())
                        .shadow(color: .black.opacity(0.2), radius: 12, y: 8)
                        .transition(.asymmetric(insertion: .offset(y: 16).combined(with: .opacity).combined(with: .scale(scale: 0.96)),
                                                removal: .opacity))
                        .accessibilityAddTraits(.updatesFrequently)
                        .onAppear { AccessibilityNotification.Announcement(toast).post() }
                        .id(toast)
                }
            }
            #if os(iOS)
            .padding(.bottom, 104)
            #else
            .padding(.bottom, 24)
            #endif
            .ignoresSafeArea(.keyboard)
            .allowsHitTesting(false)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: model.toast)
            .zIndex(2)
            if let state = lock.chatUnlock {
                ChatUnlockCard(state: state, method: lock.methodName, symbol: lock.methodSymbol)
                    .transition(.opacity)
                    .zIndex(3)
            }
            if !splashGone {
                SplashView(failure: failure, slow: splashTimeUp && model.phase == .starting) {
                    Task { await model.retryStart() }
                }
                .transition(.asymmetric(insertion: .identity, removal: .opacity.combined(with: .scale(scale: 1.12))))
                .zIndex(1)
            }
        }
        .task {
            try? await Task.sleep(for: .milliseconds(1250))
            splashTimeUp = true
        }
        .onChange(of: splashCanGo, initial: true) { _, go in
            if go { withAnimation(.timingCurve(0.5, 0, 0.75, 0, duration: 0.45)) { splashGone = true } }
        }
    }

    private var failure: String? {
        if case .failed(let message) = model.phase { return message }
        return nil
    }

    private var splashCanGo: Bool {
        guard splashTimeUp else { return false }
        switch model.phase {
        case .starting, .failed: return false
        default: return true
        }
    }

    private var content: some View {
        Group {
            switch model.phase {
            case .starting:
                Color.clear
            case .onboarding:
                OnboardingView()
            case .recoveryKey(let key):
                RecoveryKeyView(recoveryKey: key)
            case .ready:
                #if os(iOS)
                MainTabs()
                    .fullScreenCover(isPresented: notificationPromptShown) {
                        NotificationPrompt { notificationsPrompted = true }
                    }
                #else
                ConversationListView()
                #endif
            case .failed:
                Color.clear
            }
        }
        .alert("Something went wrong", isPresented: errorShown) {
            Button("OK") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
        // Read receipts only for what someone could have seen.
        .onChange(of: scenePhase == .active && !lock.locked, initial: true) { _, visible in
            model.setVisible(visible)
        }
    }

    /// Once, before the system asks: what notifications mean here.
    @AppStorage("notifications.prompted") private var notificationsPrompted = false

    private var notificationPromptShown: Binding<Bool> {
        Binding(
            get: {
                splashGone && !notificationsPrompted && !model.notifications.enabled
                    && model.notifications.authorization == .notDetermined
            },
            set: { if !$0 { notificationsPrompted = true } }
        )
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })
    }
}


/// Over the app while a locked chat checks it's you: a dark card with the
/// Face ID mark, then a green tick and "Unlocked".
struct ChatUnlockCard: View {
    let state: AppLock.ChatUnlock
    let method: String
    let symbol: String

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial).overlay(Color.black.opacity(0.35)).ignoresSafeArea()
            VStack(spacing: 12) {
                ZStack {
                    Image(systemName: symbol == "lock.open" ? "lock.fill" : symbol)
                        .font(.system(size: 52, weight: .regular))
                        .foregroundStyle(.white)
                        .opacity(state == .checking ? 1 : 0)
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 52, weight: .regular))
                        .foregroundStyle(Color(rgb: 0x5DD08A))
                        .opacity(state == .unlocked ? 1 : 0)
                        .scaleEffect(state == .unlocked ? 1 : 0.6)
                }
                Text(state == .unlocked ? "Unlocked" : method)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 168, height: 168)
            .background(Color(red: 28 / 255, green: 28 / 255, blue: 30 / 255).opacity(0.86), in: RoundedRectangle(cornerRadius: 34, style: .continuous))
            .animation(.spring(response: 0.34, dampingFraction: 0.6), value: state)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(state == .unlocked ? "Unlocked" : "Checking \(method)")
    }
}
