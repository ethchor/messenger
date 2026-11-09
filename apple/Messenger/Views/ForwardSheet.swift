import SwiftUI

/// "Forward to…", as designed: the message quoted, search, up to five chats
/// ticked, and one big button. Each chat gets its own encrypted copy,
/// marked "Forwarded".
struct ForwardSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let conversationId: String
    let message: MessageView
    @State private var picked: [String] = []
    @State private var query = ""
    @State private var working = false
    @State private var error: String?

    private static let most = 5

    private var chats: [ConversationSummary] {
        let all = model.conversations.filter { $0.active && !$0.blocked && $0.id != conversationId && !$0.vanishMode }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? all : all.filter { $0.title.lowercased().contains(q) }
    }

    private var quote: String {
        if !message.text.isEmpty { return message.text }
        let photos = message.attachments.filter { $0.kind == .photo }.count
        let videos = message.attachments.filter { $0.kind == .video }.count
        if photos + videos > 1 { return "Album · \(photos + videos) items" }
        if videos == 1 { return "Video" }
        if photos == 1 { return "Photo" }
        return message.attachments.first?.fileName ?? "Message"
    }

    private var label: String {
        switch picked.count {
        case 0: "Pick a chat"
        case 1: "Send to \(model.conversations.first { $0.id == picked[0] }?.title ?? "1 chat")"
        default: "Send to \(picked.count) chats"
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text("Forward to…").font(.display(22))
                Spacer()
                Button("Cancel") { dismiss() }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.secondary)
                    .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2).fill(Palette.tilePink).frame(width: 4)
                Text(quote)
                    .font(.system(size: 14))
                    .lineSpacing(1)
                    .foregroundStyle(Palette.secondary)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Kit.cardLine))
            .padding(.horizontal, 16)
            ComposeSearchField(text: $query, placeholder: "Search chats")
            ScrollView {
                KitCard(radius: 24) {
                    ForEach(Array(chats.enumerated()), id: \.element.id) { i, chat in
                        if i > 0 { KitSeparator() }
                        let on = picked.contains(chat.id)
                        Button { toggle(chat.id) } label: {
                            HStack(spacing: 14) {
                                Avatar(title: chat.title, isGroup: chat.kind == .group, size: 40, stamp: true)
                                Text(chat.title)
                                    .font(.system(size: 16, weight: .semibold))
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                PickTick(on: on)
                            }
                            .foregroundStyle(Palette.text)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
            }
            .frame(maxHeight: 300)
            VStack(spacing: 8) {
                Button { Task { await send() } } label: {
                    Group {
                        if working { ProgressView() } else { Text(label) }
                    }
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(picked.isEmpty ? Palette.secondary : Palette.ink)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(picked.isEmpty ? Palette.line : Palette.marigold, in: Capsule())
                }
                .buttonStyle(PressScale())
                .disabled(picked.isEmpty || working)
                Text(error ?? "Up to 5 chats. Each gets its own encrypted copy, marked “Forwarded”.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(error == nil ? Palette.secondary : Palette.danger)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 20)
        .padding(.bottom, 20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.home.ignoresSafeArea())
        #if os(iOS)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        #else
        .frame(minWidth: 420, minHeight: 560)
        #endif
    }

    private func toggle(_ id: String) {
        if let i = picked.firstIndex(of: id) {
            picked.remove(at: i)
        } else if picked.count >= Self.most {
            model.showToast("Five chats at a time")
        } else {
            picked.append(id)
        }
    }

    private func send() async {
        working = true
        defer { working = false }
        do {
            let names = picked.compactMap { id in model.conversations.first { $0.id == id }?.title }
            try await model.forward(message.id, from: conversationId, to: picked)
            model.showToast("Forwarded to " + names.joined(separator: ", "))
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}
