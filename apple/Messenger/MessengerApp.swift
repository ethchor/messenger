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

    private var errorShown: Binding<Bool> {
        Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })
    }
}
