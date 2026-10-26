import SwiftUI

@main
struct MessengerApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await model.start() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        Task { await model.syncNow() }
                    }
                }
        }
        #if os(macOS)
        .defaultSize(width: 960, height: 680)
        #endif

        #if os(macOS)
        Settings {
            SettingsView()
                .environment(model)
                .frame(width: 460)
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
