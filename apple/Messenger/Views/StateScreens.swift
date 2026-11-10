import SwiftUI

// States, as designed (R-More "States"): the honest screen before the
// system asks about notifications, and the Map when nobody is sharing.

/// Before the system prompt: what turning notifications on means.
struct NotificationPrompt: View {
    @Environment(AppModel.self) private var model
    let done: () -> Void
    @State private var working = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ZStack(alignment: .topLeading) {
                HStack(spacing: 10) {
                    Image(systemName: "bubble.left.fill")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Palette.marigold)
                        .frame(width: 36, height: 36)
                        .background(Color(rgb: 0x111110), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                    VStack(alignment: .leading, spacing: 1) {
                        HStack {
                            Text("Mira").fontWeight(.bold)
                            Spacer()
                            Text("now").foregroundStyle(Palette.secondary)
                        }
                        Text("Leaving now, see you at arrivals ✈️")
                    }
                    .font(.system(size: 13.5))
                }
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .shadow(color: .black.opacity(0.12), radius: 15, y: 10)
                .padding(.horizontal, 14)
                .padding(.top, 40)
                ShapeSticker(kind: .bolt, size: 46, delay: 0.2).offset(x: 260, y: -6)
                ShapeSticker(kind: .heart, size: 40, delay: 0.32, phase: -1).offset(x: 10, y: 120)
            }
            .frame(height: 210, alignment: .top)
            .accessibilityHidden(true)
            Text("Want a nudge when someone messages?")
                .font(.display(34))
                .displayTracking(34)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 10) {
                    Text("✓").fontWeight(.bold).foregroundStyle(Kit.okGreen)
                    Text("The push itself carries nothing readable. This device fetches and decrypts the message, then shows it.")
                }
                HStack(alignment: .top, spacing: 10) {
                    Text("!").fontWeight(.bold).foregroundStyle(Color(rgb: 0xB07A00))
                    Text("To wake the right devices, our server learns who you chat with. With notifications off, it doesn't. You can change this any time.")
                        .foregroundStyle(Palette.secondary)
                }
            }
            .font(.system(size: 15))
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            VStack(spacing: 8) {
                Button {
                    Task {
                        working = true
                        await model.setNotificationsEnabled(true)
                        working = false
                        done()
                    }
                } label: {
                    Group {
                        if working { ProgressView() } else { Text("Turn on notifications") }
                    }
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Palette.ink)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Palette.marigold, in: Capsule())
                }
                .buttonStyle(PressScale())
                Button("Not now") {
                    model.showToast("You can turn them on in Settings › Notifications")
                    done()
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Palette.text)
                .buttonStyle(.plain)
                .frame(minHeight: 48)
            }
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 24)
        .padding(.top, 40)
        .padding(.bottom, 24)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background { HomeGround().ignoresSafeArea() }
    }
}

/// The Map tab when nobody shares with you: three shapes, a line about it,
/// and sharing your own.
struct EmptyMapCard: View {
    @Environment(AppModel.self) private var model
    @State private var picking = false
    @State private var sharingWith: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            FloatingPins()
                .frame(maxWidth: .infinity, minHeight: 96, maxHeight: 96)
            .accessibilityHidden(true)
            Text("Nobody's sharing right now").font(.display(23))
            Text("When a friend shares their live location with you, they show up here. Only you see this map; it's built on this device.")
                .font(.system(size: 14.5))
                .foregroundStyle(Palette.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button { picking = true } label: {
                Text("Share yours with someone")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Palette.cream)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(Palette.ink, in: Capsule())
            }
            .buttonStyle(PressScale())
            .padding(.top, 4)
            .disabled(model.conversations.filter(\.active).isEmpty)
        }
        .foregroundStyle(Palette.text)
        .padding(20)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: .black.opacity(0.14), radius: 20, y: 14)
        .sheet(isPresented: $picking) {
            ChatPicker(title: "Share with…") { id in
                picking = false
                Task { try? await Task.sleep(for: .milliseconds(350)); sharingWith = id }
            }
        }
        .sheet(item: Binding(get: { sharingWith.map(IdBox.init) }, set: { sharingWith = $0?.id })) { box in
            ScrollView {
                LiveLocationSetup(conversationId: box.id) { sharingWith = nil }
                    .padding(.top, 24)
            }
            .background(Palette.home.ignoresSafeArea())
            #if os(iOS)
            .presentationDetents([.large])
            #endif
        }
    }

    private struct IdBox: Identifiable { let id: String }
}

/// Picks one chat.
struct ChatPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let title: String
    let pick: (String) -> Void
    @State private var query = ""

    private var chats: [ConversationSummary] {
        let all = model.conversations.filter { $0.active && !$0.blocked }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? all : all.filter { $0.title.lowercased().contains(q) }
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text(title).font(.display(22))
                Spacer()
                Button("Cancel") { dismiss() }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.secondary)
                    .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            ComposeSearchField(text: $query, placeholder: "Search chats")
            ScrollView {
                KitCard(radius: 24) {
                    ForEach(Array(chats.enumerated()), id: \.element.id) { i, chat in
                        if i > 0 { KitSeparator() }
                        Button { pick(chat.id) } label: {
                            HStack(spacing: 14) {
                                Avatar(title: chat.title, isGroup: chat.kind == .group, size: 40, stamp: true)
                                Text(chat.title).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.secondary)
                            }
                            .foregroundStyle(Palette.text)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.top, 20)
        .background(Palette.home.ignoresSafeArea())
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationCornerRadius(30)
        #else
        .frame(minWidth: 400, minHeight: 480)
        #endif
    }
}
