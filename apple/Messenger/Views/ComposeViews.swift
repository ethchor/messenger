import SwiftUI

struct NewChatView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var username = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Username", text: $username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .onSubmit { Task { await start() } }
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(.red)
                    } else {
                        Text("Ask them for their username. They need to have opened the app once.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("New chat")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if working {
                        ProgressView()
                    } else {
                        Button("Start") { Task { await start() } }
                            .disabled(username.trimmingCharacters(in: .whitespaces).count < 3)
                    }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 400, minHeight: 220)
        #endif
    }

    private func start() async {
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            try await model.startDirectChat(with: username.trimmingCharacters(in: .whitespaces))
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}

struct NewGroupView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var members = ""
    @State private var working = false
    @State private var error: String?

    private var usernames: [String] { parseUsernames(members) }

    var body: some View {
        NavigationStack {
            Form {
                Section("Group name") {
                    TextField("Name", text: $title)
                }
                Section {
                    TextField("alice, bob", text: $members, axis: .vertical)
                        .lineLimit(1...4)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                } header: {
                    Text("Members")
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(.red)
                    } else {
                        Text("Usernames, separated by commas or spaces.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("New group")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if working {
                        ProgressView()
                    } else {
                        Button("Create") { Task { await create() } }
                            .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || usernames.isEmpty)
                    }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 300)
        #endif
    }

    private func create() async {
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            try await model.createGroup(title: title, usernames: usernames)
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}

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
                Section("Members") {
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
                        Text("Safety number")
                    } footer: {
                        Text("Compare these digits with \(conversation?.title ?? "them") in person, or scan each other's code. If they match, nobody is listening in the middle.")
                    }
                }

                if canLeave {
                    ChatLocationSection(conversationId: conversationId)
                }

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
            }
            .formStyle(.grouped)
            .navigationTitle(conversation?.title ?? "Info")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await reload() }
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
