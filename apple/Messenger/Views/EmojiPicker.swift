import SwiftUI

/// One emoji the picker offers: what it's called, words to find it by, and
/// its five skin tones if it has them.
struct EmojiEntry: Hashable {
    let emoji: String
    let name: String
    let skins: [String]
    /// Lower-case words of the name and keywords, for search.
    let words: [String]

    /// This emoji in `tone` (1 light to 5 dark), or as it is.
    func inTone(_ tone: Int) -> String {
        (1...5).contains(tone) && skins.count == 5 ? skins[tone - 1] : emoji
    }
}

struct EmojiGroup: Identifiable {
    let id: Int
    let name: String
    let symbol: String
    let entries: [EmojiEntry]
}

/// The emoji the picker offers, read once from emoji.tsv (written by
/// scripts/emoji.py), and this device's recent ones and skin tone.
enum EmojiCatalog {
    static let groups: [EmojiGroup] = load()

    /// Every emoji and skin tone, to name recent ones.
    private static let byEmoji: [String: EmojiEntry] = {
        var index: [String: EmojiEntry] = [:]
        for entry in groups.flatMap(\.entries) {
            index[entry.emoji] = entry
            for skin in entry.skins { index[skin] = entry }
        }
        return index
    }()

    private static let symbols = [
        "face.smiling", "hand.raised", "pawprint", "fork.knife", "car", "soccerball", "lightbulb", "heart", "flag",
    ]

    private static func load() -> [EmojiGroup] {
        let url = Bundle.main.url(forResource: "emoji", withExtension: "tsv")
            ?? Bundle.main.url(forResource: "emoji", withExtension: "tsv", subdirectory: "Resources")
        guard let url, let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        var groups: [EmojiGroup] = []
        var name: String?
        var entries: [EmojiEntry] = []
        func finishGroup() {
            if let name {
                let symbol = symbols[min(groups.count, symbols.count - 1)]
                groups.append(EmojiGroup(id: groups.count, name: name, symbol: symbol, entries: entries))
            }
            entries = []
        }
        for line in text.split(separator: "\n") {
            if line.hasPrefix("#") { continue }
            if line.hasPrefix("@") {
                finishGroup()
                name = String(line.dropFirst())
                continue
            }
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
            guard fields.count >= 3 else { continue }
            let nameWords = fields[1].lowercased().split(whereSeparator: { " :,".contains($0) }).map(String.init)
            entries.append(EmojiEntry(
                emoji: fields[0],
                name: fields[1],
                skins: fields.count > 3 ? fields[3].split(separator: " ").map(String.init) : [],
                words: nameWords + fields[2].split(separator: " ").map(String.init)
            ))
        }
        finishGroup()
        return groups
    }

    /// Emoji with a word starting with each word of `query`.
    static func search(_ query: String) -> [EmojiEntry] {
        let words = query.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return [] }
        return Array(groups.lazy.flatMap(\.entries).filter { entry in
            words.allSatisfy { word in entry.words.contains { $0.hasPrefix(word) } }
        }.prefix(300))
    }

    static func name(of emoji: String) -> String? {
        byEmoji[emoji]?.name
    }

    private static let recentKey = "emoji.recent"
    private static let toneKey = "emoji.tone"

    /// Picked lately on this device, newest first.
    static var recent: [String] {
        UserDefaults.standard.stringArray(forKey: recentKey) ?? []
    }

    static func used(_ emoji: String) {
        var list = recent.filter { $0 != emoji }
        list.insert(emoji, at: 0)
        UserDefaults.standard.set(Array(list.prefix(24)), forKey: recentKey)
    }

    /// The skin tone for emoji that have them: 0 for the default yellow,
    /// 1 light to 5 dark.
    static var tone: Int {
        get { UserDefaults.standard.integer(forKey: toneKey) }
        set { UserDefaults.standard.set(newValue, forKey: toneKey) }
    }

    /// Forgets the recent ones and the tone, when the account leaves this device.
    static func reset() {
        UserDefaults.standard.removeObject(forKey: recentKey)
        UserDefaults.standard.removeObject(forKey: toneKey)
    }
}

/// Any emoji: by group, by name, or one used lately.
struct EmojiPicker: View {
    var title = "React"
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var tone = EmojiCatalog.tone
    @State private var recent = EmojiCatalog.recent

    private static let hands = ["✋", "✋🏻", "✋🏼", "✋🏽", "✋🏾", "✋🏿"]
    private static let toneNames = ["Default", "Light", "Medium-light", "Medium", "Medium-dark", "Dark"]
    private let columns = [GridItem(.adaptive(minimum: 44, maximum: 52), spacing: 2)]

    private var searching: Bool {
        !query.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 2, pinnedViews: [.sectionHeaders]) {
                        if searching {
                            ForEach(EmojiCatalog.search(query), id: \.emoji) { entry in
                                cell(entry.inTone(tone), name: entry.name)
                            }
                        } else {
                            if !recent.isEmpty {
                                Section {
                                    ForEach(recent, id: \.self) { cell($0, name: EmojiCatalog.name(of: $0)) }
                                } header: {
                                    header("Recently used")
                                }
                            }
                            ForEach(EmojiCatalog.groups) { group in
                                Section {
                                    ForEach(group.entries, id: \.emoji) { entry in
                                        cell(entry.inTone(tone), name: entry.name)
                                    }
                                } header: {
                                    header(group.name)
                                }
                                .id(group.id)
                            }
                        }
                    }
                    .padding(.horizontal, 10)
                }
                .overlay {
                    if searching, EmojiCatalog.search(query).isEmpty {
                        ContentUnavailableView.search(text: query)
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    if !searching {
                        groupBar(proxy)
                    }
                }
            }
            .searchable(text: $query, prompt: "Search emoji")
            .navigationTitle(title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    toneMenu
                }
            }
            .onChange(of: tone) { _, value in EmojiCatalog.tone = value }
        }
        #if os(macOS)
        .frame(minWidth: 400, idealWidth: 440, minHeight: 480, idealHeight: 560)
        #else
        .presentationDetents([.medium, .large])
        #endif
    }

    private func cell(_ emoji: String, name: String?) -> some View {
        Button {
            EmojiCatalog.used(emoji)
            onPick(emoji)
            dismiss()
        } label: {
            Text(emoji)
                .font(.system(size: 30))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name ?? emoji)
        .help(name ?? "")
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 6)
            .background(.background)
    }

    /// One button per group, to jump to it.
    private func groupBar(_ proxy: ScrollViewProxy) -> some View {
        HStack(spacing: 0) {
            ForEach(EmojiCatalog.groups) { group in
                Button {
                    withAnimation { proxy.scrollTo(group.id, anchor: .top) }
                } label: {
                    Image(systemName: group.symbol)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel(group.name)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(.bar)
    }

    private var toneMenu: some View {
        Menu {
            Picker("Skin tone", selection: $tone) {
                ForEach(0..<6, id: \.self) { index in
                    Text("\(Self.hands[index])  \(Self.toneNames[index])").tag(index)
                }
            }
            .pickerStyle(.inline)
        } label: {
            Text(Self.hands[tone])
                .accessibilityLabel("Skin tone: \(Self.toneNames[tone])")
        }
    }
}
