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
/// The full emoji picker, as designed: quick reactions, search, a tab per
/// category, eight to a row, and skin tones as swatches.
struct EmojiPicker: View {
    var title = "React"
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var tone = EmojiCatalog.tone
    @State private var recent = EmojiCatalog.recent
    /// -1: recent; otherwise a group's id.
    @State private var category = -1

    private static let quick = ["❤️", "😂", "😮", "😢", "🙏", "👍"]
    private static let toneSwatches: [UInt32] = [0xFFC83D, 0xF7DECE, 0xE8BC97, 0xC68E6A, 0x8D5A3B, 0x4E3426]
    private static let toneNames = ["Default", "Light", "Medium-light", "Medium", "Medium-dark", "Dark"]
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 8)

    private var searching: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }

    private var shown: [(String, String?)] {
        if searching {
            return EmojiCatalog.search(query).map { ($0.inTone(tone), $0.name) }
        }
        if category == -1 {
            let list = recent.isEmpty ? Self.quick : recent
            return list.map { ($0, EmojiCatalog.name(of: $0)) }
        }
        let group = EmojiCatalog.groups.first { $0.id == category }
        return group?.entries.map { ($0.inTone(tone), $0.name) } ?? []
    }

    private var heading: String {
        if searching { return "Results" }
        if category == -1 { return "Recent" }
        return EmojiCatalog.groups.first { $0.id == category }?.name ?? ""
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 6) {
                ForEach(Self.quick, id: \.self) { emoji in
                    Button { pick(emoji) } label: {
                        Text(emoji)
                            .font(.system(size: 26))
                            .frame(width: 46, height: 46)
                            .background(Palette.surface, in: Circle())
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel(EmojiCatalog.name(of: emoji) ?? emoji)
                    if emoji != Self.quick.last { Spacer(minLength: 0) }
                }
            }
            .padding(.horizontal, 16)
            ComposeSearchField(text: $query, placeholder: "Search emoji")
            HStack(spacing: 0) {
                categoryTab(id: -1, icon: "🕘", name: "Recent")
                ForEach(EmojiCatalog.groups) { group in
                    categoryTab(id: group.id, icon: group.entries.first?.emoji ?? "•", name: group.name)
                }
            }
            .padding(.horizontal, 12)
            Text(heading.uppercased())
                .font(.system(size: 12.5, weight: .bold))
                .tracking(0.5)
                .foregroundStyle(Palette.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
            ScrollView {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { _, item in
                        Button { pick(item.0) } label: {
                            Text(item.0)
                                .font(.system(size: 27))
                                .frame(maxWidth: .infinity, minHeight: 42)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(PressScale())
                        .accessibilityLabel(item.1 ?? item.0)
                        .help(item.1 ?? "")
                    }
                }
                .padding(.horizontal, 12)
                if shown.isEmpty {
                    Text("No emoji called “\(query)”")
                        .font(.system(size: 14))
                        .foregroundStyle(Palette.secondary)
                        .padding(.vertical, 30)
                }
            }
            .frame(height: 236)
            HStack(spacing: 10) {
                Text("Skin tone").font(.system(size: 12.5)).foregroundStyle(Palette.secondary)
                ForEach(0..<6, id: \.self) { index in
                    Button { tone = index } label: {
                        Circle()
                            .fill(Color(rgb: Self.toneSwatches[index]))
                            .frame(width: 26, height: 26)
                            .overlay(Circle().strokeBorder(tone == index ? Palette.text : Palette.line, lineWidth: tone == index ? 3 : 1.5))
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("\(Self.toneNames[index]) skin tone")
                    .accessibilityAddTraits(tone == index ? .isSelected : [])
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
        }
        .padding(.top, 18)
        .padding(.bottom, 20)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Palette.home.ignoresSafeArea())
        .onChange(of: tone) { _, value in EmojiCatalog.tone = value }
        .accessibilityLabel(title)
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 440, minHeight: 520)
        #else
        .presentationDetents([.height(560), .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        #endif
    }

    private func categoryTab(id: Int, icon: String, name: String) -> some View {
        let on = category == id && !searching
        return Button {
            query = ""
            category = id
        } label: {
            Text(icon)
                .font(.system(size: 18))
                .saturation(on ? 1 : 0.3)
                .frame(width: 34, height: 34)
                .background(on ? Palette.surface : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func pick(_ emoji: String) {
        EmojiCatalog.used(emoji)
        onPick(emoji)
        dismiss()
    }
}
