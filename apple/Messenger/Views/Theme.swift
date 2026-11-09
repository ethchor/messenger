import Observation
import SwiftUI
import CoreText
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

// The look of the app: inks to colour it, papers to put behind chats, and
// drawn avatars (see AvatarArt.swift). The inks are deep, like fountain pen
// ink, so white text reads well on the bubbles you send.

extension Color {
    /// A colour with a separate value in dark mode.
    init(light: UInt32, dark: UInt32, lightOpacity: Double = 1, darkOpacity: Double = 1) {
        #if os(iOS)
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(rgb: dark, alpha: darkOpacity)
                : UIColor(rgb: light, alpha: lightOpacity)
        })
        #elseif os(macOS)
        self.init(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
                ? NSColor(rgb: dark, alpha: darkOpacity)
                : NSColor(rgb: light, alpha: lightOpacity)
        })
        #endif
    }

    /// Bubbles from others. Opaque, so chat paper doesn't show through text.
    /// Chats draw on the dark sheet, where the dark value shows.
    static let incomingBubble = Color(light: 0xFFFFFF, dark: 0x2A2A2F)
    /// A card on a grouped page: white, or raised grey in dark mode.
    static var card: Color {
        #if os(iOS)
        Color(uiColor: .secondarySystemGroupedBackground)
        #else
        Color(nsColor: .controlBackgroundColor)
        #endif
    }
}

#if os(iOS)
private extension UIColor {
    convenience init(rgb: UInt32, alpha: Double) {
        self.init(
            red: CGFloat(rgb >> 16 & 0xFF) / 255,
            green: CGFloat(rgb >> 8 & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
#elseif os(macOS)
private extension NSColor {
    convenience init(rgb: UInt32, alpha: Double) {
        self.init(
            srgbRed: CGFloat(rgb >> 16 & 0xFF) / 255,
            green: CGFloat(rgb >> 8 & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}
#endif

/// The colours to choose from. Yours tints the app, and others see your
/// avatar in it.
enum Ink: String, CaseIterable, Identifiable {
    case marigold
    case blueBlack = "blue-black"
    case teal
    case forest
    case oxblood
    case aubergine
    case saffron
    case sepia
    case graphite

    var id: String { rawValue }

    var name: String {
        switch self {
        case .marigold: "Marigold"
        case .blueBlack: "Blue-black"
        case .teal: "Teal"
        case .forest: "Forest"
        case .oxblood: "Oxblood"
        case .aubergine: "Aubergine"
        case .saffron: "Saffron"
        case .sepia: "Sepia"
        case .graphite: "Graphite"
        }
    }

    /// (light, dark, dark-mode bubble). Each passes 5:1 or better for text.
    private var values: (UInt32, UInt32, UInt32) {
        switch self {
        case .marigold: (0x7A5600, 0xFFD23F, 0xE9BE2E)
        case .blueBlack: (0x26457E, 0x9BB4E6, 0x2E4F8A)
        case .teal: (0x0B6E6E, 0x6CCFCB, 0x0F6767)
        case .forest: (0x2D6A39, 0x8ACF97, 0x2B6436)
        case .oxblood: (0x872235, 0xEE9AAA, 0x7E2434)
        case .aubergine: (0x5C3886, 0xC3A6EC, 0x583882)
        case .saffron: (0x8A5A00, 0xEDC15A, 0x7A5100)
        case .sepia: (0x6A4A2D, 0xD9B58F, 0x684A2E)
        case .graphite: (0x3E4550, 0xC5CBD4, 0x3D444E)
        }
    }

    /// Lines, buttons and links.
    var color: Color { Color(light: values.0, dark: values.1) }

    /// The bubbles you send, with `onBubble` text on them. Marigold is bright
    /// in both modes, with dark text.
    var bubble: Color {
        self == .marigold ? Color(light: 0xFFD23F, dark: 0xE9BE2E) : Color(light: values.0, dark: values.2)
    }

    /// Text on your bubbles.
    var onBubble: Color { self == .marigold ? Palette.ink : .white }

    /// A pale wash, behind selected things.
    var wash: Color { Color(light: values.0, dark: values.1, lightOpacity: 0.12, darkOpacity: 0.2) }

    /// The ink at full depth in either mode: initials on the pastel.
    var deep: Color { Color(rgb: values.0) }

    /// The pale circle behind someone's avatar. The same in dark mode, like
    /// a photo would be.
    var pastel: Color {
        switch self {
        case .marigold: Color(rgb: 0xFCEFC6)
        case .blueBlack: Color(rgb: 0xDCE6FA)
        case .teal: Color(rgb: 0xD2F0EC)
        case .forest: Color(rgb: 0xDCF1DA)
        case .oxblood: Color(rgb: 0xF9DDE3)
        case .aubergine: Color(rgb: 0xEADFF8)
        case .saffron: Color(rgb: 0xFCEFC6)
        case .sepia: Color(rgb: 0xF3E6D5)
        case .graphite: Color(rgb: 0xE3E6EB)
        }
    }

    /// A pinned card for someone in this ink: the design's bright colours.
    var card: UInt32 {
        switch self {
        case .marigold: 0xFFD23F
        case .blueBlack: 0xB9D3FF
        case .teal: 0x9EDCB4
        case .forest: 0x5DC584
        case .oxblood: 0xF4A6D0
        case .aubergine: 0xC9BDFF
        case .saffron: 0xFFB48A
        case .sepia: 0xFFD9A8
        case .graphite: 0xD9DCE2
        }
    }

    /// The ink named in someone's profile, if it's one we know.
    init?(named name: String?) {
        guard let name, let ink = Ink(rawValue: name) else { return nil }
        self = ink
    }

    /// A steady ink for someone who hasn't picked one.
    static func forName(_ name: String) -> Ink {
        // Stable across launches, unlike hashValue.
        // Marigold is left out, so everyone keeps the ink they had.
        let sum = name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }
        let inks = allCases.filter { $0 != .marigold }
        return inks[sum % inks.count]
    }
}

extension AppModel {
    /// Someone's ink: the one they picked, or a steady one from their name.
    func ink(for username: String) -> Ink {
        Ink(named: profiles[username]?.ink) ?? Ink.forName(username)
    }
}

/// What goes behind a chat, drawn in the ink.
enum Paper: Hashable, Identifiable {
    case plain
    case dots
    case grid
    /// Lines that move and answer a touch.
    case live(LivePaper)

    static let patterns: [Paper] = [.plain, .dots, .grid]
    static let live: [Paper] = LivePaper.allCases.map { .live($0) }
    static var allCases: [Paper] { patterns + live }

    /// How it's stored. Papers that are gone (ruled, doodles, the ink wash,
    /// the wallpapers and scenes) no longer read back, so they turn plain.
    var rawValue: String {
        switch self {
        case .plain: "plain"
        case .dots: "dots"
        case .grid: "grid"
        case .live(let live): "live:" + live.rawValue
        }
    }

    init?(rawValue: String) {
        if rawValue.hasPrefix("live:") {
            guard let live = LivePaper(rawValue: String(rawValue.dropFirst("live:".count))) else { return nil }
            self = .live(live)
        } else if let paper = Self.patterns.first(where: { $0.rawValue == rawValue }) {
            self = paper
        } else {
            return nil
        }
    }

    var id: String { rawValue }

    var name: String {
        switch self {
        case .plain: "Plain"
        case .dots: "Dots"
        case .grid: "Grid"
        case .live(let live): live.title
        }
    }
}

/// This device's choices: the ink, the paper behind chats, and papers picked
/// for single chats. Kept in UserDefaults; the ink is also shared, through
/// the profile.
@MainActor
@Observable
final class Appearance {
    private static let inkKey = "appearance.ink"
    private static let paperKey = "appearance.paper"
    private static let chatPapersKey = "appearance.chatPapers"
    private static let themeKey = "appearance.theme"

    /// Light, dark, or as the system is.
    enum Theme: String, CaseIterable {
        case light, dark, system
        var title: String { rawValue.capitalized }
        var colorScheme: ColorScheme? {
            switch self {
            case .light: .light
            case .dark: .dark
            case .system: nil
            }
        }
    }

    var theme: Theme {
        didSet { UserDefaults.standard.set(theme.rawValue, forKey: Self.themeKey) }
    }

    var ink: Ink {
        didSet { UserDefaults.standard.set(ink.rawValue, forKey: Self.inkKey) }
    }

    var paper: Paper {
        didSet { UserDefaults.standard.set(paper.rawValue, forKey: Self.paperKey) }
    }

    private(set) var chatPapers: [String: Paper]

    init() {
        let defaults = UserDefaults.standard
        ink = Ink(named: defaults.string(forKey: Self.inkKey)) ?? .marigold
        paper = defaults.string(forKey: Self.paperKey).flatMap(Paper.init(rawValue:)) ?? .plain
        let stored = defaults.dictionary(forKey: Self.chatPapersKey) as? [String: String] ?? [:]
        chatPapers = stored.compactMapValues(Paper.init(rawValue:))
        theme = defaults.string(forKey: Self.themeKey).flatMap(Theme.init(rawValue:)) ?? .system
    }

    func paper(for conversationId: String) -> Paper {
        chatPapers[conversationId] ?? paper
    }

    /// A paper for one chat, or nil to follow the default.
    func setPaper(_ paper: Paper?, for conversationId: String) {
        chatPapers[conversationId] = paper
        UserDefaults.standard.set(chatPapers.mapValues(\.rawValue), forKey: Self.chatPapersKey)
    }

    /// Back to the start, when the account leaves this device.
    func reset() {
        for key in [Self.inkKey, Self.paperKey, Self.chatPapersKey, Self.themeKey] {
            UserDefaults.standard.removeObject(forKey: key)
        }
        ink = .marigold
        paper = .plain
        chatPapers = [:]
        theme = .system
    }
}

/// A chat's paper, filling the space it's given.
struct PaperView: View {
    let paper: Paper
    let ink: Ink
    /// Holds a live paper still, for a swatch.
    var still = false
    /// The newest message, so a live paper can ripple when one goes or comes.
    var pulse: LivePulse?
    /// What the paper is drawn on: the chat's dark sheet.
    var background: Color = Palette.sheet
    @Environment(\.colorScheme) private var colorScheme
    @State private var touch = LiveTouch()

    var body: some View {
        let strength = colorScheme == .dark ? 0.22 : 0.16
        ZStack {
            background
            switch paper {
            case .plain:
                EmptyView()
            case .live(let live):
                LivePaperView(paper: live, color: ink.color, strength: strength, touch: touch, still: still)
                    .onChange(of: pulse) { _, pulse in
                        if let pulse { touch.pulse(pulse) }
                    }
            case .dots, .grid:
                Canvas { context, size in
                    draw(paper, in: &context, size: size)
                }
                .foregroundStyle(ink.color.opacity(strength))
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ paper: Paper, in context: inout GraphicsContext, size: CGSize) {
        switch paper {
        case .dots:
            let gap: CGFloat = 18
            var dots = Path()
            for x in stride(from: gap / 2, to: size.width, by: gap) {
                for y in stride(from: gap / 2, to: size.height, by: gap) {
                    dots.addEllipse(in: CGRect(x: x - 1.1, y: y - 1.1, width: 2.2, height: 2.2))
                }
            }
            context.fill(dots, with: .foreground)
        case .grid:
            var lines = Path()
            for x in stride(from: 0, to: size.width, by: 22) {
                lines.move(to: CGPoint(x: x, y: 0))
                lines.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in stride(from: 0, to: size.height, by: 22) {
                lines.move(to: CGPoint(x: 0, y: y))
                lines.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(lines, with: .foreground, lineWidth: 0.5)
        case .plain, .live:
            break
        }
    }
}


// MARK: - The Balanced design's colours, type and pieces

/// The colours of the app beyond the inks: a warm ground, a dark sheet that
/// chats sit on in both modes, and one bright accent, marigold, for the things
/// you do (send, unread, the chosen tab, primary buttons).
enum Palette {
    /// The yellow of send, unread and the chosen tab.
    static let marigold = Color(rgb: 0xFFD23F)
    /// Dark text, on marigold and on light pills.
    static let ink = Color(rgb: 0x141414)
    /// The cream of chips and the composer's field, in both modes.
    static let cream = Color(rgb: 0xF7F5EE)
    /// Behind pages: settings, sheets.
    static let ground = Color(light: 0xFAF9F6, dark: 0x0F0F11)
    /// Behind the chat list, the chat header and You, under a faint grid.
    static let home = Color(light: 0xF7F5EE, dark: 0x0F0F11)
    static let homeGrid = Color(light: 0xECE9DE, dark: 0x17171B)
    /// Cards and rows.
    static let surface = Color(light: 0xFFFFFF, dark: 0x18181B)
    static let text = Color(light: 0x141414, dark: 0xF3F2EE)
    static let secondary = Color(light: 0x4A4840, dark: 0xA3A1A9)
    static let line = Color(light: 0xE8E6DF, dark: 0x2A2A2F)
    static let danger = Color(light: 0xB42318, dark: 0xFF8A7A)
    /// The sheet messages sit on: near-black in both modes.
    static let sheet = Color(light: 0x141414, dark: 0x1A1A1D)
    /// The chat's sheet: a light notepad in light mode, dark in dark mode.
    static let chatSheet = Color(light: 0xFBF9F3, dark: 0x1A1A1D)
    /// Behind the chat's header: dark in both modes.
    static let chatGround = Color(light: 0x141414, dark: 0x0F0F11)
    /// The sheet in vanish mode, and the ground around it.
    static let vanishSheet = Color(rgb: 0x0E0B16)
    static let vanishHome = Color(rgb: 0x07060B)
    static let vanishAccent = Color(rgb: 0xC9BDFF)
    /// Toggles that are on, and voice messages.
    static let green = Color(rgb: 0x5DC584)
    /// Unread badges: dark on light, marigold on dark.
    static let badge = Color(light: 0x141414, dark: 0xFFD23F)
    static let onBadge = Color(light: 0xFFD23F, dark: 0x141414)
    /// The floating tab bar.
    static let pill = Color(light: 0x141414, dark: 0x1F1F23)
    /// Outlines on stamps (avatars) and buttons in light mode; none in dark.
    static let stampLine = Color(light: 0x141414, dark: 0x141414, lightOpacity: 1, darkOpacity: 0)
    /// Tiles behind settings icons.
    static let tileYellow = Color(rgb: 0xFFD23F)
    static let tilePeach = Color(rgb: 0xFFB48A)
    static let tileLavender = Color(rgb: 0xC9BDFF)
    static let tileMint = Color(rgb: 0x9EDCB4)
    static let tileApricot = Color(rgb: 0xFFD9A8)
    static let tileSky = Color(rgb: 0xB9D3FF)
    static let tilePink = Color(rgb: 0xF4A6D0)
}

extension Font {
    /// Screen titles and names: a tight, heavy grotesque.
    /// Bricolage Grotesque (bundled, OFL): 600 for titles, 700 for the
    /// big ones. Falls back to the system's semibold if it can't load.
    static func display(_ size: CGFloat, weight: CGFloat = 600) -> Font {
        if let font = Bricolage.font(size: size, weight: weight) { return Font(font) }
        return .system(size: size, weight: weight >= 700 ? .bold : .semibold)
    }
}

/// The bundled display face: one variable font, registered on first use.
enum Bricolage {
    private static let registered: Bool = {
        guard let url = Bundle.main.url(forResource: "BricolageGrotesque", withExtension: "ttf") else { return false }
        var error: Unmanaged<CFError>?
        return CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) || error != nil
    }()
    nonisolated(unsafe) private static let base: CTFontDescriptor? = {
        guard registered,
              let url = Bundle.main.url(forResource: "BricolageGrotesque", withExtension: "ttf"),
              let all = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor] else { return nil }
        return all.first
    }()
    nonisolated(unsafe) private static var cache: [String: CTFont] = [:]

    static func font(size: CGFloat, weight: CGFloat) -> CTFont? {
        let key = "\(size)-\(weight)"
        if let hit = cache[key] { return hit }
        guard let base else { return nil }
        // wght and opsz axes: 'wght' = 0x77676874, 'opsz' = 0x6F70737A.
        let variations: [NSNumber: NSNumber] = [0x7767_6874: NSNumber(value: Double(weight)), 0x6F70_737A: NSNumber(value: Double(min(max(size, 12), 96)))]
        let desc = CTFontDescriptorCreateCopyWithAttributes(base, [kCTFontVariationAttribute: variations] as CFDictionary)
        let font = CTFontCreateWithFontDescriptor(desc, size, nil)
        cache[key] = font
        return font
    }
}

extension View {
    /// Display type's tight tracking.
    func displayTracking(_ size: CGFloat) -> some View {
        tracking(-0.022 * size)
    }
}

/// The chat list's and You's ground: warm paper under a faint square grid.
struct HomeGround: View {
    var vanish = false

    var body: some View {
        if vanish {
            Palette.vanishHome
        } else {
            Canvas { context, size in
                var lines = Path()
                let gap: CGFloat = 28
                for x in stride(from: 0, through: size.width, by: gap) {
                    lines.move(to: CGPoint(x: x, y: 0))
                    lines.addLine(to: CGPoint(x: x, y: size.height))
                }
                for y in stride(from: 0, through: size.height, by: gap) {
                    lines.move(to: CGPoint(x: 0, y: y))
                    lines.addLine(to: CGPoint(x: size.width, y: y))
                }
                context.stroke(lines, with: .color(Palette.homeGrid), lineWidth: 1)
            }
            .background(Palette.home)
            .accessibilityHidden(true)
        }
    }
}

/// An avatar as a stamp: a rounded square with a dark outline in light mode.
struct Stamp<Content: View>: View {
    var size: CGFloat
    var background: Color
    @ViewBuilder var content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size / 3, style: .continuous)
        content
            .frame(width: size, height: size)
            .background(background)
            .clipShape(shape)
            .overlay(shape.strokeBorder(Palette.stampLine, lineWidth: 1.5))
    }
}

/// A round 46 pt button: outlined, or filled marigold with an outline.
struct RoundButton: View {
    let systemImage: String
    let label: String
    var filled = false
    var size: CGFloat = 46
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            RoundButtonFace(systemImage: systemImage, filled: filled, size: size)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }
}

struct RoundButtonFace: View {
    let systemImage: String
    var filled = false
    var size: CGFloat = 46

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundStyle(filled ? Palette.ink : Palette.text)
            .frame(width: size, height: size)
            .background(Circle().fill(filled ? Palette.marigold : Color.clear))
            .overlay(Circle().strokeBorder(filled ? Palette.ink : Palette.text, lineWidth: 1.5))
            .contentShape(Circle())
    }
}

/// Shrinks a little while pressed.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}

/// An outlined chip; filled with the text colour when selected.
struct FilterChip: View {
    let title: String
    var count = 0
    let selected: Bool
    let action: () -> Void
    /// In a sidebar the chips are a size down, so all four fit in a row.
    @Environment(\.wideLayout) private var wide

    var body: some View {
        Button(action: action) {
            HStack(spacing: wide ? 5 : 7) {
                Text(title)
                    .font(.system(size: wide ? 13.5 : 15, weight: .medium))
                if count > 0 && !selected {
                    Text("\(count)")
                        .font(.system(size: 11.5, weight: .bold))
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 20, minHeight: 20)
                        .background(Capsule().fill(Palette.marigold))
                }
            }
            .foregroundStyle(selected ? Palette.home : Palette.text)
            .padding(.horizontal, wide ? 12 : 16)
            .frame(height: wide ? 32 : 40)
            .background(Capsule().fill(selected ? Palette.text : Color.clear))
            .overlay(Capsule().strokeBorder(Palette.text, lineWidth: 1.5))
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
        .animation(.easeOut(duration: 0.2), value: selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// An unread count: dark with marigold text, or marigold in dark mode.
struct UnreadBadge: View {
    let count: Int
    var muted = false

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(muted ? Palette.secondary : Palette.onBadge)
            .padding(.horizontal, 7)
            .frame(minWidth: 24, minHeight: 24)
            .background(Capsule().fill(muted ? Palette.line : Palette.badge))
            .transition(.scale.combined(with: .opacity))
    }
}

/// A rounded tile behind a settings icon.
struct IconTile: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(Palette.ink)
            .frame(width: 36, height: 36)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tint))
            .accessibilityHidden(true)
    }
}

/// The marigold toggle of the design.
struct MarigoldToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Toggle(configuration)
            .toggleStyle(.switch)
            .tint(Color(rgb: 0xFFC93C))
    }
}

/// The primary button: a marigold capsule with dark text.
struct MarigoldButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(Palette.ink)
            .padding(.horizontal, 20)
            .frame(minHeight: 46)
            .background(Capsule().fill(Palette.marigold))
            .overlay(Capsule().strokeBorder(Palette.stampLine, lineWidth: 1.5))
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == MarigoldButtonStyle {
    static var marigold: MarigoldButtonStyle { MarigoldButtonStyle() }
}
