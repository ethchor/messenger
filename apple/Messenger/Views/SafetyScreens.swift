import SwiftUI

// Safety numbers, as designed (R-More "Safety numbers"): a dark card asking
// "Is it really them?", everyone with a 1:1 chat and the start of their
// number, "Changed" when it's new; then one person's number as a QR code and
// 60 digits, and scanning theirs to compare.

/// Everyone you have a 1:1 chat with, to compare safety numbers.
struct SafetyNumbersView: View {
    @Environment(AppModel.self) private var model
    @State private var numbers: [String: SafetyNumber] = [:]

    private var chats: [ConversationSummary] {
        model.conversations.filter { $0.kind == .direct && $0.active }
            .sorted { ($0.safetyChangedMs ?? 0, $1.title) > ($1.safetyChangedMs ?? 0, $0.title) }
    }

    private var summary: String {
        let changed = chats.filter { $0.safetyChangedMs != nil }.count
        let people = chats.count == 1 ? "1 person" : "\(chats.count) people"
        return changed == 0 ? people : "\(people) · \(changed) changed"
    }

    var body: some View {
        DesignPage("Safety numbers", back: "Back") {
            VStack(alignment: .leading, spacing: 8) {
                Text("Is it really them?")
                    .font(.display(27))
                    .padding(.trailing, 90)
                Text("Meet up and compare numbers. If they match, nobody is sitting in the middle of your chat.")
                    .font(.system(size: 14))
                    .foregroundStyle(Color(rgb: 0xC9C6BC))
                    .padding(.trailing, 84)
                    .fixedSize(horizontal: false, vertical: true)
                Label(summary, systemImage: "lock.fill")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Palette.ink)
                    .padding(.horizontal, 11)
                    .frame(height: 28)
                    .background(Palette.marigold, in: Capsule())
                    .padding(.top, 4)
            }
            .foregroundStyle(Palette.cream)
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.ink, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
            .overlay(alignment: .topTrailing) {
                ZStack(alignment: .topTrailing) {
                    ShapeSticker(kind: .seal, size: 64, delay: 0.05, tappable: true).padding(.top, 16).padding(.trailing, 16)
                    ShapeSticker(kind: .sparkle, size: 26, delay: 0.2, phase: -1).padding(.top, 82).padding(.trailing, 64)
                }
            }
            if chats.isEmpty {
                EmptyState(illustration: .safety, title: "No one-to-one chats yet", text: "Safety numbers are for chats with one other person. Start one, then compare numbers in person.")
                    .padding(.top, 20)
            } else {
                KitCard(radius: 24) {
                    ForEach(Array(chats.enumerated()), id: \.element.id) { i, chat in
                        if i > 0 { KitSeparator() }
                        NavigationLink {
                            SafetyNumberDetail(conversationId: chat.id, title: chat.title)
                        } label: {
                            row(chat)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 6)
            }
            KitFoot("“Changed” means their number is new since you last chatted, usually because they recovered their account or reset their identity. Check it with them before sharing anything sensitive.")
        }
        .task(id: chats.map(\.id)) {
            for chat in chats where numbers[chat.id] == nil {
                if let number = try? await model.safetyNumber(of: chat.id) { numbers[chat.id] = number }
            }
        }
    }

    private func row(_ chat: ConversationSummary) -> some View {
        HStack(spacing: 14) {
            Avatar(title: chat.title, size: 44, stamp: true)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(chat.title).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                    if let changed = chat.safetyChangedMs {
                        Text("Changed " + Date(timeIntervalSince1970: TimeInterval(changed) / 1000).formatted(.dateTime.day().month(.abbreviated)))
                            .font(.system(size: 11.5, weight: .bold))
                            .foregroundStyle(Palette.ink)
                            .padding(.horizontal, 8)
                            .frame(height: 22)
                            .background(Palette.marigold, in: Capsule())
                    }
                }
                Text(peek(chat.id))
                    .font(.system(size: 12.5, design: .monospaced))
                    .tracking(0.4)
                    .foregroundStyle(Palette.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Palette.secondary)
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(chat.safetyChangedMs != nil ? Color(light: 0xFFF7DA, dark: 0x2A2615) : .clear)
        .contentShape(Rectangle())
    }

    /// The first three groups of their number.
    private func peek(_ id: String) -> String {
        guard let number = numbers[id] else { return "…" }
        return number.digits.split(separator: " ").prefix(3).joined(separator: " ") + " …"
    }
}

/// One person's safety number: you and them, a QR code, 60 digits, and a scan.
struct SafetyNumberDetail: View {
    @Environment(AppModel.self) private var model
    let conversationId: String
    let title: String
    @State private var number: SafetyNumber?
    @State private var error: String?
    @State private var scanning = false
    @State private var result: Bool?

    private var card: Color {
        if let ink = Ink(named: model.profiles[title]?.ink) { return Color(rgb: ink.card) }
        return Palette.tileSky
    }

    private var me: String { model.account?.username ?? "" }

    var body: some View {
        DesignPage("Safety number", back: "Back") {
            HStack(spacing: 0) {
                face(me, label: "You")
                ZStack {
                    Line()
                        .stroke(Palette.text.opacity(0.5), style: StrokeStyle(lineWidth: 2.5, dash: [6, 5]))
                        .frame(height: 2)
                        .padding(.horizontal, 6)
                    ShapeSticker(kind: result == true ? .heart : .seal, size: 34, delay: 0.1)
                }
                .frame(width: 110, height: 64)
                face(title, label: title)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 6)
            if let number {
                VStack(spacing: 14) {
                    QRCodeImage(text: number.qrPayload)
                        .padding(10)
                        .frame(width: 156, height: 156)
                        .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                        .accessibilityLabel("Safety number code")
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 4), spacing: 6) {
                        ForEach(Array(number.digits.split(separator: " ").enumerated()), id: \.offset) { _, group in
                            Text(String(group))
                                .font(.system(size: 16.5, weight: .medium, design: .monospaced))
                                .tracking(0.5)
                        }
                    }
                    .textSelection(.enabled)
                    .padding(.vertical, 12)
                    .padding(.horizontal, 10)
                    .background(.white.opacity(0.75), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    Text("The same 60 digits on both your phones")
                        .font(.system(size: 12.5, weight: .semibold))
                        .opacity(0.75)
                }
                .foregroundStyle(Palette.ink)
                .padding(18)
                .frame(maxWidth: .infinity)
                .background(card, in: RoundedRectangle(cornerRadius: 30, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                .background(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(Palette.ink).offset(y: 6))
                .padding(.bottom, 6)
            } else if let error {
                KitFoot(error)
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(40)
            }
            if result == true {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 17, weight: .heavy))
                        .foregroundStyle(Palette.green)
                        .frame(width: 40, height: 40)
                        .background(Palette.ink, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text("It's a match!").font(.display(19))
                        Text("Just you and \(title) in this chat. Nobody in the middle.").font(.system(size: 13.5))
                    }
                    Spacer(minLength: 0)
                }
                .foregroundStyle(Color(rgb: 0x0F3D24))
                .padding(16)
                .background(Palette.green, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    ShapeSticker(kind: .sparkle, size: 28, delay: 0.1).padding(12)
                }
                .transition(.scale(scale: 0.8).combined(with: .opacity))
                .accessibilityElement(children: .combine)
            } else if result == false {
                VStack(alignment: .leading, spacing: 4) {
                    Text("That's not \(title)'s code").font(.display(19))
                    Text("Scan the code on their phone, in your chat with them. If it still doesn't match, keep anything sensitive for when you meet.")
                        .font(.system(size: 13.5))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(Color(rgb: 0x7A1A0E))
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(rgb: 0xFFD9D2), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                .transition(.scale(scale: 0.8).combined(with: .opacity))
            }
            #if os(iOS)
            if number != nil {
                Button { scanning = true } label: {
                    Text("Scan their code")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(Palette.cream)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .background(Palette.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
            }
            #endif
            KitFoot("Not together? Read the digits to each other on a call you trust.")
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.7), value: result)
        .task {
            do {
                number = try await model.safetyNumber(of: conversationId)
            } catch {
                self.error = error.userMessage
            }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $scanning) {
            SafetyScanner(name: title) { scanned in
                scanning = false
                guard let number else { return }
                let match = scanned.trimmingCharacters(in: .whitespacesAndNewlines) == number.qrPayload
                result = match
                if match { Task { await model.markSafetyChecked(conversationId) } }
            } cancel: {
                scanning = false
            }
        }
        #endif
    }

    private func face(_ username: String, label: String) -> some View {
        VStack(spacing: 6) {
            LiftedAvatar(username: username, size: 64)
            Text(label).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
        }
        .frame(width: 90)
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            var path = Path()
            path.move(to: CGPoint(x: rect.minX, y: rect.midY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            return path
        }
    }
}

#if os(iOS)
/// The camera with a frame and a moving line: point it at their code.
private struct SafetyScanner: View {
    let name: String
    let found: (String) -> Void
    let cancel: () -> Void
    @State private var sweep = false

    var body: some View {
        ZStack {
            Color(rgb: 0x0B0B0C).ignoresSafeArea()
            QRScanner { code in found(code) }
                .ignoresSafeArea()
                .opacity(0.9)
            VStack(spacing: 20) {
                RoundedRectangle(cornerRadius: 30, style: .continuous)
                    .strokeBorder(.white.opacity(0.85), lineWidth: 3)
                    .frame(width: 240, height: 240)
                    .overlay(alignment: .top) {
                        Capsule()
                            .fill(Palette.marigold)
                            .frame(height: 3)
                            .shadow(color: Palette.marigold, radius: 8)
                            .padding(.horizontal, 14)
                            .offset(y: sweep ? 200 : 34)
                    }
                Text("Point at the code on \(name)'s phone")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                Button(action: cancel) {
                    Text("Cancel")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 20)
                        .frame(height: 44)
                        .background(.white.opacity(0.14), in: Capsule())
                }
                .buttonStyle(PressScale())
            }
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) { sweep = true }
        }
    }
}
#endif
