import SwiftUI

// Managing a group, as designed (R-More "Manage a group"): a card in the
// group's colour with its stamp and name (tap to rename), everyone in it,
// and adding people.

extension ConversationSummary {
    /// The colour the group was made in, if any.
    var groupColour: GroupColour? { GroupColour(rawValue: colour) }
}

/// The group's card: its stamp, its name (tap the pencil to rename), and faces.
struct GroupHeroCard: View {
    @Environment(AppModel.self) private var model
    let conversation: ConversationSummary
    let members: [MemberView]
    @State private var renaming = false
    @State private var draft = ""
    @State private var boing = 0
    @State private var error: String?
    @FocusState private var focused: Bool

    private var colour: GroupColour { conversation.groupColour ?? .green }

    var body: some View {
        VStack(spacing: 10) {
            Text(groupInitials(conversation.title))
                .font(.display(36, weight: 700))
                .foregroundStyle(colour.deep)
                .frame(width: 100, height: 100)
                .background(.white, in: RoundedRectangle(cornerRadius: 33, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 33, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                .background(RoundedRectangle(cornerRadius: 33, style: .continuous).fill(Palette.ink).offset(y: 7))
                .phaseAnimator([1.0, 0.84, 1.1, 1.0], trigger: boing) { view, scale in
                    view.scaleEffect(scale)
                } animation: { _ in .spring(duration: 0.22, bounce: 0.5) }
                .onTapGesture { boing += 1 }
                .accessibilityHidden(true)
            if renaming {
                HStack(spacing: 8) {
                    TextField("Group name", text: $draft)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 14)
                        .frame(height: 46)
                        .background(.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit { Task { await save() } }
                    Button { Task { await save() } } label: {
                        Text("Save")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Palette.cream)
                            .padding(.horizontal, 16)
                            .frame(height: 46)
                            .background(Palette.ink, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(PressScale())
                }
            } else {
                Button {
                    draft = conversation.title
                    renaming = true
                    focused = true
                } label: {
                    HStack(spacing: 8) {
                        Text(conversation.title)
                            .font(.display(27))
                            .displayTracking(27)
                            .multilineTextAlignment(.center)
                        Image(systemName: "pencil")
                            .font(.system(size: 13, weight: .bold))
                            .frame(width: 28, height: 28)
                            .background(.white.opacity(0.7), in: Circle())
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
                .disabled(!conversation.active)
                .accessibilityLabel("\(conversation.title). Rename")
            }
            if let error {
                Text(error).font(.system(size: 13, weight: .semibold))
            }
            HStack(spacing: 10) {
                HStack(spacing: -8) {
                    ForEach(members.prefix(6), id: \.accountId) { member in
                        Avatar(title: member.username, size: 30, stamp: true)
                            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                    }
                }
                Text("Group · \(members.count) members").font(.system(size: 14, weight: .semibold))
            }
            .accessibilityElement(children: .combine)
        }
        .foregroundStyle(Palette.ink)
        .padding(.top, 22)
        .padding(.horizontal, 18)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity)
        .background {
            Color(rgb: colour.card)
                .overlay(DotTexture().scaleEffect(x: -1, y: 1))
        }
        .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
        .overlay(alignment: .topTrailing) {
            ShapeSticker(kind: .clover, size: 44, delay: 0.08, tappable: true).padding(.top, 14).padding(.trailing, 16)
        }
        .overlay(alignment: .topLeading) {
            ShapeSticker(kind: .sparkle, size: 28, delay: 0.18, tappable: true).padding(.top, 26).padding(.leading, 22)
        }
        .overlay(alignment: .bottomTrailing) {
            ShapeSticker(kind: .bolt, size: 24, delay: 0.3, phase: -1, tappable: true).padding(.bottom, 54).padding(.trailing, 30)
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: renaming)
        .designFields()
    }

    private func save() async {
        let title = draft.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        guard title != conversation.title else { renaming = false; return }
        do {
            try await model.renameGroup(conversation.id, to: title)
            error = nil
            renaming = false
            model.showToast("Renamed. Everyone sees “\(title)”")
        } catch {
            self.error = error.userMessage
        }
    }
}

/// A member of a group: their stamp, name and handle, and Remove.
struct GroupMemberRow: View {
    let member: MemberView
    let canRemove: Bool
    let remove: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            Avatar(title: member.username, size: 42, stamp: true)
            VStack(alignment: .leading, spacing: 1) {
                Text(member.isMe ? "You" : member.username).font(.system(size: 16, weight: .semibold))
                Text("@\(member.username)").font(.system(size: 13)).foregroundStyle(Palette.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if canRemove && !member.isMe {
                Button(action: remove) {
                    Text("Remove")
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(Palette.danger)
                        .padding(.horizontal, 12)
                        .frame(height: 34)
                        .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1.5))
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Remove \(member.username)")
            }
        }
        .foregroundStyle(Palette.text)
    }
}

/// "Add people" for a group: pick from the people you chat with, or type a username.
struct AddPeopleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let conversationId: String
    let already: [String]
    var onAdded: () -> Void = {}
    @State private var picked: [String] = []
    @State private var query = ""
    @State private var working = false
    @State private var error: String?

    private var typed: String {
        var n = query.trimmingCharacters(in: .whitespaces).lowercased()
        if n.hasPrefix("@") { n.removeFirst() }
        return n
    }

    private var people: [String] {
        var all = model.conversations.filter { $0.kind == .direct && !$0.blocked }.map(\.title).filter { !already.contains($0) }
        for name in picked where !all.contains(name) { all.append(name) }
        let shown = typed.isEmpty ? all : all.filter { $0.contains(typed) }
        if isUsername(typed), typed != model.account?.username, !already.contains(typed), !shown.contains(typed) {
            return shown + [typed]
        }
        return shown
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "Add people", back: { dismiss() }) {
                Button { Task { await add() } } label: {
                    Group {
                        if working { ProgressView().tint(Palette.cream) } else { Text("Add") }
                    }
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(picked.isEmpty ? Palette.secondary : Palette.cream)
                    .padding(.horizontal, 14)
                    .frame(height: 36)
                    .background(picked.isEmpty ? Palette.line : Palette.ink, in: Capsule())
                }
                .buttonStyle(PressScale())
                .disabled(picked.isEmpty || working)
            }
            ComposeSearchField(text: $query)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let error {
                        Text(error).font(.system(size: 13)).foregroundStyle(Palette.danger).padding(.horizontal, 6)
                    }
                    KitCard(radius: 24) {
                        ForEach(Array(people.enumerated()), id: \.element) { i, name in
                            if i > 0 { KitSeparator() }
                            let on = picked.contains(name)
                            Button {
                                if let at = picked.firstIndex(of: name) { picked.remove(at: at) } else { picked.append(name) }
                                if typed == name { query = "" }
                            } label: {
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
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if people.isEmpty {
                        Text("Type a username to add someone you don't chat with yet.")
                            .font(.system(size: 13)).foregroundStyle(Palette.secondary).padding(.horizontal, 6)
                    }
                }
                .padding(16)
            }
        }
        .background(Palette.home.ignoresSafeArea())
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 520)
        #endif
    }

    private func add() async {
        working = true
        defer { working = false }
        do {
            try await model.addMembers(picked, to: conversationId)
            model.showToast(picked.count == 1 ? "\(picked[0]) was added" : "\(picked.count) people were added")
            onAdded()
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}
