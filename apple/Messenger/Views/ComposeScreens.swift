import SwiftUI

// New chat and new group, as designed (R-More): big coloured cards, a live
// username lookup, Recent faces and everyone you chat with; then picking
// people, a name and a colour for a group.

/// The colours a group is made in. The core keeps the name.
enum GroupColour: String, CaseIterable, Identifiable {
    case rose, blue, green, marigold, lilac, graphite

    var id: String { rawValue }
    var title: String { rawValue.capitalized }

    /// The stamp's tint and its letters.
    var tint: Color {
        switch self {
        case .rose: Color(rgb: 0xF9DDE3)
        case .blue: Color(rgb: 0xDCE6FA)
        case .green: Color(rgb: 0xD7F3E2)
        case .marigold: Color(rgb: 0xFCEFC6)
        case .lilac: Color(rgb: 0xEADFF8)
        case .graphite: Color(rgb: 0xE3E6EB)
        }
    }

    var deep: Color {
        switch self {
        case .rose: Color(rgb: 0x8A2846)
        case .blue: Color(rgb: 0x26457E)
        case .green: Color(rgb: 0x1E6B44)
        case .marigold: Color(rgb: 0x8A5A00)
        case .lilac: Color(rgb: 0x5C3886)
        case .graphite: Color(rgb: 0x3A3F4A)
        }
    }

    /// A pinned card in this colour.
    var card: UInt32 {
        switch self {
        case .rose: 0xF4A6D0
        case .blue: 0xB9D3FF
        case .green: 0x5DC584
        case .marigold: 0xFFD23F
        case .lilac: 0xC9BDFF
        case .graphite: 0xD9DCE2
        }
    }
}

/// A group's initials: the first letter of its first two words.
func groupInitials(_ title: String) -> String {
    let words = title.split(whereSeparator: \.isWhitespace).prefix(2)
    let letters = words.compactMap { $0.first.map { String($0) } }.joined().uppercased()
    return letters.isEmpty ? "G" : letters
}

/// Usernames as the server takes them.
func isUsername(_ text: String) -> Bool {
    text.range(of: "^[a-z][a-z0-9_.]{2,31}$", options: .regularExpression) != nil
}

/// A screen's top: a round back button, a centred title, and maybe a button.
struct ScreenHeader<Trailing: View>: View {
    let title: String
    let back: () -> Void
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 10) {
            RoundButton(systemImage: "chevron.left", label: "Back", size: 44, action: back)
            Text(title)
                .font(.display(22))
                .displayTracking(22)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)
            trailing.frame(minWidth: 44, alignment: .trailing)
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }
}

extension ScreenHeader where Trailing == EmptyView {
    init(title: String, back: @escaping () -> Void) {
        self.init(title: title, back: back) { EmptyView() }
    }
}

/// The search field of the compose screens.
struct ComposeSearchField: View {
    @Binding var text: String
    var placeholder = "Search or type a @username"
    var focused: FocusState<Bool>.Binding?

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Palette.secondary)
            field
        }
        .padding(.horizontal, 16)
        .frame(height: 46)
        .background(Palette.surface, in: Capsule())
        .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1.5))
        .padding(.horizontal, 16)
        .designFields()
    }

    @ViewBuilder private var field: some View {
        let base = TextField(placeholder, text: $text)
            .font(.system(size: 16))
            .autocorrectionDisabled()
            #if os(iOS)
            .textInputAutocapitalization(.never)
            #endif
        if let focused { base.focused(focused) } else { base }
    }
}

/// A big coloured card with a shape: New group, Invite a friend.
struct ShapeCard: View {
    let title: String
    let detail: String
    let colour: Color
    let shape: ShapeKind
    var delay: Double = 0.05

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Spacer(minLength: 0)
            Text(title).font(.display(21)).lineLimit(2)
            Text(detail).font(.system(size: 13)).opacity(0.78).lineLimit(2)
        }
        .foregroundStyle(Palette.ink)
        .multilineTextAlignment(.leading)
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 136, maxHeight: 136, alignment: .leading)
        .background { colour.overlay(DotTexture()) }
        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(alignment: .topTrailing) {
            ShapeSticker(kind: shape, size: 50, delay: delay).padding(10)
        }
    }
}

/// The faint dots on coloured cards, fading in from the top left.
struct DotTexture: View {
    var body: some View {
        Canvas { context, size in
            var dots = Path()
            for x in stride(from: CGFloat(9), to: size.width, by: 18) {
                for y in stride(from: CGFloat(9), to: size.height, by: 18) {
                    dots.addEllipse(in: CGRect(x: x - 1.6, y: y - 1.6, width: 3.2, height: 3.2))
                }
            }
            context.fill(dots, with: .color(Palette.ink.opacity(0.11)))
        }
        .mask(LinearGradient(colors: [.clear, .clear, .black], startPoint: .topLeading, endPoint: .bottomTrailing))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A person's stamp with the design's dark offset shadow.
struct LiftedAvatar: View {
    let username: String
    var size: CGFloat = 66

    var body: some View {
        Avatar(title: username, size: size, stamp: true)
            .overlay(RoundedRectangle(cornerRadius: size / 3, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
            .background(RoundedRectangle(cornerRadius: size / 3, style: .continuous).fill(Palette.ink).offset(y: 4))
    }
}

/// A round tick: marigold with a dark edge when picked.
struct PickTick: View {
    let on: Bool

    var body: some View {
        ZStack {
            Circle().fill(on ? Palette.marigold : Color.clear)
            Circle().strokeBorder(on ? Palette.ink : Palette.line, lineWidth: 2)
            if on {
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundStyle(Palette.ink)
            }
        }
        .frame(width: 26, height: 26)
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityHidden(true)
    }
}

// MARK: - New chat

struct NewChatView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var lookup: NameAvailability = .idle
    @State private var working = false
    @State private var error: String?
    @State private var newGroup = false
    @FocusState private var searching: Bool

    private var name: String {
        var n = query.trimmingCharacters(in: .whitespaces).lowercased()
        if n.hasPrefix("@") { n.removeFirst() }
        return n
    }

    private var me: String { model.account?.username ?? "" }

    /// People we have a 1:1 chat with, newest first.
    private var people: [ConversationSummary] {
        model.conversations.filter { $0.kind == .direct && !$0.blocked }
    }

    private var matching: [ConversationSummary] {
        guard !name.isEmpty else { return people }
        return people.filter { $0.title.lowercased().contains(name) }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScreenHeader(title: "New chat") { dismiss() }
                ComposeSearchField(text: $query, focused: $searching)
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 12) {
                            Button { newGroup = true } label: {
                                ShapeCard(title: "New group", detail: "Friends, family, the hike crew", colour: Palette.green, shape: .clover)
                            }
                            .buttonStyle(PressScale())
                            ShareLink(item: "@\(me)", message: Text("Find me on Messenger: @\(me)")) {
                                ShapeCard(title: "Invite a friend", detail: "Share @\(me) so they find you", colour: Palette.marigold, shape: .heart, delay: 0.12)
                            }
                            .buttonStyle(PressScale())
                        }
                        .padding(.horizontal, 16)
                        lookupCard
                        if !matching.isEmpty {
                            Text(name.isEmpty ? "Recent" : "Matches")
                                .font(.display(20))
                                .padding(.horizontal, 22)
                                .padding(.top, 2)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(alignment: .top, spacing: 14) {
                                    ForEach(matching.prefix(10), id: \.id) { chat in
                                        Button { open(chat) } label: {
                                            VStack(spacing: 8) {
                                                LiftedAvatar(username: chat.title)
                                                Text(chat.title)
                                                    .font(.system(size: 13, weight: .semibold))
                                                    .lineLimit(1)
                                                    .fixedSize()
                                                    .frame(maxWidth: 80)
                                            }
                                        }
                                        .buttonStyle(PressScale())
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.top, 2)
                                .padding(.bottom, 8)
                            }
                            Text("Everyone you chat with")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Palette.secondary)
                                .padding(.horizontal, 22)
                            KitCard(radius: 24) {
                                ForEach(Array(matching.sorted { $0.title < $1.title }.enumerated()), id: \.element.id) { i, chat in
                                    if i > 0 { KitSeparator() }
                                    Button { open(chat) } label: { personRow(chat) }
                                        .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 16)
                        }
                        Text("No phone numbers, no contact upload. Friends find each other by the exact username they share.")
                            .font(.system(size: 13))
                            .foregroundStyle(Palette.secondary)
                            .padding(.horizontal, 22)
                    }
                    .padding(.top, 14)
                    .padding(.bottom, 30)
                }
            }
            .background(Palette.home.ignoresSafeArea())
            .designFields()
            .toolbar(.hidden)
            .navigationDestination(isPresented: $newGroup) {
                NewGroupView(onDone: { dismiss() })
            }
        }
        .task(id: name) { await look() }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 620)
        #endif
    }

    private func personRow(_ chat: ConversationSummary) -> some View {
        HStack(spacing: 14) {
            Avatar(title: chat.title, size: 42, stamp: true)
            VStack(alignment: .leading, spacing: 1) {
                Text(chat.title).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                Text("@\(chat.title)").font(.system(size: 13)).foregroundStyle(Palette.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(shortTime(chat.lastActivityMs))
                .font(.system(size: 12.5))
                .foregroundStyle(Palette.secondary)
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    /// Found, or nobody by that name: shown once three letters are typed.
    @ViewBuilder private var lookupCard: some View {
        switch lookup {
        case .checking:
            HStack(spacing: 14) {
                ProgressView()
                Text("Looking for @\(name)…").font(.system(size: 15, weight: .semibold))
                Spacer()
            }
            .padding(14)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Kit.cardLine))
            .padding(.horizontal, 16)
        case .taken(let found):
            HStack(spacing: 14) {
                LiftedAvatar(username: found, size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text("@\(found)").font(.display(20)).lineLimit(1)
                    Text(error ?? "Found! Say hi to start an encrypted chat")
                        .font(.system(size: 13)).opacity(0.8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button { Task { await start(found) } } label: {
                    Group {
                        if working { ProgressView().tint(Palette.cream) } else { Text("Say hi") }
                    }
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Palette.cream)
                    .padding(.horizontal, 16)
                    .frame(height: 40)
                    .background(Palette.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
                .disabled(working)
            }
            .foregroundStyle(Palette.ink)
            .padding(14)
            .background(Palette.marigold, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
            .overlay(alignment: .topTrailing) {
                ShapeSticker(kind: .sparkle, size: 26, delay: 0.2).padding(.top, 8).padding(.trailing, 98)
            }
            .padding(.horizontal, 16)
            .transition(.scale(scale: 0.6).combined(with: .opacity))
        case .free(let missing):
            HStack(spacing: 14) {
                ShapeSticker(kind: .moon, size: 52, delay: 0.05)
                VStack(alignment: .leading, spacing: 2) {
                    Text("No @\(missing) here yet").font(.system(size: 16, weight: .bold))
                    Text("Usernames are exact, so check the spelling with them. Or send them yours.")
                        .font(.system(size: 13)).opacity(0.75)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(Palette.text)
            .padding(14)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(Kit.cardLine))
            .padding(.horizontal, 16)
            .transition(.scale(scale: 0.6).combined(with: .opacity))
        case .unknown:
            Text("Can't check usernames right now. You can still try: Say hi starts the chat if they're there.")
                .font(.system(size: 13))
                .foregroundStyle(Palette.secondary)
                .padding(.horizontal, 22)
        case .idle:
            EmptyView()
        }
    }

    private func look() async {
        error = nil
        let n = name
        guard isUsername(n), n != me else {
            lookup = .idle
            return
        }
        // Someone we already chat with needs no lookup.
        if people.contains(where: { $0.title == n }) {
            lookup = .taken(n)
            return
        }
        lookup = .checking
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled, let server = model.account?.serverUrl else { return }
        let result = await NameAvailability.check(n, server: server)
        guard !Task.isCancelled else { return }
        withAnimation(.spring(response: 0.45, dampingFraction: 0.6)) { lookup = result }
    }

    private func open(_ chat: ConversationSummary) {
        model.selectedConversationId = chat.id
        dismiss()
    }

    private func start(_ username: String) async {
        guard !working else { return }
        if let chat = people.first(where: { $0.title == username && $0.active }) {
            open(chat)
            return
        }
        working = true
        defer { working = false }
        do {
            try await model.startDirectChat(with: username)
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}

// MARK: - New group

struct NewGroupView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// Closes the whole flow once the group is made.
    var onDone: (() -> Void)?
    @State private var picked: [String] = []
    @State private var query = ""
    @State private var naming = false
    @State private var title = ""
    @State private var colour: GroupColour = .green
    @State private var working = false
    @State private var error: String?

    private var me: String { model.account?.username ?? "" }

    private var typed: String {
        var n = query.trimmingCharacters(in: .whitespaces).lowercased()
        if n.hasPrefix("@") { n.removeFirst() }
        return n
    }

    /// Everyone we have a 1:1 chat with, then anyone typed by username.
    private var people: [String] {
        var all = model.conversations.filter { $0.kind == .direct && !$0.blocked }.map(\.title)
        for name in picked where !all.contains(name) { all.append(name) }
        let shown = typed.isEmpty ? all : all.filter { $0.contains(typed) }
        if isUsername(typed), typed != me, !shown.contains(typed) { return shown + [typed] }
        return shown
    }

    var body: some View {
        Group {
            if naming { nameStep } else { pickStep }
        }
        .background(Palette.home.ignoresSafeArea())
        .designFields()
        .toolbar(.hidden)
        .animation(.spring(response: 0.38, dampingFraction: 0.86), value: naming)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 620)
        #endif
    }

    private var pickStep: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "New group", back: close) {
                Button { if !picked.isEmpty { naming = true } } label: {
                    Text("Next")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(picked.isEmpty ? Palette.secondary : Palette.cream)
                        .padding(.horizontal, 14)
                        .frame(height: 36)
                        .background(picked.isEmpty ? Palette.line : Palette.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
                .disabled(picked.isEmpty)
            }
            whoCard
                .padding(.horizontal, 16)
                .padding(.top, 2)
                .padding(.bottom, 14)
            ComposeSearchField(text: $query)
            ScrollView {
                KitCard(radius: 24) {
                    ForEach(Array(people.enumerated()), id: \.element) { i, name in
                        if i > 0 { KitSeparator() }
                        let on = picked.contains(name)
                        Button { toggle(name) } label: {
                            HStack(spacing: 14) {
                                Avatar(title: name, size: 44, stamp: true)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(name).font(.system(size: 16, weight: .semibold))
                                    Text("@\(name)").font(.system(size: 13)).foregroundStyle(Palette.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                PickTick(on: on)
                            }
                            .foregroundStyle(Palette.text)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(on ? Color(light: 0xFFF7DA, dark: 0x2A2615) : .clear)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 30)
            }
        }
    }

    /// Lavender: who's in so far, each face with an ×.
    private var whoCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(picked.isEmpty ? "Who’s in?" : "\(picked.count) picked · \(picked.count + 1) with you")
                .font(.display(26))
                .contentTransition(.numericText())
            if picked.isEmpty {
                Text("Pick at least one friend. You can always add more later.")
                    .font(.system(size: 14))
                    .padding(.trailing, 70)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(picked, id: \.self) { name in
                            Button { toggle(name) } label: {
                                LiftedAvatar(username: name, size: 50)
                                    .overlay(alignment: .topTrailing) {
                                        Text("×")
                                            .font(.system(size: 13, weight: .bold))
                                            .foregroundStyle(.white)
                                            .frame(width: 20, height: 20)
                                            .background(Circle().fill(Palette.ink))
                                            .overlay(Circle().strokeBorder(Palette.vanishAccent, lineWidth: 2))
                                            .offset(x: 5, y: -5)
                                    }
                                    .padding(.top, 6)
                            }
                            .buttonStyle(PressScale())
                            .accessibilityLabel("Remove \(name)")
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                        }
                    }
                    .padding(.trailing, 6)
                    .padding(.bottom, 6)
                }
            }
        }
        .foregroundStyle(Palette.ink)
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 118, alignment: .topLeading)
        .background { Palette.vanishAccent.overlay(DotTexture()) }
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(alignment: .topTrailing) {
            ZStack(alignment: .topTrailing) {
                ShapeSticker(kind: .clover, size: 46, delay: 0.05).padding(.top, 10).padding(.trailing, 12)
                ShapeSticker(kind: .sparkle, size: 22, delay: 0.2, phase: -1).padding(.top, 44).padding(.trailing, 66)
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.7), value: picked)
    }

    private var nameStep: some View {
        VStack(spacing: 16) {
            ScreenHeader(title: "Name it") { naming = false }
            VStack(spacing: 12) {
                Text(groupInitials(title.isEmpty ? "New group" : title))
                    .font(.display(38, weight: 700))
                    .foregroundStyle(colour.deep)
                    .frame(width: 104, height: 104)
                    .background(colour.tint, in: RoundedRectangle(cornerRadius: 34, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 34, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                    .background(RoundedRectangle(cornerRadius: 34, style: .continuous).fill(Palette.ink).offset(y: 6))
                    .animation(.easeOut(duration: 0.25), value: colour)
                HStack(spacing: 8) {
                    ForEach(GroupColour.allCases) { option in
                        Button { colour = option } label: {
                            Circle()
                                .fill(option.tint)
                                .frame(width: 30, height: 30)
                                .overlay(Circle().strokeBorder(colour == option ? Palette.text : Palette.line, lineWidth: colour == option ? 3 : 1.5))
                        }
                        .buttonStyle(PressScale())
                        .accessibilityLabel(option.title)
                        .accessibilityAddTraits(colour == option ? .isSelected : [])
                    }
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("Group name").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.secondary)
                TextField("Sunday Hike Crew", text: $title)
                    .font(.system(size: 17, weight: .semibold))
                    .padding(.horizontal, 16)
                    .frame(height: 54)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Palette.text, lineWidth: 1.5))
                    .submitLabel(.done)
                    .onSubmit { Task { await create() } }
            }
            .padding(.horizontal, 16)
            VStack(alignment: .leading, spacing: 10) {
                Text("\(picked.count + 1) members, with you")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.secondary)
                    .padding(.horizontal, 22)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(picked, id: \.self) { name in
                            VStack(spacing: 4) {
                                Avatar(title: name, size: 48, stamp: true)
                                Text(name).font(.system(size: 12, weight: .semibold)).lineLimit(1).fixedSize()
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                Text(error ?? "Anyone in the group can add or remove people. The name and members are encrypted: the server never sees them.")
                    .font(.system(size: 13))
                    .foregroundStyle(error == nil ? Palette.secondary : Palette.danger)
                    .padding(.horizontal, 22)
            }
            Spacer(minLength: 0)
            let ready = !title.trimmingCharacters(in: .whitespaces).isEmpty
            Button { Task { await create() } } label: {
                Group {
                    if working { ProgressView() } else { Text("Create group") }
                }
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(ready ? Palette.ink : Palette.secondary)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(ready ? Palette.marigold : Palette.line, in: Capsule())
            }
            .buttonStyle(PressScale())
            .disabled(!ready || working)
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        .foregroundStyle(Palette.text)
    }

    private func toggle(_ name: String) {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) {
            if let i = picked.firstIndex(of: name) { picked.remove(at: i) } else { picked.append(name) }
        }
        if isUsername(typed), typed == name { query = "" }
    }

    private func close() { dismiss() }

    private func create() async {
        let name = title.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, !working else { return }
        working = true
        defer { working = false }
        do {
            try await model.createGroup(title: name, usernames: picked, colour: colour.rawValue)
            if let onDone { onDone() } else { dismiss() }
        } catch {
            self.error = error.userMessage
        }
    }
}
