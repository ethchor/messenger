import SwiftUI

struct ConversationInfoView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let conversationId: String

    @State private var members: [MemberView] = []
    @State private var safety: SafetyNumber?
    @State private var newMembers = ""
    @State private var working = false
    @State private var error: String?
    @State private var confirmLeave = false
    @State private var confirm: ChatConfirm?
    @State private var showingMap = false

    private var conversation: ConversationSummary? {
        model.conversations.first { $0.id == conversationId }
    }

    private var canLeave: Bool {
        conversation?.active == true && conversation?.leaving == false
    }

    private var isActiveGroup: Bool {
        conversation?.kind == .group && conversation?.active == true
    }

    var body: some View {
        NavigationStack {
            Form {
                if let conversation {
                    Section {
                        InfoHero(conversation: conversation, memberCount: members.count)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        HStack(spacing: 8) {
                            InfoAction(title: "Message", systemImage: "bubble.left.fill", filled: true) { dismiss() }
                            InfoAction(title: "Map", systemImage: "map") { showingMap = true }
                            InfoAction(title: conversation.archived ? "Unarchive" : "Archive", systemImage: "archivebox") {
                                Task { await model.setArchived(conversation.id, !conversation.archived) }
                            }
                        }
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                    }
                }

                if let conversation {
                    ChatSettingsSection(conversation: conversation)
                }

                if canLeave {
                    ChatLocationSection(conversationId: conversationId)
                }

                Section {
                    ForEach(members, id: \.accountId) { member in
                        HStack {
                            Avatar(title: member.username, size: 28)
                            Text(member.username)
                            if member.isMe {
                                Text("you").foregroundStyle(.secondary)
                            }
                        }
                        .contextMenu {
                            if isActiveGroup && !member.isMe {
                                Button(role: .destructive) {
                                    Task { await remove(member) }
                                } label: {
                                    Label("Remove from group", systemImage: "person.badge.minus")
                                }
                            }
                        }
                        .swipeActions {
                            if isActiveGroup && !member.isMe {
                                Button("Remove", role: .destructive) { Task { await remove(member) } }
                            }
                        }
                    }
                } header: {
                    InfoHeader(title: "Members", detail: "\(members.count)")
                }

                if isActiveGroup {
                    Section {
                        TextField("Usernames", text: $newMembers)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                        Button("Add to group") { Task { await add() } }
                            .disabled(working || parseUsernames(newMembers).isEmpty)
                    } header: {
                        Text("Add people")
                    } footer: {
                        if let error { Text(error).foregroundStyle(.red) }
                    }
                }

                if conversation?.kind == .direct, let safety {
                    Section {
                        SafetyNumberView(number: safety)
                    } header: {
                        InfoHeader(title: "Safety number", detail: "60 digits")
                    } footer: {
                        Text("Compare these digits with \(conversation?.title ?? "them") in person, or scan each other's code. If they match, nobody is listening in the middle.")
                    }
                }

                ChatPaperSection(conversationId: conversationId)

                if canLeave {
                    Section {
                        Button(conversation?.kind == .group ? "Leave group" : "End chat", role: .destructive) {
                            confirmLeave = true
                        }
                    } footer: {
                        Text(conversation?.kind == .group
                             ? "The others see that you left. You won't get new messages from this group."
                             : "\(conversation?.title ?? "They") will see that you left. You can start a new chat later.")
                    }
                } else if conversation?.leaving == true {
                    Section {
                        Label("Leaving. The others see it once one of them is online.", systemImage: "hourglass")
                            .foregroundStyle(.secondary)
                    }
                }

                if let conversation {
                    Section {
                        if conversation.kind == .direct {
                            if conversation.blocked {
                                Button("Unblock \(conversation.title)") {
                                    Task { await model.setBlocked(conversation.title, false) }
                                }
                            } else {
                                Button("Block \(conversation.title)", role: .destructive) {
                                    confirm = .block(conversation)
                                }
                            }
                        }
                        Button("Clear chat", role: .destructive) { confirm = .clear(conversation) }
                        Button("Delete chat", role: .destructive) { confirm = .delete(conversation) }
                    }
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .background { HomeGround().ignoresSafeArea() }
            .navigationTitle(conversation?.kind == .group ? "Group" : "Contact")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .sheet(isPresented: $showingMap) { PeopleMapView() }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await reload() }
            .chatConfirmation($confirm, model: model)
            .onChange(of: model.conversations.contains { $0.id == conversationId }) { _, stillThere in
                // Deleted: nothing left to show.
                if !stillThere { dismiss() }
            }
            .confirmationDialog(
                conversation?.kind == .group ? "Leave this group?" : "End this chat?",
                isPresented: $confirmLeave,
                titleVisibility: .visible
            ) {
                Button(conversation?.kind == .group ? "Leave group" : "End chat", role: .destructive) {
                    Task { await leave() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 520)
        #endif
    }

    private func reload() async {
        do {
            members = try await model.members(of: conversationId)
            if conversation?.kind == .direct && conversation?.active == true {
                safety = try? await model.safetyNumber(of: conversationId)
            }
        } catch {
            self.error = error.userMessage
        }
    }

    private func add() async {
        working = true
        defer { working = false }
        do {
            try await model.addMembers(parseUsernames(newMembers), to: conversationId)
            newMembers = ""
            error = nil
            // The new members show up once the change has gone through.
            try? await Task.sleep(nanoseconds: 500_000_000)
            await model.syncNow()
            await reload()
        } catch {
            self.error = error.userMessage
        }
    }

    private func leave() async {
        do {
            try await model.leave(conversationId)
            await model.syncNow()
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }

    private func remove(_ member: MemberView) async {
        do {
            try await model.removeMember(member.username, from: conversationId)
            await model.syncNow()
            await reload()
        } catch {
            self.error = error.userMessage
        }
    }
}


/// The top of a chat's info: a big stamp, the name, who it is, and how long it's been sealed.
struct InfoHero: View {
    let conversation: ConversationSummary
    let memberCount: Int

    var body: some View {
        VStack(spacing: 6) {
            Avatar(title: conversation.title, isGroup: conversation.kind == .group, size: 108, stamp: true)
                .padding(.bottom, 8)
            Text(conversation.title)
                .font(.display(28))
                .tracking(-0.5)
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
            Text(conversation.kind == .group ? "Group · \(memberCount) members" : "@\(conversation.title)")
                .font(.system(size: 14.5))
                .foregroundStyle(Palette.secondary)
            Label("Sealed end to end", systemImage: "lock.fill")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Palette.secondary)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// One of the three big buttons under the name.
struct InfoAction: View {
    let title: String
    let systemImage: String
    var filled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: systemImage).font(.system(size: 17, weight: .semibold))
                Text(title).font(.system(size: 13, weight: .semibold))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 64)
            .foregroundStyle(filled ? Palette.ink : Palette.text)
            .background(filled ? Palette.marigold : Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay {
                if !filled { RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Palette.line, lineWidth: 1) }
            }
            .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

/// A card's title in the display face, with a quiet detail on the right.
struct InfoHeader: View {
    let title: String
    var detail: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.display(18))
                .foregroundStyle(Palette.text)
            Spacer()
            if let detail {
                Text(detail).font(.system(size: 13)).foregroundStyle(Palette.secondary)
            }
        }
        .textCase(nil)
    }
}
