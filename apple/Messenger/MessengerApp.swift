import SwiftUI

@main
struct MessengerApp: App {
    @State private var model = AppModel()
    @State private var lock = AppLock()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .lockable()
                .environment(model)
                .environment(lock)
                .task { await model.start() }
                .onAppear { lock.applyWindowSharing() }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        lock.appCameBack()
                        Task {
                            await lock.unlock()
                            await model.syncNow()
                        }
                    case .background:
                        lock.appWentAway()
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
        #if os(macOS)
        .defaultSize(width: 960, height: 680)
        #endif

        #if os(macOS)
        Settings {
            NavigationStack {
                SettingsView()
            }
            .lockable()
            .environment(model)
            .environment(lock)
            .frame(width: 520, height: 680)
        }
        #endif
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            switch model.phase {
            case .starting:
                ProgressView("Opening…")
            case .onboarding:
                OnboardingView()
            case .recoveryKey(let key):
                RecoveryKeyView(recoveryKey: key)
            case .ready:
                ConversationListView()
            case .failed(let message):
                ContentUnavailableView("Couldn't open your messages", systemImage: "exclamationmark.triangle", description: Text(message))
            }
        }
        .alert("Something went wrong", isPresented: errorShown) {
            Button("OK") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
    }

    private var errorShown: Binding<Bool> {
        Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })
    }
}
